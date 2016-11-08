(ns dashboard.test.zones
  (:require [bouncer.core :as b]
            [clojure.java.jdbc :refer [with-connection do-commands]]
            [clojure.test :refer [is deftest testing use-fixtures run-tests]]
            [common.db :as db]
            [common.zones :refer [get-zip-def-not-validated]]
            [dashboard.db :refer [raw-sql-update]]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :as db-tools]
            [dashboard.zones :as zones]))

;; for testing at repl
(def reset-db! db-tools/clear-and-populate-test-database)
;; you'll also have to initialize the db pool with
;; (db-tools/setup-ebdb-test-pool!)
;; sometimes you also need to run above again


(use-fixtures :once db-tools/setup-ebdb-test-for-conn-fixture)
(use-fixtures :each db-tools/clear-and-populate-test-database-fixture)

(defn stringify-config
  [zone]
  (assoc zone
         :current-zone
         (assoc zone
                :config (str (:config zone)))
         :config (str (:config zone))))

(defn create-admin!
  [db-conn]
  (data-tools/create-dash-user! {:db-conn db-conn
                                 :platform-id "foo@bar.com"
                                 :password "foobar"})
  (:id (first (db/!select db-conn "dashboard_users" [:id]
                          {:email "foo@bar.com"}))))
(defn create-earth-zone!
  "Create the earth zone. Assumes it doesn't already exist in the database"
  [db-conn]
  ;; create Earth
  (raw-sql-update
   db-conn
   (str "INSERT INTO `zones` (id,name,rank,active,color,config) "
        " VALUES "
        "(0,'Earth',0,1,'#DBDCDD',"
        "'{:gallon-choices {:0 7.5, :1 10, :2 15}, :default-gallon-choice :2, :time-choices {:0 60, :1 180, :2 300}, :default-time-choice 180, :delivery-fee {60 599, 180 399, 300 299}, :tire-pressure-price 700, :manually-closed? false, :closed-message nil}');"
        ))
  ;;due to auto-increment on id, will need to manually change it
  (raw-sql-update
   db-conn
   (str "UPDATE  `ebdb_test`.`zones` SET  `id` =  '1' WHERE  "
        "`zones`.`name` = 'Earth';")))

(deftest zone-addition-and-subtraction
  (let [db-conn (db/conn)
        admin-id (create-admin! db-conn)]
    ;; setup the earth
    (create-earth-zone! db-conn)
    ;; add Los Angeles
    (zones/create-zone! db-conn
                        {:name "Los Angeles"
                         :rank 100
                         :active true
                         :zips "90210,90211,90212,90213,90214,90215"}
                        admin-id)
    ;; is 90210 assigned to only Earth and Los Angeles?
    (let [los-angeles (zones/get-zone-by-name db-conn "Los Angeles")
          los-angeles-id (:id los-angeles)
          _ (zones/create-zone! db-conn
                                {:name ""}
                                admin-id)]
      (is (= (:zones (first (db/!select db-conn "zips" [:zip :zones]
                                        {:zip "90210"})))
             (str "1," los-angeles-id)))
      ;; 90210 is deleted from Los Angeles, it should also be deleted from the db
      (zones/update-zone! db-conn (assoc
                                   los-angeles
                                   :zips "90211,90212,90213,90214,90215"
                                   :current-zone
                                   (zones/get-zone-by-id db-conn
                                                         (:id los-angeles)))
                          admin-id)
      (is (nil? (db/!select (db/conn) "zips" [:zip :zones] {:zip "90210"})))
      ;; but 90211 still exists 
      (is (first (db/!select (db/conn) "zips" [:zip :zones] {:zip "90211"})))
      )))


