(ns dashboard.orders
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.edn :as edn]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [common.db :refer [conn !select !update mysql-escape-str]]
            [opt.planner :refer [compute-distance]]
            [common.config :as config]
            [common.couriers :as couriers]
            [common.users :as users]
            [common.util :refer [in? map->java-hash-map split-on-comma]]
            [common.zones :refer [get-zip-def order->zones]]
            [common.orders :as orders]
            [dashboard.db :as db]
            [dashboard.zones :refer [get-all-zones-from-db]]))

(def orders-select
  [:id :lat :lng :status :gallons :gas_type
   :total_price :timestamp_created :address_street
   :address_city :address_state :address_zip :user_id
   :courier_id :vehicle_id :license_plate
   :target_time_start :target_time_end :coupon_code :event_log
   :paid :stripe_charge_id :special_instructions
   :number_rating :text_rating :auto_assign_note
   :payment_info :notes :admin_event_log :subscription_id
   :tire_pressure_check])

(defn include-user-name-phone-and-courier
  "Given a vector of orders, assoc the user name, phone number and courier
  that is associated with the order"
  [db-conn orders]
  (let [users-by-id (into {}
                          (map (juxt :id identity)
                               (users/get-users-by-ids
                                db-conn
                                (distinct
                                 (concat (map :user_id orders)
                                         (map :courier_id orders))))))
        id->name #(:name (get users-by-id %))
        id->phone_number #(:phone_number (get users-by-id %))
        id->email #(:email (get users-by-id %))]
    (map #(assoc %
                 :courier_name (id->name (:courier_id %))
                 :customer_name (id->name (:user_id %))
                 :customer_phone_number (id->phone_number (:user_id %))
                 :email (id->email (:user_id %)))
         orders)))

