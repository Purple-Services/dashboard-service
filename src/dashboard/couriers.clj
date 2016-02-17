(ns dashboard.couriers
  (:require [clojure.string :as s]
            [common.couriers :refer [process-courier]]
            [common.db :refer [!select !update]]
            [common.util :refer [split-on-comma]]))

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
      (process-courier courier))))

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

(defn update-courier-zones!
  "Update the zones for courier with user-id"
  [db-conn user-id zones]
  (let [zones-assignment-set (try
                               ;; wrap in doall, otherwise Exception might not
                               ;; be caught
                               (doall (->> zones
                                           split-on-comma
                                           (map (comp #(Integer. %) s/trim))
                                           set))
                               (catch Exception e (str "Error")))
        existant-zones-set (set (map :id
                                     (!select db-conn "zones" [:id] {})))
        all-zones-exist? (every? identity
                                 (map #(contains? existant-zones-set %)
                                      zones-assignment-set))]
    (cond
      (s/blank? zones)
      (!update db-conn
               "couriers"
               {:zones zones}
               {:id user-id})
      (= zones-assignment-set "Error")
      {:success false
       :message "Incorrectly formatted zone assignment"}
      (not all-zones-exist?)
      {:success false
       :message "All zones in assignment must exist"}
      all-zones-exist?
      (!update db-conn
               "couriers"
               {:zones (s/join "," zones-assignment-set)}
               {:id user-id})
      :else {:success false
             :message "Unknown error"})))
