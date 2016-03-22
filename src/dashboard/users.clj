(ns dashboard.users
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.edn :as edn]
            [clojure.set :refer [join]]
            [clojure.string :as s]
            [crypto.password.bcrypt :as bcrypt]
            [common.db :refer [mysql-escape-str !select !update]]
            [common.users :refer [send-push get-user-by-id]]
            [common.util :refer [in? sns-publish sns-client]]))

(def users-select
  [:id :name :email :phone_number :os
   :app_version :stripe_default_card
   :stripe_cards :sift_score
   :arn_endpoint :timestamp_created
   :referral_gallons :admin_event_log])

(defn process-admin-log
  "Process the admin_event_log col of a user using a list of admins"
  [log admins]
  (let [edn-log (edn/read-string log)
        admin-ids  (filter (comp not nil?)
                           (distinct (map :admin_id edn-log)))
        get-admin (fn [a] (first (filter #(= (:id %) a)
                                         admins)))]
    (into [] (map #(assoc % :admin_email (:email (get-admin (:admin_id %))))
                  edn-log))))

(defn process-user
  "Process a users to be included as a JSON response"
  [user admins]
  (assoc user
         :timestamp_created
         (/ (.getTime
             (:timestamp_created user))
            1000)
         :admin_event_log
         (process-admin-log (:admin_event_log user) admins)))

(defn dash-users
  "Return all users who are either couriers or a user who has placed an
   order"
  [db-conn]
  (let [all-couriers (->> (!select db-conn "couriers" ["*"] {})
                          ;; remove chriscourier@test.com
                          (remove #(in? ["9eadx6i2wccjui1lebbr"] (:id %))))
        couriers-by-id (into {} (map (juxt (comp keyword :id) identity)
                                     all-couriers))
        courier-ids (distinct (map :id all-couriers))
        recent-orders (!select db-conn
                               "orders"
                               ["*"]
                               {}
                               :append " limit 100")
        users (!select db-conn "users"
                       users-select
                       {}
                       :custom-where
                       (let [customer-ids
                             (distinct (map :user_id recent-orders))]
                         (str "id in (\""
                              (s/join "\",\"" (distinct
                                               (concat customer-ids
                                                       courier-ids)))
                              "\")")))
        admins (!select db-conn "dashboard_users" [:email :id] {})]
    (map #(process-user % admins) users)))

(defn send-push-to-all-active-users
  [db-conn message]
  (do (future (run! #(send-push db-conn (:id %) message)
                    (!select db-conn "ActiveUsers" [:id :name] {})))
      {:success true}))

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

(defn search-users
  "Search users by term"
  [db-conn term]
  (let [users (!select db-conn "users"
                       users-select
                       {}
                       :custom-where
                       (str "`id` LIKE '%" term "%' "
                            "OR `email` LIKE '%" term "%' "
                            "OR `name` LIKE  '%" term "%'"))]
    (map process-user users)))

(def user-validations
  {:referral_gallons [
                      [v/number :message "Referral gallons must be a number"]
                      [v/in-range [0 50]
                       :message "Must be within 0 and 50 referral gallons"]
                      ]})

(defn update-user!
  "Given a user map, validate it. If valid, update user else return the bouncer
  error map"
  [db-conn user]
  (if (b/valid? user user-validations)
    (let [{:keys [admin_id referral_comment referral_gallons id]} user
          db-user (get-user-by-id db-conn id)
          event-log (if-let [log (edn/read-string (:admin_event_log db-user))]
                      log
                      [])
          update-result (!update db-conn "users"
                                 (assoc db-user
                                        :referral_gallons referral_gallons)
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
                           :comment (if referral_comment
                                      referral_comment
                                      "admin adjusted")}))}
                   {:id (:id db-user)})
          (assoc update-result :id (:id db-user)))
        update-result))
    {:success false
     :validation (b/validate user user-validations)}))
