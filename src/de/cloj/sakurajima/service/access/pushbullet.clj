(ns de.cloj.sakurajima.service.access.pushbullet
  (:require [clojure.spec :as s]
            [cheshire.core :as cheshire]
            [clj-http.client :as http]))

(def default-timeout 30000)

(s/def ::access-token string?)
(s/def ::title string?)
(s/def ::body string?)
(s/def ::channel string?)

(s/fdef push
  :args (s/cat :access-token ::access-token
               :kwargs (s/keys* :req-un [::title ::body]
                                :opt-un [::channel])))
(defn push [access-token & args]
  (let [push-args
        (as-> (apply hash-map args) arg
          (assoc arg :type "note")
          (if (:channel arg)
            (dissoc (assoc arg :channel_tag (:channel arg)) :channel)
            arg))]
    (http/post "https://api.pushbullet.com/v2/pushes"
               {:headers {:access-token access-token}
                :body (cheshire/generate-string push-args)
                :as :json
                :content-type :json
                :socket-timeout default-timeout
                :conn-timeout default-timeout})))
