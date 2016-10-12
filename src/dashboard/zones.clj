(ns dashboard.zones
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.data :as data]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [common.db :refer [conn !insert !select !update]]
            [common.util :refer [split-on-comma five-digit-zip-code
                                 in?]]
            [common.zones :as zones]
            [dashboard.db :refer [raw-sql-query
                                  raw-sql-update]]
            [dashboard.utils :as utils]))

#_ (defn read-zone-strings
     "Given a zone from the database, convert edn strings to clj data"
     [zone]
     (assoc zone
            :fuel_prices (stringify-keys
                          (read-string (:fuel_prices zone)))
            :service_fees (stringify-keys
                           (read-string (:service_fees zone)))
            :service_time_bracket (read-string
                                   (:service_time_bracket zone))))

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
    (map #(assoc %
                 :config
                 (let [config (:config %)]
                   (if (and (utils/edn-read? config)
                            (not (nil? config)))
                     (read-string config)
                     "config error"))
                 :zips
                 (let [zips (:zips %)]
                   (if-not (nil? zips)
                     (s/join ", " (s/split (:zips %) #","))
                     "Zip Code Error")))
         results)))

(defn get-zone-by-id
  "Return a zone as expected by the dashboard client"
  [db-conn id]
  (let [zone (raw-sql-query
              db-conn
              [(str "SELECT zones.id as `id`, zones.name as `name`,"
                    "zones.rank as `rank`,zones.active as `active`,"
                    "zones.config as `config`, "
                    "GROUP_CONCAT(distinct zips.zip) as `zips`, "
                    "COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` "
                    "LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) "
                    "WHERE zones.id = " id " "
                    "GROUP BY zones.id;")])]
    (first zone)))

(defn get-zone-by-name
  "Return a zone as expected by the dashboard client by name"
  [db-conn name]
  (let [zone (raw-sql-query
              db-conn
              [(str "SELECT zones.id as `id`, zones.name as `name`,"
                    "zones.rank as `rank`,zones.active as `active`,"
                    "zones.config as `config`, "
                    "GROUP_CONCAT(distinct zips.zip) as `zips`, "
                    "COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` "
                    "LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) "
                    "WHERE zones.name = '" name "' "
                    "GROUP BY zones.id;")])]
    (first zone)))

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

(defn existant-zips
  "Obtain the existant zips from the database"
  [db-conn zips]
  (raw-sql-query
   db-conn
   [(str "SELECT zip,zones FROM `zips` "
         "WHERE `zip` IN "
         (mysql-zips-str zips))]))

(defn existant-zips-list
  [db-conn zips]
  "Given a list of zips, return a list of zips that exist in the db"
  (map :zip (existant-zips db-conn zips)))

(defn add-zips-to-zone!
  "Given a list of zips, add these zips to zone by updating the zones
  defitnition for zips that already exist and creating new zips for those that
  are nonexistant. New zips will have the zone defition '1,<zone-id>' because
  all zips must be members of the Earth zone."
  [db-conn zips zone-id]
  (let [new-zone-string (if (= zone-id 1)
                          "1"
                          (str "1," zone-id))
        ;; all zips that exist
        existant-zips-list (existant-zips-list db-conn zips)
        zips-diff (data/diff (set existant-zips-list) (set zips))
        non-existant-zips (second zips-diff)
        mysql-non-existant-zip-values (s/replace
                                       (apply
                                        str
                                        (map #(str "(" "'" % "'"
                                                   ",'" new-zone-string "'),")
                                             non-existant-zips))
                                       #",$"
                                       ";")
        non-existant-zip-insert-statement (str "INSERT INTO `zips` (zip,zones)"
                                               " VALUES "
                                               mysql-non-existant-zip-values)
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
    ;; update the existant zones
    (when (and (not= zone-id 1)
               (not (empty? assigned-current-zips)))
      (raw-sql-update
       db-conn
       (str "UPDATE `zips` SET `zones` = CONCAT(zones,'," zone-id "') "
            "WHERE `zip` IN " assigned-current-zips-str ";")))
    ;; add the non-existant zones
    (when-not (empty? non-existant-zips)
      (raw-sql-update
       db-conn
       non-existant-zip-insert-statement))
    {:success true}))

(defn remove-zips-from-zone!
  "Given a list of zips, remove these zips from the zone"
  [db-conn zips zone-id]
  (if-not (= zone-id 1)
    (let [;; we're not going to even consider zips
          ;; that don't exist for removal as it is
          ;; nonsense to do so
          existant-zips (get-all-zips-by-zone-id db-conn zone-id)
          ;;(existant-zips db-conn zips)
          reg-match #(re-matches (re-pattern
                                  (str "^1," zone-id "$"))
                                 %)
          single-zone-zips (filter #(reg-match (:zones %)) existant-zips)
          multiple-zone-zips (filter #(not (reg-match (:zones %)))
                                     existant-zips)
          remove-zone (fn [zone zone-id]
                        (s/replace zone (re-pattern (str "," zone-id)) ""))
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
      {:success true}))
  {:success false :message "You can't remove zips from zone-id = 1"})


