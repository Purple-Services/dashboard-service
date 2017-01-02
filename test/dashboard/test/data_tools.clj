(ns dashboard.test.data-tools
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [common.db :as db]
            [common.util :as util]
            [crypto.password.bcrypt :as bcrypt]
            [dashboard.login :as login]
            [dashboard.users :as users]
            [dashboard.test.db-tools :refer [setup-ebdb-test-for-conn-fixture
                                             setup-ebdb-test-pool!
                                             clear-and-populate-test-database
                                             clear-and-populate-test-database-fixture]]
            ))

(use-fixtures :once setup-ebdb-test-for-conn-fixture)
(use-fixtures :each clear-and-populate-test-database-fixture)

(defn create-dash-user!
  "Create a dashboard user"
  [{:keys [db-conn platform-id password full-name]}]
  (is (:success (db/!insert db-conn "dashboard_users"
                            {:id (util/rand-str-alpha-num 20)
                             :email platform-id
                             :password_hash (bcrypt/encrypt password)
                             :reset_key ""}))))
(defn register-user!
  "Create a user"
  [{:keys [db-conn platform-id password full-name]}]
  (is (:success (db/!insert db-conn "users"
                            {:id (util/rand-str-alpha-num 20)
                             :email platform-id
                             :type "native"
                             :password_hash (bcrypt/encrypt password)
                             :reset_key ""
                             :phone_number ""
                             :phone_number_verified 0
                             :name full-name}))))
(defn register-courier!
  "Create a courier. new-courier hash-map is equivalent to the arguments to
  register-user!"
  [new-courier]
  (register-user! new-courier)
  (let [db-conn (:db-conn new-courier)
        courier
        (first (db/!select (:db-conn new-courier) "users" ["*"]
                           {:email (:platform-id new-courier)}))]
    (is (:success (users/convert-to-courier! db-conn courier)))))

(defn vehicle-map
  "Given vehicle information, create a vehicle map for it"
  [{:keys [active user_id year make model color gas_type only_top_tier
           license_plate]
    :or {active 1
         user_id (util/rand-str-alpha-num 20)
         year "2016"
         make "Nissan"
         model "Altima"
         color "Blue"
         gas_type "87"
         only_top_tier 0
         license_plate "FOOBAR"}}]
  {:id (util/rand-str-alpha-num 20)
   :active 1
   :user_id user_id
   :year year
   :make make
   :model model
   :color color
   :gas_type gas_type
   :only_top_tier only_top_tier
   :license_plate license_plate})

(defn create-vehicle!
  "Add vehicle to user"
  [db-conn vehicle user]
  (db/!insert db-conn
              "vehicles"
              (merge vehicle
                     {:user_id (:id user)})))

(defn order-map
  "Given an order's information, create an order map for it"
  [{:keys [id status user_id courier_id vehicle_id license_plate
           target_time_start target_time_end gallons gas_type is_top_tier
           tire_pressure_check special_instructions lat lng address_street
           address_city address_state address_zip referral_gallons_used
           coupon_code subscription_id subscription_discount gas_price
           service_fee total_price paid stripe_charge_id stripe_refund_id
           stripe_balance_transaction_id time_paid payment_info number_rating
           text_rating event_log admin_event_log notes]
    :or {id (util/rand-str-alpha-num 20)
         status "unassigned"
         user_id (util/rand-str-alpha-num 20)
         courier_id (util/rand-str-alpha-num 20)
         vehicle_id (util/rand-str-alpha-num 20)
         license_plate "FOOBAR"
         target_time_start (quot (c/to-long (l/local-now))
                                 1000)
         target_time_end (quot (c/to-long (t/plus (l/local-now)
                                                  (t/hours 3)))
                               1000)
         gallons 10
         gas_type "87"
         is_top_tier 1
         tire_pressure_check 0
         special_instructions ""
         lat (str "34.0" (rand-int 9))
         lng (str "-118.4" (rand-int 9))
         address_street "123 Foo Br"
         address_city "Los Angeles"
         address_zip "90210"
         referral_gallons_used 0
         coupon_code ""
         subscription_id 0
         subscription_discount 0
         gas_price 250
         service_fee 399
         paid 0
         stripe_charge_id "no charge id"
         stripe_refund_id "no refund id"
         stripe_balance_transaction_id "no transaction id"}}]
  {:id id
   :status status
   :user_id user_id
   :courier_id courier_id
   :vehicle_id vehicle_id
   :license_plate license_plate
   :target_time_start target_time_start
   :target_time_end target_time_end
   :gallons gallons
   :gas_type gas_type
   :is_top_tier is_top_tier
   :tire_pressure_check tire_pressure_check
   :special_instructions special_instructions
   :lat lat
   :lng lng
   :address_street address_street
   :address_city address_city
   :address_zip address_zip
   :referral_gallons_used referral_gallons_used
   :coupon_code coupon_code
   :subscription_id subscription_id
   :subscription_discount subscription_discount
   :gas_price gas_price
   :service_fee service_fee
   :total_price (+ (* gas_price gallons) service_fee)
   :paid paid
   :stripe_charge_id stripe_charge_id
   :stripe_refund_id stripe_refund_id
   :stripe_balance_transaction_id stripe_balance_transaction_id})

