(ns dashboard.analytics
  (:require [common.config :as config]
            [common.db :refer [conn !select !insert !update
                               mysql-escape-str]]
            [common.util :refer [in?]]
            [clojure.core.matrix :as mat]
            [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.data.csv :as csv]
            [clojure-csv.core :refer [write-csv]]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.periodic :as periodic]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]))

(def count-filter (comp count filter))

(def ymd-formatter (time-format/formatter "yyyy-MM-dd"))

(defn joda->ymd
  "Convert Joda Timestamp object to formatted date string."
  [x]
  (-> ymd-formatter
      (time-format/with-zone (time/time-zone-for-id "America/Los_Angeles"))
      (time-format/unparse x)))

(defn unix->ymd
  "Convert integer unix timestamp to formatted date string."
  [x]
  (joda->ymd (time-coerce/from-long (* 1000 x))))

(defn users-by-day
  "Get map of all users, in seqs, keyed by date, sorted past -> present."
  [users]
  (into {} (sort-by first (group-by (comp unix->ymd
                                          int
                                          (partial * 1/1000)
                                          #(.getTime %)
                                          :timestamp_created)
                                    users))))

(defn orders-by-day
  "Get map of all orders, in seqs, keyed by date, sorted past -> present."
  [orders]
  (into {} (sort-by first (group-by (comp unix->ymd :target_time_start)
                                    orders))))

(defn orders-up-to-day
  "Get all orders up to date where date is in YYYY-MM-dd format."
  [orders ^String date]
  (filter #(<= (time-coerce/to-long (time-coerce/from-string (first %)))
               (time-coerce/to-long (time-coerce/from-string date)))
          (orders-by-day orders)))

(defn user-count
  "Given a vector of orders, get the amount of orders each user made"
  [orders]
  (map #(hash-map :user_id (first %) :count (count (second %)))
       (group-by :user_id orders)))

(defn user-order-count-by-day
  "Get a map of {:user_id <id> :count <order_count>} given orders and date in
  YYYY-MM-dd format."
  [orders ^String date]
  (user-count (flatten (map second (orders-up-to-day orders date)))))

(defn get-first-order-by-user
  "Get the first order made by user. If they never ordered, then nil."
  [user orders] ;; 'orders' is coll of all orders (by any user)
  (first (sort-by :target_time_start
                  <
                  (filter #(and (= "complete" (:status %))
                                (= (:user_id %) (:id user)))
                          orders))))

(defn users-ordered-to-date
  "Given a list of orders and a date, run a filter predicate to determine 
  cumulative orders. Example predicate to get all users who have ordered exactly
  once: (fn [x] (= x 1))"
  [orders date pred]
  (count-filter pred (map :count (user-order-count-by-day orders date))))

(defn made-first-order-this-day
  "Is this the date that the user made their first order?"
  [user date orders] ;; 'orders' is coll of all orders (by any user)
  (when-let [first-order-by-user (get-first-order-by-user user orders)]
    (= date (unix->ymd (:target_time_start first-order-by-user)))))

