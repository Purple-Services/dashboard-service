(ns dashboard-clj.config
  (:require [environ.core :refer [env]]))

(defn test-or-dev-env? [env]
  "Given env, return true if we are in test or dev"
  (if (or (= (env :env) "test") (= (env :env) "dev")) true false))

(if (test-or-dev-env? env)
  (do
    (System/setProperty "BASE_URL" (env :base-url))
    (System/setProperty "DASHBOARD_GOOGLE_BROWSER_API_KEY"
                        (env :dashboard-google-browser-api-key))
    (System/setProperty "DB_USER" (env :db-user))
    (System/setProperty "EMAIL_USER" (env :email-user))
    (System/setProperty "EMAIL_PASSWORD" (env :email-password))))

;; this is only needed for "only-prod"
(def db-user (System/getProperty "DB_USER"))

;;;; Base Url of the web service
;; Should include trailing forward-slash (e.g., "http://domain.com/")
(def base-url (System/getProperty "BASE_URL"))

;; Google Maps API Key(s)
(def dashboard-google-browser-api-key
  (System/getProperty "DASHBOARD_GOOGLE_BROWSER_API_KEY"))

(def email-from-address (System/getProperty "EMAIL_USER"))
(def email {:host "smtp.gmail.com"
            :user (System/getProperty "EMAIL_USER")
            :pass (System/getProperty "EMAIL_PASSWORD")
            :ssl :yes!!!11})

;; An order can be cancelled only if its status is one of these
(def cancellable-statuses ["unassigned" "assigned" "accepted" "enroute"])
