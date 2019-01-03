(ns dashboard.gas-purchases
  (:require [common.db :refer [conn !select !update mysql-escape-str]]
            [common.config :as config]
            [common.util :refer [in? map->java-hash-map split-on-comma cents->dollars-str now-unix rand-str-alpha-num]]
            [dashboard.db :as db]
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

(defn gas-purchases-since-date
  "Get all gas purchases since date. A blank date will return all orders.
  When unix-epoch? is true, assume date is in unix epoch seconds"
  [db-conn from-date to-date search-term]
  (if (and (not (nil? from-date))
           (not (nil? to-date)))
    (or (!select db-conn "gas_purchases" ["*"] {}
                 :custom-where
                 (str "timestamp_recorded >= "
                      "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                      (-> from-date
                          (str " 00:00:00")
                          mysql-escape-str)
                      "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))"
                      " AND timestamp_recorded <= "
                      "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                      (-> to-date
                          (str " 23:59:59")
                          mysql-escape-str)
                      "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))"))
        [])
    []))

(defn update-gas-purchase-field!
  [db-conn id field-name value]
  (if-let [input-errors
           (first
            (b/validate
             {(keyword field-name) (cond
                                     (in? ["gallons"] field-name)
                                     (try (Double/parseDouble (str value))
                                          (catch Exception e 9999999))

                                     (in? ["total_price"] field-name)
                                     (try (Integer. (str value))
                                          (catch Exception e 9999999))

                                     :else value)}
             :gallons [[v/in-range [0.0000001 999999]]]
             :total_price [v/integer [v/in-range [0 999999]]]))]
    {:success false
     :message (str (s/join ". " (flatten (vals input-errors))) ".")}
    (if (sql/with-connection db-conn
          (sql/do-prepared
           (str "UPDATE gas_purchase SET "
                (mysql-escape-str field-name)
                " = "
                (str "\"" (mysql-escape-str (str value)) "\"")
                (cond
                  (= "timestamp_recorded" field-name)
                  ", was_timestamp_manually_changed = 1"
                  
                  :else "")
                " WHERE id = \""
                (mysql-escape-str id)
                "\"")))
      {:success true}
      {:success false})))

(defn add-blank-gas-purchase!
  [db-conn fleet-location-id]
  {:success false}
  ;; (if (sql/with-connection db-conn
  ;;       (sql/do-prepared
  ;;        (str "INSERT INTO fleet_deliveries "
  ;;             "(id, courier_id, fleet_location_id, gallons, gas_type, "
  ;;             "is_top_tier, gas_price, service_fee, total_price, "
  ;;             "timestamp_recorded) VALUES "
  ;;             "('" (rand-str-alpha-num 20) "', "
  ;;             "'', "
  ;;             "'" (mysql-escape-str fleet-location-id) "', "
  ;;             "0, "
  ;;             "'87', "
  ;;             "1, "
  ;;             "0, "
  ;;             "0, "
  ;;             "0, "
  ;;             (now-unix)
  ;;             ")")))
  ;;   {:success true}
  ;;   {:success false})
  )

(defn delete-gas-purchases!
  [db-conn ids]
  (if (sql/with-connection db-conn
        (sql/do-prepared
         (str "UPDATE gas_purchases SET deleted = 1"
              " WHERE id IN (\""
              (s/join "\",\"" (map mysql-escape-str ids))
              "\")")))
    {:success true}
    {:success false}))

(defn download-gas-purchases
  [db-conn ids]
  (let [results (db/raw-sql-query
                 db-conn
                 [(str "SELECT users.name as `courier_name`, "
                       "gas_purchases.id as `id`, "
                       "date_format(convert_tz(FROM_UNIXTIME(gas_purchases.timestamp_recorded), \"UTC\", "
                       "\"America/Los_Angeles\"),\"%Y-%m-%d %H:%i:%S\") as `timestamp_recorded`, "
                       "gas_purchases.gallons as `gallons`, "
                       "gas_purchases.gas_type as `gas_type`, "
                       "gas_purchases.total_price as `total_price`, "
                       "gas_purchases.deleted as `deleted` "
                       "FROM `gas_purchases` "
                       "LEFT JOIN users ON gas_purchases.courier_id = users.id "
                       "WHERE gas_purchases.id IN (\""
                       (s/join "\",\"" (map mysql-escape-str ids))
                       "\") "
                       "ORDER BY gas_purchases.timestamp_recorded DESC")])]
    (ring-io/piped-input-stream
     (fn [ostream]
       (let [writer (io/writer ostream)]
         (csv/write-csv
          writer
          (apply vector
                 (concat [["Timestamp (Pacific)"
                           "Purchase ID"
                           "Courier"
                           "Octane"
                           "Gallons"
                           "Total Price"]]
                         (map (fn [o]
                                (vec [(:timestamp_recorded o)
                                      (:id o)
                                      (:courier_name o)
                                      (:gas_type o)
                                      (:gallons o)
                                      (cents->dollars-str (:total_price o))]))
                              results))))
         (.flush writer))))))

