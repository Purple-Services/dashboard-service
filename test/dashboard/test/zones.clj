(ns dashboard.test.zones
  (:require [clojure.java.jdbc :refer [with-connection do-commands]]
            [clojure.test :refer [is deftest testing use-fixtures]]
            [common.db :as db]
            [dashboard.db :refer [raw-sql-update]]
            [dashboard.test.db-tools :as db-tools]
            [dashboard.zones :as zones]))

;; for testing at repl
(def reset-db! db-tools/clear-and-populate-test-database)
;; you'll also have to initialize the db pool with
;; (db-tools/setup-ebdb-test-pool!)


(use-fixtures :once db-tools/setup-ebdb-test-for-conn-fixture)
(use-fixtures :each db-tools/clear-and-populate-test-database-fixture)

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
  (let [db-conn (db/conn)]
    ;; setup the earth
    (create-earth-zone! db-conn)
    ;; add Los Angeles
    (zones/create-zone! db-conn
                        {:name "Los Angeles"
                         :rank 100
                         :active true
                         :zips "90210,90211,90212,90213,90214,90215"})
    ;; is 90210 assigned to only Earth and Los Angeles?
    (let [los-angeles (first
                       (db/!select db-conn "zones" ["*"] {:name "Los Angeles"}))
          los-angeles-id (:id los-angeles)
          _ (zones/create-zone! db-conn
                                {:name ""})
          ]
      (is (= (:zones (first (db/!select db-conn "zips" [:zip :zones]
                                        {:zip "90210"})))
             (str "1," los-angeles-id)))
      ;; 90210 is delete from Los Angeles, it should also be deleted from the db
      (zones/update-zone! db-conn (assoc
                                   los-angeles
                                   :zips "90211,90212,90213,90214,90215"))
      (is (nil? (db/!select (db/conn) "zips" [:zip :zones] {:zip "90210"})))
      ;; but 90211 still exists 
      (is (first (db/!select (db/conn) "zips" [:zip :zones] {:zip "90211"}))))))


(deftest zips-with-mulitple-zones
  (let [db-conn (db/conn)
        ;; create san diego
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
                                                 :delivery-fee {60 599, 180 399, 300 299}})})
        san-diego (first (db/!select db-conn "zones" ["*"] {:name "San Diego"}))
        ;; create La Jolla
        _ (zones/create-zone! db-conn {:name "La Jolla"
                                       :rank 1000
                                       :active true
                                       :zips "92037,92108,92111,92117,92121,92122,92123,92126,92131"
                                       :config (str {:manually-closed? false})})
        la-jolla (first (db/!select db-conn "zones" ["*"] {:name "La Jolla"}))
        ;; create El Cajon
        _ (zones/create-zone! db-conn {:name "El Cajon"
                                       :rank 100
                                       :active true
                                       :zips "91901,91935,91941,91942,91945,91977,91978,92019,92020,92021,92040,92071,92114,92115,92119,92120,92124"

                                       :config (str {:manually-closed? false})})
        el-cajon (first (db/!select db-conn "zones" ["*"] {:name "El Cajon"}))]
    (testing "A zone's config man be modified without affecting other zones"
      ;; update San Diego's config
      (zones/update-zone! db-conn
                          (assoc (zones/get-zone-by-name db-conn "San Diego")
                                 :config (str {:manually-closed? true})))
      ;; La Jolla and San Diego should have the same zips
      (is (=  "92037,92108,92111,92117,92121,92122,92123,92126,92131"
              (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      (is (= "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154"
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    (testing "A zone's zips can be removed without affecting other zones"
      ;; remove 92131 from La Jolla
      (zones/update-zone!
       db-conn
       (assoc (zones/get-zone-by-name db-conn "La Jolla")
              :zips "92037,92108,92111,92117,92121,92122,92123,92126"))
      ;; La Jolla should have one less zip
      (is (= "92037,92108,92111,92117,92121,92122,92123,92126"
             (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
      ;; And San Diego should not be affected
      (is (= "91901,91910,91911,91913,91932,91935,91941,91942,91945,91950,91977,91978,92007,92008,92009,92010,92011,92014,92019,92020,92021,92024,92037,92040,92054,92067,92071,92075,92091,92101,92102,92103,92104,92105,92106,92107,92108,92109,92110,92111,92113,92114,92115,92116,92117,92119,92120,92121,92122,92123,92124,92126,92129,92130,92131,92140,92154"
             (:zips (zones/get-zone-by-name db-conn "San Diego")))))))


(deftest manually-created-zips
  (let [db-conn (db/conn)
        ;; populate the zones table
        zones-insert-sql (db-tools/process-sql "database/zones-test.sql")
        _ (with-connection (db/conn) (apply do-commands zones-insert-sql))
        ;; populate the zips table
        zips-insert-sql (db-tools/process-sql "database/zips-test.sql")
        _ (with-connection (db/conn) (apply do-commands zips-insert-sql))
        ]
    ;; update San Diego's config
    (zones/update-zone! db-conn
                        (assoc (zones/get-zone-by-name db-conn "San Diego")
                               :config (str {:manually-closed? true})))
    ;; La Jolla should not have nil zips
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "La Jolla")))))
    ;; but neither should San Dieo
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "San Diego")))))
    ;; remove 92131 from La Jolla
    (zones/update-zone!
     db-conn
     (assoc (zones/get-zone-by-name db-conn "La Jolla")
            :zips "92037, 92108, 92111, 92117, 92121, 92122, 92123, 92126"))
    ;; La Jolla should have one less zip
    (is (= "92037,92108,92111,92117,92121,92122,92123,92126"
           (:zips (zones/get-zone-by-name db-conn "La Jolla"))))
    ;; And San Diego should not be affected
    (is (not (nil? (:zips (zones/get-zone-by-name db-conn "San Diego")))))))