#_ (def zone-validations
     {:price-87 [[v/required :message "87 Octane price can not be blank!"]
                 [v/number
                  :message "87 Octance price must be in a dollar amount"]
                 [v/in-range [1 5000] :message
                  "Price must be within $0.01 and $50.00"]]
      :price-91 [[v/required :message "91 Octane price can not be blank!"]
                 [v/number
                  :message "91 Octance price must be in a dollar amount"]
                 [v/in-range [1 5000] :message
                  "Price must be within $0.01 and $50.00"]]
      :service-fee-60 [[v/required :message
                        "One hour service fee can not be blank!"]
                       [v/number
                        :message
                        "One hour service fee must be in a dollar amount"]
                       [v/in-range [0 5000] :message
                        "Price must be within $0.00 and $50.00"]]
      :service-fee-180 [[v/required :message
                         "Three hour service fee can not be blank!"]
                        [v/number
                         :message
                         "Three hour service fee must be in a dollar amount"]
                        [v/in-range [0 5000] :message
                         "Price must be within $0.00 and $50.00"]]
      :service-fee-300 [[v/required :message
                         "Five hour service fee can not be blank!"]
                        [v/number
                         :message
                         "Five hour service fee must be in a dollar amount"]
                        [v/in-range [0 5000] :message
                         "Price must be within $0.00 and $50.00"]]
      :service-time-bracket-begin [[v/required :message
                                    "Begin time can not be blank!"]
                                   [v/number
                                    :message "Service time must be a number"]
                                   [v/integer
                                    :message
                                    "Service time must be a whole number!"]
                                   [v/in-range [0 1440]
                                    :message
                                    "Service time must be between 0 and 1440"]]
      :service-time-bracket-end [[v/required :message
                                  "End time can not be blank!"]
                                 [v/number
                                  :message "Service time must be a number"]
                                 [v/integer
                                  :message
                                  "Service time must be a whole number!"]
                                 [v/in-range [0 1440]
                                  :message
                                  "Service time must be between 0 and 1440"]]})

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
  (boolean (not (nil? (get-zone-by-id (conn) id)))))

(defn zone-validations [& [id]]
  {:id   [[zone-exists?
           :message "That zone doesn't yet exist, create it first"]]
   :name [[name-available-or-new-name? id
           :message "Name already exists! Please use a unique zone name"]
          [v/required :message "Name can not be blank!"]
          [v/string :message "Name must be a string"]]
   :rank [[v/required :message "Rank can not be blank!"]
          [v/integer :message "Rank must be a whole number"]
          [v/in-range [1 10000] :message
           "Rank must be between 1 and 10000"]]
   :active [[v/required :message "Active must be present"]
            [v/boolean :message "Active must be either true or false"]]
   :zips [[v/required :message "A zone must have zip codes associated with it"]
          [zips-valid? :message (str "You must provide 5-digit zip codes "
                                     "separated by a commas")]]})

(def new-zone-validations
  (let [zone-validations (zone-validations)]
    (-> zone-validations
        (assoc :name
               [[name-available? :message "Name is already in use"]
                [v/required :message "Name can not be blank!"]
                [v/string :message "Name must be a string"]])
        (dissoc :id))))

(defn create-zone!
  "Given a new-zone map, validate it. If valid, create zone else return the
  bouncer error map."
  [db-conn new-zone]
  (if (b/valid? new-zone new-zone-validations)
    (let [{:keys [name rank active zips]} new-zone
          zips-vec (zip-str->zip-vec zips)
          insert-result (!insert db-conn "zones"
                                 {:name name
                                  :rank rank
                                  :active active})
          new-zone (first (!select db-conn "zones"
                                   [:id] {:name name}))
          id (:id new-zone)]
      (if (:success insert-result)
        (do
          ;; add the zips
          (add-zips-to-zone! db-conn zips-vec id)
          ;; return a result
          (assoc insert-result :id id))
        insert-result))
    {:success false
     :validation (b/validate new-zone new-zone-validations)}))

(defn update-zone!
  "Given a zone map, validate it. If valid, update zone else return the
  bouncer error map.

  Update fields are fuel_prices, service_fees and service_time_bracket

  name is a string

  rank is an integer with a value between 1 and 10000

  active? is a boolean

  fuel-prices is an edn string map of the format
  '{:87 <integer cents> :91 <integer cents>}'.

  service-fees is an edn string map of the format
  '{:60 <integer cents> :180 <integer cents> :300 <integer cents>}'.

  service-time-bracket is an edn string vector of the format
  '[<service-start> <service-end>]' where <service-start> and <service-end>
  are integer values of the total minutes elapsed in a day at a particular
  time.

  ex:
  The vector [450 1350] represents the time bracket 7:30am-10:30pm where
  7:30am is represented as 450 which is (+ (* 60 7) 30)
  10:30pm is represened as 1350 which is (+ (* 60 22) 30)"
  [db-conn zone]
  (let [{:keys [id name rank active zips]} zone]
    (if (b/valid? zone (zone-validations id))
      (let [new-zips-vec (zip-str->zip-vec zips)
            old-zips-vec (map :zips (get-all-zips-by-zone-id db-conn id))
            update-result (!update db-conn "zones"
                                   {:name name
                                    :rank rank
                                    :active active}
                                   {:id id})
            [old-zips new-zips _] (data/diff old-zips-vec new-zips-vec)]
        (if (:success update-result)
          (do
            ;; update the zips in the zone
            (remove-zips-from-zone! db-conn old-zips id)
            (add-zips-to-zone! db-conn new-zips id)
            ;; return the result with id of zone
            (assoc update-result :id id))
          update-result))
      {:success false
       :validation (b/validate zone (zone-validations id))})))
