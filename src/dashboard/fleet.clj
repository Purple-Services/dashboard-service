(ns dashboard.fleet
  (:require [clojure.java.jdbc :as sql]
            [common.db :refer [conn !select !update mysql-escape-str]]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [opt.planner :refer [compute-distance]]
            [common.config :as config]
            [common.couriers :as couriers]
            [common.users :as users]
            [common.util :refer [in? map->java-hash-map split-on-comma]]
            [common.zones :refer [get-zip-def order->zones]]
            [common.orders :as orders]
            [dashboard.db :as db]
            [dashboard.zones :refer [get-all-zones-from-db]]))



(defn fleet-deliveries-since-date
  "Get all fleet deliveries since date. A blank date will return all orders.
  When unix-epoch? is true, assume date is in unix epoch seconds"
  [db-conn date & [unix-epoch?]]
  (cond (not (nil? date))
        (db/raw-sql-query db-conn
                          [(str "SELECT accounts.name as `account_name`, "
                                "fleet_locations.account_id as `account_id`, "
                                "fleet_locations.name as `fleet_location_name`, "
                                "users.name as `courier_id`, "
                                "fleet_deliveries.id as `id`, "
                                "fleet_deliveries.fleet_location_id as `fleet_location_id`, "
                                "fleet_deliveries.timestamp_created as `timestamp_created`, "
                                "fleet_deliveries.vin as `vin`, "
                                "fleet_deliveries.license_plate as `license_plate`, "
                                "fleet_deliveries.gallons as `gallons`, "
                                "fleet_deliveries.gas_type as `gas_type`, "
                                "fleet_deliveries.gas_price as `gas_price`, "
                                "fleet_deliveries.service_fee as `service_fee`, "
                                "fleet_deliveries.total_price as `total_price`, "
                                "fleet_deliveries.gas_type as `Octane`, "
                                "if(fleet_deliveries.is_top_tier, 'Yes', 'No') as `is_top_tier`, "
                                "fleet_deliveries.approved as `approved`, "
                                "fleet_deliveries.deleted as `deleted` "
                                "FROM `fleet_deliveries` "
                                "LEFT JOIN fleet_locations ON fleet_locations.id = fleet_deliveries.fleet_location_id "
                                "LEFT JOIN accounts ON accounts.id = fleet_locations.account_id "
                                "LEFT JOIN users ON fleet_deliveries.courier_id = users.id "
                                "WHERE fleet_deliveries.timestamp_created >= "
                                (if unix-epoch?
                                  (str "FROM_UNIXTIME(" date ")")
                                  (str "'" date "'")))])
        
        (nil? date) []
        
        :else {:success false
               :message "Unknown error occured"}))

(def delivery-validations
  {:gallons [v/required]})

(defn update-fleet-delivery!
  [db-conn delivery]
  (if (b/valid? delivery delivery-validations)
    (let [update-result (!update db-conn "fleet_deliveries"
                                 (dissoc delivery :id)
                                 {:id (:id delivery)})]
      update-result)
    {:success false
     :validation (b/validate delivery delivery-validations)}))

(defn approve-fleet-deliveries!
  [db-conn ids]
  (if (sql/with-connection db-conn
        (sql/do-prepared
         (str "UPDATE fleet_deliveries SET approved = 1"
              " WHERE id IN (\""
              (s/join "\",\"" (map mysql-escape-str ids))
              "\")")))
    {:success true}
    {:success false}))

(defn delete-fleet-deliveries!
  [db-conn ids]
  (if (sql/with-connection db-conn
        (sql/do-prepared
         (str "UPDATE fleet_deliveries SET deleted = 1"
              " WHERE id IN (\""
              (s/join "\",\"" (map mysql-escape-str ids))
              "\")")))
    {:success true}
    {:success false}))

(defn download-fleet-deliveries
  [db-conn ids]
  (let [result (!select db-conn
                        "fleet_deliveries"
                        ["*"] {}
                        :custom-where
                        (str "id IN (\""
                             (s/join "\",\"" (map mysql-escape-str ids))
                             "\")"))
        _ (println result)]
    {:success true} ;; need to send back CSV here?
    ))
