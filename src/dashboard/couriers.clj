(ns dashboard.couriers
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [common.couriers :refer [parse-courier-zones]]
            [common.db :refer [conn !select !update mysql-escape-str]]
            [common.util :refer [in? split-on-comma get-event-time unix->full cents->dollars]]
            [dashboard.utils :as utils]
            [dashboard.db :as db]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [ring.util.io :as ring-io]
            [clojure.java.jdbc :as sql]))

(defn get-by-id
  "Get a courier from db by courier's id"
  [db-conn id]
  (let [courier (first (!select db-conn
                                "couriers"
                                ["*"]
                                {:id id}))]
    (if (empty? courier)
      {:success false
       :error (str "There is no courier with id: " id)}
      (assoc (parse-courier-zones courier)
             :timestamp_created
             (/ (.getTime
                 (:timestamp_created courier))
                1000)))))

(defn include-lateness
  "Given a courier map m, return a map with :lateness included using
  the past 100 orders"
  [db-conn m]
  (let [db-orders (->>
                   (!select
                    db-conn "orders" ["*"] {}
                    :append (str "ORDER BY target_time_start DESC LIMIT 100"))
                   (map #(assoc %
                                :was-late
                                (let [completion-time
                                      (-> (str "kludgeFix 1|" (:event_log %))
                                          (s/split #"\||\s")
                                          (->> (apply hash-map))
                                          (get "complete"))]
                                  (and completion-time
                                       (> (Integer. completion-time)
                                          (:target_time_end %)))))))]
    (map (fn [courier]
           (assoc courier
                  :lateness
                  (let [orders (filter #(and (= (:courier_id %)
                                                (:id courier))
                                             (= (:status %)
                                                "complete"))
                                       db-orders)
                        total (count orders)
                        late (count (filter :was-late orders))]
                    (if (pos? total)
                      (str (format "%.0f"
                                   (float (- 100
                                             (* (/ late
                                                   total)
                                                100))))
                           "%")
                      "No orders.")))) m)))

(defn include-os-and-app-version
  "Given a courier map m, return a map with :os and :app_version"
  [db-conn m]
  (let [courier-ids (distinct (map :id m))
        courier-users (!select db-conn "users"
                               [:os :app_version :id]
                               {}
                               :custom-where
                               (str "id IN (\""
                                    (s/join "\",\"" (concat courier-ids))
                                    "\")"))]
    (map (fn [courier]
           (let [user-courier (first
                               (filter #(= (:id courier) (:id %))
                                       courier-users))]
             (assoc courier
                    :os (:os user-courier)
                    :app_version (:app_version user-courier))))
         m)))

(defn include-arn-endpoint
  "Given a hash-map of couriers m, return a map with :arn_endpoint assoc'd onto
  each courier"
  [db-conn m]
  (let [courier-ids (distinct (map :id m))
        courier-users (!select db-conn "users"
                               [:arn_endpoint :id]
                               {}
                               :custom-where
                               (str "id IN (\""
                                    (s/join "\",\"" (concat courier-ids))
                                    "\")"))]
    (map (fn [courier]
           (let [user-courier (first
                               (filter #(= (:id courier) (:id %))
                                       courier-users))]
             (assoc courier
                    :arn_endpoint (:arn_endpoint user-courier))))
         m)))

(def courier-validations
  {:zones [;; verify that the zone assignments can be read as edn
           ;; must be done first to prevent throwing an error
           ;; from edn-read
           [#(every? identity
                     (->> %
                          split-on-comma
                          (map utils/edn-read?)))
            :message (str "Zones assignments must be "
                          "comma-separated integer "
                          "(whole number) values!") ]
           ;; verify that all zones are integers
           [#(every? integer?
                     (->> %
                          split-on-comma
                          (map edn/read-string)
                          (filter (comp not nil?))))
            :message (str "All zones must be integer (whole number) "
                          "values!")]
           ;; verify that all zone assignments exist
           [#(let [existant-zones-set (set (map :id (!select (conn) "zones"
                                                             [:id] {})))
                   zones (->> %
                              split-on-comma
                              (map edn/read-string)
                              (filter (comp not nil?)))]
               (every? identity (map (fn [zone]
                                       (contains? existant-zones-set zone))
                                     zones)))
            :message (str "All zones in assignment must exist")]]
   :active [v/required v/boolean]})

