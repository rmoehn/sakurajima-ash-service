(defproject sakurajima-ash-service "0.1.0-SNAPSHOT"
  :description "Pushes ash and eruption notices for volcano Sakurajima to various endpoints"
  :url "https://github.com/rmoehn/sakurajima-ash-service"
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.9.0-rc9"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]]
                   :source-paths ["dev"]}})