(defn gen-stats-csv
  "Generates and saves a CSV file with some statistics."
  []
  (with-open [out-file (io/writer "stats.csv")]
    (csv/write-csv
     out-file
     (let [db-conn (conn)
           dates (map joda->ymd
                      (take-while #(time/before? % (time/now))
                                  (periodic/periodic-seq  ;; Apr 10th
                                   (time-coerce/from-long 1428708478000)
                                   (time/hours 24))))
           users (!select db-conn "users" [:timestamp_created :id] {})
           users-by-day (users-by-day users)
           orders (!select db-conn "orders" [:target_time_start :event_log
                                             :target_time_end :status
                                             :coupon_code :user_id] {})
           completed-orders (filter #(= "complete" (:status %)) orders)
           orders-by-day (orders-by-day orders)
           coupons (!select db-conn "coupons" [:type :code] {})
           standard-coupon-codes (->> (filter #(= "standard" (:type %)) coupons)
                                      (map :code))]
       (apply
        mapv
        vector
        (concat [["Date"
                  "New Users"
                  "New Active Users"
                  "Referral Coupons Used"
                  "Standard Coupons Used"
                  "First-Time Orders"
                  "Recurrent Orders"
                  "Cancelled Orders"
                  "Completed Orders"
                  "On-Time Completed Orders"
                  "Late Completed Orders"
                  "Cumulative Single Order Users"
                  "Cumulative Double Order Users"
                  "Cumulative 3 or More Orders Users"]]
                (pmap (fn [date]
                        (let [us (get users-by-day date)
                              os (get orders-by-day date)

                              num-complete ;; Number of complete orders that day
                              (count-filter #(= "complete" (:status %)) os)
                              
                              num-complete-late ;; Completed, but late
                              (count-filter #(let [completion-time
                                                   (-> (str "kludgeFixLater 1|"
                                                            (:event_log %))
                                                       (s/split #"\||\s")
                                                       (->> (apply hash-map))
                                                       (get "complete"))]
                                               (and completion-time
                                                    (> (Integer. completion-time)
                                                       (:target_time_end %))))
                                            os)

                              new-active-users ;; Made first order that day
                              (count-filter #(made-first-order-this-day %
                                                                        date
                                                                        orders)
                                            users)]
                          (vec [;; date in "1989-08-01" format
                                date

                                ;; new users (all)
                                (count us)
                                
                                ;; made first order that day
                                new-active-users
                                
                                ;; referral coupons
                                (count-filter
                                 #(and (not (s/blank? (:coupon_code %)))
                                       (not (in? standard-coupon-codes
                                                 (:coupon_code %))))
                                 os)

                                ;; standard coupons
                                (count-filter
                                 (comp (partial in? standard-coupon-codes)
                                       :coupon_code)
                                 os)
                                
                                ;; first-time orders
                                new-active-users

                                ;; recurrent
                                (- (count os) new-active-users)

                                ;; cancelled
                                (count-filter #(= "cancelled" (:status %)) os)

                                ;; completed
                                num-complete

                                ;; completed on-time
                                (- num-complete num-complete-late)

                                ;; completed late
                                num-complete-late

                                ;; cumulatively ordered once
                                (users-ordered-to-date completed-orders date
                                                       (fn [x] (= x 1)))
                                ;; cumulatively ordered twice
                                (users-ordered-to-date completed-orders date
                                                       (fn [x] (= x 2)))
                                ;; cumulatively ordered three or more times
                                (users-ordered-to-date completed-orders date
                                                       (fn [x] (>= x 3)))
                                ])))
                      dates)))))))
;; !! implement this tomorrow!
(defn raw-sql-query
  "Given a raw query-vec, return the results"
  [db-conn query-vec]
  (sql/with-connection db-conn
    (sql/with-query-results results
      query-vec
      (doall results))))

(defn vec-elements->str
  [v]
  (->> v
       (map str)
       (into [])))

(defn vec-of-vec-elements->str
  [v]
  (->> v
       (map vec-elements->str)
       (into [])))

(defn transpose-dates
  "Given a vector of maps of the form
  [{:name <name>
    :date <date>
    :count <count>},
    ...]

  Return a vector of vectors of the form
  [[\"dates\" date_1 ... date_i]
   [:name_1 date_count_1 ... date_count_i]
   ...
   [:name_i date_count_1 ... date_count_i]

  date_count_i is 0 when the corresponding vector for that date does not exist"
  [date-count-vec]
  (let [dates (->> date-count-vec
                   (map :date)
                   distinct)
        names (->> date-count-vec
                   (map :name)
                   distinct)
        count-for-date (fn [date maps]
                         (first (filter #(= date (:date %))
                                        maps)))
        name-count (fn [name-val dates]
                     (let [name-maps
                           (filter #(= name-val (:name %))
                                   date-count-vec)
                           name-counts
                           (map #(if-let [order-count
                                          (:count
                                           (count-for-date % name-maps))]
                                   order-count
                                   0) dates)]
                       (into [] (concat [name-val] name-counts))))
        courier-orders (map #(name-count % dates) names)
        dates-vector (into [] (concat ["dates"] dates))
        final-vector (into [] (concat [dates-vector] courier-orders))]
    final-vector))

(defn get-event-time-mysql
  "return a MySQL string for retrieving an event time from an order "
  [event-log-name event]
  (str "substr(" event-log-name ",locate('" event "'," event-log-name ") + 9,10)"))

(defn convert-datetime-timezone-to-UTC-mysql
  "Return a MySQL string for converting date in timezone to UTC unix timestamp"
  [datetime timezone]
  (str "unix_timestamp(convert_tz('" datetime "','" timezone "','UTC'))"))

;; perhaps this and orders-per-courier could be more general
(defn total-orders-per-timeframe
  "Get a count of orders per timeframe using timezone. Timezone is a plain-text
  description. ex: 'America/Los_Angeles'. This depends on proper setup of the
  timezones table in mySQL.
  see: http://dev.mysql.com/doc/refman/5.7/en/time-zone-support.html"
  [db-conn timeframe from-date to-date & [timezone]]
  (let [timeformat (condp = timeframe
                     "hourly" "%Y-%m-%d %H"
                     "daily" "%Y-%m-%d"
                     "weekly" "%Y-%U"
                     "%Y-%m-%d")
        timezone (or timezone "America/Los_Angeles")
        sql (str "select date_format(convert_tz(from_unixtime("
                 (get-event-time-mysql "event_log" "complete")
                 "),'UTC',?),?) as 'date'"
                 ",COUNT(DISTINCT id) as 'orders' from orders where"
                 " status = 'complete' AND  "
                 (get-event-time-mysql "event_log" "complete")
                 " > 1420070400 "
                 "AND "
                 (get-event-time-mysql "event_log" "complete")
                 " >= "
                 (convert-datetime-timezone-to-UTC-mysql from-date timezone) " "
                 "AND "
                 (get-event-time-mysql "event_log" "complete")
                 " <= "
                 (convert-datetime-timezone-to-UTC-mysql to-date timezone) " "
                 "GROUP BY "
                 "date_format(convert_tz(from_unixtime("
                 (get-event-time-mysql "event_log" "complete")
                 "),'UTC',?),?);")
        orders-per-day-result
        (raw-sql-query
         db-conn
         [sql
          timezone timeformat timezone timeformat])]
    orders-per-day-result))


;; it could also be the case that csv is too big to store in the browser
;; in production, might have to save files anyway.
(defn total-orders-per-timeframe-response
  "Get a count of orders per day using timezone. response-type
   is either json or csv"
  [db-conn response-type timeframe from-date to-date & [timezone]]
  (let [orders-per-day-result
        (total-orders-per-timeframe db-conn timeframe from-date to-date
                                    timezone)]
    (cond (= response-type "json")
          (let [processed-orders
                (->> orders-per-day-result
                     (map #(vals %))
                     (map #(hash-map (.toString (first %)) (second %)))
                     (sort-by first))
                x (into [] (flatten (map keys processed-orders)))
                y (into [] (flatten (map vals processed-orders)))]
            {:x x
             :y y})
          (= response-type "csv")
          (let [csv (->> orders-per-day-result
                         (map #(vec (vals %)))
                         (into [])
                         (mat/transpose)
                         (vec-of-vec-elements->str)
                         (write-csv))]
            {:data csv
             :type "csv"}))))

(defn orders-per-courier
  "Return a list of couriers and their order total orders for timeframe using
  timezone "
  [db-conn timeframe from-date to-date & [timezone]]
  (let [timezone (or timezone "America/Los_Angeles")
        timeformat (condp = timeframe
                     "hourly" "%Y-%m-%d %H"
                     "daily" "%Y-%m-%d"
                     "weekly" "%Y-%U"
                     "%Y-%U")
        sql (str "SELECT (SELECT `users`.`name` AS `name` FROM `users` WHERE "
                 "(`users`.`id` = `orders`.`courier_id`)) AS `name`, "
                 "date_format(convert_tz(from_unixtime("
                 (get-event-time-mysql "`orders`.`event_log`" "complete")
                 "),'UTC',?),?) "
                 "AS `date`,count(0) AS `count` FROM `orders` "
                 "WHERE ((`orders`.`status` = 'complete') "
                 "AND (`orders`.`courier_id` <> '6nJd1SMjMnxxhUsKp3Nk')) "
                 "AND "
                 (get-event-time-mysql "event_log" "complete")
                 " > 1420070400 "
                 "AND "
                 (get-event-time-mysql "event_log" "complete")
                 " >= "
                 (convert-datetime-timezone-to-UTC-mysql from-date timezone) " "
                 "AND "
                 (get-event-time-mysql "event_log" "complete")
                 " <= "
                 (convert-datetime-timezone-to-UTC-mysql to-date timezone) " "
                 "GROUP BY"
                 " `orders`.`courier_id`,"
                 "date_format(convert_tz(from_unixtime("
                 (get-event-time-mysql "`orders`.`event_log`" "complete")
                 "),'UTC',?),?) ORDER BY "
                 "date_format(convert_tz(from_unixtime("
                 (get-event-time-mysql "`orders`.`event_log`" "complete")
                 "),'UTC',?),?) asc;")
        orders-per-courier-result
        (raw-sql-query
         db-conn
         [sql
          timezone timeformat timezone timeformat timezone timeformat])]
    orders-per-courier-result))

(defn orders-per-courier-response
  "Return a list of couriers and their total order count for timeframe using
  optional timezone. default timezone is 'America/Los_Angeles'."
  [db-conn response-type timeframe from-date to-date & [timezone]]
  (let [from-date (str from-date " 00:00:00")
        to-date   (str to-date " 23:59:59")
        orders-per-courier-result
        (orders-per-courier db-conn
                            timeframe
                            from-date
                            to-date
                            timezone)]
    (cond (= response-type "csv")
          (let [csv (->> orders-per-courier-result
                         (transpose-dates)
                         (vec-of-vec-elements->str)
                         (write-csv))]
            {:data csv
             :type "csv"}))))

;; ;; orders per week (global)

;; select date_format(convert_tz(from_unixtime(substr(event_log,locate('complete',event_log)+ 9,10)),'UTC','America/Los_Angeles'),'%Y-%u') as 'date',COUNT(DISTINCT id) as 'orders' from orders where status = 'complete' AND  substr(event_log,locate('complete',event_log) + 9, 10) > 1420070400 GROUP BY date_format(convert_tz(from_unixtime(substr(event_log,locate('complete',event_log)+ 9,10)),'UTC','America/Los_Angeles'),'%Y-%u');
