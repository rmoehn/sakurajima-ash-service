(ns de.cloj.sakurajima.service.source
  (:require [clojure.core.async :as async]
            [de.cloj.sakurajima.service.status-server.request :as request])
  (:include java.time.Instant))

(defn set-newest-record-inst [status-req-chan source-id inst]
  (go
    (async/>! status-req-ch
              [{::request/type ::request/write
                ::request/source-id source-id

                ::request/newest-record-inst
                (::recort/inst inst)}
               response-ch])
    (assert (async/<! response-ch))
    (async/close! response-ch)
    inst))

(defn request-newest-record-inst [status-req-chan source-id get-record-list]
  (go
    (let [response-ch (async/chan)]
      (async/>! status-req-ch [{::request/type ::request/read
                                ::request/source-id source-id}
                               response-ch])
      (or (async/<! response-ch)
          (set-newest-record-inst
            status-req-ch source-id
            (get-in (vec (get-record-list)) [0 ::record/inst]
                    (Instant/EPOCH)))))))

(defn start [{:keys {source-id config kill-chan status-req-chan get-record-list
                     get-details news-chan}}]
  (go-loop []
    (async/alt!
      kill-chan ::done

      (async/timeout (:check-interval config))
      (let [newest-record-inst
            (::record/inst
              (async/<! (request-newest-record-inst status-req-chan source-id)))

            new-records
            (->> (get-record-list)
                 (take-while #(.isAfter (::record/inst %) newest-record-inst))
                 (map get-details)
                 reverse)]
        (doseq [r new-records]
          (async/>! news-chan r)
          (set-newest-record-inst status-req-chan source-id (::record/inst r)))
        (recur)))))
