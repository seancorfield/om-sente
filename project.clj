(defproject om-sente "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2356"] ;; 2199
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.cache "0.6.4"]
                 [om "0.7.3"]
                 [sablono "0.2.22"]
                 [com.taoensso/sente "1.2.0"]
                 [http-kit "2.1.19"]
                 [compojure "1.2.0"]
                 [ring/ring-core "1.3.1"]
                 [jetty/javax.servlet "5.1.12"]]

  :plugins [[lein-cljsbuild "1.0.3"]] ;; 1.0.3

  :source-paths ["src/clj"]

  :cljsbuild { 
    :builds [{:id "om-sente"
              :source-paths ["src/cljs"]
              :compiler {
                :output-to "om_sente.js"
                :output-dir "out"
                :optimizations :none
                :source-map true}}]})
