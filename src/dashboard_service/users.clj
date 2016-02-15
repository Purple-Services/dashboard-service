(ns dashboard-service.users
  (:require [clojure.set :refer [join]]
            [clojure.string :as s]
            [crypto.password.bcrypt :as bcrypt]
            [common.db :refer [!select mysql-escape-str]]
            [common.users :refer [send-push]]
            [common.util :refer [in? sns-publish sns-client]]))

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
