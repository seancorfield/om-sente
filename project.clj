(defproject om-sente "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2173"] ;; 2199
                 [org.clojure/core.async "0.1.278.0-76b25b-alpha"]
                 [org.clojure/core.cache "0.6.3"]
                 [om "0.5.3"]
                 [com.taoensso/sente "0.9.0"]
                 [http-kit "2.1.18"]
                 [compojure "1.1.6"]
                 [ring/ring-core "1.2.2"]
                 [jetty/javax.servlet "5.1.12"]]

  :plugins [[lein-cljsbuild "1.0.2"]] ;; 1.0.3

  :source-paths ["src/clj"]

  :cljsbuild { 
    :builds [{:id "om-sente"
              :source-paths ["src/cljs"]
              :compiler {
                :output-to "om_sente.js"
                :output-dir "out"
                :optimizations :none
                :source-map true}}]})
