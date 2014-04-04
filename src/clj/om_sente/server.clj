(ns om-sente.server
  (:require [clojure.core.async :as async
             :refer [<! <!! chan go]]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.handler :as h]
            [compojure.route :as r]
            [org.httpkit.server :as kit]
            [taoensso.sente :as s]))

(let [{:keys [ch-recv send-fn ajax-post-fn
              ajax-get-ws-fn]}
      (s/make-channel-socket! {})]
  (def ring-ajax-post   ajax-post-fn)
  (def ring-ajax-get-ws ajax-get-ws-fn)
  (def ch-chsk          ch-recv)
  (def chsk-send!       send-fn))

(defroutes server
  (GET  "/"   req (slurp "index.html"))
  (GET  "/ws" req (#'ring-ajax-get-ws req))
  (POST "/ws" req (#'ring-ajax-post   req))
  (r/files "" :root "")
  (r/not-found "<p>Page not found. I has a sad!</p>"))

(defn handle-reply
  "Process callback replies."
  [reply]
  nil)

(defn handle-error
  "Process callback errors."
  [reply]
  nil)

(defn handle-data
  "Main event processor."
  [[event & args :as data]]
  ;; right now we just echo back anything we receive
  (case event
    :echo (chsk-send! data 3000
                      (fn [reply]
                        (if (s/cb-success? reply)
                          (handle-reply reply)
                          (handle-error reply))))))

(defn -main [& args]
  (go (loop [data (<! ch-chsk)]
        (handle-data data)
        (recur (<! ch-chsk))))
  (let [port (or (System/getenv "PORT") 8444)]
    (kit/run-server (h/site #'server) {:port port})))
