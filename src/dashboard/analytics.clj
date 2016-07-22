(ns dashboard.analytics
  (:require [common.config :as config]
            [common.db :refer [conn !select !insert !update
                               mysql-escape-str]]
            [common.util :refer [in?]]
            [dashboard.schedules :refer [schedules-by-courier-id]]
            [clojure.core.matrix :as mat]
            [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.set :refer [rename-keys]]
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
    "monthly" "%Y-%m"
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
  [{:keys [select-statement from-date to-date timezone timeformat where-clause]}]
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
         (when where-clause
           (str where-clause " "))
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
  [{:keys [select-statement from-date to-date timezone timeformat where-clause]}]
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
         (when where-clause
           (str where-clause " "))
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
  response-type is either 'csv', 'sql-map'."
  [db-conn sql response-type]
  (let [query-result (raw-sql-query db-conn [sql])
        vec-of-vec-result (->> query-result
                               (transpose-dates))]
    (condp = response-type
      "csv" (let [csv (->> vec-of-vec-result
                           (vec-of-vec-elements->str)
                           (write-csv))]
              {:data csv
               :type "csv"})
      "sql-map" query-result)))

(defn get-event-within-timestamps
  "Return a MySQL string for retrieving event in event-log-name that occurs
  within start-time to end-time. times are unix epoch seconds"
  [event-log-name event start-time end-time]
  (str (get-event-time-mysql event-log-name event)
       " >= "
       start-time " "
       "AND "
       (get-event-time-mysql event-log-name event)
       " <= "
       end-time " "))

;; this is where start_time / end_time keys could be replaced with start_time and end_time
(defn schedule-date-where-statements
  "given a vector of scheduled-times hash-map, create a where statement for each
  date.
  schedules-times is a vector of maps of the form

  [{:start_time <unix_timestamp>
    :end_time   <unix_timestamp>},
     ... ]
  "
  [schedule-times timezone]
  (str "(" (apply str (interpose
                       " or "
                       (map #(let [{:keys [start_time end_time]} %]
                               (str "("
                                    (get-event-within-timestamps "event_log"
                                                                 "complete"
                                                                 start_time
                                                                 end_time)
                                    ")"))
                            schedule-times)))
       ")"))

(defn scheduled-orders-of-courier-sql
  "return a query for getting the schedule orders of courier-id
  in the date range from-date to-date during scheduled-times. scheduled-times
  is a vector of maps of the form

  [{:start_time datetime
    :end_time   datetime},
     ... ]"
  [{:keys [courier-id scheduled-times timezone timeformat]}]
  (str  "select (select `users`.`name` as `name` from `users` where "
        "(`users`.`id` = `orders`.`courier_id`)) as `name`, "
        "date_format(convert_tz(from_unixtime("
        (get-event-time-mysql "`orders`.`event_log`" "complete")
        "),'utc','"
        timezone
        "'),'"
        timeformat
        "') "
        "as `date`, "
        "count(0) as `count` "
        "from `orders` "
        "where ((`orders`.`status` = 'complete') "
        "and (`orders`.`courier_id` = '" courier-id "')) "
        "and "
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
        ))

(defn get-scheduled-orders-of-courier
  [{:keys [courier-id scheduled-times timezone timeformat db-conn]}]
  (raw-sql-query db-conn [(scheduled-orders-of-courier-sql
                           {:courier-id courier-id
                            :scheduled-times scheduled-times
                            :timezone timezone
                            :timeformat timeformat})]))

