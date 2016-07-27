(ns de.cloj.sakurajima.service.status-server.request
  (:require [clojure.spec :as s]
            [de.cloj.sakurajima.service.global-specs :as gs]))

(s/def ::type ::gs/nsq-keyword)
(s/def ::source-id ::gs/nsq-keyword)
(s/def ::newest-record-inst inst?)
(s/def ::response-ch ::gs/chan)
