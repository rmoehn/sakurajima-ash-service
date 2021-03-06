(ns de.cloj.sakurajima.service.sources.record
  "Interface for obtaining records"
  (:require [clojure.spec :as s]
            [de.cloj.sakurajima.service.global-specs]))

;;;; Some predicates

(declare inst)

(defn sorted-by-inst? [record-list]
  (= record-list (sort-by inst record-list)))

(defn same-source? [record-list]
  (if-let [first-source (::source-id (first record-list))]
    (every? #(= first-source (::source-id %)) record-list)
    true))


;;;; Repeatedly needed specs

(defmulti record-multispec ::source-id)
(defmulti record-details-multispec ::source-id)

(s/def ::source-id (s/and keyword?
                          #(= "de.cloj.sakurajima.service.source"
                              (namespace %))))
(s/def ::record (s/multi-spec record-multispec ::source-id))
(s/def ::record-list (s/and (s/coll-of ::record
                                       :kind sequential?
                                       :distinct true)
                            sorted-by-inst?
                            same-source?))
(s/def ::record-details (s/multi-spec record-details-multispec ::source-id))


;;;; The three interface multis

(s/fdef get-list
  :args (s/cat :source-id ::source-id)
  :ret ::record-list)
(defmulti get-list (fn [source-id] source-id))

(s/fdef inst
  :args (s/cat :record ::record)
  :ret inst?)
(defmulti inst ::source-id)

(s/fdef add-details
  :args (s/cat :record ::record)
  :ret (s/merge ::record-details ::record))
(defmulti add-details ::source-id)
