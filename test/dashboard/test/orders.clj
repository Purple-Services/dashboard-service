(ns dashboard.test.orders
  (:require [clojure.test :refer [is deftest testing use-fixtures]]
            [clj-time.coerce :as c]
            [clj-time.local :as l]
            [common.db :as db]
            [dashboard.orders :as orders]
            [dashboard.test.data-tools :as data-tools]
            [dashboard.test.db-tools :as db-tools]
            [dashboard.users :as users]))

(use-fixtures :once db-tools/setup-ebdb-test-for-conn-fixture)
(use-fixtures :each db-tools/clear-and-populate-test-database-fixture)

(def reset-db! db-tools/reset-db!)
(def setup-ebdb-test-pool! db-tools/setup-ebdb-test-pool!)

(defn get-order-by-email
  [email orders]
  (first (filter #(= (:email %) email) orders)))

(deftest first-time-ordered
  (let [db-conn (db/conn)
        now (quot (c/to-long (l/local-now))
                  1000)
        ;; create the admin
        admin-email "admin@purpleapp.com"
        admin-password "foobar"
        admin-full-name "Purple Admin"
        _ (data-tools/create-minimal-dash-user!
           {:email admin-email
            :password admin-password
            :full-name admin-full-name
            :db-conn db-conn})
        admin (first (db/!select db-conn "dashboard_users" ["*"]
                                 {:email admin-email}))
        ;; update the admins perms
        _ (data-tools/give-perms!
           {:user admin
            :perms (str "view-dash,view-couriers,view-users,"
                        "view-zones,view-orders,edit-orders")
            :db-conn db-conn})
        ;; create the courier
        courier-email "courier@purpleapp.com"
        courier-password "courier"
        courier-full-name "Purple Courier"
        _ (data-tools/register-courier! {:db-conn db-conn
                                         :platform-id courier-email
                                         :password courier-password
                                         :full-name courier-full-name})
        courier (first (users/search-users db-conn courier-email))
        ;; create the nonmember
        nonmember-email "regular@foo.com"
        nonmember-password "regularfoo"
        nonmember-full-name "Regular User"
        _  (data-tools/create-app-user-vehicle-order!
            {:email nonmember-email
             :password nonmember-password
             :full-name nonmember-full-name
             :db-conn db-conn})
        nonmember (first (users/search-users db-conn nonmember-email))
        ;; create the member
        member-email "member@foo.com"
        member-password "memberfoo"
        member-full-name "Member User"
        _     (data-tools/create-app-user-vehicle-order!
               {:email member-email
                :password member-password
                :full-name member-full-name
                :db-conn db-conn})
        member (first (users/search-users db-conn member-email))
        current-orders (orders/orders-since-date db-conn
                                                 now
                                                 true)
        nonmember-order (get-order-by-email nonmember-email current-orders)
        member-order (get-order-by-email member-email current-orders)]
    (testing "First time orders are indicated"
      ;; are both orders indicated as being first time?
      (is (:first_order? nonmember-order))
      (is (:first_order? member-order)))
    (testing "Cancel the orders, reorder and check if first_order"
      ;; cancel both of those orders
      (orders/cancel-order db-conn
                           (:id member)
                           (:id member-order)
                           (:id admin)
                           "Test")
      (orders/cancel-order db-conn
                           (:id nonmember)
                           (:id nonmember-order)
                           (:id admin)
                           "Test")
      ;; create two new orders
      (data-tools/create-order! db-conn
                                (data-tools/order-map {:user_id
                                                       (:id member)}))
      (data-tools/create-order! db-conn
                                (data-tools/order-map {:user_id
                                                       (:id nonmember)}))
      (let [current-orders (orders/orders-since-date db-conn
                                                     now
                                                     true)]
        ;; should still be first time orders
        (is (every? true? (map :first_order? current-orders)))
        (let [active-member-order (->> current-orders
                                       (filter #(= (:email %) member-email))
                                       (filter #(contains? #{"unassigned"}
                                                           (:status %)))
                                       first)
              active-nonmember-order (->> current-orders
                                          (filter #(= (:email %)
                                                      nonmember-email))
                                          (filter #(contains? #{"unassigned"}
                                                              (:status %)))
                                          first)]
          ;; assign orders to a courier and mark them as complete
          (orders/assign-to-courier-by-admin
           db-conn (:id active-member-order) (:id courier))
          (mapv #(orders/update-status-by-admin
                  db-conn
                  (:id active-member-order) %)
                ["accepted" "enroute" "servicing" "complete"])
          (orders/assign-to-courier-by-admin
           db-conn (:id active-nonmember-order) (:id courier))
          (mapv #(orders/update-status-by-admin
                  db-conn
                  (:id active-nonmember-order) %)
                ["accepted" "enroute" "servicing" "complete"])
          (is (= false (:first_order? (first
                                       (orders/search-orders
                                        db-conn
                                        (:id active-nonmember-order))))))
          (is (= false (:first_order? (first
                                       (orders/search-orders
                                        db-conn
                                        (:id active-member-order)))))))))))
