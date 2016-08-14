(ns de.cloj.sakurajima.service.pushbullet-appender
  (:require [clojure.string :as string]
            [de.cloj.sakurajima.service.access.pushbullet :as pushbullet]
            [taoensso.encore :as enc]))

;;; Credits: https://github.com/ptaoussanis/timbre/blob/master/src/taoensso/timbre/appenders/example.clj

(defn pushbullet-appender [opts]
  (let [{:keys [access-token min-level]} opts]
    {:enabled?   true
     :async?     true
     :min-level  min-level
     :rate-limit [[3  (enc/ms :mins  1)]  ; 3 calls/min
                  [12 (enc/ms :hours 1)]] ; 12 calls/hour
     :output-fn  :inherit
     :fn
     (fn [data]
       (let [{:keys [level output_]} data]
         (pushbullet/push access-token
                          :title (str (-> level
                                          (string/replace-first ":" "")
                                          string/upper-case)
                                      " Sakurajima Ash Service")
                          :body (force output_))))}))
