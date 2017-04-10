(ns dashboard.utils
  (:require [common.db :refer [conn !select !update mysql-escape-str]]
            [clojure.edn :as edn]))

(defn edn-read?
  "Attempt to read x with edn/read-string, return true if x can be read, false
  otherwise."
  [x]
  (try (edn/read-string x)
       true
       (catch Exception e false)))

(defn append-to-admin-event-log
  [m & {:keys [admin-id action comment previous-value new-value]}]
  (let [entry (merge {:timestamp (quot (System/currentTimeMillis) 1000)
                      :admin_id admin-id
                      :action action}
                     (when comment {:comment comment})
                     (when previous-value {:previous_value previous-value})
                     (when new-value {:new_value new-value}))]
    (assoc m
           :admin_event_log
           (-> (or (edn/read-string (:admin_event_log m)) [])
               (merge entry)
               str))))

(defn db-append-to-admin-event-log
  [db-conn table id
   & {:keys [admin-id action comment new-value previous-value
             ;; if you don't give previous-value you can use the
             ;; below to retrieve it on the fly by given column name.
             ;; of course, you'll need to call this func before your
             ;; logic that changes the value in the db
             retrieve-column-previous-value
             ]}]
  (when-let [limited-record
             (first
              (!select db-conn
                       table
                       (conj [:admin_event_log]
                             (keyword retrieve-column-previous-value))
                       {:id id}))]
    (!update db-conn
             table
             (append-to-admin-event-log
              limited-record
              :admin-id admin-id
              :action action
              :comment comment
              :previous-value
              (or previous-value
                  (when retrieve-column-previous-value
                    ((keyword retrieve-column-previous-value) limited-record)))
              :new-value new-value)
             {:id id})))
