(ns de.cloj.sakurajima.service.endpoints.pushbullet
  (:require [clojure.spec :as s]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]
            [de.cloj.sakurajima.service.source :as source]
            [de.cloj.sakurajima.service.sources.record :as record]))

(defmulti notification-title ::record/source-id)

(defmethod notification-title ::source/vaac [record]
  (str "Sakurajima VAA " (::vaac-access/inst record)))

(defmulti notification-body ::record/source-id)

(defmethod notification-body ::source/vaac [record]
  (::vaac-access/vaa-text record))

(s/def ::pushbullet-access-token string?)
(s/fdef make-action
  :arg (s/cat :config (s/keys :req-un [::pushbullet-access-token])))

(defn make-action [config]
  (fn action [record]
    (http/post "https://api.pushbullet.com/v2/pushes"
               {:headers {:access-token (:pushbullet-access-token config)}
                :body (cheshire/generate-string
                        {:type "note"
                         :title (notification-title record)
                         :body (notification-body record)
                         :channel_tag "sakurajima-ash"})
                :as :json
                :content-type :json})))
