(defproject dashboard "1.0.0-SNAPSHOT"
  :description "Dashboard Service"
  :url "http://purpledelivery.com"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/algo.generic "0.1.2"]
                 [compojure "1.1.8"]
                 [bouncer "1.0.0"]
                 [buddy/buddy-auth "0.8.1"]
                 [cheshire "5.4.0"]
                 [enlive "1.1.5"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [ring/ring-json "0.4.0"]
                 [ring-cors "0.1.7"]
                 [common "1.0.0-SNAPSHOT"]
                 [opt "0.1.0-SNAPSHOT"]]
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler dashboard.handler/handler
         :port 3001
         :auto-reload? false
         :auth-refresh? false
         :browser-uri "dashboard/"
         :reload-paths ["src" "resources" "checkouts"]})
