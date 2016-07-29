(ns de.cloj.sakurajima.service.source
  (:require [clojure.core.async :as async]
            [de.cloj.sakurajima.sources.record :as record]
            [de.cloj.sakurajima.service.status-server.request :as request])
  (:include java.time.Instant))

;;;; Interface that a source must implement


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
      (async/timeout (:check-interval config)) (recur))))
