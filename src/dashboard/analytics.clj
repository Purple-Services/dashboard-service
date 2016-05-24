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
            [clojure.core.memoize :refer [memo memo-clear!]]
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

(def get-first-order-by-user-memoized
  (memo get-first-order-by-user))

(defn users-ordered-to-date
  "Given a list of orders and a date, run a filter predicate to determine 
  cumulative orders. Example predicate to get all users who have ordered exactly
  once: (fn [x] (= x 1))"
  [orders date pred]
  (count-filter pred (map :count (user-order-count-by-day orders date))))


(defn made-first-order-this-day
  "Is this the date that the user made their first order?"
  [user date orders] ;; 'orders' is coll of all orders (by any user)
  (when-let [first-order-by-user (get-first-order-by-user-memoized user orders)]
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
                (map (fn [date]
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
                             (count-filter #(made-first-order-this-day
                                             %
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
                     dates))))))
  (memo-clear! get-first-order-by-user-memoized))

(defn raw-sql-query
  "Given a raw query-vec, return the results"
  [db-conn query-vec]
  (sql/with-connection db-conn
    (sql/with-query-results results
      query-vec
      (doall results))))

(defn sql-results
  "Obtain results for sql string"
  [db-conn sql]
  (raw-sql-query db-conn [sql]))

(defn concat-vector-at-idx
  "Concat a vector v at idx into host vector h"
  [v h idx]
  (let [[before after] (split-at idx h)]
    (vec (concat before v after))))

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

  date_count_i is 0 when the corresponding vector for that date does not exist,
  assuming all dates are represented in the maps. If a date is not present in
  the maps, it will not be included in the output."
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

;; timezone is a string and defaults to 'America/Los_Angeles' and depends on
;; proper setup of the timezones table in mySQL.
;; see: http://dev.mysql.com/doc/refman/5.7/en/time-zone-support.html

(defn convert-datetime-timezone-to-UTC-mysql
  "Return a MySQL string for converting date in timezone to UTC unix timestamp"
  [datetime timezone]
  (str "unix_timestamp(convert_tz('" datetime "','" timezone "','UTC'))"))

(defn timeframe->timeformat
  "Convert a timeframe into a timeformat.
  ex: 'daily' -> '%Y-%m-%d'"
  [timeframe]
  (condp = timeframe
    "hourly" "%Y-%m-%d %H"
    "daily" "%Y-%m-%d"
    "weekly" "%Y-%U"
    "%Y-%m-%d"))

(defn get-event-within-time-range
  "Return a MySQL string for retrieving event in event-log-name that occurs
  within from-date to to-date in timezone."
  [event-log-name event from-date to-date & [timezone]]
  (let [timezone (or timezone "America/Los_Angeles")]
    (str (get-event-time-mysql event-log-name event)
         " >= "
         (convert-datetime-timezone-to-UTC-mysql from-date timezone) " "
         "AND "
         (get-event-time-mysql event-log-name event)
         " <= "
         (convert-datetime-timezone-to-UTC-mysql to-date timezone) " ")))

(defn totals-query
  "Return a MySQL string for retrieving totals of select-statement for
  completed orders in the range from-date to to-date using timezone.
  timeformat can be generated from timeframe->timeformat"
  [{:keys [select-statement from-date to-date timezone timeformat]}]
  (let [from-date (str from-date " 00:00:00")
        to-date   (str to-date " 23:59:59")]
    (str "select date_format(convert_tz(from_unixtime("
         (get-event-time-mysql "event_log" "complete")
         "),'UTC','"
         timezone
         "'),'"
         timeformat
         "') as 'date'"
         ","
         select-statement
         " from orders where"
         " status = 'complete' AND  "
         (get-event-time-mysql "event_log" "complete")
         " > 1420070400 "
         "AND "
         (get-event-within-time-range "event_log" "complete"
                                      from-date
                                      to-date
                                      timezone)
         "GROUP BY "
         "date_format(convert_tz(from_unixtime("
         (get-event-time-mysql "event_log" "complete")
         "),'UTC','"
         timezone "'),'" timeformat
         "');")))

