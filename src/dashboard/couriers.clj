(ns dashboard.couriers
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [common.couriers :refer [process-courier]]
            [common.db :refer [!select !update conn]]
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

(defn edn-read?
  "Attempt to read x with edn/read-string, return true if x can be read,false
  otherwise "
  [x]
  (try (edn/read-string x)
       true
       (catch Exception e false)))

(def courier-validations
  {:zones [;; verify that the zone assignments can be read as edn
           ;; must be done first to prevent throwing an error
           ;; from edn-read
           [#(every? identity
                     (->> %
                          split-on-comma
                          (map edn-read?)))
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
            :message (str "All zones in assignment must exist")]]})

(defn update-courier!
  "Update the zones for courier with user-id"
  [db-conn courier]
  ;; make sure the zones string will split into valid edn elements
  (if (b/valid? courier courier-validations)
    (let [zones-str (->> (:zones courier)
                         split-on-comma
                         (map edn/read-string)
                         (filter (comp not nil?))
                         set
                         sort
                         (s/join ","))]
      (assoc
       (!update db-conn "couriers" {:zones zones-str} {:id (:id courier)})
       :id (:id courier)))
    {:success false
     :validation (b/validate courier courier-validations)}))
