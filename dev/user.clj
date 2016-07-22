(ns user
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.repl :refer [pst doc find-doc]]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [de.cloj.sakurajima.service.topics :as topics]
            [net.cgrand.enlive-html :as html]))

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


(defn start-endpoint [news-pub action]
  (let [news-in (async/chan)]
    (println news-pub)
    (async/sub news-pub ::topics/all news-in)
    (async/go-loop []
      (action (async/<! news-in))
      (recur))))


(defn twitter-action [news]
  (with-open [*out* (io/writer (System/err))] (println "Twitter: " news)))

(defn log-action [news]
  (with-open [*out* (io/writer (System/out))] (println "Log: " news)))


(defn go-service []
  (let [news-chan (async/chan)
        news-pub (async/pub news-chan (constantly ::topics/all))]
    (start-endpoint news-pub twitter-action)
    (start-endpoint news-pub log-action)
    news-chan))

(comment

  (async/sub (async/pub (async/chan) (constantly :huhu)) :huhu (async/chan))

  (refresh)
  (def ch (go-service))
  (async/put! ch "hello")

  (def vaac-list-url "https://ds.data.jma.go.jp/svd/vaac/data/vaac_list.html")

  ; Credits: https://github.com/swannodette/enlive-tutorial/
  (defn fetch-url [url]
    (html/html-resource (io/as-url url)))

  (def list-resource (fetch-url vaac-list-url))

  [::inst ::volcano ::area ::advisory-no ::vaa-text-url ::va-graphic-url ::va-initial-url ::va-forecast-url ::satellite-img-url]


  (require '[clojure.pprint :refer [pprint]])

  (let [one-thing (->> (html/select list-resource [:tr.mtx])
                       (map #(html/select % [:td :a]))
                       second
                       (map #(get-in % [:attrs :href])))]
    (pprint one-thing))

  (defn absolute-url [relative-url]
    (io/as-url (str "https://ds.data.jma.go.jp/svd/vaac/data/" relative-url)))

  (defn url-from-onclick [onclick]
    (absolute-url
      (second (re-matches #"(?xms) open .+? \(' (.+?) '\)" onclick))))

  (defn text-or-url [td-node]
    (if-let [a-node (first (html/select td-node [:a]))]
      (let [href (get-in a-node [:attrs :href])]
        (if (= href "javascript:void(0)")
          (url-from-onclick (get-in a-node [:attrs :onclick]))
          (absolute-url href)))
      (let [text (html/text td-node)]
        (when-not (= text "-")
          text))))

  ; Credits: Clojure Data Analysis Cookbook, page 23
  (defn raw-vaac-list [list-resource]
    (->> (html/select list-resource [:tr.mtx])
         rest
         (map #(html/select % [:td]))
         (map #(map text-or-url %))
         (map #(zipmap [::time ::friendly-time ::volcano ::area ::advisory-no
                        ::vaa-text-url ::va-graphic-url ::va-initial-url
                        ::va-forecast-url ::satellite-img-url] %))))

  (import '[java.time Instant LocalDateTime ZoneId]
          java.time.format.DateTimeFormatter)

  (defn instant [timestamp]
    (let [formatter (DateTimeFormatter/ofPattern "yyy/MM/dd HH:mm:ss")]
      (-> timestamp
          (LocalDateTime/parse formatter)
          (.atZone (ZoneId/of "UTC"))
          Instant/from)))

  (instant "2016/06/02 15:34:00")

  (defn prepared-sakurajima-vaacs [raw-vaacs]
    (->> raw-vaacs
         (filter #(re-find #"(?i)sakurajima" (::volcano %)))
         (map #(assoc % ::inst (instant (::time %))))
         (map #(dissoc % ::time ::friendly-time))))

  (pprint (prepared-sakurajima-vaacs (raw-vaac-list list-resource)))


  )

;(defn go-service-control [control-chan news-in endpoints]
;  (async/go-loop
;    (alt!
;      control-chan ([v] (if (nil? v) ::stop-ok
;                                  ))
;      )
;    (let [[v ch] (alts! (conj sources control-chan))]
;      (if (nil? v)
;        (if
;          (throw (RuntimeException.
;                   (str ("Channel " ch " closed unexpectedly.")))))
;
;        )
;      )
;    )
;  )
;
;
;(defrecord)
