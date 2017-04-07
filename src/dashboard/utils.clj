(ns dashboard.utils
  (:require [clojure.edn :as edn]))

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
