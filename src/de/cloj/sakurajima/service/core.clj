(ns de.cloj.sakurajima.service.core
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.spec.test :as stest]
            beckon
            [de.cloj.sakurajima.service.access.pushbullet :as pushbullet]
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]
            [de.cloj.sakurajima.service.endpoint :as endpoint]
            [de.cloj.sakurajima.service.endpoints.log :as log-endpoint]
            [de.cloj.sakurajima.service.endpoints.pushbullet
             :as pushbullet-endpoint]
            [de.cloj.sakurajima.service.pushbullet-appender
             :as pushbullet-appender]
            [de.cloj.sakurajima.service.source :as source]
            [de.cloj.sakurajima.service.sources.record :as record]
            [de.cloj.sakurajima.service.sources.vaac :as vaac-source]
            [de.cloj.sakurajima.service.status-server :as status-server]
            [de.cloj.sakurajima.service.topics :as topics]
            [taoensso.timbre :as t])
  (:gen-class))

(def default-timeout 30000)

(def system nil)

(def wait-for-exit (async/chan))

;;;; Startup and shotdown code

(defn go-service [config]
  (let [status-request-chan (async/chan)
        status-server-res-chan (status-server/start config status-request-chan)

        news-chan (async/chan)
        news-pub (async/pub news-chan (constantly ::topics/all))

        endpoint-res-chans
        [(endpoint/start-go news-pub log-endpoint/action)
         (endpoint/start-thread news-pub
                                (pushbullet-endpoint/make-action config))]


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

(defn wait-for-slow-log []
  (Thread/sleep 3000)) ; Naive wait for the Pushbullet request triggered by warn.

(defn stop-service [chan-map]
  (t/warn "Stopping Sakurajima Ash Service.")
  (wait-for-slow-log)
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
  (async/put! wait-for-exit :exit))

(s/def ::pushbullet-access-token ::pushbullet/access-token)
(s/def ::config (s/keys :req-un [::source/check-interval
                                 ::status-server/status-file
                                 ::pushbullet-access-token]))
(defn read-config [maybe-path]
  (let [default
        {:check-interval 300
         :status-file "/tmp/sakurajima-ash-service-status.edn"}

        provided
        (if maybe-path
          (edn/read-string (slurp maybe-path))
          {})]
    (-> (merge default provided)
        (update :status-file io/as-file)
        (as-> x (s/assert ::config x)))))

(defn unconditional-exit-in [msec]
  (async/thread
    (async/<!! (async/timeout msec))
    (System/exit 1)))


;;;; Main entrypoint

;; TODO:
;;  x Add Beckon dependency.
;;  x Add command line parsing.
;;     x Just config file.
;;  x Add config file reading.
;;     x Read config file.
;;     x Merge with defaults.
;;  x Add uncaught exception handler that writes to the log.
;;  x Abstract out Pushbullet push code.
;;  x Write Timbre appender for Pushbullet.
;;  x Configure Timbre to write to file (> DEBUG)
;;     x and Pushbullet (> Error).
;;  x Install JRE on Hadar.
;;  x Change Timbre config to write to STDOUT. (Because using an external tool
;;    for log management is more convenient.)
;;  x Copy to Hadar.
;;  x Run in a way that pushes to Pushbullet when the process ends (starts).
;;  x Make an Uberjar.
;;  x Include run config in project.clj
;;  x Add timeouts.
;;  x Factor out access token. And remove occurences in code.
;;  x Write about access token in README.
;;  - Add citations to pushes: http://www.jma.go.jp/jma/en/copyright.html

(defn start [args]
  (s/check-asserts true)
  (stest/instrument (stest/instrumentable-syms))

  (let [config (read-config (first args))]
    (t/merge-config!
      {:appenders {:pushbullet (pushbullet-appender/pushbullet-appender
                                 {:access-token
                                  (:pushbullet-access-token config)

                                  :min-level :warn})}
       :output-fn (partial t/default-output-fn {:stacktrace-fonts {}})})
    (alter-var-root #'system (constantly (go-service config))))

  (t/info "Sakurajima Ash Service started.")
  (async/<!! wait-for-exit)
  (t/info "Sakurajima Ash Service stopped."))


(defn -main [& args]
  ; Credits: https://github.com/ptaoussanis/timbre/blob/master/src/taoensso/timbre.cljx
  (t/handle-uncaught-jvm-exceptions!
    (fn uncaught-jvm-exception-handler [throwable ^Thread thread]
      (unconditional-exit-in default-timeout)
      (t/errorf throwable "Uncaught exception on thread: %s" (.getName thread))
      (wait-for-slow-log)
      (System/exit 1)))

  (reset! (beckon/signal-atom "INT") #{shutdown-cleanly})
  (reset! (beckon/signal-atom "TERM") #{shutdown-cleanly})

  (start args)
  (System/exit 0))
