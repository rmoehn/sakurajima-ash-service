(ns de.cloj.sakurajima.service.access.vaac
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [net.cgrand.enlive-html :as html])
  (:import [java.time Instant LocalDateTime ZoneId]
           java.time.format.DateTimeFormatter))

;;;; Constants

(def vaa-list-url "https://ds.data.jma.go.jp/svd/vaac/data/vaac_list.html")


;;;; General helper

; Credits: https://github.com/swannodette/enlive-tutorial/
(defn fetch-url [url]
  (html/html-resource (io/as-url url)))


;;;; Tokyo VAAC-specific helpers

(defn instant [timestamp]
  (let [formatter (DateTimeFormatter/ofPattern "yyy/MM/dd HH:mm:ss")]
    (-> timestamp
        (LocalDateTime/parse formatter)
        (.atZone (ZoneId/of "UTC"))
        Instant/from)))

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


;;;; Main scraping and transforming code

; Credits: Clojure Data Analysis Cookbook, page 23
(defn raw-vaa-list [list-resource]
  (->> (html/select list-resource [:tr.mtx])
       rest
       (map #(html/select % [:td]))
       (map #(map text-or-url %))
       (map #(zipmap [::time ::friendly-time ::volcano ::area ::advisory-no
                      ::vaa-text-url ::va-graphic-url ::va-initial-url
                      ::va-forecast-url ::satellite-img-url] %))))

(defn prepared-sakurajima-vaa-list [raw-vaas]
  (->> raw-vaas
       (filter #(re-find #"(?i)sakurajima" (::volcano %)))
       (map #(assoc % ::inst (instant (::time %))))
       (map #(dissoc % ::time ::friendly-time))))

(defn sakurajima-vaa-text [text-resource]
  (-> text-resource
      (html/select [:div.mtx])
      first    ; Take found node out of one-element list.
      :content ; Extract strings interleaved with :br nodes.
      rest     ; Drop leading whitespace string.
      butlast  ; Leave out trailing whitespace string.
      (as-> str-br-list (filter string? str-br-list))
      (as-> str-list (string/join \newline str-list))))


;;;; Public interface

(defn get-sakurajima-vaa-list []
  (-> vaa-list-url
      fetch-url
      raw-vaa-list
      prepared-sakurajima-vaa-list))

(defn get-sakurajima-vaa-text [vaa-text-url]
  (-> vaa-text-url
      fetch-url
      sakurajima-vaa-text))
