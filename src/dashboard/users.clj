(ns dashboard.users
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.edn :as edn]
            [clojure.set :refer [join]]
            [clojure.string :as s]
            [crypto.password.bcrypt :as bcrypt]
            [common.db :refer [mysql-escape-str !select !update !insert]]
            [common.users :refer [send-push get-user-by-id]]
            [common.util :refer [in? sns-publish sns-client sns-client]]))

(def users-select
  [:id :name :email :phone_number :os
   :app_version :stripe_default_card
   :stripe_cards :sift_score
   :arn_endpoint :timestamp_created
   :referral_gallons :admin_event_log
   :subscription_id :referral_code :is_courier
   :type])

(defn process-admin-log
  [log admins]
  (let [edn-log (edn/read-string log)
        admin-ids  (filter (comp not nil?)
                           (distinct (map :admin_id edn-log)))
        get-admin (fn [a] (first (filter #(= (:id %) a)
                                         admins)))]
    (into [] (map #(assoc % :admin_email (:email (get-admin (:admin_id %))))
                  edn-log))))

(defn process-user
  "Process a user to be included as a JSON response"
  [user admins db-conn]
  (let [orders-count (:total
                      (first (!select db-conn "orders" ["count(*) as total"]
                                      {:user_id (:id user)})))
        last-active (:target_time_start
                     (first (!select
                             db-conn
                             "orders"
                             [:target_time_start]
                             {:user_id (:id user)}
                             :append "ORDER BY target_time_start DESC LIMIT 1"
                             )))]
    (assoc user
           :timestamp_created
           (/ (.getTime
               (:timestamp_created user))
              1000)
           :admin_event_log
           (process-admin-log (:admin_event_log user) admins)
           :orders_count orders-count
           :last_active last-active)))

