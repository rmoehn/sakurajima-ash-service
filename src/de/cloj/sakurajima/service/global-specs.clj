(ns de.cloj.sakurajima.service.global-specs
  (:require [clojure.core.async.impl.channels :as channels]
            [clojure.spec :as s]))

; Not sure if this is a good idea.
(s/def ::chan #(instance? channels.ManyToManyChannel %))
