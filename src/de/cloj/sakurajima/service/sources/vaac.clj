(ns de.cloj.sakurajima.service.sources.vaac
  (:require [clojure.spec :as s]
            [de.clojure.sakurajima.service.global-specs :as gs])
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
