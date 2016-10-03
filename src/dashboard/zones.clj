(ns dashboard.zones
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [common.db :refer [!select !update]]
            [common.util :refer [split-on-comma five-digit-zip-code
                                 in?]]
            [dashboard.db :refer [raw-sql-query]]))

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
         ["SELECT zones.id as `id`, zones.name as `name`, zones.rank as `rank`,zones.active as `active`,zones.config as `config`, GROUP_CONCAT(distinct zips.zip) as `zips`, COUNT(DISTINCT zips.zip) as `zip_count` FROM `zones` LEFT JOIN zips ON FIND_IN_SET (zones.id,zips.zones) GROUP BY zones.id;"])]
    (map #(assoc %
                 :config
                 (read-string (:config %))
                 :zips
                 (s/join ", " (s/split (:zips %) #",")))
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
                    "GROUP BY zones.id;"
                    )])]
    (first zone)))

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

(def zone-validations
  {:name [[v/required :message "Name can not be blank!"]
          [v/string :message "Name must be a string"]]
   :rank [[v/required :message "Rank can not be blank!"]
          [v/integer :message "Rank must be a whole number"]
          [v/in-range [1 10000] :message
           "Rank must be between 1 and 10000"]]
   :active [[v/required :message "Active must be present"]
            [v/boolean :message "Active must be either true or false"]]})

(defn validate-and-update-zone!
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
  (if (b/valid? zone zone-validations)
    (let [{:keys [id name rank active]} zone
          update-result (!update db-conn "zones"
                                 {:name name
                                  :rank rank
                                  :active active}
                                 {:id id})]
      (if (:success update-result)
        (assoc update-result :id id)
        update-result))
    {:success false
     :validation (b/validate zone zone-validations)}))
