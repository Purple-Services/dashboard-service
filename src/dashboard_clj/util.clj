(ns dashboard-clj.util
  (:import [com.amazonaws.services.sns.model PublishRequest])
  (:require [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [clj-aws.core :as aws]
            [clj-aws.sns :as sns]
            [postal.core :as postal]
            [dashboard-clj.config :as config]
            ))

(defmacro !
  "Keeps code from running during compilation."
  [& body]
  `(when-not *compile-files*
     ~@body))

(defmacro only-prod
  "Only run this code when in production mode."
  [& body]
  `(when (= config/db-user "purplemasterprod")
     ~@body))

(defn in? 
  "true if seq contains elm"
  [seq elm]  
  (some #(= elm %) seq))

(defmacro unless-p
  "Use x unless the predicate is true for x, then use y instead."
  [pred x y]
  `(if-not (~pred ~x)
     ~x
     ~y))

(defn five-digit-zip-code
  [zip-code]
  (subs zip-code 0 5))

(defn map->java-hash-map
  "Recursively convert Clojure PersistentArrayMap to Java HashMap."
  [m]
  (postwalk #(unless-p map? % (java.util.HashMap. %)) m))

(defn rand-str
  [ascii-codes length]
  (apply str (repeatedly length #(char (rand-nth ascii-codes)))))

(defn rand-str-alpha-num
  [length]
  (rand-str (concat (range 48 58)  ;; 0-9
                    (range 65 91)  ;; A-Z
                    (range 97 123) ;; a-z
                    )
            length))

(defn split-on-comma [x] (s/split x #","))

(defn new-auth-token []
  (rand-str-alpha-num 128))



;; Amazon SNS (Push Notifications)
(! (do
     (def aws-creds (aws/credentials (System/getProperty "AWS_ACCESS_KEY_ID")
                                     (System/getProperty "AWS_SECRET_KEY")))
     (def sns-client (sns/client aws-creds))
     (.setEndpoint sns-client "https://sns.us-west-2.amazonaws.com")))


(defn send-email [message-map]
  (try (postal/send-message config/email
                            (assoc message-map
                                   :from (str "Purple Services Inc <"
                                              config/email-from-address
                                              ">")))
       {:success true}
       (catch Exception e
         {:success false
          :message "Message could not be sent to that address."})))

(defn sns-publish
  [client target-arn message]
  (try
    (let [req (PublishRequest.)
          is-gcm? (.contains target-arn "GCM/Purple")]
      (.setMessage req (if is-gcm?
                         (str "{\"GCM\": \"{ "
                              "\\\"data\\\": { \\\"message\\\": \\\""
                              message
                              "\\\" } }\"}")
                         message))
      (when is-gcm? (.setMessageStructure req "json"))
      (.setTargetArn req target-arn)
      (.publish client req))
    (catch Exception e
      (only-prod (send-email {:to "chris@purpledelivery.com"
                              :subject "Purple - Error"
                              :body (str "AWS SNS Publish Exception: "
                                         (.getMessage e)
                                         "\n\n"
                                         "target-arn: "
                                         target-arn
                                         "\nmessage: "
                                         message)})))))
(defn timestamp->unix-epoch
  "Convert a java.sql.Timestamp timestamp to unix epoch seconds"
  [timestamp]
  (/ (.getTime timestamp) 1000))

(defn convert-timestamp
  "Replace :timestamp_created value in m with unix epoch seconds"
  [m]
  (assoc m :timestamp_created (timestamp->unix-epoch (:timestamp_created m))))

(defn convert-timestamps
  "Replace the :timestamp_created value with unix epoch seconds in each map of
  vector"
  [v]
  (map convert-timestamp v))
