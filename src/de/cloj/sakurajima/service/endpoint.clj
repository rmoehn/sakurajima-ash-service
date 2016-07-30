(ns de.cloj.sakurajima.service.endpoint
  (:require [clojure.core.async :as async]
            [de.cloj.sakurajima.service.topics :as topics]))

(defn start [news-pub action]
  (let [news-in (async/chan)]
    (async/sub news-pub ::topics/all news-in)
    (async/go-loop []
      (when-let [news (async/<! news-in)]
        (action news)
        (recur)))))
