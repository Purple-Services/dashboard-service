(ns dashboard-clj.users
  (:require [clojure.set :refer [join]]
            [clojure.string :as s]
            [crypto.password.bcrypt :as bcrypt]
            [dashboard-clj.db :refer [!select mysql-escape-str]]
            [dashboard-clj.util :refer [in? sns-publish sns-client]]))

(def safe-authd-user-keys
  "The keys of a user map that are safe to send out to auth'd user."
  [:id :type :email :name :phone_number :referral_code
   :referral_gallons :is_courier])

(defn valid-email?
  "Syntactically valid email address?"
  [email]
  (boolean (re-matches #"^\S+@\S+\.\S+$" email)))

(defn valid-password?
  "Only for native users."
  [password]
  (boolean (re-matches #"^.{6,100}$" password)))

(defn auth-native?
  "Is password correct for this user map?"
  [user auth-key]
  (bcrypt/check auth-key (:password_hash user)))

(defn valid-session?
  [db-conn user-id token]
  (let [session (!select db-conn
                         "sessions"
                         [:id
                          :timestamp_created]
                         {:user_id user-id
                          :token token})]
    (if (seq session)
      true
      false)))

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
                       [:id :name :email :phone_number :os
                        :app_version :stripe_default_card
                        :stripe_cards
                        :sift_score
                        :arn_endpoint :timestamp_created]
                       {}
                       :custom-where
                       (let [customer-ids
                             (distinct (map :user_id recent-orders))]
                         (str "id IN (\""
                              (s/join "\",\"" (distinct
                                               (concat customer-ids
                                                       courier-ids)))
                              "\")")))]
    (map #(assoc % :timestamp_created
                 (/ (.getTime
                     (:timestamp_created %))
                    1000))
         users)))

(defn get-user
  "Gets a user from db. Optionally add WHERE constraints."
  [db-conn & {:keys [where]}]
  (first (!select db-conn "users" ["*"] (merge {} where))))

(defn get-user-by-id
  "Gets a user from db by user-id."
  [db-conn user-id]
  (get-user db-conn :where {:id user-id}))

(defn get-users-by-ids
  "Gets multiple users by a list of ids."
  [db-conn ids]
  (if (seq ids)
    (!select db-conn
             "users"
             ["*"]
             {}
             :custom-where
             (str "id IN (\""
                  (->> ids
                       (map mysql-escape-str)
                       (interpose "\",\"")
                       (apply str))
                  "\")"))
    []))

(defn include-user-data
  "Enrich a coll of maps that have :id's of users (e.g., couriers), with user
  data."
  [db-conn m]
  (join m
        (map #(select-keys % safe-authd-user-keys)
             (get-users-by-ids db-conn (map :id m)))
        {:id :id}))

(defn send-push
  "Sends a push notification to user."
  [db-conn user-id message]
  (let [user (get-user-by-id db-conn user-id)]
    (when-not (s/blank? (:arn_endpoint user))
      (sns-publish sns-client
                   (:arn_endpoint user)
                   message))
    {:success true}))

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