(defn update-courier!
  "Update the zones for courier with user-id"
  [db-conn courier]
  ;; make sure the zones string will split into valid edn elements
  (if (b/valid? courier courier-validations)
    (let [{:keys [id zones active]} courier
          zones-str (->> zones
                         split-on-comma
                         (map edn/read-string)
                         (filter (comp not nil?))
                         set
                         sort
                         (s/join ","))
          ;; have to manually get this, get-by-id for courier process
          ;; the courier and leads to problems with timestamp_created
          db-courier (first (!select db-conn "couriers"
                                     ["*"]
                                     {:id id}))
          update-result (!update db-conn "couriers"
                                 (assoc db-courier
                                        :zones zones-str
                                        :active active)
                                 {:id id})]
      (if (:success update-result)
        (assoc update-result :id (:id db-courier))
        {:success false
         :message "database errror"}))
    {:success false
     :validation (b/validate courier courier-validations)}))

(defn download-courier-orders
  [db-conn courier-id]
  (let [results (db/raw-sql-query
                 db-conn
                 [(str "SELECT orders.id as `id`, "
                       "orders.status as `status`, "
                       "date_format(convert_tz(orders.timestamp_created, \"UTC\", "
                       "\"America/Los_Angeles\"),\"%Y-%m-%d %H:%i:%S\") as `timestamp_created`, "
                       "orders.event_log as `event_log`, "
                       "orders.target_time_end as `target_time_end`, "
                       "orders.auto_assign_note as `auto_assign_note`, "
                       "orders.address_street as `address_street`, "
                       "orders.address_zip as `address_zip`, "
                       "users.name as `customer_name`, "
                       "orders.license_plate as `license_plate`, "
                       "orders.gas_type as `gas_type`, "
                       "orders.gallons as `gallons`, "
                       "if(orders.is_top_tier, 'Yes', 'No') as `is_top_tier` "
                       "FROM `orders` "
                       "LEFT JOIN users ON orders.user_id = users.id "
                       "WHERE orders.courier_id = \"" (mysql-escape-str courier-id) "\" "
                       "ORDER BY timestamp_created DESC")])]
    (ring-io/piped-input-stream
     (fn [ostream]
       (let [writer (io/writer ostream)]
         (csv/write-csv
          writer
          (apply
           vector
           (concat [["Order ID"
                     "Status"
                     "Time Placed"
                     "Time Assigned"
                     "Time Completed"
                     "Deadline"
                     "Duration"
                     "Assigned By"
                     "Address"
                     "Customer Name"
                     "License Plate"
                     "Octane"
                     "Gallons"
                     "Top Tier?"]]
                   (map (fn [o]
                          (let [time-assigned (get-event-time (:event_log o) "assigned")
                                time-completed (get-event-time (:event_log o) "complete")]
                            (vec [(:id o)
                                  (:status o)
                                  (:timestamp_created o)
                                  (when time-assigned (unix->full time-assigned))
                                  (when time-completed (unix->full time-completed))
                                  (unix->full (:target_time_end o))
                                  (when (and time-assigned time-completed)
                                    (let [diff (- time-completed time-assigned)
                                          hours (quot diff 3600)
                                          minutes (quot (mod diff 3600) 60)]
                                      (str hours "' " minutes "\"")))
                                  (:auto_assign_note o)
                                  (str (:address_street o)
                                       ", " (:address_zip o))
                                  (:customer_name o)
                                  (:license_plate o)
                                  (:gas_type o)
                                  (:gallons o)
                                  (:is_top_tier o)])))
                        results))))
         (.flush writer))))))

