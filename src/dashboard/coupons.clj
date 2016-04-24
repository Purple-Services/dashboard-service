(ns dashboard.coupons
  (:require [bouncer.core :as b]
            [bouncer.validators :as v]
            [clojure.string :as s]
            [common.coupons :refer [get-coupon-by-code format-coupon-code]]
            [common.db :refer [conn !insert !select !update]]
            [common.util :refer [rand-str-alpha-num split-on-comma]]))

(defn get-coupons
  "Retrieve all coupons"
  [db-conn]
  (!select db-conn "coupons" ["*"] {:type "standard"}))

(defn get-coupon
  "Retrieve coupon by id"
  [db-conn id]
  (first (!select db-conn "coupons" ["*"] {:id id})))

(defn in-future?
  "Is seconds a unix epoch value that is in the future?"
  [seconds]
  (> seconds (quot (.getTime (java.util.Date.)) 1000)))

(defn code-available?
  "Is the code for coupon currently available?"
  [code]
  (not (boolean (get-coupon-by-code (conn) code))))

(def new-coupon-validations
  {:code [[code-available? :message "Code is already in use"]
          [v/required :message "Code must not be blank"]]
   :value [[v/required :message "Amount must be present!"]
           [v/number :message "Amount must in a dollar amount!"]
           [v/in-range [1 5000]
            :message "Amount must be within $0.01 and $50.00"]]
   :expiration_time [v/required
                     [v/integer :message
                      "Expiration time is not in an integer."]
                     [in-future?
                      :message "Expiration date must be in the future!"]]
   :only_for_first_orders [v/required
                           v/boolean]})

(defn create-standard-coupon!
  "Given a new-coupon map, validate it. If valid, create coupon else return the
  bouncer error map."
  [db-conn new-coupon]
  (if (b/valid? new-coupon new-coupon-validations)
    (let [{:keys [code value expiration_time only_for_first_orders]} new-coupon
          id (rand-str-alpha-num 20)
          insert-result (!insert db-conn "coupons"
                                 {:id id
                                  :code (format-coupon-code code)
                                  :value (* value -1)
                                  :expiration_time expiration_time
                                  :only_for_first_orders
                                  (if only_for_first_orders 1 0)
                                  :type "standard"
                                  :used_by_license_plates ""
                                  :used_by_user_ids ""})]
      (if (:success insert-result)
        (assoc insert-result :id id)
        insert-result))
    {:success false
     :validation (b/validate new-coupon new-coupon-validations)}))

(def coupon-validations
  (assoc new-coupon-validations
         :code [[#(not (code-available? %)) :message "Code does not exist!"]
                [v/required :message "Code must not be blank"]]))

(defn update-standard-coupon!
  "Given a coupon map, validate it. If valid, create coupon else return the
  bouncer error map"
  [db-conn coupon]
  (if (b/valid? coupon coupon-validations)
    (let [{:keys [code value expiration_time only_for_first_orders]} coupon
          db-coupon (get-coupon-by-code db-conn code)
          update-result (!update db-conn "coupons"
                                 (assoc db-coupon
                                        :value (* value -1)
                                        :expiration_time expiration_time
                                        :only_for_first_orders
                                        (if only_for_first_orders 1 0))
                                 {:id (:id db-coupon)})]
      (if (:success update-result)
        (assoc update-result :id (:id db-coupon))
        update-result))
    {:success false
     :validation (b/validate coupon coupon-validations)}))