(defn get-daily-scheduled-orders-per-courier
  "get the orders that were completed during schedule for each courier
  over the dates from-date to-date in timezone. schedule is a vector of maps
  of the form
  [{:courier-id \"okqfycgkwrn4yntglpbf\"
    :scheduled-times [{:start_time <unix_timestamp>
                       :end_time <unix_timestamp>
                       ...
                      ]},
   ...
  ]
  "
  [{:keys [schedule timeformat timezone db-conn]}]
  (map (fn [{:keys [courier-id scheduled-times]}]
         (get-scheduled-orders-of-courier
          {:courier-id courier-id
           :scheduled-times scheduled-times
           :timezone timezone
           :timeformat timeformat
           :db-conn db-conn}))
       schedule))

(defn scheduled-orders-response
  "return a response from db-conn using schedule and timezone that gives the
  scheduled order count for each courier. the 'schedule order count' for each
  courier is the amount of completed orders the courier did while scheduled.
  schedule is a vector of maps of the form

  [{:courier-id \"okqfycgkwrn4yntglpbf\"
    :scheduled-times [{:start_time \"2016-05-01 06:30:00\"
                       :end_time \"2016-05-01 18:30:00\"},
                       ...
                      ]},
   ...,]
  reponse-type is a string, either \"csv\" or \"sql-map\" "
  [{:keys [from-date to-date timezone timeformat db-conn response-type]
    :or {:response-type "csv"}}]
  (let [start-time (str from-date " 00:00:00") ;; further tz info is appended in
        end-time   (str to-date " 23:59:59")   ;; schedules-by-courier-id
        schedule   (schedules-by-courier-id start-time end-time timezone)
        total-orders (per-courier-response
                      db-conn
                      (per-courier-query
                       {:select-statement "count(0) AS `count`"
                        :from-date from-date
                        :to-date to-date
                        :timezone timezone
                        :timeformat timeformat})
                      "sql-map")
        scheduled-orders-count (flatten
                                (get-daily-scheduled-orders-per-courier
                                 {:schedule schedule
                                  :timezone timezone
                                  :timeformat timeformat
                                  :db-conn db-conn}))
        renamed-total (map #(rename-keys % {:count :total}) total-orders)
        renamed-scheduled (map #(rename-keys % {:count :scheduled})
                               scheduled-orders-count)
        joined-sets (map (fn [item]
                           (let [scheduled-map (first
                                                (filter #(and (= (:name item)
                                                                 (:name %))
                                                              (= (:date item)
                                                                 (:date %)))
                                                        renamed-scheduled))]
                             (if scheduled-map
                               (merge item {:scheduled
                                            (:scheduled scheduled-map)})
                               (merge item {:scheduled 0}))))
                         renamed-total)
        scheduled-counts (map #(rename-keys % {:scheduled :count}) joined-sets)]
    (condp = response-type
      "csv" {:data (->> (transpose-dates (sort-by :date
                                                  scheduled-counts))
                        (vec-of-vec-elements->str)
                        (write-csv))
             :type "csv"}
      ;; note: This will return sets of the format:
      ;; ({:name <name>
      ;;  :date <date>
      ;;  :total <total_orders>
      ;;  :scheduled <scheduled_orders>
      ;;  }, ...)
      "sql-map" joined-sets)))

(defn flex-orders-response
  "Similar to scheduled-orders-response, expect returns flex-orders-response."
  [{:keys [from-date to-date timezone timeformat db-conn response-type]
    :or {:response-type "csv"}}]
  (let [start-time from-date
        end-time   to-date
        scheduled-sql-maps  (doall (scheduled-orders-response
                                    {:from-date start-time
                                     :to-date end-time
                                     :timezone timezone
                                     :timeformat timeformat
                                     :db-conn db-conn
                                     :response-type "sql-map"}))
        flex-sql-maps (map #(merge % {:flex (- (:total %) (:scheduled %))})
                           scheduled-sql-maps)
        flex-counts (map #(rename-keys % {:flex :count}) flex-sql-maps)]
    (condp = response-type
      "csv"
      {:data (->> (transpose-dates (sort-by :date flex-counts))
                  (vec-of-vec-elements->str)
                  (write-csv))
       :type "csv"}
      "sql-map" scheduled-sql-maps)))
