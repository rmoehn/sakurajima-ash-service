(ns de.cloj.sakurajima.service.global-specs
  (:require [clojure.core.async.impl.channels :as channels]
            [clojure.spec :as s])
  (:import clojure.core.async.impl.channels.ManyToManyChannel
           java.net.URL))

; Not sure if this is a good idea.
(s/def ::chan #(instance? ManyToManyChannel %))

(s/def ::url #(instance? java.net.URL %))

(s/def ::nsq-keyword (s/and keyword? #(namespace %)))
