(ns dashboard.schedules
  (:require [clj-http.client :as client]))



(def api-url
  "https://api.wheniwork.com/2/")

(def wiw-key "5876b896f67489deb99592e46c4d5bc45d55ce10")

(def username "james@purpleapp.com")

(def password "")

(def token "e125c97e9affe18742f83b39cc48b1500a722eca")

(def wiw-opts
  {:headers {"W-Token" token}})

(defn wiw-req
  [method endpoint & [params headers]]
  (try (let [opts (merge-with merge 
                              wiw-opts
                              {:form-params params}
                              {:headers headers})
             resp (:body ((resolve (symbol "clj-http.client" method))
                          api-url
                          opts))]
         (println opts)
         (println resp)
         {:success (not (:error resp))
          :resp resp})
       (catch Exception e
         {:success false
          :resp {:error {:message "Unknown error."}}})))

(defn wiw-req
  [method endpoint & [params headers]]
  (let [opts (merge-with merge 
                         wiw-opts
                         {:form-params params}
                         {:headers headers})
        resp (:body ((resolve (symbol "clj-http.client" method))
                     api-url
                     opts))]
    ;;(println opts)
    ;;(println resp)
    {:success (not (:error resp))
     :resp resp}))