(defn download-courier-statistics
  [db-conn from-date to-date]
  (let [orders (db/raw-sql-query
                db-conn
                [(str "SELECT orders.id as `id`, "
                      "orders.courier_id as `courier_id`, "
                      "users.name as `courier_name`, "
                      "orders.status as `status`, "
                      "date_format(convert_tz(orders.timestamp_created, \"UTC\", "
                      "\"America/Los_Angeles\"),\"%Y-%m-%d %H:%i:%S\") as `timestamp_created`, "
                      "orders.event_log as `event_log`, "
                      "orders.target_time_end as `target_time_end`, "
                      "orders.number_rating as `number_rating`, "
                      "orders.gas_type as `gas_type`, "
                      "orders.gas_price as `gas_price`, "
                      "orders.gallons as `gallons` "
                      "FROM `orders` "
                      "LEFT JOIN users ON orders.courier_id = users.id "
                      "WHERE orders.target_time_start >= "
                      (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                           (-> from-date
                               (str " 00:00:00")
                               mysql-escape-str)
                           "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                      " AND orders.target_time_start <= "
                      (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                           (-> to-date
                               (str " 23:59:59")
                               mysql-escape-str)
                           "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                      "AND orders.status = 'complete' "
                      "AND orders.courier_id != '' "
                      "ORDER BY orders.timestamp_created DESC")])
        fleet-deliveries (db/raw-sql-query
                          db-conn
                          [(str "SELECT fleet_deliveries.id as `id`, "
                                "fleet_deliveries.courier_id as `courier_id`, "
                                "fleet_deliveries.gallons as `gallons`, "
                                "fleet_deliveries.total_price as `total_price`, "
                                "fleet_deliveries.gas_price as `gas_price`, "
                                "fleet_deliveries.gas_type as `gas_type`, "
                                "fleet_deliveries.timestamp_recorded as `timestamp_recorded` "
                                "FROM `fleet_deliveries` "
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
                                "AND fleet_deliveries.deleted != 1 "
                                "ORDER BY fleet_deliveries.timestamp_recorded DESC")])
        gas-purchases (db/raw-sql-query
                       db-conn
                       [(str "SELECT gas_purchases.id as `id`, "
                             "gas_purchases.courier_id as `courier_id`, "
                             "gas_purchases.gallons as `gallons`, "
                             "gas_purchases.total_price as `total_price`, "
                             "gas_purchases.gas_type as `gas_type`, "
                             "gas_purchases.timestamp_recorded as `timestamp_recorded` "
                             "FROM `gas_purchases` "
                             "WHERE gas_purchases.timestamp_recorded >= "
                             (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                                  (-> from-date
                                      (str " 00:00:00")
                                      mysql-escape-str)
                                  "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                             " AND gas_purchases.timestamp_recorded <= "
                             (str "UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('"
                                  (-> to-date
                                      (str " 23:59:59")
                                      mysql-escape-str)
                                  "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC'))")
                             "AND gas_purchases.deleted != 1 "
                             "ORDER BY gas_purchases.timestamp_recorded DESC")])
        ;; all state log entries in date range
        state-log (db/raw-sql-query
                   db-conn
                   [(str "SELECT state_log.data as `data`, "
                         "state_log.timestamp_created as `timestamp_created` "
                         "FROM `state_log` "
                         "WHERE state_log.timestamp_created >= "
                         (str "CONVERT_TZ(STR_TO_DATE('"
                              (-> from-date
                                  (str " 00:00:00")
                                  mysql-escape-str)
                              "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC')")
                         " AND state_log.timestamp_created <= "
                         (str "CONVERT_TZ(STR_TO_DATE('"
                              (-> to-date
                                  (str " 23:59:59")
                                  mysql-escape-str)
                              "', '%Y-%m-%d %H:%i:%s'), 'America/Los_Angeles', 'UTC')"))])
        
        _ (println "Time spent On Duty (number of Pings): "
                   (reduce (fn [a b]
                             (let [parsed-data (edn/read-string b)
                                   on-duty-ids (map :id (:on-duty-couriers parsed-data))
                                   in-on-duty-ids? (partial in? on-duty-ids)]
                               (into {} (map (fn [[k v]] [k (if (in-on-duty-ids? k)
                                                              (inc v)
                                                              v)]) a))))
                           (into {} (map (juxt identity (constantly 0))
                                         (keys (group-by :courier_id orders))))
                           (map :data state-log)))
        grouped-orders (group-by :courier_id orders)
        grouped-fleet-deliveries (group-by :courier_id fleet-deliveries)
        grouped-gas-purchases (group-by :courier_id gas-purchases)]
    (ring-io/piped-input-stream
     (fn [ostream]
       (let [writer (io/writer ostream)]
         (csv/write-csv
          writer
          (apply
           vector
           (concat [["Courier Name"
                     "# Orders"
                     "# Fleet Deliveries"
                     "# Gas Purchases"
                     "Gallons Purchased"
                     "Gallons Delivered (Orders)"
                     "Gallons Delivered (Fleet Deliveries)"
                     "Gallons Delivered Total - Purchased"
                     "Avg. Gas Price Bought"
                     "Avg. Gas Price Sold (Orders)"
                     "Avg. Gas Price Sold (Fleet Deliveries)"
                     "Avg. Assigned->Accepted Time"
                     "Avg. Time Enroute"
                     "Avg. Time Servicing"
                     "Avg. Accepted->Completed Time"
                     "% Late"
                     "Avg. Rating (out of 5)"
                     ]]
                   (map (fn [[courier-id group-of-orders]]
                          (let [gallons-purchased (->> (get grouped-gas-purchases courier-id)
                                                       (map :gallons)
                                                       (apply +)
                                                       double)
                                gallons-delivered-b2c (->> group-of-orders
                                                           (map :gallons)
                                                           (apply +)
                                                           double)
                                gallons-delivered-b2b (->> (get grouped-fleet-deliveries courier-id)
                                                           (map :gallons)
                                                           (apply +)
                                                           double)
                                avg-time-between-statuses
                                (fn [start-status end-status]
                                  (let [diff (/ (apply +
                                                       (map (fn [o]
                                                              (- (get-event-time (:event_log o) end-status)
                                                                 (get-event-time (:event_log o) start-status)))
                                                            group-of-orders))
                                                (count group-of-orders))
                                        hours (quot diff 3600)
                                        minutes (quot (mod diff 3600) 60)]
                                    (str hours "' " minutes "\"")))]
                            (vec [(:courier_name (first group-of-orders))
                                  (count group-of-orders)
                                  (count (get grouped-fleet-deliveries courier-id))
                                  (count (get grouped-gas-purchases courier-id))
                                  (format "%.2f" gallons-purchased)
                                  (format "%.2f" gallons-delivered-b2c)
                                  (format "%.2f" gallons-delivered-b2b)
                                  (format "%.2f" (- (+ gallons-delivered-b2c
                                                       gallons-delivered-b2b)
                                                    gallons-purchased))
                                  (some->> (get grouped-gas-purchases courier-id)
                                           (map #(/ (:total_price %) (:gallons %)))
                                           (apply +)
                                           (#(/ % (count (get grouped-gas-purchases courier-id))))
                                           int
                                           cents->dollars)
                                  (some->> group-of-orders
                                           (map :gas_price)
                                           (apply +)
                                           (#(/ % (count group-of-orders)))
                                           int
                                           cents->dollars)
                                  (some->> (get grouped-fleet-deliveries courier-id)
                                           (map :gas_price)
                                           (apply +)
                                           (#(/ % (count (get grouped-fleet-deliveries courier-id))))
                                           int
                                           cents->dollars)
                                  (avg-time-between-statuses "assigned" "accepted")
                                  (avg-time-between-statuses "enroute" "servicing")
                                  (avg-time-between-statuses "servicing" "complete")
                                  (avg-time-between-statuses "accepted" "complete")
                                  (str (format "%.2f"
                                               (double 
                                                (* 100
                                                   (/ (count (filter (fn [o]
                                                                       (> (get-event-time (:event_log o) "complete")
                                                                          (:target_time_end o)))
                                                                     group-of-orders))
                                                      (count group-of-orders)))))
                                       "%")
                                  (let [rated-orders (filter :number_rating group-of-orders)]
                                    (if (empty? rated-orders)
                                      "None"
                                      (format
                                       "%.2f"
                                       (double 
                                        (/ (apply + (map :number_rating rated-orders))
                                           (count rated-orders))))))
                                  ])))
                        grouped-orders))))
         (.flush writer))))))