(defn per-courier-query
  "Return a MySQL string for retrieving per-courier for select-statement for
  completed orders in the range from-date to to-date using timezone.
  timeformat can be generated from timeframe->timeformat"
  [{:keys [select-statement from-date to-date timezone timeformat]}]
  (let [from-date (str from-date " 00:00:00")
        to-date   (str to-date " 23:59:59")]
    (str "SELECT (SELECT `users`.`name` AS `name` FROM `users` WHERE "
         "(`users`.`id` = `orders`.`courier_id`)) AS `name`, "
         "date_format(convert_tz(from_unixtime("
         (get-event-time-mysql "`orders`.`event_log`" "complete")
         "),'UTC','"
         timezone
         "'),'"
         timeformat
         "') "
         "AS `date`, "
         select-statement " "
         "FROM `orders` "
         "WHERE ((`orders`.`status` = 'complete') "
         "AND (`orders`.`courier_id` <> '6nJd1SMjMnxxhUsKp3Nk')) "
         "AND "
         (get-event-time-mysql "event_log" "complete")
         " > 1420070400 "
         "AND "
         (get-event-within-time-range "event_log" "complete"
                                      from-date
                                      to-date)
         "GROUP BY"
         " `orders`.`courier_id`,"
         "date_format(convert_tz(from_unixtime("
         (get-event-time-mysql "`orders`.`event_log`" "complete")
         "),'UTC','"
         timezone
         "'),'"
         timeformat
         "') ORDER BY "
         "date_format(convert_tz(from_unixtime("
         (get-event-time-mysql "`orders`.`event_log`" "complete")
         "),'UTC','"
         timezone
         "'),'"
         timeformat
         "') asc;")))

