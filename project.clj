(defproject dashboard "1.1.0-SNAPSHOT"
  :description "Dashboard Service API that the dashboard client connects to."
  :url "https://dash.purpleapp.com"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.csv "0.1.3"]
                 [org.clojure/core.memoize "0.5.8"]
                 [clojure-csv/clojure-csv "2.0.1"]
                 [net.mikera/core.matrix "0.52.0"]
                 [org.clojure/algo.generic "0.1.2"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [compojure "1.1.8"]
                 [bouncer "1.0.0"]
                 [buddy/buddy-auth "0.8.1"]
                 [cheshire "5.4.0"]
                 [enlive "1.1.5"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [ring/ring-json "0.4.0"]
                 [ring/ring-ssl "0.2.1"]
                 [dk.ative/docjure "1.11.0-SNAPSHOT"]
                 [common "2.0.2-SNAPSHOT"]
                 [ring-cors "0.1.7"]
                 [opt "1.0.6-SNAPSHOT"]]
  :plugins [[lein-ring "0.9.7"]
            [lein-beanstalk "0.2.7"]
            [com.jakemccrary/lein-test-refresh "0.17.0"]]
  :ring {:handler dashboard.handler/handler
         :port 3001
         :auto-reload? true
         :auth-refresh? false
         :browser-uri "/"
         :reload-paths ["src" "resources" "checkouts"]}
  :aws {:beanstalk {:environments [{:name "dashboard-prod"}
                                   {:name "dashboard-dev-env"}]
                    :s3-bucket "leinbeanstalkpurple"
                    :region "us-west-2"}}
  :profiles {:default [:base :system :user :provided :local]
             :shared [{:dependencies
                       [[javax.servlet/servlet-api "2.5"]
                        [ring/ring-mock "0.3.0"]
                        [org.seleniumhq.selenium/selenium-java "2.47.1"]
                        [clj-webdriver "0.7.2"]
                        [ring "1.5.0"]
                        [pjstadig/humane-test-output "0.6.0"]]
                       :injections
                       [(require 'pjstadig.humane-test-output)
                        (pjstadig.humane-test-output/activate!)]}]
             :local [:shared :profiles/local
                     {:env {:base-url "http://localhost:3001/"}}]
             :dev [:shared :profiles/dev
                   {:env {:base-url "http://localhost:3001/"}}]
             :prod [:shared :profiles/prod
                    {:env {:base-url "http://localhost:3001/"}}]
             :app-integration-test {:env {:test-db-host "localhost"
                                          :test-db-name "ebdb_test"
                                          :test-db-port "3306"
                                          :test-db-user "root"
                                          :test-db-password ""}
                                    :jvm-opts ["-Dwebdriver.chrome.driver=/usr/lib/chromium-browser/chromedriver"]
                                    :plugins [[lein-environ "1.1.0"]]
                                    :dependencies
                                    [[javax.servlet/servlet-api "2.5"]
                                     [ring/ring-mock "0.3.0"]
                                     [org.seleniumhq.selenium/selenium-java "2.47.1"]
                                     [clj-webdriver "0.7.2"]
                                     [pjstadig/humane-test-output "0.6.0"]]}
             :app-integration-dev-deploy
             {:aws {:access-key ~(System/getenv "AWS_ACCESS_KEY")
                    :secret-key ~(System/getenv "AWS_SECRET_KEY")}}})