(deftest zips-with-mulitple-zones
  (let [db-conn (db/conn)
        admin-id (create-admin! db-conn)
        ;; setup the earth
        _ (create-earth-zone! db-conn)
        ;; create san diego
        _ (zones/create-zone! db-conn {:name "San Diego"
                                       :rank 100
                                       :active true
                                       :zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154"
                                       :config (str
                                                {:gas-price {"87" 300, "91" 316},
                                                 :hours [[[420 1230]] [[420 1230]] [[420 1230]] [[420 1230]] [[420 1230]] [[780 1020]] []],
                                                 :manually-closed? false,
                                                 :constrain-num-one-hour? true,
                                                 :time-choices {:0 60, :1 180, :2 300},
                                                 :delivery-fee {60 599, 180 399, 300 299}})}
                              admin-id)
        san-diego (zones/get-zone-by-name db-conn "San Diego")
        ;; create La Jolla
        _ (zones/create-zone! db-conn {:name "La Jolla"
                                       :rank 1000
                                       :active true
                                       :zips "92037,92108,92111,92117,92121,92122,92123,92126,92131"
                                       :config (str {:manually-closed? false})}
                              admin-id)
        la-jolla (zones/get-zone-by-name db-conn "La Jolla")
        ;; create El Cajon
        _ (zones/create-zone! db-conn {:name "El Cajon"
                                       :rank 100
                                       :active true
                                       :zips "91901,91935,91941,91942,91945,91977,91978,92019,92020,92021,92040,92071,92114,92115,92119,92120,92124"

                                       :config (str {:manually-closed? false})}
                              admin-id)
        el-cajon (zones/get-zone-by-name db-conn "El Cajon")]
    (testing "A zone's config man be modified without affecting other zones"
      ;; update San Diego's config
      (zones/update-zone! db-conn
                          (-> (zones/get-zone-by-name db-conn "San Diego")
                              (stringify-config)
                              (assoc :config (str {:manually-closed? true})))
                          admin-id)
      ;; La Jolla and San Diego should have the same zips
      (is (= (zones/db-zips->obj-zips "92037,92108,92111,92117,92121,92122,92123,92126,92131")
             (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      (is (= (zones/db-zips->obj-zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154")
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "One zip can be removed without affecting another zone"
      ;; remove 92131 from La Jolla
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "La Jolla")
           (stringify-config)
           (assoc :zips "92037,92108,92111,92117,92121,92122,92123,92126"))
       admin-id)
      ;; La Jolla should have one less zip
      (is (= (zones/db-zips->obj-zips "92037,92108,92111,92117,92121,92122,92123,92126")
             (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      ;; And San Diego should not be affected
      (is (= (zones/db-zips->obj-zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154")
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "Multiple zips can be removed without affecting another zone"
      ;; remove 92111,92037,92126 from La Jolla
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "La Jolla")
           (stringify-config)
           (assoc :zips "92108,92117,92121,92122,92123"))
       admin-id)
      (is (= (zones/db-zips->obj-zips "92108,92117,92121,92122,92123")
             (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      (is (= (zones/db-zips->obj-zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154")
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "Multiple zips can be added without affecting another zone"
      ;; add 92111,92037,92126 back to La Jolla
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "La Jolla")
           (stringify-config)
           (assoc  :zips "92108,92117,92121,92122,92123,92111,92037,92126"))
       admin-id)
      (is (= (zones/db-zips->obj-zips "92037,92108,92111,92117,92121,92122,92123,92126")
             (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      (is (= (zones/db-zips->obj-zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154")
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "A zip will return the proper parameters, even upon deletion from one zone"
      (is (= ["Earth" "San Diego" "La Jolla"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126")))))
      ;; remove 92126 from San Diego
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "San Diego")
           (stringify-config)
           (assoc  :zips (zones/db-zips->obj-zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,,92129,92130,92131,92140,92154")))
       admin-id)
      (is (= ["Earth" "La Jolla"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126")))))))
  )


(deftest manually-created-zips
  (let [db-conn (db/conn)
        admin-id (create-admin! db-conn)
        ;; populate the zones table
        zones-insert-sql (db-tools/process-sql "database/zones-test.sql")
        _ (with-connection (db/conn) (apply do-commands zones-insert-sql))
        ;; populate the zips table
        zips-insert-sql (db-tools/process-sql "database/zips-test.sql")
        _ (with-connection (db/conn) (apply do-commands zips-insert-sql))
        ]
    ;; update San Diego's config
    (zones/update-zone! db-conn
                        (-> (zones/get-zone-by-name db-conn "San Diego")
                            (stringify-config)
                            (assoc :config (str {:manually-closed? true})))
                        admin-id)
    ;; La Jolla should not have nil zips
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "La Jolla")))))
    ;; but neither should San Dieo
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    ;; remove 92131 from La Jolla
    (zones/update-zone!
     db-conn
     (-> (zones/get-zone-by-name db-conn "La Jolla")
         (stringify-config)
         (assoc :zips "92037, 92108, 92111, 92117, 92121, 92122, 92123, 92126"))
     admin-id)
    ;; La Jolla should have one less zip
    (is (= (zones/db-zips->obj-zips
            "92037,92108,92111,92117,92121,92122,92123,92126")
           (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
    ;; And San Diego should not be affected
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "A zip will return the proper parameters, even upon deletion from one zone"
      (is (= ["Earth" "San Diego" "La Jolla"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126")))))
      ;; remove 92126 from San Diego
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "San Diego")
           (stringify-config)
           (assoc :zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,,92129,92130,92131,92140,92154"))
       admin-id)
      (is (= ["Earth" "La Jolla"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126")))))
      ;; put the zip back into San Diego, 92126 still returns proper results
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "San Diego")
           (stringify-config)
           (assoc :zips "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92129,92130,92131,92140,92154,92126"))
       admin-id)
      (is (= ["Earth" "San Diego" "La Jolla"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126")))))
      ;; remove the zip from La Jolla, 92126 still returns proper results
      (zones/update-zone!
       db-conn
       (-> (zones/get-zone-by-name db-conn "La Jolla")
           (stringify-config)
           (assoc :zips "92037,92108,92111,92117,92121,92122,92123,92131"))
       admin-id)
      (is (= ["Earth" "San Diego"]
             (:zone-names (get-zip-def-not-validated db-conn (str "92126"))))))
    ))

(def valid-zone {:name "FooBar"
                 :rank 100
                 :active true
                 :zips "90210,90211,90212"
                 :config {:hours [[[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]]}})
(defn zone->zone-config-str
  "Convert a zone's config map to a str"
  [zone]
  (assoc zone :config (str (:config zone))))

(defn get-bouncer-error
  [validation-map ks]
  (get-in (second validation-map)
          (vec (concat [:bouncer.core/errors] ks))))

(deftest new-zone-hour-validations
  (testing "A valid zip will return as valid with no errors"
    (is (b/valid?  valid-zone
                   zones/new-zone-validations)))
  (testing "Invalid hours in config are caught properly"
    ;; garbage given for minutes
    (is (= '("Hours must be given in integer format")
           (get-bouncer-error
            (b/validate (assoc-in  valid-zone [:config :hours]
                                   [[[450 1350]]
                                    [["foo" 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]])
                        zones/new-zone-validations)
            [:config :hours])))
    ;; minute counts outside of 0 1440
    (is (= '("Hours must be within the range of 12:00AM-11:59 PM")
           (get-bouncer-error
            (b/validate (assoc-in  valid-zone [:config :hours]
                                   [[[450 1350]]
                                    [[0 1440]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]])
                        zones/new-zone-validations)
            [:config :hours])))
    ;; minute counts outside of 1535 1440
    (is (= '("Hours must be within the range of 12:00AM-11:59 PM")
           (get-bouncer-error
            (b/validate (assoc-in  valid-zone [:config :hours]
                                   [[[1535 900]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[]]
                                    [[]]])
                        zones/new-zone-validations)
            [:config :hours])))
    ;; a closed hour is after an opening hour
    (is (= '("Opening Hour must occur before Closing Hour")
           (get-bouncer-error
            (b/validate (assoc-in  valid-zone [:config :hours]
                                   [[[450 1350]]
                                    [[200 100]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]])
                        zones/new-zone-validations)
            [:config :hours])))
    ;; too few days submitted
    (is (= '("Too few days submitted. Hours for M-Su must be included")
           (get-bouncer-error
            (b/validate (assoc-in  valid-zone [:config :hours]
                                   [[[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]
                                    [[450 1350]]])
                        zones/new-zone-validations)
            [:config :hours])))
    ;; too many days submitted
    (is (= ;;'("Too many days submitted. Only M-Su can be included")
         (get-bouncer-error
          (b/validate (assoc-in  valid-zone [:config :hours]
                                 [[[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]
                                  [[450 1350]]])
                      zones/new-zone-validations)
          [:config :hours])))
    ;; no hours submitted for config, passes validation
    (is (b/valid? (assoc-in valid-zone [:config]
                            (dissoc (:config valid-zone)
                                    :hours)) zones/new-zone-validations))))

(deftest zone-id-name-validations
  (let [db-conn (db/conn)
        admin-id (create-admin! db-conn)]
    ;; setup the earth
    (create-earth-zone! db-conn)
    ;; add FooBar
    (zones/create-zone! db-conn
                        (zone->zone-config-str valid-zone)
                        admin-id)
    (let [foobar (zones/get-zone-by-name db-conn "FooBar")]
      (testing "A zone can't be updated with the wrong id"
        (is (= '("That zone doesn't yet exist, create it first")
               (get-bouncer-error
                (:validation  (zones/update-zone! db-conn
                                                  (-> foobar
                                                      (stringify-config)
                                                      (assoc :id -1))
                                                  admin-id))
                [:id]))))
      (testing "Zones can't be created with a name that already exists"
        (is (= '("Name is already in use")
               (get-bouncer-error
                (:validation (zones/create-zone!
                              db-conn (zone->zone-config-str
                                       (assoc valid-zone
                                              :name "FooBar"))
                              admin-id))
                [:name]))))
      (testing "A zone can't be updated to a name that already exists"
        (zones/create-zone! db-conn (zone->zone-config-str
                                     (assoc valid-zone
                                            :name "BazQux"))
                            admin-id)
        (let [bazqux (zones/get-zone-by-name db-conn "BazQux")]
          (is (= '("Name already exists! Please use a unique zone name")
                 (get-bouncer-error
                  (:validation (zones/update-zone!
                                db-conn
                                (zone->zone-config-str
                                 (-> bazqux
                                     (stringify-config)
                                     (assoc :name "FooBar")))
                                admin-id))
                  [:name]))))))))
