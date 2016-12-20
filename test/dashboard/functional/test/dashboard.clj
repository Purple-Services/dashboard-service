(ns dashboard.functional.test.dashboard
  (:require [clj-webdriver.taxi :refer :all]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [common.db :as db]
            [environ.core :refer [env]]
            [dashboard.functional.test.selenium :as selenium]
            [dashboard.handler]
            [dashboard.login :as login]
            [dashboard.users :as users]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :refer [setup-ebdb-test-pool!
                                             clear-test-database
                                             setup-ebdb-test-for-conn-fixture
                                             clear-and-populate-test-database
                                             clear-and-populate-test-database-fixture
                                             reset-db!]]
            [dashboard.test.zones :as test-zones]
            [dashboard.zones :as zones]
            [ring.adapter.jetty :refer [run-jetty]]))

;; for manual testing:
;; (selenium/setup-test-env!) ; make sure profiles.clj was loaded with
;;                   ; :base-url "http:localhost:5746/"
;; -- run tests --
;; (reset-db!) ; note: most tests will need this run between them anyhow
;; -- run more tests
;; (selenium/shutdown-test-env!

(def home-tab {:xpath "//li/a/div[text()='Home']"})

(def zones-tab
  {:xpath "//li/a/div[text()='Zones']"})
(def create-zone
  {:xpath "//button[text()='Create a New Zone']"})

(def zone-form-name
  {:xpath "//label[text()='Name']/parent::div/input"})

(def zone-form-rank
  {:xpath "//label[text()='Rank']/parent::div/input"})

(def zone-form-active?
  {:xpath "//label[text()='Active? ']/parent::div/input"})

(def zone-form-zip-codes
  {:xpath "//label[text()='Zip Codes']/parent::div/textarea"})

(def save-button
  {:xpath "//button[text()='Save']"})

(def dismiss-button
  {:xpath "//button[text()='Dismiss']"})

(def yes-button
  {:xpath "//button[text()='Yes']"})

(def table-refresh-button
  {:xpath "//div[contains(@class,'active')]//i[contains(@class,'fa-refresh')]"})

(def zones-edit-button
  {:xpath "//button[text()='Edit']"})

(def add-delivery-times
  {:xpath "//button[text()='Add Delivery Times']"})

(def one-hour-checkbox
  {:xpath "//label[text()='Delivery Times Available']/parent::div//div[text()='1 Hour ']/input[@type='checkbox']"})

(use-fixtures :once selenium/with-server selenium/with-browser
  selenium/with-redefs-fixture
  setup-ebdb-test-for-conn-fixture)
(use-fixtures :each clear-and-populate-test-database-fixture)

;; end fns for testing at the repl

(defn check-error-alert
  "Wait for an error alert to appear and test that it says msg"
  [msg]
  (is (= msg
         (selenium/get-error-alert))))

(defn check-success-alert
  "Wait for an error alert to appear and test that it says msg"
  [msg]
  (is (= msg
         (selenium/get-success-alert))))

(defn set-minimum-dash-env!
  []
  (let [db-conn (db/conn)
        dash-email "foo@bar.com"
        dash-password "foobar"
        dash-full-name "Foo Bar"
        ;; create dash user
        _ (data-tools/create-dash-user! {:db-conn db-conn
                                         :platform-id dash-email
                                         :password dash-password
                                         :full-name dash-full-name})
        ;; give very minimal perms
        _ (data-tools/give-perms!
           {:user (login/get-user-by-email
                   db-conn "foo@bar.com")
            :db-conn db-conn
            :perms "view-dash,view-couriers,view-users,view-zones,view-orders"})
        user-email "baz@qux.org"
        user-password "bazqux"
        user-full-name "Baz Qux"
        ;; create a user
        _ (data-tools/register-user! {:db-conn db-conn
                                      :platform-id user-email
                                      :password user-password
                                      :full-name user-full-name})
        user (first (db/!select db-conn "users" ["*"] {:email user-email}))
        ;; create a courier account
        courier-email "courier@purpleapp.com"
        courier-password "courier"
        courier-full-name "Purple Courier"
        ;; create the courier
        _ (data-tools/register-user! {:db-conn db-conn
                                      :platform-id courier-email
                                      :password courier-password
                                      :full-name courier-full-name})
        courier (first (db/!select db-conn "users" ["*"]
                                   {:email courier-email}))
        _ (users/convert-to-courier! db-conn courier)
        ;; put courier on the map
        _ (data-tools/update-courier-position! {:db-conn db-conn
                                                :courier courier})
        vehicle (data-tools/vehicle-map {:user_id (:id user)})
        ;; add a vehicle to user
        _ (data-tools/create-vehicle! db-conn vehicle user)
        ;; add an order
        order (data-tools/order-map {:user_id (:id user)
                                     :vehicle_id (:id vehicle)
                                     :courier_id (:id courier)})
        _ (data-tools/create-order! (db/conn) order)
        logout {:xpath "//a[text()='Logout']"}]
    (selenium/go-to-uri "login")
    (selenium/login-dashboard dash-email dash-password)
    (wait-until #(exists? logout))))

(deftest login-tests
  (let [email "foo@bar.com"
        password "foobar"
        logout {:xpath "//a[text()='Logout']"}
        full-name "Foo Bar"
        user-email "baz@qux.org"
        user-password "bazqux"
        full-name "Baz Qux"
        db-conn (db/conn)
        _ (data-tools/register-user! {:db-conn db-conn
                                      :platform-id user-email
                                      :password user-password
                                      :full-name full-name})
        user (first (db/!select db-conn "users" ["*"] {:email user-email}))
        vehicle (data-tools/vehicle-map {:user_id (:id user)})
        _ (data-tools/create-vehicle! db-conn vehicle user)
        order (data-tools/order-map {:user_id (:id user)
                                     :vehicle_id (:id vehicle)})
        _ (data-tools/create-order! (db/conn) order)
        error-message {:xpath "//div[@id='error-message']"}
        ]
    (testing "Login with a username and password that doesn't exist"
      (selenium/go-to-uri "login")
      (selenium/login-dashboard email password)
      (wait-until #(exists? error-message))
      (is (= "Error: Incorrect email / password combination."
             (text (find-element error-message)))))
    (testing "Create a user, login with credentials"
      (data-tools/create-dash-user! {:db-conn db-conn
                                     :platform-id email
                                     :password password
                                     :full-name full-name})
      ;; give very minimal perms
      (data-tools/give-perms!
       {:user (login/get-user-by-email
               db-conn "foo@bar.com")
        :db-conn db-conn
        :perms "view-dash,view-couriers,view-users,view-zones,view-orders"})
      ;; create a user, vehicle and order
      (selenium/go-to-uri "login")
      (selenium/login-dashboard email password)
      (wait-until #(exists? logout)))
    (testing "Log back out."
      (selenium/logout-dashboard)
      (is (exists? (find-element selenium/login-button))))))


(defn create-minimal-dash-user!
  "Given a map of:
  {:email     <email>
   :password  <password>
   :full-name <fullname>
   :db-conn   <db-conn>}
  Create a Dash user with minimal permissions"
  [{:keys [email password full-name db-conn]}]
  ;; create a new dash user
  (data-tools/create-dash-user! {:db-conn db-conn
                                 :platform-id email
                                 :password password
                                 :full-name full-name})
  ;; give very minimal perms
  (data-tools/give-perms!
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
        _ (data-tools/register-user! {:db-conn db-conn
                                      :platform-id email
                                      :password password
                                      :full-name full-name})
        user (first (db/!select db-conn "users" ["*"] {:email email}))
        ;; create the user vehicle
        vehicle (data-tools/vehicle-map {:user_id (:id user)})
        _ (data-tools/create-vehicle! db-conn vehicle user)
        ;; create an order
        order (data-tools/order-map {:user_id (:id user)
                                     :vehicle_id (:id vehicle)})
        _ (data-tools/create-order! (db/conn) order)]))

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

(defn create-zone!
  "Given a map:
  {:name <str>
   :rank <int>
   :active? <boolean> ; optional, will use form default
  }

  Click the 'Create a New Zone' button and add the values"
  [{:keys [name rank active?]}]
  ;; wait for the create zone button
  (wait-until #(exists? create-zone))
  ;; click the create zone button
  (click (find-element create-zone))
  ;; wait until the form is available
  (wait-until #(exists? zone-form-name))
  ;; input the name
  (input-text zone-form-name name)
  ;; clear out rank
  (clear zone-form-rank)
  ;; input the rank
  (input-text zone-form-rank (str rank))
  ;; input if it is active or not
  (while (not= (selected? zone-form-active?)
               active?)
    (click zone-form-active?)))

(deftest zones-test
  (let [dash-user {:email "foo@bar.com"
                   :password "foobar"
                   :full-name "Foo Bar"}
        app-user       {:email "baz@qux.com"
                        :password "bazqux"
                        :full-name "Baz Qux"}
        db-conn        (db/conn)
        _ (create-minimal-dash-env! {:dash-user dash-user
                                     :app-user app-user
                                     :db-conn db-conn})
        dash-user-id (:id (login/get-user-by-email db-conn "foo@bar.com"))
        ]
    ;; setup the env
    (create-minimal-dash-env! {:dash-user dash-user
                               :app-user app-user
                               :db-conn db-conn})
    ;; login the dash user
    (selenium/login-dashboard (:email dash-user) (:password dash-user))
    (wait-until #(exists? home-tab))
    (testing "Zones is not viewable by a dashboard user without proper perms"
      (data-tools/give-perms!
       {:user (login/get-user-by-email
               db-conn (:email dash-user))
        :db-conn db-conn
        :perms "view-dash,view-couriers,view-users,view-orders"})
      (refresh)
      (is (not (exists? zones-tab))))
    (testing "A dashboard user who can only view zones can see an existing one, but can't edit or create them"
      (data-tools/give-perms!
       {:user (login/get-user-by-email
               db-conn "foo@bar.com")
        :db-conn db-conn
        :perms "view-dash,view-couriers,view-users,view-zones,view-orders"})
      (zones/create-zone! db-conn (test-zones/zone->zone-config-str
                                   {:name "Foo"
                                    :rank 100
                                    :active true
                                    :zips "11111,22222,33333"})
                          dash-user-id)
      (refresh)
      ;; go to zones tab
      (wait-until #(exists? zones-tab))
      (click (find-element zones-tab))
      ;; Zip Codes: label exists
      (is (exists? {:xpath "//span[text()='Zip Codes: ']"})))
    (testing "A zone can be edited by a user with proper permissions"
      ;; give zone creation perms to user
      (data-tools/give-perms!
       {:user (login/get-user-by-email
               db-conn "foo@bar.com")
        :db-conn db-conn
        :perms
        "view-dash,view-couriers,view-users,view-zones,edit-zones,view-orders"})
      (refresh)
      ;; go to zones tab
      (wait-until #(exists? zones-tab))
      (click zones-tab)
      (wait-until #(visible? zones-edit-button))
      (is (visible? zones-edit-button)))
    (testing "A basic zone can be created and all zips are added"
      ;; give zone creation perms to user
      (data-tools/give-perms!
       {:user (login/get-user-by-email
               db-conn "foo@bar.com")
        :db-conn db-conn
        :perms "view-dash,view-couriers,view-users,view-zones,edit-zones,create-zones,view-orders"})
      (refresh)
      (wait-until #(exists? zones-tab))
      (click zones-tab)
      (create-zone! {:name "Bar" :rank 1000 :active? true})
      (input-text zone-form-zip-codes "90210, 90211, 90212")
      (wait-until #(exists? save-button))
      (click save-button)
      (wait-until #(exists? yes-button))
      (click yes-button)
      (wait-until
       #(exists?
         {:xpath "//table//th[text()='Rank']/../../..//tr/td/span[text()='Bar']"
          }))
      (is (exists?
           {:xpath
            "//table//th[text()='Rank']/../../..//tr/td/span[text()='Bar']"})))
    (testing "A user fails to edit a zone if their definition is stale"
      ;; try to edit the delivery times
      (wait-until #(exists? zones-edit-button))
      (click zones-edit-button)
      (wait-until #(exists? add-delivery-times))
      (click add-delivery-times)
      (wait-until #(exists? one-hour-checkbox))
      (click one-hour-checkbox)
      (click save-button)
      ;; update the zone behind the scenes
      (zones/update-zone! db-conn (-> (zones/get-zone-by-name db-conn "Foo")
                                      (test-zones/stringify-config)
                                      (assoc :rank 1000))
                          dash-user-id)
      (wait-until #(exists? yes-button))
      (click yes-button)
      (wait-until #(exists? {:xpath "//div[contains(@class,'alert-danger') and contains(text(),'Someone else was editing this zone while you were.')]"}))
      (check-error-alert "Someone else was editing this zone while you were. Click 'Dismiss' and click the refresh button below to get the updated version before making changes.")
      (wait-until #(exists? dismiss-button))
      (click dismiss-button)
      (click table-refresh-button)
      ;; let's try editing again
      (wait-until #(exists? zones-edit-button))
      (click zones-edit-button)
      (wait-until #(exists? add-delivery-times))
      (click add-delivery-times)
      (wait-until #(exists? one-hour-checkbox))
      (click one-hour-checkbox)
      (click save-button)
      (wait-until #(exists? yes-button))
      (click yes-button)
      (wait-until #(exists? {:xpath "//span[text()='Delivery Times Available: ']/parent::h5[text()='3 Hour, 5 Hour']"}))
      (is (exists? {:xpath "//span[text()='Delivery Times Available: ']/parent::h5[text()='3 Hour, 5 Hour']"})))
    ;; test that when updated:

    ;; 1. There are no double entries for zones

    ;; 2. That zips that are only members of earth and the current zone
    ;;    are removed
    ;;
    ;; 3. when zips are assigned to more than 2 zones, they are updated
    ;;    and not deleted

    ;; 4. new zips are added to the database

    ;; when a name is updated, it can't have a name that is already taken

    ;; when a zone's gas price is changed, a zip changes to the proper price

    ;; when a zone is changed, a zip within it is closed

    ;; whena zone's custom closed message is changed, it is updated for a zip

    ;; that a zip respects its zone's hours

    ;; that a zips respects its delivery fees

    ;; 1 hr deliveries can be turned off
    ))
