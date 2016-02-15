(ns dashboard-service.orders
  (:require [clojure.algo.generic.functor :refer [fmap]]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [common.db :refer [!select]]
            [opt.planner :refer [compute-distance]]
            [common.users :as users]
            [common.util :refer [in? map->java-hash-map split-on-comma]]
            [common.zones :refer [get-zone-by-zip-code]]))

(defn orders-since-date
  "Get all orders since date. A blank date will return all orders. When
  unix-epoch? is true, assume date is in unix epoch seconds"
  [db-conn date & [unix-epoch?]]
  (!select db-conn "orders"
           [:id :lat :lng :status :gallons :gas_type
            :total_price :timestamp_created :address_street
            :address_city :address_state :address_zip :user_id
            :courier_id :vehicle_id :license_plate
            :target_time_start :target_time_end :coupon_code :event_log
            :paid :stripe_charge_id :special_instructions
            :number_rating :text_rating
            :payment_info]
           {}
           :custom-where
           (str "timestamp_created > "
                (if unix-epoch?
                  (str "FROM_UNIXTIME(" date ")")
                  (str "'" date "'")))))

(defn include-user-name-phone-and-courier
  "Given a vector of orders, assoc the user name, phone number and courier
  that is associated with the order"
  [db-conn orders]
  (let [users-by-id (into {}
                          (map (juxt :id identity)
                               (users/get-users-by-ids
                                db-conn
                                (distinct
                                 (concat (map :user_id orders)
                                         (map :courier_id orders))))))
        id->name #(:name (get users-by-id %))
        id->phone_number #(:phone_number (get users-by-id %))
        id->email #(:email (get users-by-id %))]
    (map #(assoc %
                 :courier_name (id->name (:courier_id %))
                 :customer_name (id->name (:user_id %))
                 :customer_phone_number (id->phone_number (:user_id %))
                 :email (id->email (:user_id %)))
         orders)))

(defn include-vehicle
  "Given a vector of orders, assoc the vehicle information that is associated
  with the order "
  [db-conn orders]
  (let [vehicles-by-id
        (->> (!select db-conn "vehicles"
                      [:id :year :make :model :color :gas_type
                       :license_plate]
                      {}
                      :custom-where
                      (let [vehicle-ids (distinct (map :vehicle_id orders))]
                        (str "id IN (\""
                             (s/join "\",\"" vehicle-ids)
                             "\")")))
             (group-by :id))
        id->vehicle #(first (get vehicles-by-id %))]
    (map #(assoc % :vehicle (id->vehicle (:vehicle_id %)))
         orders)))

(defn include-zone-info
  "Given a vector of orders, assoc the zone and zone_color associated with the
  order"
  [orders]
  (map #(assoc %
               :zone-color
               (:color
                (get-zone-by-zip-code
                 (:address_zip %)))
               :zone
               (:id
                (get-zone-by-zip-code
                 (:address_zip %))))
       orders))

(defn include-was-late
  "Given a vector of orders, assoc the boolean was_late associated with the
  order"
  [orders]
  (map #(assoc %
               :was-late
               (let [completion-time
                     (-> (str "kludgeFix 1|" (:event_log %))
                         (s/split #"\||\s")
                         (->> (apply hash-map))
                         (get "complete"))]
                 (and completion-time
                      (> (Integer. completion-time)
                         (:target_time_end %)))))
       orders))

(defn include-eta
  "Given a vector of orders, assoc the etas for active couriers that are
  associated with the order"
  [db-conn orders]
  (let [all-couriers (->> (!select db-conn "couriers" ["*"] {})
                          ;; remove chriscourier@test.com
                          (remove #(in? ["9eadx6i2wCCjUI1leBBr"] (:id %))))
        users-by-id  (->> (!select db-conn "users"
                                   [:id :name :email :phone_number :os
                                    :app_version :stripe_default_card
                                    :sift_score
                                    :arn_endpoint :timestamp_created]
                                   {})
                          (group-by :id))
        id->name #(:name (first (get users-by-id %)))
        couriers-by-id (into {} (map (juxt (comp keyword :id) identity)
                                     all-couriers))
        dist-map (compute-distance
                  {"orders" (->> orders
                                 (filter #(in? ["unassigned"
                                                "assigned"
                                                "accepted"
                                                "enroute"]
                                               (:status %)))
                                 (map #(assoc %
                                              :status_times
                                              (-> (:event_log %)
                                                  (s/split #"\||\s")
                                                  (->> (remove s/blank?)
                                                       (apply hash-map)
                                                       (fmap read-string)))))
                                 (map (juxt :id stringify-keys))
                                 (into {}))
                   "couriers" (->> (!select db-conn
                                            "couriers"
                                            [:id :lat :lng :last_ping
                                             :connected :zones]
                                            {:active true
                                             :on_duty true})
                                   (map #(update-in % [:zones]
                                                    split-on-comma))
                                   (map #(assoc % :assigned_orders []))
                                   (map (juxt :id stringify-keys))
                                   (into {}))})]
    (map #(assoc %  :etas (if-let [this-dist-map (get dist-map (:id %))]
                            (map (fn [x]
                                   {:name (id->name (key x))
                                    :busy (:busy
                                           ((keyword (key x))
                                            couriers-by-id))
                                    :minutes (quot (val x) 60)})
                                 (into {} (get this-dist-map "etas")))))

         orders)))
