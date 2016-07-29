(ns de.cloj.sakurajima.service.sources.record
  (:require [clojure.spec :as s]))

(defn sorted-by-inst? [record-list]
  (= record-list (sort-by inst record-list)))

(defn same-source? [record-list]
  (if-let [first-source (::source-id (first record-list))]
    (every? #(= first-source (::source-id %)))
    true))

(defmulti record-multispec ::source-id)
(defmulti record-details-multispec ::source-id)

(s/def ::record (s/multispec record-multispec ::source-id))
(s/def ::record-list (s/and (s/coll-of ::record
                                       :kind sequential?
                                       :distinct true)
                            sorted-by-inst?
                            same-source?))
(s/def ::record-details (s/multispec record-details-multispec ::source-id))

(s/fdef
  :args (s/cat :source-id ::source-id)
  :ret ::record-list)
(defmulti get-list (fn [source-id] source-id))

(s/fdef
  :args (s/cat :record ::record)
  :ret inst?)
(defmulti inst ::source-id)

(s/fdef
  :args (s/cat :record (s/merge ::record-details ::record))
  :ret ::record)
(defmulti add-details ::source-id)

