(ns om-sente.server
  (:require [clojure.core.async :as async
             :refer [<! <!! chan go]]
            [compojure.core :refer [defroutes GET POST routes]]
            [compojure.handler :as h]
            [compojure.route :as r]
            [org.httpkit.server :as kit]
            [taoensso.sente :as s]))

(let [{:keys [ch-recv send-fn ajax-post-fn
              ajax-get-or-ws-handshake-fn] :as sente-info}
      (s/make-channel-socket! {})]
  (println "SENTE:" sente-info)
  (def ring-ajax-post   ajax-post-fn)
  (def ring-ajax-get-ws ajax-get-or-ws-handshake-fn)
  (def ch-chsk          ch-recv)
  (def chsk-send!       send-fn)
  (println "defs in place"))

(defn root
  [path]
  (println "user.dir" (System/getProperty "user.dir"))
  (str (System/getProperty "user.dir") path))

(defroutes server
  (-> (routes
       (GET  "/"   req (slurp "index.html"))
       (GET  "/qw" req (#'ring-ajax-get-ws req))
       (POST "/qw" req (#'ring-ajax-post   req))
       (r/files "/" {:root (root "")})
       (r/not-found "<p>Page not found. I has a sad!</p>"))
      h/site))

(defn handle-data
  "Main event processor."
  [[event & args :as data]]
  ;; right now we just echo back anything we receive
  (println "handle-data" data)
  (case event
    :test/echo (chsk-send! "test" data)))

(defn -main [& args]
  (println "starting -main")
  (go (loop [data (<! ch-chsk)]
        (handle-data data)
        (recur (<! ch-chsk))))
  (println "about to start server")
  (let [port (or (System/getenv "PORT") 8444)]
    (kit/run-server #'server {:port port})))

(println "server loaded")
