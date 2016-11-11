(ns dashboard.zones
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [common.db :refer [conn !insert !select !update]]
            [common.util :refer [split-on-comma five-digit-zip-code
                                 in?]]
            [common.zones :as zones]
            [dashboard.db :refer [raw-sql-query
                                  raw-sql-update]]
            [dashboard.utils :as utils]))

(defn db-zips->obj-zips
  [zips]
  (if-not (nil? zips)
    (s/join ", " (s/split zips #","))
    "Zip Code Error"))

(defn format-zone
  "Given a zone, format it so that it will return proper json"
  [zone]
  (assoc zone
         :config
         (let [config (:config zone)]
           (if (and (utils/edn-read? config)
                    (not (nil? config)))
             (edn/read-string config)
             nil))
         :zips
         (db-zips->obj-zips (:zips zone))))

(defn get-all-zones-from-db
  "Retrieve all zones from the DB"
  [db-conn]
  (let [results
        (raw-sql-query
         db-conn
         [(str "SELECT zones.id as `id`, zones.name as `name`,"
               "zones.rank as `rank`, zones.active as `active`,"
               "zones.color as `color`, zones.config as `config`, "
               "GROUP_CONCAT(distinct zips.zip) as `zips`, "
               "COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` "
               "LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) "
               "GROUP BY zones.id;")])]
    (map format-zone
         results)))

(defn get-zone-by-id
  "Return a zone as expected by the dashboard client"
  [db-conn id]
  (let [zone (raw-sql-query
              db-conn
              [(str "SELECT zones.id as `id`, zones.name as `name`,"
                    "zones.rank as `rank`,zones.active as `active`,"
                    "zones.color as `color`, zones.config as `config`, "
                    "GROUP_CONCAT(distinct zips.zip) as `zips`, "
                    "COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` "
                    "LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) "
                    "WHERE zones.id = " id " "
                    "GROUP BY zones.id;")])]
    (if-not (nil? zone)
      (format-zone (first zone))
      nil)))

(defn get-zone-by-name
  "Return a zone as expected by the dashboard client by name"
  [db-conn name]
  (let [zone (raw-sql-query
              db-conn
              [(str "SELECT zones.id as `id`, zones.name as `name`,"
                    "zones.rank as `rank`,zones.active as `active`,"
                    "zones.color as `color`, zones.config as `config`, "
                    "GROUP_CONCAT(distinct zips.zip) as `zips`, "
                    "COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` "
                    "LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) "
                    "WHERE zones.name = '" name "' "
                    "GROUP BY zones.id;")])]
    (if-not (nil? zone)
      (format-zone (first zone))
      nil)))

(defn get-all-zips-by-zone-id
  "Return all zips associated with zone-id"
  [db-conn zone-id]
  (let [zips (raw-sql-query
              db-conn
              [(str "SELECT zip,zones "
                    "FROM zips "
                    "WHERE FIND_IN_SET (" zone-id ",zips.zones);")])]
    zips))

(defn mysql-zips-str
  "Given a list of zips, return a mysql-zips-str for a WHERE IN
  statement"
  [zips]
  (str "(" (s/join ", " zips) ")"))

(defn existent-zips
  "Obtain the existent zips from the database"
  [db-conn zips]
  (if-not (nil? zips)
    (raw-sql-query
     db-conn
     [(str "SELECT zip,zones FROM `zips` "
           "WHERE `zip` IN "
           (mysql-zips-str zips))])
    '()))

(defn existent-zips-list
  [db-conn zips]
  "Given a list of zips, return a list of zips that exist in the db"
  (map :zip (existent-zips db-conn
                           (filter (comp not nil?) zips))))

(defn add-zips-to-zone!
  "Given a list of zips, add these zips to zone by updating the zones
  defitnition for zips that already exist and creating new zips for those that
  are nonexistent. New zips will have the zone defition '1,<zone-id>' because
  all zips must be members of the Earth zone."
  [db-conn zips zone-id]
  (cond (nil? zips)
        {:success true}
        :else
        (let [;; filter out all nil values from zips
              zips (filter (comp not nil?) zips)
              new-zone-string (if (= zone-id 1)
                                "1"
                                (str "1," zone-id))
              ;; all zips that exist
              existent-zips-list (existent-zips-list db-conn zips)
              zips-diff (data/diff (set existent-zips-list) (set zips))
              non-existent-zips (second zips-diff)
              mysql-non-existent-zip-values (s/replace
                                             (apply
                                              str
                                              (map #(str "(" "'" % "'"
                                                         ",'" new-zone-string "'),")
                                                   non-existent-zips))
                                             #",$"
                                             ";")
              non-existent-zip-insert-statement (str "INSERT INTO `zips` (zip,zones)"
                                                     " VALUES "
                                                     mysql-non-existent-zip-values)
              ;; zips that exist, but are already assigned to
              ;; some zones
              assigned-current-zips (raw-sql-query
                                     db-conn
                                     [(str "SELECT zip,zones FROM `zips` "
                                           "WHERE `zip` IN "
                                           (mysql-zips-str zips)
                                           "AND "
                                           "NOT FIND_IN_SET "
                                           "(" zone-id ",zips.zones);")])
              assigned-current-zips-str (str
                                         "("
                                         (s/join ", "
                                                 (map :zip assigned-current-zips))
                                         ")")]
          ;; update the existent zones
          (when (and (not= zone-id 1)
                     (not (empty? assigned-current-zips)))
            (raw-sql-update
             db-conn
             (str "UPDATE `zips` SET `zones` = CONCAT(zones,'," zone-id "') "
                  "WHERE `zip` IN " assigned-current-zips-str ";")))
          ;; add the non-existent zones
          (when-not (empty? non-existent-zips)
            (raw-sql-update
             db-conn
             non-existent-zip-insert-statement))
          {:success true})))

(defn remove-zips-from-zone!
  "Given a list of zips, remove these zips from the zone"
  [db-conn zips zone-id]
  (cond (= zone-id 1)
        {:success false :message "You can't remove zips from zone-id = 1"}
        (nil? zips)
        {:success true}
        :else
        (let [;; we're not going to even consider zips
              ;; that don't exist for removal as it is
              ;; nonsense to do so
              existent-zips (get-all-zips-by-zone-id db-conn zone-id)
              ;; need to consider ONLY the zips that are removed,
              ;; not all of them
              zips-to-remove (filter #(contains?
                                       (set (filter
                                             ;; this filters out
                                             ;; any nils in zips
                                             (comp not nil?)
                                             zips))
                                       (:zip %))
                                     existent-zips)
              reg-match #(re-matches (re-pattern
                                      (str "^1," zone-id "$"))
                                     %)
              single-zone-zips (filter #(reg-match (:zones %)) zips-to-remove)
              multiple-zone-zips (filter #(not (reg-match (:zones %)))
                                         zips-to-remove)
              remove-zone (fn [zone zone-id]
                            (s/join ","
                                    (sort (filter (partial not= (str zone-id))
                                                  (s/split zone #",")))))
              modified-zones (map #(assoc %
                                          :zones
                                          (remove-zone (:zones %) zone-id))
                                  multiple-zone-zips)
              modified-zones-values (s/join
                                     ","
                                     (map #(str "('" (:zip %) "','" (:zones %) "')")
                                          modified-zones))
              modify-zones-statement (str "INSERT INTO `zips` (zip,zones) VALUES "
                                          modified-zones-values " "
                                          "ON DUPLICATE KEY UPDATE zip=VALUES(zip),"
                                          "zones=VALUES(zones);")
              delete-zones-values (str "(" (s/join
                                            ","
                                            (map #(str "'" % "'")
                                                 (map :zip single-zone-zips)))
                                       ")")
              delete-zones-statement (str "DELETE FROM `zips` WHERE (zip) IN "
                                          delete-zones-values ";")]
          ;; when there are zips that should be mofidied
          (when-not (empty? multiple-zone-zips)
            (raw-sql-update
             db-conn
             modify-zones-statement))
          ;; when there are zips that should be deleted
          (when-not (empty? single-zone-zips)
            (raw-sql-update
             db-conn
             delete-zones-statement))
          {:success true})))

(defn name-available?
  "Is the name for zone currently available?"
  [name]
  (not (boolean (get-zone-by-name (conn) name))))

(defn valid-zip?
  "Check that a zip code is just 5 digits"
  [zip-code]
  (boolean (re-matches #"\d{5}" zip-code)))

(defn new-zone-name?
  "Is name a new name for zone with id?"
  [name id]
  (let [current-zone (get-zone-by-id (conn) id)]
    (not= (:name current-zone) name)))

(defn current-zone-name?
  "Is name the same for zone?"
  [name id]
  (let [current-zone (get-zone-by-id (conn) id)]
    (= (:name current-zone) name)))

(defn name-available-or-new-name?
  "Is the name either available or a new name in the db?"
  [name id]
  (or (and (new-zone-name? name id)
           (name-available? name))
      (current-zone-name? name id)))

(defn zip-str->zip-vec
  "Given a list of zip codes separated by a non-number, seperate them into an
  vector of zips."
  [zip-str]
  ;; can be given a wide variety of garbage, but only valid zips will be
  ;; returned. This is to ensure that the db never gets populated with
  ;; invalid-looking zips. However, there is no guarantee that the zips
  ;; actually exist
  (into [] (re-seq #"\d{5}" zip-str)))

(defn zips-valid?
  "Given a string of zips, check to see if they return some valid zips"
  [zip-codes]
  (let [zips (zip-str->zip-vec zip-codes)]
    (and (not (empty? zips)) (every? identity (map valid-zip? zips)))))

(defn zone-exists?
  "Does the zone with id exist?"
  [id]
  (boolean (not (nil? (!select (conn) "zones" [:id] {:id id})))))

(defn pre-validator
  "Add a pre condition to a vector of validators"
  [validators pre]
  (into
   []
   (map
    #(into [] (concat % [:pre pre]))
    validators)))

(defn stale-current-zone?
  [current-zone]
  (let [current-zone-db (get-zone-by-id (conn) (:id current-zone))]
    (boolean (= current-zone current-zone-db))))

(defn zone-validations [& [id]]
  {:id   [[zone-exists?
           :message "That zone doesn't yet exist, create it first"]]
   :name [[name-available-or-new-name? id
           :message "Name already exists! Please use a unique zone name"]
          [v/required :message "Name can not be blank!"]
          [v/string :message "Name must be a string"]]
   :rank [[v/required :message "Rank can not be blank!"]
          [v/integer :message "Rank must be a whole number"]
          [v/in-range [2 10000] :message
           "Rank must be between 1 and 10000"]]
   :active [[v/required :message "Active must be present"]
            [v/boolean :message "Active must be either true or false"]]
   :zips [[v/required :message "A zone must have zip codes associated with it"]
          [zips-valid? :message (str "You must provide 5-digit zip codes "
                                     "separated by a commas")]]
   :current-zone
   [[stale-current-zone?
     :message (str "Someone else was editing this zone while you were. "
                   "Click 'Dismiss' and click the refresh button below to "
                   "get the updated version before making changes.")]]
   [:config :hours] (pre-validator
                     [[vector?
                       :message (str "Hours must be in vector format.")]
                      [(partial every?
                                (partial every?
                                         #(every? integer? %)))
                       :message
                       "Hours must be given in integer format"]
                      [(partial every?
                                (partial every?
                                         #(every? (fn [x]
                                                    (v/in-range x [0 1439]))
                                                  %)))
                       :message
                       "Hours must be within the range of 12:00AM-11:59 PM"]
                      [(partial every?
                                (partial every?
                                         #(<= (first %) (second %))))
                       :message
                       "Opening Hour must occur before Closing Hour"]
                      [#(>= (count %) 7)
                       :message
                       "Too few days submitted. Hours for M-Su must be included"]
                      [#(>= 7 (count %))
                       :message
                       "Too many days submitted. Only M-Su can be included"]
                      [(partial every?
                                (partial every?
                                         #(and (vector? %)
                                               (= 2 (count %))
                                               (every? integer? %)
                                               (every? (fn [x]
                                                         (v/in-range x [0 1440]))
                                                       %)
                                               (<= (first %) (second %)))))
                       :message
                       "Hours have been incorrectly formatted"]]
                     (comp not nil? #(get-in % [:config :hours])))

   })

(def new-zone-validations
  (let [zone-validations (zone-validations)]
    (-> zone-validations
        (assoc :name
               [[name-available? :message "Name is already in use"]
                [v/required :message "Name can not be blank!"]
                [v/string :message "Name must be a string"]])
        (dissoc :id)
        (dissoc :current-zone))))

(defn log-zone-event!
  [db-conn {:keys [user_id action entity_id data comment]}]
  (!insert db-conn "dashboard_event_log"
           {:user_id user_id
            :action action
            :entity_type "zone"
            :entity_id entity_id
            :data data
            :comment comment}))

(defn create-zone!
  "Given a zone map, validate it. If valid, create zone else return the
  bouncer error map."
  [db-conn zone admin-id]
  (let [{:keys [name rank active zips config]} zone
        new-zone (assoc zone :config (edn/read-string config))]
    (if (b/valid? new-zone new-zone-validations)
      (let [zips-vec (zip-str->zip-vec zips)
            insert-result (!insert db-conn "zones"
                                   {:name name
                                    :rank rank
                                    :active active
                                    :config config})
            new-zone (first (!select db-conn "zones"
                                     [:id] {:name name}))
            id (:id new-zone)]
        (if (:success insert-result)
          (do
            ;; add the zips
            (add-zips-to-zone! db-conn zips-vec id)
            ;; add event log
            (log-zone-event! db-conn {:user_id admin-id
                                      :action "create-zone"
                                      :entity_id id
                                      :data (str (get-zone-by-id db-conn id))
                                      :comment ""})
            ;; return a result
            (assoc insert-result :id id))
          insert-result))
      {:success false
       :validation (b/validate new-zone new-zone-validations)})))

(defn update-zone!
  "Given a zone map, validate it. If valid, update zone else return the
  bouncer error map."
  [db-conn zone admin-id]
  (let [{:keys [id name rank active zips config current-zone]} zone
        updated-zone (assoc zone
                            :config (edn/read-string config)
                            :current-zone
                            (assoc current-zone
                                   :config
                                   (edn/read-string (:config current-zone))))]
    (if (b/valid? updated-zone (zone-validations id))
      (let [old-zone (get-zone-by-id db-conn id)
            new-zips-vec (zip-str->zip-vec zips)
            old-zips-vec (map :zip (get-all-zips-by-zone-id db-conn id))
            update-result (!update db-conn "zones"
                                   {:name name
                                    :rank rank
                                    :active active
                                    :config config}
                                   {:id id})
            [old-zips new-zips _] (data/diff old-zips-vec new-zips-vec)]
        (if (:success update-result)
          (do
            ;; update the zips in the zone
            (remove-zips-from-zone! db-conn old-zips id)
            (add-zips-to-zone! db-conn new-zips id)
            (let [new-zone (get-zone-by-id db-conn id)
                  zone-diff (data/diff old-zone new-zone)
                  [old-changed-values new-changed-values _] zone-diff]
              (log-zone-event! db-conn {:user_id admin-id
                                        :action "modify-zone"
                                        :entity_id id
                                        :data (str {:changed-values
                                                    {:old-values
                                                     old-changed-values
                                                     :new-values
                                                     new-changed-values}})
                                        :comment ""}))
            ;; return the result with id of zone
            (assoc update-result :id id))
          update-result))
      {:success false
       :validation (b/validate updated-zone (zone-validations id))})))
