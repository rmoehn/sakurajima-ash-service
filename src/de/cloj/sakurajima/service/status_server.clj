(ns de.cloj.sakurajima.service.status-server
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec :as s]
            [de.cloj.sakurajima.service.global-specs :as gs]
            [de.cloj.sakurajima.service.status-server.request :as request])
  (:import java.io.File
           java.time.Instant))

;;;; Print method for Instants

;; Credits: http://blog.jenkster.com/2014/02/using-joda-time-as-your-clojure-inst-class.html
(defmethod print-method java.time.Instant
  [inst writer]
  (.write writer (format "#de.cloj.sakurajima/inst \"%s\"" inst)))


;;;; Helpers

(defn read-status-file [file]
  (->> file
       slurp
       (edn/read-string
         {:readers {'de.cloj.sakurajima/inst #(Instant/parse %)}})
       (s/assert ::status-map)))

;; Credits:
;;  - https://github.com/clojure-cookbook/clojure-cookbook/blob/first-edition/04_local-io/4-10_using-temp-files.asciidoc
;;  - https://github.com/clojure-cookbook/clojure-cookbook/blob/first-edition/04_local-io/4-14_read-write-clojure-data-structures.asciidoc
;;  - https://github.com/clojure-cookbook/clojure-cookbook/blob/first-edition/04_local-io/4-05_copy-file.asciidoc
;;  - https://github.com/clojure-cookbook/clojure-cookbook/blob/first-edition/04_local-io/4-06_delete-file.asciidoc
(defn safely-write [file data]
  (let [temp-file (File/createTempFile "sas-status" "edn")]
    (spit temp-file (prn-str data))
    (io/copy temp-file file)
    (io/delete-file temp-file)))


;;;; Processing of requests

(defmulti request-type ::request/type)
(defmethod request-type ::request/read [_]
  (s/keys :req [::request/type ::request/source-id]))
(defmethod request-type ::request/write [_]
  (s/keys :req [::request/type ::request/source-id
                ::request/newest-record-inst]))

(s/def ::request/request (s/multi-spec request-type ::request/type))

(s/def ::status-file #(instance? File %))
(s/def ::config (s/keys :req-un [::status-file]))

(s/def ::status-map (s/map-of ::gs/nsq-keyword inst?))

(s/fdef process
  :args (s/cat :config ::config
               :request (s/multi-spec request-type ::request/type)))

(defmulti process (fn [_ request] (::request/type request)))

(defmethod process ::request/read [{status-file :status-file}
                                   {source-id ::request/source-id}]
  (if (.exists status-file)
    (-> status-file
        read-status-file
        (get source-id))
    nil)) ; Just to be explicit.

(defmethod process ::request/write
  [{status-file :status-file}
   {source-id ::request/source-id
    newest-record-inst ::request/newest-record-inst}]
  (-> (if (.exists status-file)
        (read-status-file status-file)
        {})
      (assoc source-id newest-record-inst)
      (as-> new-status-map (safely-write status-file new-status-map)))
  ::ok)


;;;; Public interface

(s/fdef start
  :args (s/cat :config ::config :request-ch ::gs/chan))
(defn start [config request-ch]
  (async/go-loop []
    (when-let [[request response-ch] (async/<! request-ch)]
      (if-let [res (process config request)]
        (async/>! response-ch res)
        (async/close! response-ch))
      (recur))))
