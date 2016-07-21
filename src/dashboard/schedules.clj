(ns dashboard.schedules
  (:require [clj-http.client :as client]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.set :refer [rename-keys join]]
            [common.db :refer [!select conn]]
            [common.config :refer [wiw-api-url wiw-key wiw-token]]
            [clojure.string :as s]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clj-time.format :as f]))

;; This namespace deals with retrieving schedules. Currently, wheniwork.com
;; is used by purple. The main purpose of this is to differentiate between
;; orders that are done when scheduled and when in flex mode.

;; Caveats:

;; 1. Currently only schedules are retrieved. Any orders done within that
;;    time are considered 'scheduled' any orders outside of that time are
;;    considered 'flex'. If the courier is still working beyond their schedule
;;    time, then any orders completed during that time will be considered
;;    'flex'. The alternative would be to use clock times (more complicated),
;;    but this will depend on the courier accurately clocking in and out.
;;    Possible problems could be

;; 	a. The courier did not clock in until later. All orders before this 
;;         time would be considered'flex'

;; 	b. The courier never clocked in, all orders would be considered flex.

;; 2. Timezones should be respected when retrieving shifts with 'list-shifts'.

;; 3. A map of purple courier-ids / wiw courier-ids must be used. Curerntly,
;;    purple ids are longer than the allowed Employee ID. The courier's
;;    phone number is used to correlate purple ids with wiw ids. Therefore, the
;;    courier must use the same phone number on Purple as used on wiw.

(def wiw-opts
  {:headers {"W-Token" wiw-token}
   :as :json
   :content-type :json
   :coerce :always})

(defn wiw-req
  [method endpoint & {:keys [form-params headers query-params]}]
  (try (let [opts (merge-with merge 
                              wiw-opts
                              {:form-params form-params}
                              {:query-params query-params}
                              {:headers headers})
             resp (keywordize-keys  (:body ((resolve (symbol "clj-http.client"
                                                             method))
                                            (str  wiw-api-url endpoint)
                                            opts)))]
         (if (not (:error resp))
           {:success (not (:error resp))
            :resp resp}
           {:success (boolean (:error resp))
            :error {:error resp}}))
       (catch Exception e
         {:success false
          :resp {:error (.getMessage e)}})))

(defn list-shifts
  "Given a start and end date, return the shifts for that range.
  dates are strings of the format 'YYYY-MM-DD HH:MM:SS'. Optionally,
  a utc offeset can be used for dates: 'YYYY-MM-DD HH:MM:SS -0700' for PDT"
  [start end]
  (wiw-req "get" "shifts" :query-params {"start" start
                                         "end" end}))

(defn list-users
  "List all users"
  []
  (wiw-req "get" "users"))

(defn purple-id-wiw-id-map
  "Generate a vector of hashmaps of the following form:
  [
  {:email <email>
   :name <purple user name>
   :user_id <wiw-id>
   :id <purple-id> }
  ]"
  []
  (let [wiw-users (map #(rename-keys % {:id :user_id})
                       (map #(hash-map :phone_number
                                       (s/replace (:phone_number %) #"\+1" "")
                                       :id (:id %))
                            (get-in (list-users) [:resp :users])))
        wiw-emails (distinct (map :phone_number wiw-users))
        custom-where-string (str "phone_number IN (\""
                                 (s/join "\",\"" wiw-emails)
                                 "\") and is_courier = 1")
        purple-ids (!select (conn) "users" [:phone_number :id :name] {}
                            :custom-where custom-where-string)]
    (join wiw-users purple-ids)))

(defn correlated-shifts
  "Given a start and end date, return a set of hashmaps of the following form:
  #{{:email <email address>
     :name <purple user name>
     :user_id <wiw id>
     :id <purple user id>
     :start_time <date>
     :end_time <date>},
  ...
  }
  dates are strings of the format 'YYYY-MM-DD HH:MM:SS"
  [start end]
  (let [id-map (purple-id-wiw-id-map)
        shifts (map #(hash-map :start_time (quot (c/to-long (:start_time %))
                                                 1000)
                               :end_time (quot (c/to-long (:end_time %))
                                               1000)
                               :user_id (:user_id %))
                    (get-in (list-shifts start end) [:resp :shifts]))]
    (join id-map shifts)))

(defn process-schedules-by-courier-id
  "Process correlated-shifts, as returned by correlated-shifts fn, into a set of
  maps of the following form:
  #{{:name <courier name>,
     :courier-id <purple courier id string>
     :scheduled-times [{:start_time <time string>
                        :end_time   <time string>},
                       ...],
    }}
  This is suitable for feeding to analytics/scheduled-orders-response"
  [correlated-shifts]
  (let [courier-id-names (distinct (map #(hash-map :name (:name %) :courier-id
                                                   (:id %)) correlated-shifts))
        select-times (fn [correlated-shift]
                       (assoc correlated-shift
                              :scheduled-times
                              (into
                               []
                               (map #(select-keys %
                                                  [:start_time :end_time])
                                    (:scheduled-times correlated-shift)))))]
    (->> (group-by :id correlated-shifts)
         (map #(hash-map :courier-id (key %)
                         :scheduled-times (val %)))
         (map select-times)
         (join courier-id-names))))

(defn append-timezone-offset
  "Append a timezone offset to a datetime string

  ex:
  (append-timezone-offset \"2016-05-15 00:00:00\" \"America/Los_Angeles\")
  > \"2016-05-15 00:00:00 -0700\"
  (append-timezone-offset \"2016-11-11 00:00:00\" \"America/Los_Angeles\")
  > \"2016-11-11 00:00:00 -0800\"
  "
  [datetime timezone]
  (let [tz-formatter (f/with-zone (f/formatter "Z")
                       (t/time-zone-for-id timezone))]
    (str datetime " " (f/unparse tz-formatter (c/from-string datetime)))))

(defn schedules-by-courier-id
  "Given a start and end datetime string and timezonef, return the schedules for
  all courier in that time range. dates are strings of the format
  'YYYY-MM-DD HH:MM:SS'. timezone is a string ex. \"America/Los_Angeles\"
  See process-schedules-by-courier-id for description of return object"
  [start end timezone]
  (let [start-time (append-timezone-offset start timezone)
        end-time   (append-timezone-offset end timezone)]
    (-> (correlated-shifts start-time end-time)
        (process-schedules-by-courier-id))))
