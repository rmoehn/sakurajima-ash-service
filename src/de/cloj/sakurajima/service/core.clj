(ns de.cloj.sakurajima.service.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.spec.test :as stest]
            beckon
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]
            [de.cloj.sakurajima.service.endpoint :as endpoint]
            [de.cloj.sakurajima.service.endpoints.log :as log-endpoint]
            [de.cloj.sakurajima.service.endpoints.pushbullet
             :as pushbullet-endpoint]
            [de.cloj.sakurajima.service.source :as source]
            [de.cloj.sakurajima.service.sources.record :as record]
            [de.cloj.sakurajima.service.sources.vaac :as vaac-source]
            [de.cloj.sakurajima.service.status-server :as status-server]
            [de.cloj.sakurajima.service.topics :as topics]
            [taoensso.timbre :as t]
            [taoensso.timbre.appenders.core :as appenders]))

(def system nil)

;;;; Startup and shotdown code

(defn go-service [config]
  (let [status-request-chan (async/chan)
        status-server-res-chan (status-server/start config status-request-chan)

        news-chan (async/chan)
        news-pub (async/pub news-chan (constantly ::topics/all))
        endpoint-res-chans (doall
                             (map #(endpoint/start news-pub %)
                                  [log-endpoint/action
                                   (pushbullet-endpoint/make-action config)]))

        vaac-source-kill-chan (async/chan)
        vaac-source-res-chan
        (source/start {:source-id
                       :de.cloj.sakurajima.service.source/vaac

                       :config config
                       :kill-chan vaac-source-kill-chan
                       :status-req-chan status-request-chan
                       :news-chan news-chan})]
    {::news-chan news-chan
     ::endpoint-res-chans endpoint-res-chans
     ::status-request-chan status-request-chan
     ::status-server-res-chan status-server-res-chan
     ::source-kill-chans [vaac-source-kill-chan]
     ::source-res-chans [vaac-source-res-chan]}))

(defn stop-service [chan-map]
  (t/info "Stopping sources…")
  (doseq [kc (::source-kill-chans chan-map)]
    (async/close! kc))
  (doseq [src (::source-res-chans chan-map)]
    (async/<!! src))
  (t/info "Sources stopped.")

  (t/info "Stopping status server…")
  (async/close! (::status-request-chan chan-map))
  (async/<!! (::status-server-res-chan chan-map))
  (t/info "Status server stopped.")

  (t/info "Stopping endpoints…")
  (async/close! (::news-chan chan-map))
  (doseq [erc (::endpoint-res-chans chan-map)]
    (async/<!! erc))
  (t/info "Endpoints stopped."))

(defn shutdown-cleanly []
  (alter-var-root #'system stop-service)
  (t/info "Sakurajima Ash Service stopped.")
  (System/exit 0))

(defn read-config [maybe-path]
  (let [default
        {:check-interval 300
         :log-file-path "/tmp/sakurajima-ash-service-log.txt"
         :status-file "/tmp/sakurajima-ash-service-status.edn"
         :pushbullet-access-token "o.x0AMstDXCT6Y6nKaapHCouXB73ptmV3l"}

        provided
        (if maybe-path
          (edn/read-string (slurp maybe-path))
          {})]
    (-> (merge default provided)
        (update :status-file io/as-file))))

;;;; Main entrypoint

;; TODO:
;;  x Add Beckon dependency.
;;  x Add command line parsing.
;;     x Just config file.
;;  x Add config file reading.
;;     x Read config file.
;;     x Merge with defaults.
;;  x Add uncaught exception handler that writes to the log.
;;  - Abstract out Pushbullet push code.
;;  - Write Timbre appender for Pushbullet.
;;  x Configure Timbre to write to file (> DEBUG)
;;     - and Pushbullet (> Error).
;;  - Install JRE on Hadar.
;;  - Copy to Hadar.
;;  - Run in a way that pushes to Pushbullet when the process ends.
;;  - Make an Uberjar.
;;  - Include run config in project.clj

(defn -main [& args]
  (t/handle-uncaught-jvm-exceptions!)

  (reset! (beckon/signal-atom "INT") #{shutdown-cleanly})
  (reset! (beckon/signal-atom "TERM") #{shutdown-cleanly})

  (s/check-asserts true)
  (stest/instrument (stest/instrumentable-syms))

  (let [config (read-config (first args))]
    (t/merge-config!
      {:appenders {:spit (appenders/spit-appender
                           {:fname (:log-file-path config)})
                   :println nil}
       :output-fn (partial t/default-output-fn {:stacktrace-fonts {}})})
    (alter-var-root #'system (constantly (go-service config))))

  (t/info "Sakurajima Ash Service started."))
