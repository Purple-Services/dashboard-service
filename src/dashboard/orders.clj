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
            [common.users :as users]
            [common.util :refer [in? map->java-hash-map split-on-comma]]
            [common.zones :refer [get-all-zones-from-db
                                  get-zones]]
            [common.orders :refer [get-by-id cancel]]))

(def orders-select
  [:id :lat :lng :status :gallons :gas_type
   :total_price :timestamp_created :address_street
   :address_city :address_state :address_zip :user_id
   :courier_id :vehicle_id :license_plate
   :target_time_start :target_time_end :coupon_code :event_log
   :paid :stripe_charge_id :special_instructions
   :number_rating :text_rating
   :payment_info :notes :admin_event_log :subscription_id])

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
  (let [zones (get-zones db-conn)
        get-zone-by-zip-code (fn [zip-code]
                               (first (filter #(in? (:zip_codes %) zip-code)
                                              zones)))]
    (map #(assoc %
                 :zone-color
                 (:color
                  (get-zone-by-zip-code
                   (:address_zip %)))
                 :zone
                 (:id
                  (get-zone-by-zip-code
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

(defn include-eta
  "Given a vector of orders, assoc the etas for active couriers that are
  associated with the order"
  [db-conn orders]
  (let [all-couriers (->> (!select db-conn "couriers" ["*"] {})
                          ;; remove chriscourier@test.com
                          (remove #(in? ["9eadx6i2wCCjUI1leBBr"] (:id %))))
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
                                                       (fmap read-string)))))
                                 (map (juxt :id stringify-keys))
                                 (into {}))
                   "couriers" (->> (!select db-conn
                                            "couriers"
                                            [:id :lat :lng :last_ping
                                             :connected :zones]
                                            {:active true
                                             :on_duty true})
                                   (map #(update-in % [:zones]
                                                    split-on-comma))
                                   (map #(assoc % :assigned_orders []))
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
          current-order (get-by-id db-conn id)
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
       (admin-event-log-str->edn)))

(defn cancel-order
  "Cancel an order and create an entry in the admin_event_log"
  [db-conn user-id order-id admin-id cancel-reason]
  (let [cancel-response (cancel
                         (conn)
                         user-id
                         order-id
                         :origin-was-dashboard true
                         :notify-customer true
                         :suppress-user-details true
                         :override-cancellable-statuses
                         (conj config/cancellable-statuses "servicing"))
        current-order  (get-by-id db-conn order-id)
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
                            [(get-by-id db-conn order-id)]
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
