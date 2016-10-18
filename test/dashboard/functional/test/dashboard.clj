(ns dashboard.functional.test.dashboard
  (:require [clj-webdriver.taxi :refer :all]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [common.db :as db]
            [environ.core :refer [env]]
            [dashboard.handler]
            [dashboard.login :as login]
            [dashboard.users :as users]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :refer [setup-ebdb-test-pool!
                                             clear-test-database
                                             setup-ebdb-test-for-conn-fixture
                                             clear-and-populate-test-database
                                             clear-and-populate-test-database-fixture]]
            [ring.adapter.jetty :refer [run-jetty]]))

;; note: you will need a bit of elisp in order be able to use load the file
;; without having the vars named improperly
;; here is an example of what you will need below:
;; (defun portal-clj-reset ()
;;   (when (string= (buffer-name) "dashboard.clj")
;;     (cider-interactive-eval
;;      "(reset-vars!)")))
;; (add-hook 'cider-mode-hook
;; 	  (lambda ()
;; 	    (add-hook 'cider-file-loaded-hook 'portal-clj-reset)))

;; for manual testing:
;; (setup-test-env!) ; make sure profiles.clj was loaded with
;;                   ; :base-url "http:localhost:5746/"
;; -- run tests --
;; (reset-db!) ; note: most tests will need this run between them anyhow
;; -- run more tests
;; (stop-server server)
;; (stop-browser)


;; normally, the test server runs on port 3000. If you would like to manually
;; run tests, you can set this to (def test-port 3000) in the repl
;; just reload this file (C-c C-l in cider) when running
(def test-port 5747)
(def test-base-url (str "http://localhost:" test-port "/"))
(def base-url test-base-url)


;; common elements
(def logout-lg-xpath
  {:xpath "//ul/li[contains(@class,'hidden-lg')]//a[text()='Logout']"})
(def logout-sm-xpath
  {:xpath "//ul[contains(@class,'hidden-xs')]/li//a[text()='Logout']"})

(def login-button {:xpath "//button[@id='login']"})

(def login-email-input
  {:xpath "//input[@id='email']"})
(def login-password-input
  {:xpath "//input[@id='password']"})


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

(def zone-submit
  {:xpath "//button[text()='Save']"})

(def yes-button
  {:xpath "//button[text()='Yes']"})

(defn start-server [port]
  (let [_ (setup-ebdb-test-pool!)
        server (run-jetty #'dashboard.handler/handler
                          {:port port
                           :join? false
                           })]
    server))

(defn stop-server [server]
  (do
    (clear-test-database)
    ;; close out the db connection
    (.close (:datasource (db/conn)))
    (.stop server)))

(defn with-server [t]
  (let [server (start-server test-port)]
    (t)
    (stop-server server)))

(defn start-browser []
  (set-driver! {:browser :chrome}))

(defn stop-browser []
  (quit))

(defn with-browser [t]
  (start-browser)
  (t)
  (stop-browser))

(defn with-redefs-fixture [t]
  (with-redefs [common.config/base-url test-base-url]
    (t)))

(use-fixtures :once with-server with-browser with-redefs-fixture)
(use-fixtures :each clear-and-populate-test-database-fixture)

;; beging fns for testing at the repl
(defn reset-vars!
  []
  (def base-url (env :base-url))
  ;; obviously means that :base-url will use port 5744
  (def test-port 5746))

(defn set-server!
  []
  (def server (start-server test-port))
  (setup-ebdb-test-pool!))

(defn setup-test-env!
  []
  (reset-vars!)
  (set-server!)
  (start-browser))

(defn reset-db! []
  (clear-and-populate-test-database))

;; end fns for testing at the repl

;; this function is used to slow down clojure so the browser has time to catch
;; up. If you are having problems with tests passing, particuarly if they appear
;; to randomly fail, try increasing the amount of sleep time before the call
;; that is failing
(defn sleep
  "Sleep for ms."
  [& [ms]]
  (let [default-ms 700
        time (or ms default-ms)]
    (Thread/sleep time)))

(defn go-to-login-page
  "Navigate to the portal"
  []
  (to (str base-url "login")))

(defn go-to-uri
  "Given an uri, go to it"
  [uri]
  (to (str base-url uri)))


(defn login-dashboard
  "Login with the client using email and password as credentials"
  [email password]
  (go-to-uri "login")
  (let [email-input    (find-element login-email-input)
        password-input (find-element {:xpath "//input[@type='password']"})]
    (input-text email-input email)
    (input-text password-input password)
    (click (find-element login-button))))

(defn logout-dashboard
  "Logout, assuming the portal has already been logged into"
  []
  (click (if (visible? (find-element logout-lg-xpath))
           (find-element logout-lg-xpath)
           (find-element logout-sm-xpath))))

(defn check-error-alert
  "Wait for an error alert to appear and test that it says msg"
  [msg]
  (let [alert-danger {:xpath "//div[contains(@class,'alert-danger')]"}]
    (wait-until #(exists?
                  alert-danger))
    (is (= msg
           (text (find-element
                  alert-danger))))))

(defn check-success-alert
  "Wait for an error alert to appear and test that it says msg"
  [msg]
  (let [alert-danger {:xpath "//div[contains(@class,'alert-success')]"}]
    (wait-until #(exists?
                  alert-danger))
    (is (= msg
           (text (find-element
                  alert-danger))))))

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
    (go-to-uri "login")
    (login-dashboard dash-email dash-password)
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
      (go-to-uri "login")
      (login-dashboard email password)
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
      (go-to-uri "login")
      (login-dashboard email password)
      (wait-until #(exists? logout)))
    (testing "Log back out."
      (logout-dashboard)
      (is (exists? (find-element login-button))))))


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
        ]
    ;; setup the env
    (create-minimal-dash-env! {:dash-user dash-user
                               :app-user app-user
                               :db-conn db-conn})
    ;; login the dash user
    (login-dashboard (:email dash-user) (:password dash-user))
    ;; go to zones
    (wait-until #(exists? zones-tab))
    (click (find-element zones-tab))
    ;; test that when a zone is created, all zips are added
    (create-zone! {:name "Foo" :rank 1000 :active? true})
    (input-text zone-form-zip-codes "90210, 90211, 90212")
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
