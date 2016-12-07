(ns dashboard.test.users
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [common.db :as db]
            [dashboard.login :as login]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :as db-tools]
            [dashboard.users :as users]))
;; for testing at repl
(def reset-db! db-tools/clear-and-populate-test-database)
;; you'll also have to initialize the db pool with
;; (db-tools/setup-ebdb-test-pool!)
;; sometimes you also need to run above again

(use-fixtures :once db-tools/setup-ebdb-test-for-conn-fixture)
(use-fixtures :each db-tools/clear-and-populate-test-database-fixture)

(deftest users-can-be-updated
  (let [;; create dash user
        db-conn (db/conn)
        dash-email "foo@bar.com"
        dash-password "foobar"
        dash-full-name "Foo Bar"
        ;; create dash user
        _ (data-tools/create-dash-user! {:db-conn db-conn
                                         :platform-id dash-email
                                         :password dash-password
                                         :full-name dash-full-name})
        dash-user-server (first (db/!select db-conn "dashboard_users" ["*"]
                                            {:email dash-email}))
        dash-user-id (:id dash-user-server)
        _ (data-tools/give-perms!
           {:user (login/get-user-by-email
                   db-conn "foo@bar.com")
            :db-conn db-conn
            :perms "view-dash,view-couriers,view-users,view-zones,view-orders"})
        user-email "baz@qux.org"
        user-password "bazqux"
        user-full-name "Baz Qux"
        ;; create a user
        _ (data-tools/register-user! {:db-conn db-conn
                                      :platform-id user-email
                                      :password user-password
                                      :full-name user-full-name})
        server-user (-> (first (db/!select db-conn "users" ["*"]
                                           {:email user-email}))
                        (users/process-user (db/!select db-conn
                                                        "dashboard_users"
                                                        [:email :id] {})
                                            db-conn))]
    (testing "The user's gallons can be updated"
      (is (:success
           (users/update-user! db-conn (assoc server-user
                                              :referral_gallons 5
                                              :referral-comment
                                              "Credit for lack of service"
                                              :admin_id dash-user-id)))))
    (testing "Check that user's gallons has actually been updated"
      (is (= 5.0
             (:referral_gallons (first (db/!select db-conn "users" ["*"]
                                                   {:email user-email}))))))
    (testing "User can be converted to a courier"
      (is (:success
           (users/convert-to-courier! db-conn server-user))))))
