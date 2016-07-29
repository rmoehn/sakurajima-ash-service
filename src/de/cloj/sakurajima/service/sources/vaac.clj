(ns de.cloj.sakurajima.service.sources.vaac
  (:require [clojure.spec :as s]
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]
            [de.cloj.sakurajima.service.global-specs :as gs])
  (:import java.time.Instant))

(s/def ::inst inst?)
(s/def ::last-report-inst inst?)
(s/def ::check-interval (s/and int? pos?))

(s/fdef start
  :args (s/cat :kill-chan ::gs/chan
               :status (s/keys :opt [::last-report-inst])
               :config (s/keys :req [::check-interval])
               :new-chan ::gs/chan))

(s/fdef find-new-report
  :ret (s/nilable (s/keys ::inst)))

;(defn find-new-report []
;  (extract-report (download-vaac-listing)))

(defn find-new-report []
  {::inst (Instant/now)
   ::text "Most likely Sakurajima hasn't erupted."})

(defn start [kill-chan status config news-chan]
  (async/go-loop [last-report-inst (get status ::last-report-inst
                                        Instant/EPOCH)]
    (async/alt!
      kill-chan
      (assoc status ::last-report-inst last-report-inst)

      (async/timeout (::check-interval config))
      (if-let [new-report (find-new-report last-report-inst)]
        (do
          (>! news-chan new-report)
          (recur (::inst new-report)))
        (recur last-report-inst)))))

(defrecord Component [kill-chan status config news-chan]
  component/Component

  (start [this]
   (async/go-loop [last-report-inst (get status ::last-report-inst
                                        Instant/EPOCH)]
    (async/alt!
      kill-chan
      (assoc status ::last-report-inst last-report-inst)

      (async/timeout (::check-interval config))
      (if-let [new-report (find-new-report last-report-inst)]
        (do
          (>! news-chan new-report)
          (recur (::inst new-report)))
        (recur last-report-inst)))))

  (stop [this]
    (async/put! kill-chan :stop)))

(defn new-component [status config news-chan]
  (map->Component {:kill-chan (async/chan)
                   :status status
                   :config config
                   :news-chan news-chan}))

(defmethod record/record-multispec :de.cloj.sakurajima.servier.source/vaac [_]
  (s/merge ::vaac-access/vaac-list (s/merge (s/keys :req [::record/source-id])
                                            ::vaac-access/vaa-list-item)))

(defmethod record/record-details-multispec
  :de.cloj.sakurajima.servier.source/vaac
  [_]
  (s/keys :req [::vaa-access/vaa-text]))

(defmethod record/get-list :de.cloj.sakurajima.service.source/vaac [_]
  (map #(assoc % ::record/source-id :de.cloj.sakurajima.service.source/vaac)
       (vaac-access/get-sakurajima-vaa-list)))

(defmethod record/inst :de.cloj.sakurajima.service.source/vaac [vaa-list-item]
  (::vaac-access/inst vaa-list-item))

(defmethod record/add-details :de.cloj.sakurajima.service.source/vaac
  [_ vaa-list-item]
  (assoc vaa-list-item ::vaa-access/vaa-text
         (vaac-access/get-sakurajima-vaa-text
           (::vaac-access/vaa-text-url vaa-list-item))))
