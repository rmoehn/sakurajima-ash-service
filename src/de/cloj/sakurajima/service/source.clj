(ns de.cloj.sakurajima.service.source
  (:require [clojure.core.async :as async]
            [de.cloj.sakurajima.sources.record :as record]
            [de.cloj.sakurajima.service.status-server.request :as request]
            [de.cloj.sakurajima.service.global-specs])
  (:include java.time.Instant))

;;;; For talking with the status server

(defn set-newest-record-inst [status-req-chan source-id inst]
  (go
    (async/>! status-req-ch
              [{::request/type ::request/write
                ::request/source-id source-id

                ::request/newest-record-inst inst}
               response-ch])
    (assert (async/<! response-ch))
    inst))

(defn request-newest-record-inst [status-req-chan source-id]
  (go
    (let [response-ch (async/chan)]
      (async/>! status-req-ch [{::request/type ::request/read
                                ::request/source-id source-id}
                               response-ch])
      (or (async/<! response-ch)
          (set-newest-record-inst
            status-req-ch source-id
            (if-let [record (first (record/get-list source-id))]
              (inst record)
              (Instant/EPOCH)))))))


;;;; Public interface

(s/def ::check-interval (s/and integer? pos?))
(s/def ::config (s/keys :req-un [::check-interval]))
(s/def ::kill-chan ::gs/chan)
(s/def ::status-req-chan ::gs/chan)
(s/def ::news-chan ::gs/chan)

(s/fdef start
  :arg (s/cat :argmap (s/keys :req-un [::record/source-id ::config ::kill-chan
                                       ::status-req-chan ::news-chan])))

(defn start [{:keys {source-id config kill-chan status-req-chan news-chan}}]
  (go-loop []
    (let [newest-record-inst
          (::record/inst
            (async/<! (request-newest-record-inst status-req-chan source-id)))

          new-records
          (->> (record/get-list source-id)
               (take-while #(.isAfter (inst %) newest-record-inst))
               (map add-details)
               reverse)]
      (doseq [r new-records]
        (async/>! news-chan r)
        (set-newest-record-inst status-req-chan source-id (inst r))))
    (async/alt!
      kill-chan ::done
      (async/timeout (* 1000 (:check-interval config))) (recur))))
