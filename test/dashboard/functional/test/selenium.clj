(ns dashboard.functional.test.selenium
  (:require [clj-webdriver.taxi :refer :all]
            [common.db :as db]
            [dashboard.handler]
            [dashboard.test.db-tools :refer [setup-ebdb-test-pool!
                                             clear-test-database
                                             setup-ebdb-test-for-conn-fixture
                                             clear-and-populate-test-database
                                             clear-and-populate-test-database-fixture]]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]))

;; note: you will need a bit of elisp in order be able to use load the file
;; without having the vars named improperly
;; here is an example of what you will need below
;; ex:  ~/emacs/cider/cider-config.el
;;
;; (defun dashboard-clj-reset ()
;;   (when (string= (buffer-name) "selenium.clj")
;;     (cider-interactive-eval
;;      "(dashboard.functional.test.selenium/reset-vars!)")))
;; (add-hook 'cider-mode-hook
;; 	  (lambda ()
;; 	    (add-hook 'cider-file-loaded-hook 'dashboard-clj-reset)))

;; for manual testing:
;; (startup-test-env!) ; make sure profiles.clj was loaded with
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

(defn start-server [port]
  (let [_ (setup-ebdb-test-pool!)
        ;; !!!BIG WARNING: with-redefs is very tricky in terms of being
        ;; recognized across threads (which jetty uses!)
        ;; that is why alter-var-root is used here
        _   (alter-var-root
             #'common.sendgrid/send-template-email
             (fn [send-template-email]
               (fn [to subject message
                    & {:keys [from template-id substitutions]}]
                 (println "No reset password email was actually sent"))))
        server (run-jetty #'dashboard.handler/handler
                          {:port port
                           :join? false})]
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

;; beging fns for testing at the repl
(defn reset-vars!
  []
  (def base-url (env :base-url))
  ;; obviously means that :base-url will use port 5746
  (def test-port 5746))

(defn set-server!
  []
  (def server (start-server test-port))
  (setup-ebdb-test-pool!))

(defn startup-test-env!
  []
  (reset-vars!)
  (set-server!)
  (start-browser))

(defn shutdown-test-env!
  []
  (stop-server server)
  (stop-browser))

(defn reset-db! []
  (clear-and-populate-test-database))

;; end fns for testing at the repl

;; common elements
(def login-email-input
  {:xpath "//input[@type='text' and @id='email']"})

(def login-button {:xpath "//button[@id='login']"})

;; common elements
(def logout-lg-xpath
  {:xpath "//ul/li[contains(@class,'hidden-lg')]//a[text()='Logout']"})
(def logout-sm-xpath
  {:xpath "//ul[contains(@class,'hidden-xs')]/li//a[text()='Logout']"})


;; fns for controlling the browser
(defn get-table-body-cell-text
  "Given a table, get the text in the table body at row r and column c"
  [table r c]
  (let [table-xpath (or (:xpath table) table)]
    (text {:xpath (str table-xpath "/tbody/tr[position()= " r "]"
                       "/td[position()=" c "]")})))

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


(defn get-error-alert
  "Wait for an error alert to appear and test that it says msg"
  []
  (let [alert-danger {:xpath "//div[contains(@class,'alert-danger')]"}]
    (wait-until #(exists?
                  alert-danger))
    (text (find-element
           alert-danger))))

(defn get-success-alert
  "Wait for an error alert to appear and test that it says msg"
  []
  (let [alert-danger {:xpath "//div[contains(@class,'alert-success')]"}]
    (wait-until #(exists?
                  alert-danger))
    (text (find-element
           alert-danger))))
