(ns dashboard.handler
  (:require [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [clout.core :as clout]
            [common.config :as config]
            [common.coupons :as coupons]
            [common.couriers :as couriers]
            [common.db :refer [!select conn]]
            [common.orders :as orders]
            [common.users :as users]
            [common.util :refer [convert-timestamp convert-timestamps]]
            [common.zones :as zones]
            [compojure.core :refer :all]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [dashboard.analytics :as analytics]
            [dashboard.coupons :refer [create-standard-coupon!
                                       get-coupons
                                       get-coupon
                                       update-standard-coupon!]]
            [dashboard.couriers :refer [get-by-id include-lateness
                                        update-courier!
                                        include-os-and-app-version
                                        include-arn-endpoint]]
            [dashboard.login :as login]
            [dashboard.orders :refer [include-eta
                                      include-user-name-phone-and-courier
                                      include-vehicle
                                      include-was-late
                                      include-zone-info
                                      admin-event-log-str->edn
                                      orders-since-date
                                      search-orders
                                      update-order!
                                      cancel-order
                                      orders-by-user-id
                                      update-status-by-admin
                                      assign-to-courier-by-admin]]
            [dashboard.pages :as pages]
            [dashboard.users :refer [dash-users
                                     process-user
                                     search-users
                                     send-push-to-all-active-users
                                     send-push-to-users-list
                                     send-push-to-table-view
                                     send-push-to-user
                                     update-user!
                                     convert-to-courier!]]
            [dashboard.zones :refer [get-zone-by-id
                                     update-zone!
                                     create-zone!
                                     get-all-zones-from-db]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.util.response :refer [header set-cookie response redirect]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.ssl :refer [wrap-ssl-redirect]]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            ))

(defn wrap-page [resp]
  (header resp "content-type" "text/html; charset=utf-8"))

(defn wrap-force-ssl [resp]
  (if config/has-ssl?
    (wrap-ssl-redirect resp)
    resp))

(defn valid-session-wrapper?
  "given a request, determine if the user-id has a valid session"
  [request]
  (let [cookies (keywordize-keys
                 ;; the reason resolve is used here is because
                 ;; parse-cookies is not a public fn, this hack
                 ;; gets around that
                 ((resolve 'ring.middleware.cookies/parse-cookies) request))
        user-id (get-in cookies [:user-id :value])
        token   (get-in cookies [:token :value])]
    (users/valid-session? (conn) user-id token)))

(def login-rules
  [{:pattern #"/ok" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/css/.*" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/js/.*" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/fonts/.*" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/login" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/logout" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*(/.*|$)"
    :handler valid-session-wrapper?
    :redirect "/login"}])

;; if the route is omitted, it is accessible by all dashboard users
;; url and method must be given, if one is missing, the route will be allowed!
;; login/logout should be unprotected!
;;
;; these routes are organized to match dashboard-routes
;; and follow the same conventions.
;;
;; a user who can access everything would have the following permissions:
;; #{"view-dash","view-couriers","edit-couriers","view-users","edit-users,
;;   "send-push","view-coupons","edit-coupons","create-coupons","view-zones",
;;   "edit-zones", "view-orders","edit-orders","download-stats",
;;   "convert-to-courier"}
;;
(def dashboard-uri-permissions
  [
   ;;!! main dash
   {:uri "/"
    :method "GET"
    :permissions ["view-dash"]}
   {:uri ""
    :method "GET"
    :permissions ["view-dash"]}
   {:uri "/dash-app"
    :method "GET"
    :permissions ["view-dash"]}
   {:uri "/permissions"
    :method "GET"
    :permissions ["view-dash"]}
   ;;!! dash maps
   {:uri "/dash-map-orders"
    :method "GET"
    :permissions ["view-orders" "view-zones"]}
   {:uri "/dash-map-couriers"
    :method "GET"
    :permissions ["view-orders" "view-couriers" "view-zones"]}
   ;;!! couriers
   {:uri "/courier/:id"
    :method "GET"
    :permissions ["view-couriers"]}
   {:uri "/courier"
    :method "PUT"
    :permissions ["edit-couriers"]}
   {:uri "/couriers"
    :method "POST"
    :permissions ["view-couriers"]}
   ;;!! users
   {:uri "/user/:id"
    :method "GET"
    :permissions ["view-users"]}
   {:uri "/user"
    :method "PUT"
    :permissions ["view-users" "edit-users"]}
   {:uri "/users"
    :method "GET"
    :permissions ["view-users" "view-orders"]}
   {:uri "/users-count"
    :method "GET"
    :permissions ["view-users" "view-orders"]}
   {:uri "/members-count"
    :method "GET"
    :permissions ["view-users" "view-orders"]}
   {:uri "/send-push-to-all-active-users"
    :method "POST"
    :permissions ["view-users" "send-push"]}
   {:uri "/send-push-to-users-list"
    :method "POST"
    :permissions ["view-users" "send-push"]}
   {:uri "/send-push-to-user"
    :method "POST"
    :permissions ["view-users" "send-push"]}
   {:uri "/users/convert-to-courier"
    :method "PUT"
    :permissions ["convert-to-courier"]}
   ;;!! coupons
   {:uri "/coupon/:code"
    :method "GET"
    :permissions ["view-coupons"]}
   {:uri "/coupon"
    :method "PUT"
    :permissions ["edit-coupons"]}
   {:uri "/coupon"
    :method "POST"
    :permissions ["create-coupons"]}
   {:uri "/coupons"
    :method "GET"
    :permissions ["view-coupons"]}
   ;;!! zones
   {:uri "/zone/:id"
    :method "GET"
    :permissions ["view-zones"]}
   {:uri "/zones"
    :method "GET"
    :permissions ["view-zones"]}
   {:uri "/zctas"
    :method "POST"
    :permissions ["view-zones"]}
   {:uri "/zone"
    :method "PUT"
    :permissions ["edit-zones"]}
   {:uri "/zone"
    :method "POST"
    :permissions ["create-zones"]}
   ;;!! orders
   {:uri "/order/:id"
    :method "GET"
    :permissions ["view-orders"]}
   {:uri "/order"
    :method "PUT"
    :permissions ["edit-orders"]}
   {:uri "/cancel-order"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/update-status"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/assign-order"
    :method "POST"
    :permissions ["edit-orders"]}
   {:uri "/orders-since-date"
    :method "POST"
    :permissions ["view-orders"]}
   ;;!! analytics
   {:uri "/total-orders-customer"
    :method "GET"
    :permissions ["download-stats"]}
   {:uri "/status-file/:filename"
    :method "GET"
    :permissions ["download-stats"]}
   {:uri "/generate-file/:filename"
    :method "POST"
    :permissions ["download-stats"]}
   {:uri "/download-file/:filename"
    :method "GET"
    :permissions ["download-stats"]}
   ;;!! Marketing
   {:uri "/send-push-to-table-view"
    :method "POST"
    :permissions ["download-stats" "send-push" "marketing"]}])

(defn allowed?
  "given a vector of uri-permission maps and a request map, determine if the
  user has permission to access the response of a request"
  [uri-permissions request]
  (let [cookies   (keywordize-keys
                   ;; parse-cookies is not a public fn, so it is resolved
                   ((resolve 'ring.middleware.cookies/parse-cookies) request))
        user-id   (get-in cookies [:user-id :value])
        user-permission  (login/get-permissions (conn) user-id)
        uri       (:uri request)
        method    (-> request
                      :request-method
                      name
                      s/upper-case)
        uri-permission  (:permissions (first (filter #(and
                                                       (clout/route-matches
                                                        (:uri %) {:uri uri})
                                                       (= method (:method %)))
                                                     uri-permissions)))
        user-uri-permission-compare (map #(contains? user-permission %)
                                         uri-permission )
        user-has-permission? (boolean (and (every? identity
                                                   user-uri-permission-compare)
                                           (seq user-uri-permission-compare)))]
    (cond (empty? uri-permission) ; no permission associated with uri, allow
          true
          :else user-has-permission?)))

(defn on-error
  [request value]
  {:status 403
   :header {}
   :body (str "you do not have permission to access " (:uri request))})

(def access-rules
  [{:pattern #".*(/.*|$)"
    :handler (partial allowed? dashboard-uri-permissions)
    :on-error on-error}])

;; the following convention is used for ordering dashboard
;; routes
;; 1. routes are organized under groups (e.g. ;;!! group)
;;    and mirror the order of the dashboard navigation tabs.
;; 2. requests that result in a single object come before
;;    those that potentially result in multiple objects.
;; 3. if there are multiple methods for the same route,
;;    list them in the order get, put, post
;; 4. routes that don't fit a particular pattern are listed
;;    last
;; 5. try to be logical and use your best judgement when these
;;    rules fail
(defroutes dashboard-routes
  ;;!! main dash
  (GET "/" []
       (-> (pages/dash-app)
           response
           wrap-page))
  (GET "/permissions" {cookies :cookies}
       (let [user-perms (login/get-permissions
                         (conn)
                         (get-in cookies ["user-id" :value]))
             accessible-routes (login/accessible-routes
                                dashboard-uri-permissions
                                user-perms)]
         (response (into [] accessible-routes))))
  ;;!! login / logout
  (GET "/login" []
       (-> (pages/dash-login)
           response
           wrap-page))
  (POST "/login" {body :body
                  headers :headers
                  remote-addr :remote-addr}
        (response
         (let [b (keywordize-keys body)]
           (login/login (conn) (:email b) (:password b)
                        (or (get headers "x-forwarded-for")
                            remote-addr)))))
  (GET "/logout" []
       (-> (redirect "/login")
           (set-cookie "token" "null" {:max-age -1})
           (set-cookie "user-id" "null" {:max-age -1})))
  ;;!! dash maps
  (GET "/dash-map-orders" []
       (-> (pages/dash-map :callback-s
                           "dashboard_cljs.core.init_map_orders")
           response
           wrap-page))
  (GET "/dash-map-couriers" []
       (-> (pages/dash-map :callback-s
                           "dashboard_cljs.core.init_map_couriers")
           response
           wrap-page))
  ;;!! couriers
  ;; return all couriers
  ;; get a courier by id
  (GET "/courier/:id" [id]
       (response
        (into []
              (->> (get-by-id (conn) id)
                   list
                   (users/include-user-data (conn))
                   (include-lateness (conn))
                   (include-os-and-app-version (conn))
                   (include-arn-endpoint (conn))))))
  ;; update a courier
  ;; currently, only the zones can be updated
  (PUT "/courier" {body :body}
       (let [b (keywordize-keys body)]
         (response (update-courier!
                    (conn)
                    b))))
  (POST "/couriers" {body :body}
        (response
         (let [b (keywordize-keys body)]
           {:couriers (->> (couriers/all-couriers (conn))
                           convert-timestamps
                           (users/include-user-data (conn))
                           (include-lateness (conn))
                           (include-os-and-app-version (conn))
                           (include-arn-endpoint (conn)))})))
  ;;!! users
  ;; get a user by id
  (GET "/user/:id" [id]
       (response
        (let [db-conn (conn)]
          (into []
                (->
                 (users/get-user-by-id (conn) id)
                 (process-user (!select
                                db-conn "dashboard_users" [:email :id] {})
                               db-conn)
                 list)))))
  ;; edit an existing user
  (PUT "/user" [:as {body :body
                     cookies :cookies}]
       (let [b (keywordize-keys body)
             admin-id (-> (keywordize-keys cookies)
                          :user-id
                          :value)]
         (response
          (update-user! (conn)
                        (assoc b
                               :admin_id admin-id)))))
  (GET "/users" []
       (response
        (into []
              (dash-users (conn)))))
  (GET "/users-count" []
       (response
        (into []
              (!select (conn) "users" ["count(*) as total"] {}))))
  (GET "/members-count" []
       (response
        (into []
              (!select (conn) "users" ["count(*) as total"] {}
                       :custom-where
                       (str "`subscription_id` > 0 AND `subscription_id` < 3")))))
  (POST "/send-push-to-all-active-users" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (send-push-to-all-active-users (conn)
                                          (:message b)))))
  (POST "/send-push-to-users-list" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (send-push-to-users-list (conn)
                                    (:message b)
                                    (:user-ids b)))))
  (POST "/send-push-to-user" {body :body}
        (response
         (let [b (keywordize-keys body)
               {:keys [message user-id]} b]
           (send-push-to-user (conn)
                              message
                              user-id))))
  (GET "/users/search/:term" [term]
       (response
        (into []
              (search-users (conn) term))))
  (PUT "/users/convert-to-courier" {body :body}
       (response
        (convert-to-courier! (conn) (:user (keywordize-keys body)))))
  ;; get all orders for a user
  (GET "/users/orders/:id" [id]
       (response
        (let [db-conn (conn)]
          (into []
                (orders-by-user-id db-conn id)))))
  ;;!! coupons
  ;; get a coupon by id
  (GET "/coupon/:id" [id]
       (response
        (into []
              (->> (get-coupon (conn) id)
                   convert-timestamp
                   list))))
  ;; edit an existing coupon
  (PUT "/coupon" {body :body}
       (let [b (keywordize-keys body)]
         (response
          (update-standard-coupon! (conn) b))))
  ;; create a new coupon
  (POST "/coupon" {body :body}
        (let [b (keywordize-keys body)]
          (response
           (create-standard-coupon! (conn) b))))
  ;; get the current coupon codes
  (GET "/coupons" []
       (response
        (into []
              (-> (get-coupons (conn))
                  (convert-timestamps)))))
  ;;!! zones
  ;; get a zone by its id
  (GET "/zone/:id" [id]
       (response
        (into []
              (->> (get-zone-by-id (conn) id)
                   list))))
  ;; update a zone's description. currently only supports
  ;; updating fuel_prices, service_fees and service_time_bracket
  (PUT "/zone" [:as {body :body
                     cookies :cookies}]
       (let [b (keywordize-keys body)
             admin-id (-> (keywordize-keys cookies)
                          :user-id
                          :value)]
         (response
          (update-zone! (conn) b admin-id))))
  (POST "/zone" [:as {body :body
                     cookies :cookies}]
        (let [b (keywordize-keys body)
              admin-id (-> (keywordize-keys cookies)
                          :user-id
                          :value)]
          (response
           (create-zone! (conn) b admin-id))))
  ;; return all zones - not used this way anymore
  (GET "/zones" []
       (response
        ;; needed because cljs.reader/read-string can't handle
        ;; keywords that begin with numbers
        (into [] (get-all-zones-from-db (conn)))))
  ;; return all zips we will use
  (GET "/zips/:zip" [zip]
       (let [zip-result (zones/get-zip-def-not-validated (conn) (str zip))]
         (response
          (if (nil? zip-result)
            {}
            zip-result))))
  ;; return zcta defintions for zips
  (POST "/zctas" {body :body}
        (response
         (let [b (keywordize-keys body)]
           {:zctas
            (zones/get-zctas-for-zips (conn) (:zips b))})))
  ;;!! orders
  ;; given an order id, get the detailed information for that
  ;; order
  (GET "/order/:id"  [id]
       (response
        (let [order (orders/get-by-id (conn) id)]
          (if ((comp not empty?) order)
            (into []
                  (->>
                   [order]
                   (include-user-name-phone-and-courier
                    (conn))
                   (include-vehicle (conn))
                   (include-zone-info (conn))
                   (include-eta (conn))
                   (include-was-late)
                   (admin-event-log-str->edn)))))))
  ;; edit an order
  (PUT "/order" [:as {body :body
                      cookies :cookies}]
       (let [order (keywordize-keys body)
             admin-id (-> (keywordize-keys cookies)
                          :user-id
                          :value)]
         (response
          (update-order! (conn) (assoc order
                                       :admin-id admin-id)))))
  ;; cancel the order
  (POST "/cancel-order" [:as {body :body
                              cookies :cookies}]
        (response
         (let [b (keywordize-keys body)
               admin-id (-> (keywordize-keys cookies)
                            :user-id
                            :value)]
           (cancel-order (conn)
                         (:user_id b)
                         (:order_id b)
                         admin-id
                         (:cancel-reason b)))))
  ;; admin updates status of order (e.g., enroute -> servicing)
  (POST "/update-status" {body :body}
        (response
         (let [b (keywordize-keys body)
               {:keys [order_id new-status]} b]
           (update-status-by-admin (conn) order_id new-status))))
  ;; admin assigns courier to an order
  (POST "/assign-order" {body :body}
        (response
         (let [b (keywordize-keys body)]
           (assign-to-courier-by-admin (conn)
                                       (:order_id b)
                                       (:courier_id b)))))
  ;; given a date in the format yyyy-mm-dd, return all orders
  ;; that have occurred since then
  (POST "/orders-since-date"  {body :body}
        (response
         (let [b (keywordize-keys body)]
           (orders-since-date (conn)
                              (:date b)
                              (:unix-epoch? b)))))
  ;;!! analytics
  (POST "/total-orders-customer" {body :body}
        (response (let [b (keywordize-keys body)
                        {:keys [timezone timeframe response-type from-date
                                to-date]} b]
                    (analytics/total-for-select-response
                     (conn)
                     (analytics/totals-query
                      {:select-statement
                       "COUNT(DISTINCT id) as `orders`"
                       :from-date from-date
                       :to-date to-date
                       :timezone timezone
                       :timeformat (analytics/timeframe->timeformat
                                    timeframe)})
                     response-type))))
  (GET "/status-file/:filename" [filename]
       (response (let [status-file (java.io.File. filename)]
                   (cond
                     ;; stats file doesn't exist
                     (not (.exists status-file))
                     {:status "non-existent"}
                     ;; stats file exists, but processing
                     (= (.length status-file) 0)
                     {:status "processing"}
                     ;; stats file exists, not processing
                     (> (.length status-file) 0)
                     {:status "ready"
                      :timestamp (quot (.lastModified status-file)
                                       1000)}
                     ;; unknown error
                     :else {:status "unknown error"}))))
  (POST "/generate-file/:filename" {{filename :filename} :params
                                    body :body}
        (response (let [b (keywordize-keys body)
                        status-file (java.io.File. filename)
                        {:keys [parameters]} b
                        {:keys [from-date to-date
                                timezone timeframe]} parameters
                        generator-parameters {:from-date from-date
                                              :to-date to-date
                                              :timezone timezone
                                              :timeframe timeframe
                                              :db-conn (conn)}]
                    (cond
                      ;; the stats file is being generated
                      (and (= (.length status-file) 0)
                           (.exists status-file))
                      (do
                        {:success false
                         :message "processing"})
                      ;; stats file doesn't exist or has
                      ;; non-zero value, assume file should
                      ;; be generated
                      (or (not (.exists status-file))
                          (> (.length status-file) 0))
                      (do
                        ;; have to touch filename so that
                        ;; we can tell when the file is processing
                        (spit filename "")
                        ;; a bit hacky to use condp here, better if
                        ;; this was dispatched on a map
                        (condp = filename
                          "totals.xlsx"
                          (do
                            (future
                              (analytics/generate-totals-xlsx
                               generator-parameters))
                            {:success true})
                          "couriers-totals.xlsx"
                          (do (future
                                (analytics/generate-couriers-totals-xlsx
                                 generator-parameters))
                              {:success true})
                          "stats.xlsx"
                          (do (future (analytics/generate-stats-xlsx))
                              {:success true})
                          "managed-accounts.xlsx"
                          (do (future (analytics/generate-managed-accounts-xlsx
                                       generator-parameters))
                              {:success true})
                          "fleet-accounts.xlsx"
                          (do (future (analytics/generate-fleet-accounts-xlsx
                                       generator-parameters))
                              {:success true})
                          ;; unknown error
                          :else {:success false
                                 :message "unknown error"}))
                      :else {:success false
                             :message "unknown error"}))))
  (GET "/download-file/:filename" [filename]
       (-> (response (java.io.File. filename))
           (header "Content-Type"
                   (str "application/vnd.openxmlformats-officedocument."
                        "spreadsheetml.sheet"
                        " name=\""
                        filename "\""))
           (header "Content-Disposition"
                   (str "attachment; filename=\""
                        filename "\""))))
  ;; Monthly subscriptions
  ;;!! Marketing
  (POST "/send-push-to-table-view" {body :body}
        (response (let [b (keywordize-keys body)]
                    (send-push-to-table-view (conn)
                                             (:message b)
                                             (:table-view b)))))
  ;;!! search
  (POST "/search" {body :body}
        (response
         (let [b (keywordize-keys body)
               term (:term b)]
           {:users (into [] (search-users (conn) term))
            :orders (into [] (search-orders (conn) term))})))
  (route/resources "/"))

(defroutes all-routes
  (wrap-force-ssl dashboard-routes)
  (GET "/ok" [] (response {:success true})))

(def handler
  (->
   all-routes
   (wrap-cors :access-control-allow-origin [#".*"]
              :access-control-allow-methods [:get :put :post :delete])
   (wrap-access-rules {:rules access-rules})
   (wrap-access-rules {:rules login-rules})
   (wrap-cookies)
   (wrap-json-body)
   (wrap-json-response)))