(defn create-order!
  "Given an order, create it in the database"
  [db-conn order]
  (is (:success (db/!insert db-conn "orders"
                            order))))

(defn give-perms!
  "Given an existing user, add perms to that user"
  [{:keys [user perms db-conn]}]
  (is (:success (db/!update db-conn "dashboard_users"
                            {:permissions perms}
                            {:id (:id user)}))))

(deftest simple-data-tools-test
  (let [email "foo@bar.com"
        password "foobar"
        full-name "Foo Bar"
        _ (register-user! {:db-conn (db/conn)
                           :platform-id email
                           :password password
                           :full-name full-name})
        user (first (db/!select (db/conn) "users" ["*"] {:email email}))
        vehicle (vehicle-map {:user_id (:id user)})
        _ (create-vehicle! (db/conn) vehicle user)
        order (order-map {:user_id (:id user)
                          :vehicle_id (:id vehicle)})
        _ (create-order! (db/conn) order)]
    (is (= (:id vehicle)
           (:vehicle_id (first (db/!select (db/conn) "orders" ["*"]
                                           {:vehicle_id (:id vehicle)})))))))
(defn update-courier-position!
  [{:keys [lat lng db-conn courier]
    :or {lat (str "34.0" (rand-int 9))
         lng (str "-118.4" (rand-int 9))}}]
  (db/!update (db/conn) "couriers"
              {:on_duty 1
               :connected 1
               :lat lat
               :lng lng
               :last_ping (quot (c/to-long (l/local-now)) 1000)}
              {:id (:id courier)}))

(defn create-minimal-dash-user!
  "Given a map of:
  {:email     <email>
   :password  <password>
   :full-name <fullname>
   :db-conn   <db-conn>}
  Create a Dash user with minimal permissions"
  [{:keys [email password full-name db-conn]}]
  ;; create a new dash user
  (create-dash-user! {:db-conn db-conn
                      :platform-id email
                      :password password
                      :full-name full-name})
  ;; give very minimal perms
  (give-perms!
   {:user (login/get-user-by-email
           db-conn email)
    :db-conn db-conn
    :perms "view-dash,view-couriers,view-users,view-zones,view-orders"}))

(defn create-app-user-vehicle-order!
  "Given a map of:
  {:email    <email>
  :password  <password>
  :full-name <fullname>
  :db-conn   <db-conn>}
  create a minimal app user who has a vehicle registered with one order"
  [{:keys [email password full-name db-conn]}]
  (let [
        ;; create the native app user
        _ (register-user! {:db-conn db-conn
                           :platform-id email
                           :password password
                           :full-name full-name})
        user (first (db/!select db-conn "users" ["*"] {:email email}))
        ;; create the user vehicle
        vehicle (vehicle-map {:user_id (:id user)})
        _ (create-vehicle! db-conn vehicle user)
        ;; create an order
        order (order-map {:user_id (:id user)
                          :vehicle_id (:id vehicle)})
        _ (create-order! (db/conn) order)]))

(defn create-minimal-dash-env!
  "Given a map of:
  {:dash-user {:email <email>
               :password <password>
               :full-name <dashboard user full name>
              }
   :app-user {:email <email>
              :password <password>
              :full-name <app user full name>
             }
   :db-conn <db-conn>
  }

  create the minial dash environment needed to run tests. This
  includes creating a dashboard user, creating an app user with a vehicle
  and one order"
  [{:keys [dash-user app-user db-conn]}]
  (create-minimal-dash-user! (assoc dash-user :db-conn db-conn))
  (create-app-user-vehicle-order! (assoc app-user :db-conn db-conn)))
