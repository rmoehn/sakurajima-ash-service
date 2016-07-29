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
    (every? #(= first-source (::source-id %)))
    true))


;;;; Repeatedly needed specs

(defmulti record-multispec ::source-id)
(defmethod record-multispec :default [x]
  (throw (IllegalArgumentException. ("No implementation for " x))))
(defmulti record-details-multispec ::source-id)
(defmethod record-details-multispec :default [x]
  (throw (IllegalArgumentException. ("No implementation for " x))))

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
(defmethod get-list :default [source-id]
  (throw (IllegalArgumentException. (str "No implementation for " source-id))))

;(s/fdef inst
;  :args (s/cat :record ::record)
;  :ret inst?)
(defmulti inst ::source-id)
(defmethod inst :default [record]
  (throw (IllegalArgumentException. (str "No implementation for " record))))

(s/fdef add-details
  :args ::record
  :ret (s/cat :record (s/merge ::record-details ::record)))
(defmulti add-details ::source-id)
(defmethod add-details :default [record]
  (throw (IllegalArgumentException. (str "No implementation for " record))))
