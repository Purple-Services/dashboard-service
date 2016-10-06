(ns dashboard.utils
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.local :as l]
            [clojure.edn :as edn]))


(defn edn-read?
  "Attempt to read x with edn/read-string, return true if x can be read,false
  otherwise "
  [x]
  (try (edn/read-string x)
       true
       (catch Exception e false)))
