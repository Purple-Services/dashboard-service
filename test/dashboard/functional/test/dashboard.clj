(ns dashboard.functional.test.dashboard
  (:require [clj-webdriver.taxi :refer :all]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [common.db :as db]
            [environ.core :refer [env]]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :refer [setup-ebdb-test-pool!
                                             clear-test-database
                                             setup-ebdb-test-for-conn-fixture
                                             clear-and-populate-test-database
                                             clear-and-populate-test-database-fixture]]
            [dashboard.handler]
            [dashboard.login :as login]
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
      ;;(check-error-alert "Error: Incorrect email / password combination.")
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
      (wait-until #(exists? logout))
      ;;(is (exists? (find-element logout)))
      )
    (testing "Log back out."
      (logout-dashboard)
      (is (exists? (find-element login-button))))))
