(ns dashboard.utils
  (:require [clojure.edn :as edn]))

(defn edn-read?
  "Attempt to read x with edn/read-string, return true if x can be read,false
  otherwise "
  [x]
  (try (edn/read-string x)
       true
       (catch Exception e false)))
