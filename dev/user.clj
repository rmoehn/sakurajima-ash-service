(ns user
  (:require [clojure.java.io :as io]
            [clojure.repl :refer [pst doc find-doc]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]))

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



(defrecord Service [control-chan result-chan sources endpoints]
  component/Lifecycle

  (start [component]
    (assoc :result-chan (go-service-control control-chan sources endpoints)))

  (stop [component]
    (async/close! control-chan)
    (async/take! result-chan
                 #(if (!= ::service.lifecycle/stop-ok)
                    (throw (RuntimeException.
                             "Service control shut down abnormally."))))))

(defn new-service [sources endpoints]
  (map->Service {:control-chan (chan)
                 :sources sources
                 :endpoints endpoints}))

(defn endpoint [news-pub action]
  (let [news-in (chan)]
    (async/sub news-pub ::topics/all news-in)
    (go-loop
      (action (async/<! news-in)))))


(defn twitter-action [news]
  ;...
  )

(defn log-action [news]
  ;...
  )

(defn go-service []
  (let [news-pub (async/pub (async/chan) (fn [] ::topics/all))]
    (-> news-pub
        (endpoint twitter-action)
        (endpoint log-action))))

(defn go-service-control [control-chan news-in endpoints]
  (async/go-loop
    (alt!
      control-chan ([v] (if (nil? v) ::stop-ok
                                  ))
      )
    (let [[v ch] (alts! (conj sources control-chan))]
      (if (nil? v)
        (if
          (throw (RuntimeException.
                   (str ("Channel " ch " closed unexpectedly.")))))

        )
      )
    )
  )


(defrecord)
