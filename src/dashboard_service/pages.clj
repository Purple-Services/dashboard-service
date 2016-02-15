(ns dashboard-service.pages
  (:require [net.cgrand.enlive-html :refer [after append html do-> unwrap content
                                            set-attr deftemplate]]
            [common.config :as config]))

(deftemplate dash-login-template "templates/dashmap.html"
  [x]
  [:title] (content "Purple - Dashboard Login")

  [:#pikaday-css] unwrap

  [:head] (do->
           (append (html [:meta
                         {:name "viewport"
                          :content "width=device-width, initial-scale=1"}]))
           (append (html [:link
                          {:rel "stylesheet"
                           :type "text/css"
                           :href
                           (str config/base-url "css/dashmap.css")}]))
           (append (html [:link
                          {:rel "stylesheet"
                           :type "text/css"
                           :href
                           (str config/base-url "css/bootstrap.min.css")}]))
           (append (html [:link
                          {:rel "stylesheet"
                           :type "text/css"
                           :href
                           (str config/base-url "css/sb-admin.css")}])))

  [:#base-url] (set-attr :value (str (:base-url x)))

  [:#map] (set-attr :id "login")

  [:#map-init]  (fn [node] (html [:script "dashboard_cljs.core.login();"])))


(defn dash-login
  []
  (apply str (dash-login-template
              {:base-url
               (str config/base-url "dashboard/")})))

(deftemplate dash-app-template "templates/dashmap.html"
  [x]
  [:title] (content "Purple - Dashboard App")

  [:#pikaday-css] unwrap

  [:head] (do->
           (append (html [:meta
                         {:name "viewport"
                          :content "width=device-width, initial-scale=1"}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url "css/pikaday.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url "css/dashmap.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href
                            (str config/base-url "css/bootstrap.min.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url "css/sb-admin.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url "css/dashboard.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url
                                       "css/font-awesome.min.css")}])))
  
  [:#base-url] (set-attr :value (str (:base-url x)))

  [:#map] (set-attr :id "app")

  [:#map-init]  (fn [node]
                  (html [:script "dashboard_cljs.core.init_new_dash();"]))

  [:#dashboard-cljs]

  (after
   (html
    [:script {:src
              (str "https://maps.googleapis.com/maps/api/js?"
                   "key="
                   config/dashboard-google-browser-api-key)}])))

(defn dash-app
  []
  (apply str (dash-app-template
              {:base-url
               (str config/base-url "dashboard/")})))

(deftemplate dash-map-template "templates/dashmap.html"
  [x]
  ;; we need dashmap.css, pikaday.css and dashboard_cljs.js
  [:head] (do->
           (append (html [:link
                          {:rel "stylesheet"
                           :type "text/css"
                           :href
                           (str config/base-url "css/dashmap.css")}]))
           (append (html
                    [:link {:rel "stylesheet"
                            :type "text/css"
                            :href (str config/base-url "css/pikaday.css")}])))

  [:#base-url] (set-attr :value (str (:base-url x)))
  [:#map-init]
  (set-attr :src
            (str "https://maps.googleapis.com/maps/api/js?"
                 "key="
                 config/dashboard-google-browser-api-key
                 "&callback="
                 (:callback-s x))))

(defn dash-map
  [& {:keys [read-only courier-manager callback-s]}]
  (apply str (dash-map-template {:base-url (str  config/base-url "dashboard/")
                                 :read-only read-only
                                 :callback-s callback-s })))
