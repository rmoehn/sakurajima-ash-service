(ns de.cloj.sakurajima.service.sources.vaac
  "VAAC implementation of the interface for obtaining records."
  (:require [clojure.spec :as s]
            [de.cloj.sakurajima.service.sources.record :as record]
            [de.cloj.sakurajima.service.access.vaac :as vaac-access]))

(defmethod record/record-multispec :de.cloj.sakurajima.servier.source/vaac [_]
  (s/merge ::vaac-access/vaac-list (s/merge (s/keys :req [::record/source-id])
                                            ::vaac-access/vaa-list-item)))

(defmethod record/get-list :de.cloj.sakurajima.service.source/vaac [_]
  (map #(assoc % ::record/source-id :de.cloj.sakurajima.service.source/vaac)
       (vaac-access/get-sakurajima-vaa-list)))

(defmethod record/inst :de.cloj.sakurajima.service.source/vaac [vaa-list-item]
  (::vaac-access/inst vaa-list-item))

(defmethod record/record-details-multispec
  :de.cloj.sakurajima.servier.source/vaac
  [_]
  (s/keys :req [::vaac-access/vaa-text]))

(defmethod record/add-details :de.cloj.sakurajima.service.source/vaac
  [_ vaa-list-item]
  (assoc vaa-list-item ::vaac-access/vaa-text
         (vaac-access/get-sakurajima-vaa-text
           (::vaac-access/vaa-text-url vaa-list-item))))
