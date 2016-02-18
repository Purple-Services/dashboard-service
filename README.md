# dashboard
Dashboard API + logic specific to dashboard.

## Runing the server

profiles.clj:

```clojure
{:dev { :env {:aws-access-key-id "ANYTHINGWHENLOCAL"
              :aws-secret-key "ANYTHINGWHENLOCAL"
              :db-host "localhost" ; AWS host: "purple-dev-db.cqxql2suz5ru.us-west-2.rds.amazonaws.com"
              :db-name "ebdb"
              :db-port "3306"
              :db-user "purplemaster"
              :db-password "localpurpledevelopment2015" ; AWS pwd: HHjdnb873HHjsnhhd
              :email-user "no-reply@purpledelivery.com"
              :email-password "HJdhj34HJd"
              :stripe-private-key "sk_test_6Nbxf0bpbBod335kK11SFGw3"
              :sns-app-arn-apns "arn:aws:sns:us-west-2:336714665684:app/APNS_SANDBOX/Purple" ;; sandbox is also used for couriers on prod
              :sns-app-arn-gcm  "arn:aws:sns:us-west-2:336714665684:app/GCM/Purple" ;; also used on prod
              :twilio-account-sid "AC0a0954acca9ba8c527f628a3bfaf1329"
              :twilio-auto-token "3da1b036da5fb7716a95008c318ff154"
              :twilio-form-number "+13239243338"
              ;; 192.168.0.1 is used because it is a IP address on the local LAN
              ;; that can be accessed from devices connected through the LAN
              ;; helpful for testing on mobile devices
              :base-url "http://192.168.0.1:3001/" ;; crucial, must be different than the app-service port!
              :has-ssl "NO"
              :segment-write-key "test"
              :sift-science-api-key "test"
              :dashboard-google-browser-api-key "AIzaSyA0p8k_hdb6m-xvAOosuYQnkDwjsn8NjFg"
              :env "dev"}
       :dependencies [[javax.servlet/servlet-api "2.5"]
                      [ring-mock "0.1.5"]]}}
```

The lein-ring plugin has its own entry in project.clj. The server port must be
different from the default port of 3000 that the app-service server runs on
if both servers are needed for development. To be on the safe side, run the
dashboard service on port 3001.

## Log in

If you are using the scripts provided in app-service for building the local
database, then the following login can be used:

u: test@test.com
p: qwerty123


### Edit permissions

See the comment src/dashboard/handler.clj in dashboard-uri-permissions
for the permissions needed to access the dashboard. Edit the 'permissions' field
of a user in `dashboard_users` to enable/disable features.
