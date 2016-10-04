(ns dashboard.couriers
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [common.couriers :refer [parse-courier-zones]]
            [common.db :refer [!select !update conn]]
            [common.util :refer [split-on-comma]]
            [dashboard.utils :as utils]))

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
             :timestamp_create
             (/ (.getTime
                 (:timestamp_create courier))
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