(defn include-vehicle
  "Given a vector of orders, assoc the vehicle information that is associated
  with the order "
  [db-conn orders]
  (let [vehicles-by-id
        (->> (!select db-conn "vehicles"
                      [:id :year :make :model :color :gas_type
                       :license_plate]
                      {}
                      :custom-where
                      (let [vehicle-ids (distinct (map :vehicle_id orders))]
                        (str "id IN (\""
                             (s/join "\",\"" vehicle-ids)
                             "\")")))
             (group-by :id))
        id->vehicle #(first (get vehicles-by-id %))]
    (map #(assoc % :vehicle (id->vehicle (:vehicle_id %)))
         orders)))

(defn include-zone-info
  "Given a vector of orders, assoc the zone and zone_color associated with the
  order"
  [db-conn orders]
  (let [zones (map #(assoc %
                           :zips (map s/trim (s/split (:zips %) #",")))
                   (sort-by #(if (:active %) 1 0) ; active zones first
                            >
                            (get-all-zones-from-db db-conn)))
        get-zones-by-zip (fn [zip]
                           (filter #(in? (:zips %) zip) zones))
        get-market-by-zip (fn [zip]
                            (first (filter #(= 100 (:rank %))
                                           (get-zones-by-zip zip))))
        get-submarket-by-zip (fn [zip]
                               (first (filter #(= 1000 (:rank %))
                                              (get-zones-by-zip zip))))]
    (map #(assoc %
                 :market-color
                 (:color
                  (get-market-by-zip
                   (:address_zip %)))
                 :market
                 (:name
                  (get-market-by-zip
                   (:address_zip %)))
                 :submarket
                 (:name
                  (get-submarket-by-zip
                   (:address_zip %)))
                 :zones
                 (map :id
                      (get-zones-by-zip
                       (:address_zip %))))
         orders)))

(defn include-was-late
  "Given a vector of orders, assoc the boolean was_late associated with the
  order"
  [orders]
  (map #(assoc %
               :was-late
               (let [completion-time
                     (-> (str "kludgeFix 1|" (:event_log %))
                         (s/split #"\||\s")
                         (->> (apply hash-map))
                         (get "complete"))]
                 (and completion-time
                      (> (Integer. completion-time)
                         (:target_time_end %)))))
       orders))

;; currently only works for 1 order in orders
(defn include-eta
  "Given a vector of orders, assoc the etas for active couriers that are
  associated with the order"
  [db-conn orders]
  (let [all-couriers (!select db-conn "couriers" ["*"] {})
        users-by-id  (->> (!select db-conn "users"
                                   [:id :name :email :phone_number :os
                                    :app_version :stripe_default_card
                                    :sift_score
                                    :arn_endpoint :timestamp_created]
                                   {})
                          (group-by :id))
        id->name #(:name (first (get users-by-id %)))
        couriers-by-id (into {} (map (juxt (comp keyword :id) identity)
                                     all-couriers))
        order-zones (order->zones db-conn (first orders))
        dist-map (compute-distance
                  {"orders" (->> orders
                                 (filter #(in? ["unassigned"
                                                "assigned"
                                                "accepted"
                                                "enroute"]
                                               (:status %)))
                                 (map #(assoc %
                                              :status_times
                                              (-> (:event_log %)
                                                  (s/split #"\||\s")
                                                  (->> (remove s/blank?)
                                                       (apply hash-map)
                                                       (fmap read-string)))
                                              :zones order-zones))
                                 (map (juxt :id stringify-keys))
                                 (into {}))
                   "couriers" (->> (couriers/get-couriers db-conn
                                                          :where {:active true
                                                                  :on_duty true})
                                   (filter #(some (:zones %) order-zones))
                                   (map #(assoc % :zones (apply list (:zones %))))
                                   (map #(assoc % :assigned_orders [])) ; legacy
                                   (map (juxt :id stringify-keys))
                                   (into {}))})]
    (map #(assoc %  :etas (if-let [this-dist-map (get dist-map (:id %))]
                            (map (fn [x]
                                   {:name (id->name (key x))
                                    :busy (:busy
                                           ((keyword (key x))
                                            couriers-by-id))
                                    :minutes (quot (val x) 60)})
                                 (into {} (get this-dist-map "etas")))))

         orders)))

(defn admin-event-log-str->edn
  "Convert admin_event_log of orders from a string to edn"
  [orders]
  (map #(assoc %
               :admin_event_log (edn/read-string (:admin_event_log %)))
       orders))

(defn include-first-order
  "Add the boolean first_order? to all orders indicating whether or not this is
  the first order for the user"
  [db-conn orders]
  (let [users-string (str "(\""
                          (s/join "\",\"" (map :user_id orders))
                          "\")")
        sql-string (str "SELECT users.id,"
                        "if(0 = ifnull(o.count,0),1,0) as first_order "
                        "FROM users LEFT JOIN "
                        "(SELECT count(id) as count,user_id FROM orders "
                        "WHERE status = 'complete' "
                        "AND user_id in " users-string " GROUP BY user_id) o "
                        "ON o.user_id = users.id "
                        "WHERE users.id IN " users-string ";")
        first-time-ordered-results (db/raw-sql-query db-conn [sql-string])
        get-by-id (fn [id] (first (filter #(= id (:id %))
                                          first-time-ordered-results)))]
    (map #(assoc %
                 :first_order? (if (= (:first_order (get-by-id (:user_id %))) 1)
                                 true
                                 false))
         orders)))

(def order-validations
  {:notes [[v/string :message "Note must be a string!"]
           [v/string :message "Cancellation reason must be a string!"]]})

(defn add-event-to-admin-event-log
  "Add an event to admin_event_log of order and return a string of the new event
  log"
  [order event]
  (let [admin-event-log  (if-let [log (edn/read-string
                                       (:admin_event_log order))]
                           log
                           [])]
    (str (merge admin-event-log event))))

(defn add-cancel-reason
  "Given current-order from the database, new-cancel-reason and admin-id, add
  the new-cancel-reason to the admin_event_log of current-order."
  [current-order new-cancel-reason admin-id]
  (let [admin-event-log  (if-let [log (edn/read-string
                                       (:admin_event_log current-order))]
                           log
                           [])
        current-cancel-reason (->> admin-event-log
                                   (filter #(= (:action %) "cancel-order"))
                                   (sort-by :timestamp)
                                   reverse
                                   first
                                   :comment)]
    (cond (nil? new-cancel-reason)
          current-order
          (= new-cancel-reason current-cancel-reason)
          current-order
          (not= new-cancel-reason current-cancel-reason)
          (assoc current-order
                 :admin_event_log
                 (add-event-to-admin-event-log
                  current-order
                  {:timestamp (quot
                               (System/currentTimeMillis) 1000)
                   :admin_id admin-id
                   :action "cancel-order"
                   :comment new-cancel-reason})))))

(defn add-notes
  "Given current-order and new-notes, add them to the current-order"
  [current-order new-notes]
  (let [current-notes (:notes current-order)]
    (if-not (nil? new-notes)
      (assoc current-order
             :notes new-notes)
      current-order)))

(defn update-order!
  "Update fields of an order"
  [db-conn order]
  (if (b/valid? order order-validations)
    (let [{:keys [admin-id id cancel_reason notes]} order
          current-order (orders/get-by-id db-conn id)
          new-order (-> current-order
                        (add-cancel-reason cancel_reason admin-id)
                        (add-notes notes))
          {:keys [admin_event_log notes]} new-order
          update-order-result (!update db-conn "orders"
                                       ;; note: only fields that can be updated
                                       ;; by update-order! are included here.
                                       {:admin_event_log admin_event_log
                                        :notes notes}
                                       {:id (:id current-order)})]
      (if (:success update-order-result)
        (assoc update-order-result :id (:id current-order))
        update-order-result))
    {:success false
     :validation (b/validate order order-validations)}))

(defn process-orders
  "Process orders, with data from db-conn, to send to dashboard client "
  [orders db-conn]
  (->> orders
       (include-user-name-phone-and-courier db-conn)
       (include-vehicle db-conn)
       (include-zone-info db-conn)
       (include-was-late)
       (admin-event-log-str->edn)
       (include-first-order db-conn)))

(defn cancel-order
  "Cancel an order and create an entry in the admin_event_log"
  [db-conn user-id order-id admin-id cancel-reason]
  (let [cancel-response (orders/cancel
                         (conn)
                         user-id
                         order-id
                         :origin-was-dashboard true
                         :notify-customer true
                         :suppress-user-details true
                         :override-cancellable-statuses
                         (conj config/cancellable-statuses "servicing"))
        current-order  (orders/get-by-id db-conn order-id)
        event          {:timestamp (quot (System/currentTimeMillis) 1000)
                        :admin_id admin-id
                        :action "cancel-order"
                        :comment (or cancel-reason "None")}
        admin-event-log (add-event-to-admin-event-log current-order event)]
    (cond (:success cancel-response)
          (do
            (!update db-conn
                     "orders"
                     {:admin_event_log admin-event-log}
                     {:id order-id})
            {:success true
             :order (first (process-orders
                            [(orders/get-by-id db-conn order-id)]
                            db-conn))})
          :else cancel-response)))

(defn orders-since-date
  "Get all orders since date. A blank date will return all orders. When
  unix-epoch? is true, assume date is in unix epoch seconds"
  [db-conn date & [unix-epoch?]]
  (cond (not (nil? date))
        (let [orders (!select db-conn "orders"
                              orders-select
                              {}
                              :custom-where
                              (str "timestamp_created >= "
                                   (if unix-epoch?
                                     (str "FROM_UNIXTIME(" date ")")
                                     (str "'" date "'"))))]
          (into [] (process-orders orders db-conn)))
        (nil? date)
        []
        :else
        {:success false
         :message "Unknown error occured"}))

(defn search-orders
  [db-conn term]
  (let [escaped-term (mysql-escape-str term)
        orders (!select db-conn "orders"
                        orders-select
                        {}
                        :custom-where
                        (str "`id` LIKE '%" escaped-term "%' "
                             "OR `address_street` LIKE  '%" escaped-term "%' "
                             "OR `license_plate` LIKE '%" escaped-term "%' "
                             "OR `coupon_code` LIKE '%" escaped-term "%'")
                        :append "ORDER BY target_time_start LIMIT 100")]
    (process-orders orders db-conn)))

(defn orders-by-user-id
  "Retrieve all orders made by user-id"
  [db-conn user-id]
  (process-orders (!select db-conn "orders" orders-select
                           {:user_id user-id}
                           :append "ORDER BY target_time_start DESC")
                  db-conn))

(defn update-status-by-admin
  "Change the status of order-id to new-status"
  [db-conn order-id new-status]
  (if-let [order (orders/get-by-id db-conn order-id)]
    (let [current-status (:status order)
          advanced-status (orders/next-status current-status)]
      ;; Orders with "complete", "cancelled" or "unassigned" statuses can not be
      ;; advanced. These orders should not be modifiable in the dashboard
      ;; console, however this is checked on the server below.
      (cond
        (contains? #{"complete" "cancelled" "unassigned"} (:status order))
        {:success false
         :message (str "An order's status can not be advanced if it is already "
                       "complete, cancelled, or unassigned.")}
        ;; Likewise, the dashboard user should not be allowed to advanced
        ;; to "assigned", but we check it on the server anyway.
        (contains? #{"assigned"} advanced-status)
        {:success false
         :message (str "An order's status can not be advanced to assigned. "
                       "Please assign a courier to this order in "
                       "order to advance this order.")}
        ;; the next-status of an order should correspond to the status
        ;; update the status to "accepted", if not it is an error
        (not= advanced-status new-status)
        {:success false
         :message (str "This order's current status is '" current-status
                       "' and can not be advanced to '" new-status "'")}
        (= advanced-status "accepted")
        (do (orders/accept db-conn order-id)
            ;; let the courier know
            (users/send-push
             db-conn (:courier_id order)
             "An order that was assigned to you is now marked as Accepted.")
            {:success true
             :message advanced-status})
        ;; update the status to "enroute"
        (= advanced-status "enroute")
        (do (orders/begin-route db-conn order)
            ;; let the courier know
            (users/send-push db-conn (:courier_id order)
                             "Your order status has been advanced to enroute.")
            {:success true
             :message advanced-status})
        ;; update the status to "servicing"
        (= advanced-status "servicing")
        (do (orders/service db-conn order)
            ;; let the courier know
            (users/send-push
             db-conn (:courier_id order)
             "Your order status has been advanced to servicing.")
            {:success true
             :message advanced-status})
        ;; update the order to "complete"
        (= advanced-status "complete")
        (do (orders/complete db-conn order)
            ;; let the courier know
            (users/send-push db-conn (:courier_id order)
                             "Your order status has been advanced to complete.")
            {:success true
             :message advanced-status})
        ;; something wasn't caught
        :else {:success false
               :message "An unknown error occured."
               :status advanced-status}))
    ;; the order was not found on the server
    {:success false
     :message "An order with that ID could not be found."}))

(defn assign-to-courier-by-admin
  "Assign new-courier-id to order-id and alert the couriers of the
  order reassignment"
  [db-conn order-id new-courier-id admin-id]
  (let [order (orders/get-by-id db-conn order-id)
        old-courier-id (:courier_id order)
        admin-email (when admin-id
                      (some->> (!select db-conn
                                        "dashboard_users"
                                        [:email]
                                        {:id admin-id})
                               first
                               :email))
        change-order-assignment #(!update db-conn "orders"
                                          {:courier_id new-courier-id
                                           :auto_assign_note
                                           (cond
                                             (= (last (s/split admin-email #"@")) "purpleapp.com")
                                             (s/capitalize (first (s/split admin-email #"@")))
                                             :else admin-email)}
                                          {:id order-id})
        notify-new-courier #(do (users/send-push
                                 db-conn new-courier-id
                                 (str "You have been assigned a new order,"
                                      " please check your "
                                      "Orders to view it"))
                                (users/text-user
                                 db-conn new-courier-id
                                 (orders/new-order-text db-conn order true)))
        notify-old-courier #(users/send-push
                             db-conn old-courier-id
                             (str "You are no longer assigned to the order at: "
                                  (:address_street order)))]
    (cond
      (= (:status order) "unassigned")
      (do
        ;; because the accept fn sets the couriers busy status to true,
        ;; there is no need to further update the courier's status
        (orders/assign db-conn order-id new-courier-id)
        ;; response
        {:success true
         :message (str order-id " has been assigned to " new-courier-id)})
      (contains? #{"assigned" "accepted" "enroute" "servicing"} (:status order))
      (do
        ;; update the order so that is assigned to new-courier-id
        (change-order-assignment)
        ;; set the new-courier to busy
        (couriers/set-courier-busy db-conn new-courier-id true)
        ;; adjust old courier to correct busy setting
        (couriers/update-courier-busy db-conn old-courier-id)
        ;; notify the new-courier that they have a new order
        (notify-new-courier)
        ;; notify the old-courier that they lost an order
        (notify-old-courier)
        ;; response
        {:success true
         :message (str order-id " has been assigned from " new-courier-id " to "
                       old-courier-id)})
      (contains? #{"complete" "cancelled"} (:status order))
      (do
        ;; update the order so that is assigned to new-courier
        (change-order-assignment)
        ;; response
        {:success true
         :message (str order-id " has been assigned from " new-courier-id " to "
                       old-courier-id)})
      :else
      {:success false
       :message "An unknown error occured."})))