(defn total-for-select-response
  "Provide a response from db-conn for sql generated using totals-query.
  response-type is  either 'json' or 'csv'."
  [db-conn sql response-type]
  (let [query-result
        (raw-sql-query db-conn [sql])]
    (cond (= response-type "json")
          (let [processed-orders
                (->> query-result
                     (map #(vals %))
                     (map #(hash-map (.toString (first %)) (second %)))
                     (sort-by first))
                x (into [] (flatten (map keys processed-orders)))
                y (into [] (flatten (map vals processed-orders)))]
            {:x x
             :y y})
          (= response-type "csv")
          (let [csv (->> query-result
                         (map #(vec (vals %)))
                         (into [])
                         (mat/transpose)
                         (vec-of-vec-elements->str)
                         (write-csv))]
            {:data csv
             :type "csv"}))))

(defn per-courier-response
  "Return a response from db-conn for sql generated using per-courier-query.
  response-type is either 'json' or 'csv'."
  [db-conn sql response-type]
  (let [query-result (raw-sql-query db-conn [sql])]
    (cond (= response-type "csv")
          (let [csv (->> query-result
                         (transpose-dates)
                         (vec-of-vec-elements->str)
                         (write-csv))]
            {:data csv
             :type "csv"}))))

(defn schedule-time-where-statement
  "Create a where statement from the map
  {:start datetime :end datetime}"
  [schedule-time timezone]
  (let [{:keys [start end]} schedule-time]
    (get-event-within-time-range "event_log" "complete"
                                 start
                                 end
                                 timezone
                                 )))

(defn schedule-date-where-statements
  "Given a vector of scheduled-times hash-map, create a where statement for each
  date.
  schedules-times is a vector of maps of the form

  [{:start datetime
    :end   datetime},
     ... ]
  "
  [schedule-times timezone]
  (str "(" (apply str (interpose
                       " OR "
                       (map #(let [{:keys [start end]} %]
                               (str "("
                                    (get-event-within-time-range "event_log"
                                                                 "complete"
                                                                 start
                                                                 end
                                                                 timezone)
                                    ")"))
                            schedule-times)))
       ")"))

(defn scheduled-orders-of-courier-sql
  "Return a query for getting the schedule orders of courier-id
  in the date range from-date to-date during scheduled-times. scheduled-times
  is a vector of maps of the form

  [{:start datetime
    :end   datetime},
     ... ]"
  [{:keys [courier-id scheduled-times timezone timeframe]}]
  (let [timeformat (timeframe->timeformat timeframe)]
    (str  "SELECT (SELECT `users`.`name` AS `name` FROM `users` WHERE "
          "(`users`.`id` = `orders`.`courier_id`)) AS `name`, "
          "date_format(convert_tz(from_unixtime("
          (get-event-time-mysql "`orders`.`event_log`" "complete")
          "),'UTC','"
          timezone
          "'),'"
          timeformat
          "') "
          "AS `date`, "
          "count(0) AS `count` "
          "FROM `orders` "
          "WHERE ((`orders`.`status` = 'complete') "
          "AND (`orders`.`courier_id` = '" courier-id "')) "
          "AND "
          (schedule-date-where-statements scheduled-times timezone)
          "GROUP BY"
          " `orders`.`courier_id`,"
          "date_format(convert_tz(from_unixtime("
          (get-event-time-mysql "`orders`.`event_log`" "complete")
          "),'UTC','"
          timezone
          "'),'"
          timeformat
          "') ORDER BY "
          "date_format(convert_tz(from_unixtime("
          (get-event-time-mysql "`orders`.`event_log`" "complete")
          "),'UTC','"
          timezone
          "'),'"
          timeformat
          "') asc;"
          )))

(defn get-scheduled-orders-of-courier
  [{:keys [courier-id scheduled-times timezone timeframe db-conn]}]
  (raw-sql-query db-conn [(scheduled-orders-of-courier-sql
                           {:courier-id courier-id
                            :scheduled-times scheduled-times
                            :timezone timezone
                            :timeframe timeframe})]))

(defn get-daily-scheduled-orders-per-courier
  "Get the orders that were completed during schedule for each courier
  over the dates from-date to-date in timezone. schedule is a vector of maps
  of the form
  [{:courier-id \"oKQFyCgkwrn4YNtGlPbF\"
    :scheduled-times [{:start \"2016-05-01 06:30:00\"
                       :end \"2016-05-01 18:30:00\"},
                       ...
                      ]},
   ...
  ]
  "
  [{:keys [schedule timezone db-conn]}]
  (map (fn [{:keys [courier-id scheduled-times]}]
         (get-scheduled-orders-of-courier
          {:courier-id courier-id
           :scheduled-times scheduled-times
           :timezone timezone
           :timeframe "daily"
           :db-conn db-conn
           }))
       schedule))

(defn vector-of-scheduled-orders-count
  "Create a vector of vectors for the count of orders for each courier made
  while scheduled. see 'get-daily-scheduled-orders-per-courier' for more
  information on params"
  [{:keys [schedule timezone db-conn]}]
  (transpose-dates (reduce concat
                           (get-daily-scheduled-orders-per-courier
                            {:schedule schedule
                             :timezone timezone
                             :db-conn db-conn}))))

(defn scheduled-orders-response
  "Return a response from db-conn using schedule and timezone that gives the
  scheduled order count for each courier. The 'schedule order count' for each
  courier is the amount of completed orders the courier did while scheduled.
  schedule is a vector of maps of the form

  [{:courier-id \"oKQFyCgkwrn4YNtGlPbF\"
    :scheduled-times [{:start \"2016-05-01 06:30:00\"
                       :end \"2016-05-01 18:30:00\"},
                       ...
                      ]},
   ...,]
  The data returned is in csv format"
  [{:keys [schedule timezone db-conn]}]
  (let [csv (-> (vector-of-scheduled-orders-count {:schedule schedule
                                                   :timezone timezone
                                                   :db-conn db-conn})
                (vec-of-vec-elements->str)
                (write-csv))]
    {:data csv
     :type csv}))
