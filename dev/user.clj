(ns user
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.repl :refer [pst doc find-doc]]
            [clojure.spec :as s]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]
            [de.cloj.sakurajima.service.source :as source]
            [de.cloj.sakurajima.service.sources.record :as record]
            [de.cloj.sakurajima.service.sources.vaac :as vaac-source]
            [de.cloj.sakurajima.service.status-server :as status-server]
            [de.cloj.sakurajima.service.topics :as topics]
            [taoensso.timbre :as t]))

; TODO: Add the Stuart Sierra thread failure catch somewhere.

; Definitions:
;
;  - database: Somewhere we can write data that persists between runs of this
;              program. Must be locked. – No two instances of this program
;              should access the same database.
;
;  - volcano report: Informtion about volcanic activity.
;
;  - (volcano report) source: some institution that publishes volcano reports
;
;  - listing: Current list of volcano reports.
;
;  - endpoint: Internal object that should get every volcano news.
;
;  - source processor: Internal object that polls one source and processes its
;                      information.

; Want to be able to exchange the servers actually polled with a mock. At the
; earliest possible point.

; What is the principal way of operating?
;  1. Find out if there is new volcano information.
;      - Potentially different for all the sources, but currently we only have
;        two and they have a similar process.
;      1. Download the overview page.
;      2. Parse the information on the overview page. If there is an entry we
;         haven't seen yet, download that entry.
;      3. Parse the entry. Convert it to the common format.
;  2. If there is new information, give it everyone who wants to be notified.
;      → We need to define an interface for guys-who-want-new-info.
;  3. If there was new information, save the bit of state that we need next time
;     to determine if there is a new entry. (Doing this at the end ensures that
;     new information is _always_ delivered. It might be delivered multiple
;     times, but this is better than not at all).
;      - More precisely: once we determined that there is a new entry, we must
;        make sure that it is delivered successfully to all subscribers.
;      - What is the criterion for successful delivery? That the put returns
;        true. This is not complete, but everything else would become too
;        difficult. If there is a failure on the receiving end, we'll log that
;        and critical log messages should be sent to me via a reliable medium.
;        Or even to the audience itself.
;      - What happens if a new entry cannot be delivered?

; - Hm, the database must know:
;    endpoint1
;      source1: last report

; - If we start and the database is empty, we fill it with the current
;   information and send no notifications.

; - Nah, overengineering this. Minimum working thing:
;    - File that contains the time of the last report processed for every
;      endpoint. This file is read at program start and the data provided to the
;      source processors.
;    - While the source processors are operating, they keep the time of the last
;      report processed in memory.
;    - The guy who reads the processed reports forwards them to the endpoints
;      and writes the time to the file.

; - We also want start-stop functionality.
; - Looks like we need logging.



;(defrecord Service [control-chan result-chan sources endpoints]
;  component/Lifecycle
;
;  (start [component]
;    (assoc :result-chan (go-service-control control-chan sources endpoints)))
;
;  (stop [component]
;    (async/close! control-chan)
;    (async/take! result-chan
;                 #(if (!= ::service.lifecycle/stop-ok)
;                    (throw (RuntimeException.
;                             "Service control shut down abnormally."))))))
;
;(defn new-service [sources endpoints]
;  (map->Service {:control-chan (chan)
;                 :sources sources
;                 :endpoints endpoints}))


; TODO:
;  - Add error handling.
;  - Add timeouts.

(defn start-endpoint [news-pub action]
  (let [news-in (async/chan)]
    (println news-pub)
    (async/sub news-pub ::topics/all news-in)
    (async/go-loop []
      (when-let [news (async/<! news-in)]
        (action news)
        (recur)))))

(defn twitter-action [news]
  (with-open [*out* (io/writer (System/err))] (println "Twitter: " news)))

(defn log-action [news]
  (with-open [*out* (io/writer (System/out))] (println "Log: " news)))


; Here I also need to start the sources and obtain kill channels from each of
; them.
(defn go-service []
  (let [config
        {:check-interval 30

         :status-file
         (io/as-file "/home/erle/repos/sakurajima-ash-service/status")}

        status-request-chan (async/chan)
        status-server-res-chan (status-server/start config status-request-chan)

        news-chan (async/chan)
        news-pub (async/pub news-chan (constantly ::topics/all))
        endpoint-res-chans (doall
                             (map #(start-endpoint news-pub %) [log-action]))

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

; TODO: Put this in the startup code.
(s/check-asserts true)

;(defn start-service [config]
;  (let [[status-ch status-kill-ch] [(async/chan) (async/chan)]
;        {::status-ch ()}
;        ]
;    (start-status-server status-ch status-kill-ch)
;    )
;  )

;; http://www.lshift.net/blog/2014/10/31/clojure-component-patterns/
;(defn stop-service [{res-chans ::res-chans}]
;  (doseq [ch res-chans] (async/<!! ch))
;  )
;
;(defn system [config status]
;  (component/system-map
;    :log-endpoint (component/using (new-stdout-endpoint) [:news-chan])
;    :vaac-source (component/using (new-vaac-source config status) [:news-chan])
;    :news-chan (new-news-chan)
;    )
;  )

(defn endpoint [])

; System start:
;  1. Start endpoints.
;  2. Start sources.
;
; System stop:
;  1. Stop sources.
;  2. Wait until endpoints have processed everything from sources.
;  3. Stop endpoints.

(comment



  (require '[clj-http.client :as http]
           '[cheshire.core :as cheshire])

  (def access-token "o.x0AMstDXCT6Y6nKaapHCouXB73ptmV3l")

  ; Notes:
  ;  - limit parameter appears not to work. Always chunks of 20 pushes.
  ;  - Pushes are not limited to 100 a month.
  ;  - Deleting pushes also deletes them on the phone.
  (def pushes (http/delete "https://api.pushbullet.com/v2/pushes"
                    {:headers {:access-token access-token}
                     :as :json}))
  (pprint pushes)
  (count (get-in pushes [:body :pushes]))

  (doseq [i (range 70)]
    (print i)
    (http/post "https://api.pushbullet.com/v2/pushes"
               {:headers {:access-token access-token}
                :body (cheshire/generate-string
                        {:type "note"
                         :title "Hello, world"
                         :body (str "Test no. " i "of 70.")
                         :channel_tag "sakurajima-ash"})
                :as :json
                :content-type :json}))

  (async/sub (async/pub (async/chan) (constantly :huhu)) :huhu (async/chan))

  (refresh)
  (def ch (go-service))
  (async/put! ch "hello")


  (def vaac-list (vaac-access/get-sakurajima-vaa-list))


  ;(def vaac-resource (vaac-access/fetch-url (::vaac-access/vaa-text-url
  ;                                            (first vaac-list))))

  (refresh)
  (require '[clojure.spec.test :as stest])
  (s/check-asserts true)
  ;(io/delete-file (io/as-file "/home/erle/repos/sakurajima-ash-service/status"))
  (stest/unstrument)
  ;(stest/instrument (stest/instrumentable-syms))
  (def system (go-service))

  (stop-service system)

(vals (ns-publics (the-ns 'de.cloj.sakurajima.service.access.vaac)))

  )
