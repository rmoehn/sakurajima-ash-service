(defproject sakurajima-ash-service "0.1.0-SNAPSHOT"
  :description "Pushes ash and eruption notices for volcano Sakurajima to various endpoints"
  :url "https://github.com/rmoehn/sakurajima-ash-service"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha10"]
                 [org.clojure/core.async "0.2.385"]
                 [beckon "0.1.1"]
                 [cheshire "5.6.3"]
                 [com.taoensso/timbre "4.7.0"]
                 [clj-http "2.2.0"]
                 [com.stuartsierra/component "0.3.1"]
                 [diehard "0.3.0"]
                 [enlive "1.1.6"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["dev"]}
             :uberjar {:main de.cloj.sakurajima.service.core
                       :aot [de.cloj.sakurajima.service.core]}}

  :jar-name "sakurajima-ash-service-%s-slim.jar"
  :uberjar-name "sakurajima-ash-service-%s.jar")
