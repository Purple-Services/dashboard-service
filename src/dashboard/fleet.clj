(ns dashboard.fleet
  (:require [common.db :refer [conn !select !update mysql-escape-str]]
            [opt.planner :refer [compute-distance]]
            [common.config :as config]
            [common.couriers :as couriers]
            [common.users :as users]
            [common.util :refer [in? map->java-hash-map split-on-comma cents->dollars-str now-unix rand-str-alpha-num]]
            [common.zones :refer [get-zip-def order->zones]]
            [common.vin :refer [get-info-batch]]
            [common.orders :as orders]
            [dashboard.db :as db]
            [dashboard.zones :refer [get-all-zones-from-db]]
            [clojure.java.jdbc :as sql]
            [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.edn :as edn]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [ring.util.io :as ring-io]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]))

(defn all-fleet-locations
  [db-conn]
  (sort-by :name (!select db-conn "fleet_locations" ["*"] {})))

(defn fleet-deliveries-since-date
  "Get all fleet deliveries since date. A blank date will return all orders.
  When unix-epoch? is true, assume date is in unix epoch seconds"
  [db-conn from-date to-date search-term]
  (if (and (not (nil? from-date))
           (not (nil? to-date)))
    (or (db/raw-sql-query db-conn
                          [(str "SELECT accounts.name as `account_name`, "
                                "fleet_locations.account_id as `account_id`, "
                                "fleet_locations.name as `fleet_location_name`, "
                                "users.name as `courier_name`, "
                                "fleet_deliveries.id as `id`, "
                                "fleet_deliveries.fleet_location_id as `fleet_location_id`, "
                                "fleet_deliveries.timestamp_created as `timestamp_created`, "
                                "fleet_deliveries.timestamp_recorded as `timestamp_recorded`, "
                                "fleet_deliveries.vin as `vin`, "
                                "fleet_deliveries.license_plate as `license_plate`, "
                                "fleet_deliveries.gallons as `gallons`, "
                                "fleet_deliveries.gas_type as `gas_type`, "
                                "fleet_deliveries.gas_price as `gas_price`, "
                                "fleet_deliveries.service_fee as `service_fee`, "
                                "fleet_deliveries.total_price as `total_price`, "
                                "fleet_deliveries.gas_type as `Octane`, "
                                "fleet_deliveries.is_top_tier as `is_top_tier`, "
                                "fleet_deliveries.approved as `approved`, "
                                "fleet_deliveries.deleted as `deleted` "
                                "FROM `fleet_deliveries` "
                                "LEFT JOIN fleet_locations ON fleet_locations.id = fleet_deliveries.fleet_location_id "
                                "LEFT JOIN accounts ON accounts.id = fleet_locations.account_id "
                                "LEFT JOIN users ON fleet_deliveries.courier_id = users.id "
                                "WHERE fleet_deliveries.timestamp_recorded >= "
                                (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                                     (-> from-date
                                         (str " 00:00:00")
                                         mysql-escape-str)
                                     "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                                " AND fleet_deliveries.timestamp_recorded <= "
                                (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                                     (-> to-date
                                         (str " 23:59:59")
                                         mysql-escape-str)
                                     "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                                (when (not (s/blank? search-term))
                                  (str " AND (vin LIKE \"%"
                                       (mysql-escape-str search-term)
                                       "%\" OR license_plate LIKE \"%"
                                       (mysql-escape-str search-term)
                                       "%\")")))])
        [])
    []))

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

(defn get-by-index
  "Get an el from coll whose k is equal to v. Returns nil if el doesn't exist"
  [coll k v]
  (first (filter #(= (k %) v) coll)))

(defn update-fleet-delivery-field!
  [db-conn id field-name value]
  (if-let [input-errors
           (first
            (b/validate
             {(keyword field-name) (cond
                                     (in? ["gallons"] field-name)
                                     (try (Double/parseDouble (str value))
                                          (catch Exception e 9999999))

                                     (in? ["gas_price" "service_fee"] field-name)
                                     (try (Integer. (str value))
                                          (catch Exception e 9999999))

                                     :else value)}
             :gallons [[v/in-range [0.0000001 999999]]]
             :gas_price [v/integer [v/in-range [0 999999]]]
             :service_fee [v/integer [v/in-range [0 999999]]]))]
    {:success false
     :message (str (s/join ". " (flatten (vals input-errors))) ".")}
    (if (sql/with-connection db-conn
          (sql/do-prepared
           (str "UPDATE fleet_deliveries SET "
                (mysql-escape-str field-name)
                " = "
                (case field-name
                  "is_top_tier" value
                  (str "\"" (mysql-escape-str (str value)) "\""))
                (cond 
                  (in? ["gallons" "gas_price" "service_fee"] field-name)
                  (str ", total_price = GREATEST(0, CEIL((gas_price * gallons) + service_fee))")

                  (= "vin" field-name)
                  (let [vin (s/upper-case value)
                        vin-info (get-by-index (->> [vin]
                                                    distinct
                                                    (into [])
                                                    get-info-batch
                                                    :resp)
                                               :vin
                                               vin)]
                    (when vin-info
                      (str ", year = \""
                           (mysql-escape-str (:year vin-info))
                           "\", make = \""
                           (mysql-escape-str (:make vin-info))
                           "\", model = \""
                           (mysql-escape-str (:model vin-info))
                           "\"")))

                  (= "timestamp_recorded" field-name)
                  ", was_timestamp_manually_changed = 1"
                  
                  :else "")
                " WHERE id = \""
                (mysql-escape-str id)
                "\"")))
      {:success true}
      {:success false})))

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

(defn add-blank-fleet-delivery!
  [db-conn fleet-location-id]
  (if (sql/with-connection db-conn
        (sql/do-prepared
         (str "INSERT INTO fleet_deliveries "
              "(id, courier_id, fleet_location_id, gallons, gas_type, "
              "is_top_tier, gas_price, service_fee, total_price, "
              "timestamp_recorded) VALUES "
              "('" (rand-str-alpha-num 20) "', "
              "'', "
              "'" (mysql-escape-str fleet-location-id) "', "
              "0, "
              "'87', "
              "1, "
              "0, "
              "0, "
              "0, "
              (now-unix)
              ")")))
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
  (let [results (db/raw-sql-query
                 db-conn
                 [(str "SELECT accounts.name as `account_name`, "
                       "fleet_locations.account_id as `account_id`, "
                       "fleet_locations.name as `fleet_location_name`, "
                       "users.name as `courier_name`, "
                       "fleet_deliveries.id as `id`, "
                       "fleet_deliveries.fleet_location_id as `fleet_location_id`, "
                       "date_format(convert_tz(FROM_UNIXTIME(fleet_deliveries.timestamp_recorded), \"UTC\", "
                       "\"America/Los_Angeles\"),\"%Y-%m-%d %H:%i:%S\") as `timestamp_recorded`, "
                       "fleet_deliveries.year as `year`, "
                       "fleet_deliveries.make as `make`, "
                       "fleet_deliveries.model as `model`, "
                       "fleet_deliveries.vin as `vin`, " 
                       "fleet_deliveries.license_plate as `license_plate`, "
                       "fleet_deliveries.gallons as `gallons`, "
                       "fleet_deliveries.gas_type as `gas_type`, "
                       "fleet_deliveries.gas_price as `gas_price`, "
                       "fleet_deliveries.service_fee as `service_fee`, "
                       "fleet_deliveries.total_price as `total_price`, "
                       "fleet_deliveries.gas_type as `gas_type`, "
                       "if(fleet_deliveries.is_top_tier, 'Yes', 'No') as `is_top_tier`, "
                       "fleet_deliveries.approved as `approved`, "
                       "fleet_deliveries.deleted as `deleted` "
                       "FROM `fleet_deliveries` "
                       "LEFT JOIN fleet_locations ON fleet_locations.id = fleet_deliveries.fleet_location_id "
                       "LEFT JOIN accounts ON accounts.id = fleet_locations.account_id "
                       "LEFT JOIN users ON fleet_deliveries.courier_id = users.id "
                       "WHERE fleet_deliveries.id IN (\""
                       (s/join "\",\"" (map mysql-escape-str ids))
                       "\") "
                       "ORDER BY fleet_deliveries.timestamp_recorded DESC")])]
    (ring-io/piped-input-stream
     (fn [ostream]
       (let [writer (io/writer ostream)]
         (csv/write-csv
          writer
          (apply vector
                 (concat [["Timestamp (Pacific)"
                           "Order ID"
                           "Location"
                           "Courier"
                           "Make"
                           "Model"
                           "Year"
                           "VIN"
                           "Plate or Stock Number"
                           "Octane"
                           "Top Tier?"
                           "Gallons"
                           "Gallon Price"
                           "Service Fee"
                           "Total Price"]]
                         (map (fn [o]
                                (vec [(:timestamp_recorded o)
                                      (:id o)
                                      (:fleet_location_name o)
                                      (:courier_name o)
                                      (:make o)
                                      (:model o)
                                      (:year o)
                                      (:vin o)
                                      (:license_plate o)
                                      (:gas_type o)
                                      (:is_top_tier o)
                                      (:gallons o)
                                      (cents->dollars-str (:gas_price o))
                                      (cents->dollars-str (:service_fee o))
                                      (cents->dollars-str (:total_price o))]))
                              results))))
         (.flush writer))))))

