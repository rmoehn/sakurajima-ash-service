(ns de.cloj.sakurajima.service.endpoints.log
  (:require [clojure.pprint :refer [pprint]]
            [taoensso.timbre :as t]))

(defn action [record]
  (t/infof "Found new record:\n%s" (with-out-str (pprint record))))