(defn dash-users
  "Return all users who are either couriers or a user who has placed an
   order"
  [db-conn]
  (let [all-couriers (->> (!select db-conn "couriers" ["*"] {})
                          ;; remove chriscourier@test.com
                          (remove #(in? ["9eadx6i2wCCjUI1leBBr"] (:id %))))
        couriers-by-id (into {} (map (juxt (comp keyword :id) identity)
                                     all-couriers))
        courier-ids (distinct (map :id all-couriers))
        recent-orders (!select db-conn
                               "orders"
                               ["*"]
                               {}
                               :append " LIMIT 100")
        users (!select db-conn "users"
                       users-select
                       {}
                       :custom-where
                       (let [customer-ids
                             (distinct (map :user_id recent-orders))]
                         (str "id IN (\""
                              (s/join "\",\"" (distinct
                                               (concat customer-ids
                                                       courier-ids)))
                              "\")")))
        admins (!select db-conn "dashboard_users" [:email :id] {})]
    (map #(process-user % admins db-conn) users)))

(defn send-push-to-all-active-users
  [db-conn message]
  (do (future (run! #(send-push db-conn (:id %) message)
                    (!select db-conn "ActiveUsers" [:id :name] {})))
      {:success true}))

(defn send-push-to-table-view
  "Send push notifications to all users in mySQL view-table. Assumes that
  the view will have an arn_enpoint column."
  [db-conn message table-view]
  (do (future (run! #(sns-publish sns-client (:arn_endpoint %) message)
                    (!select db-conn table-view [:arn_endpoint] {})))
      {:success true :message (str "You sent " message " to " table-view)}))

(defn send-push-to-users-list
  [db-conn message user-ids]
  (do (future (run! #(send-push db-conn (:id %) message)
                    (!select db-conn
                             "users"
                             [:id :name]
                             {}
                             :custom-where
                             (str "id IN (\""
                                  (->> user-ids
                                       (map mysql-escape-str)
                                       (interpose "\",\"")
                                       (apply str))
                                  "\")"))))
      {:success true}))

(defn send-push-to-user
  [db-conn message user-id]
  (do (future (run! #(send-push db-conn (:id %) message)
                    (!select db-conn
                             "users"
                             [:id :name]
                             {}
                             :custom-where
                             (str "id IN (\""
                                  (->> user-id
                                       (mysql-escape-str)
                                       (apply str))
                                  "\")"))))
      {:success true}))

(defn search-users
  "Search users by term"
  [db-conn term]
  (let [escaped-term (mysql-escape-str term)
        phone-number-term (-> term
                              (s/replace #"-|\(|\)" "")
                              mysql-escape-str)
        admins (!select db-conn "dashboard_users" [:email :id] {})
        users (!select db-conn "users"
                       users-select
                       {}
                       :custom-where
                       (str "`id` LIKE '%" escaped-term "%' "
                            "OR `name` LIKE  '%" escaped-term "%' "
                            "OR `phone_number` LIKE '%" phone-number-term "%' "
                            "OR `email` LIKE '%" escaped-term "%' "
                            "OR `referral_code` LIKE '%" escaped-term "%'")
                       :append "ORDER BY timestamp_created LIMIT 100")]
    (map #(process-user % admins db-conn) users)))

(def user-validations
  {:referral_gallons [
                      [v/number :message "Referral gallons must be a number"]
                      [v/in-range [0 50000]
                       :message "Must be within 0 and 50,000 referral gallons"]
                      ]})

(defn update-user!
  "Given a user map, validate it. If valid, update user else return the bouncer
  error map"
  [db-conn user]
  (if (b/valid? user user-validations)
    (let [{:keys [admin_id referral_comment referral_gallons id]} user
          db-user  (dissoc (get-user-by-id db-conn id)
                           :account_manager_id)
          event-log (if-let [log (edn/read-string (:admin_event_log db-user))]
                      log
                      [])
          update-result (!update db-conn "users"
                                 {:referral_gallons referral_gallons}
                                 {:id (:id db-user)})]
      (if (:success update-result)
        (do
          ;; update the log
          (!update db-conn "users"
                   {:admin_event_log
                    (str (merge
                          event-log
                          {:timestamp (quot (System/currentTimeMillis) 1000)
                           :admin_id admin_id
                           :action "adjust_referral_gallons"
                           :previous_value (:referral_gallons db-user)
                           :new_value referral_gallons
                           :comment (or referral_comment "")}))}
                   {:id (:id db-user)})
          (assoc update-result :id (:id db-user)))
        ;; update that there was a failure
        (do
          ;; update the log
          (!update db-conn "users"
                   {:admin_event_log
                    (str (merge
                          event-log
                          {:timestamp (quot (System/currentTimeMillis) 1000)
                           :admin_id admin_id
                           :action "adjust_referral_gallons"
                           :previous_value (:referral_gallons db-user)
                           :comment "There was a failure updating gallons"}))}
                   {:id (:id db-user)})
          (assoc update-result :id (:id db-user)))))
    {:success false
     :validation (b/validate user user-validations)}))

;; this is how to get the table views with arn_endpoint
;; SELECT c.table_name FROM INFORMATION_SCHEMA.COLUMNS c INNER JOIN (SELECT table_name,table_type FROM information_schema.tables where table_schema = 'ebdb_prod' AND table_type = 'view') t ON c.table_name = t.table_name WHERE COLUMN_NAME IN ('arn_endpoint') AND TABLE_SCHEMA='ebdb_prod' ;


;;curl 'http://localhost:3001/users/convert-to-courier' -X PUT -H 'Content-Type: application/json' -H 'Cookie: token=YfZmnG0hqYlC5opndFasR1lvWzQKRFM7pwFk6wZdMnPE29yodw8ANVCR0exmk8kmSV7Zcto27yzKttp8nstjDLWu47iejts4dyiwBKVb9ejCP6wRbE8eo04frtGPEGjL; user-id=BCzKBHrWlJz9fddj7Xmc' -d '{"user": {"id":"IVN6rQbtkJiifeRXSFXd"}}'
(defn convert-to-courier!
  "Convert a user to a courier. This creates a courier account"
  [db-conn user]
  (let [{:keys [id]} user
        current-user (first (!select db-conn "users" users-select
                                     {:id (:id user)}))
        is-native?  (boolean (= (:type current-user) "native"))]
    (println (:type current-user))
    (cond (not is-native?)
          {:success false
           :message (str "This user registered with " (:type current-user) ". "
                         "They must register through the app using an email "
                         "address in order to be a courier.")}
          (> (:total
              (first (!select db-conn "orders" ["count(*) as total"]
                              {:user_id (:id user)})))
             0)
          {:success false
           :message (str "This user already has already made order requests as "
                         "a customer! A courier can not have any fuel delivery "
                         "requests. They will need to create another user "
                         "account with an email address that is different from "
                         "their customer account.")}
          :else
          (let [new-courier-result (!insert db-conn "couriers"
                                            {:id id
                                             :active 1
                                             :busy 0
                                             :lat 0
                                             :lng 0
                                             :zones ""})
                set-is-courier-result (!update db-conn "users"
                                               {:is_courier 1} {:id id})]
            (cond (and (:success new-courier-result)
                       (:success set-is-courier-result))
                  {:success true
                   :message "User successfully converted to a courier."}
                  (not (:success set-is-courier-result))
                  {:success false
                   :message "User could not be updated"}
                  (not (:success new-courier-result))
                  {:success false
                   :message "Courier account could not be created"}
                  :else {:success false
                         :message "Unknown error occured"})))))
