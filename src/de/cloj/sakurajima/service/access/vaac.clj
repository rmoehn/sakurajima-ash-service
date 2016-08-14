(ns de.cloj.sakurajima.service.access.vaac
  (:require [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.string :as string]
            [clj-http.client :as http]
            [de.cloj.sakurajima.service.global-specs :as gs]
            [diehard.core :as diehard]
            [net.cgrand.enlive-html :as html]
            [taoensso.timbre :as t])
  (:import java.net.SocketTimeoutException
           [java.time Instant LocalDateTime ZoneId]
           java.time.format.DateTimeFormatter))

;;;; Constants

(def default-timeout 15000)

(def vaa-list-url
  (io/as-url "https://ds.data.jma.go.jp/svd/vaac/data/vaac_list.html"))


;;;; General helper

;; Credits: https://github.com/swannodette/enlive-tutorial/
(defn fetch-url [url]
  (diehard/with-retry
    {:retry-on SocketTimeoutException
     :max-retries 3
     :backoff-ms [5000 60000]
     :on-failed-attempt (fn [_ _] (t/debugf "VAA request to %s timed out." url))
     :on-failure (fn [_ _] (t/warnf "Failed to download %s." url))}
    (-> url
        str
        (http/get {:socket-timeout default-timeout
                   :conn-timeout default-timeout})
        :body
        html/html-snippet)))


;;;; Tokyo VAAC-specific helpers

(defn instant [timestamp]
  (let [formatter (DateTimeFormatter/ofPattern "yyyy/MM/dd HH:mm:ss")]
    (-> timestamp
        (LocalDateTime/parse formatter)
        (.atZone (ZoneId/of "UTC"))
        Instant/from)))

(defn absolute-url [relative-url]
  (io/as-url (str "https://ds.data.jma.go.jp/svd/vaac/data/" relative-url)))

(defn url-from-onclick [onclick]
  (absolute-url
    (second (re-matches #"(?xms) open .+? \(' (.+?) '\)" onclick))))

(s/def ::tag keyword?)
(s/def ::attrs (s/nilable (s/map-of keyword? string?)))
(s/def ::content (s/nilable (s/coll-of (s/or :node ::node :string string?))))
(s/def ::tag-node (s/keys :req-un [::tag ::attrs ::content]))
(s/def ::type #{:dtd :comment})
(s/def ::data (s/or :string string? :string-coll (s/coll-of string?)))
(s/def ::type-node (s/keys :req-un [::type ::data]))
(s/def ::node (s/or :tag-node ::tag-node :type-node ::type-node
                    :whitespace (s/and string? string/blank?)))

(s/fdef text-or-url
  :args (s/cat :td-node ::node)
  :ret (s/or :string (s/nilable string?) :url ::gs/url))

(defn text-or-url [td-node]
  (if-let [a-node (first (html/select td-node [:a]))]
    (let [href (get-in a-node [:attrs :href])]
      (if (= href "javascript:void(0)")
        (url-from-onclick (get-in a-node [:attrs :onclick]))
        (absolute-url href)))
    (let [text (html/text td-node)]
      (when-not (= text "-")
        text))))


;;;; Main scraping and transforming code

(s/def ::nstring (s/and string? #(not (string/blank? %))))

(s/def ::time ::nstring)
(s/def ::friendly-time ::nstring)
(s/def ::inst inst?)
(s/def ::volcano ::nstring)
(s/def ::area ::nstring)
(s/def ::advisory-no ::nstring)
(s/def ::vaa-text-url ::gs/url)
(s/def ::va-graphic-url (s/nilable ::gs/url))
(s/def ::va-initial-url (s/nilable ::gs/url))
(s/def ::satellite-img-url (s/nilable ::gs/url))


(s/def ::raw-vaa-map (s/keys :req [::time ::friendly-time ::volcano ::area
                                   ::advisory-no ::vaa-text-url ::va-graphic-url
                                   ::va-initial-url ::va-forecast-url
                                   ::satellite-img-url]))

; Credits: Clojure Data Analysis Cookbook, page 23
(defn raw-vaa-list [list-resource]
  (->> (html/select list-resource [:tr.mtx])
       rest
       (map #(html/select % [:td]))
       (map #(map text-or-url %))
       (map #(zipmap [::time ::friendly-time ::volcano ::area ::advisory-no
                      ::vaa-text-url ::va-graphic-url ::va-initial-url
                      ::va-forecast-url ::satellite-img-url] %))))

(s/def ::vaa-list-item (s/keys :req [::inst ::volcano ::area
                                     ::advisory-no ::vaa-text-url
                                     ::va-graphic-url
                                     ::va-initial-url ::va-forecast-url
                                     ::satellite-img-url]))

(defn sorted-by-date? [vaa-list]
  (= vaa-list (sort-by ::inst vaa-list)))

(s/def ::raw-vaa-list (s/and (s/coll-of ::raw-vaa-map
                                        :kind sequential?
                                        :distinct true)
                             sorted-by-date?))

(s/def ::prepared-sakurajima-vaa-list (s/coll-of ::vaa-list-item
                                                 :kind sequential?
                                                 :distinct true))

(s/fdef prepared-sakurajima-vaa-list
  :args (s/cat :raw-vaas ::raw-vaa-list)
  :ret ::prepared-sakurajima-vaa-list
  :fn (s/and
        (fn [{{raw-vaas :raw-vaas} :args ret :ret}]
          (= (count raw-vaas) (count ret)))
        (fn [{{raw-vaas :raw-vaas} :args ret :ret}]
          (every? identity
                  (map #(= (::advisory-no %1) (::advisory-no %2))
                       raw-vaas ret)))))


(defn prepared-sakurajima-vaa-list [raw-vaas]
  (->> raw-vaas
       (filter #(re-find #"(?i)sakurajima" (::volcano %)))
       (map #(assoc % ::inst (instant (::time %))))
       (map #(dissoc % ::time ::friendly-time))))

(s/fdef sakurajima-vaa-text
  :args (s/cat :text-nodes (s/coll-of ::node))
  :ret string?)

(defn sakurajima-vaa-text [text-nodes]
  (-> text-nodes
      (html/select [:div.mtx])
      first    ; Take found node out of one-element list.
      :content ; Extract strings interleaved with :br nodes.
      rest     ; Drop leading whitespace string.
      butlast  ; Leave out trailing whitespace string.
      (as-> str-br-list (filter string? str-br-list))
      (as-> str-list (string/join \newline str-list))))


;;;; Public interface

(s/fdef get-sakurajima-vaa-list
  :args empty?
  :ret ::prepared-sakurajima-vaa-list)

(defn get-sakurajima-vaa-list []
  (-> vaa-list-url
      fetch-url
      raw-vaa-list
      (as-> the-list (s/assert ::raw-vaa-list the-list))
      prepared-sakurajima-vaa-list))

(s/fdef get-sakurajima-vaa-text
  :args (s/cat :vaa-text-url ::gs/url)
  :ret string?)

(defn get-sakurajima-vaa-text [vaa-text-url]
  (-> vaa-text-url
      fetch-url
      sakurajima-vaa-text))
