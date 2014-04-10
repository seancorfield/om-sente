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

(defn unique-id
  "Return a really unique ID (for an unsecured session ID)."
  []
  (rand-int 10000))

(defn index
  "Handle index page request. Injects session uid if needed."
  [req]
  {:status 200
   :session (if (get-in req [:session :uid])
              (:session req)
              (assoc (:session req) :uid (unique-id)))
   :body (slurp "index.html")})

(defroutes server
  (-> (routes
       (GET  "/"   req (#'index req))
       (GET  "/qw" req (#'ring-ajax-get-ws req))
       (POST "/qw" req (#'ring-ajax-post   req))
       (r/files "/" {:root (root "")})
       (r/not-found "<p>Page not found. I has a sad!</p>"))
      h/site))

(defmulti handle-event (fn [event ring-req] (first event)))

(defmethod handle-event :test/echo
  [[_ msg] req]
  (chsk-send! (get-in req [:session :uid]) [:test/reply (str "<" msg ">")]))

(defmethod handle-event :default
  [_ req]
  nil)

;; :session/status -> :session/state :open / :secure
;; :session/auth username password -> :auth/fail or :auth/success
;; (updates session uid etc)

(defmethod handle-event :session/status
  [_ req]
  (chsk-send! (get-in req [:session :uid])
              [:session/state (if (get-in req [:session :token])
                                :secure
                                :open)]))

(defmethod handle-event :session/auth
  [[_ username password] req]
  (chsk-send! (get-in req [:session :uid])
              [(if (and (= "admin" username)
                        (= "secret" password))
                 :auth/succcess
                 :auth/fail)]))

(defn handle-data
  "Main event processor."
  [{:keys [client-uuid ring-req event] :as data}]
  (println data)
  (handle-event event ring-req))

(defn -main [& args]
  (println "starting -main")
  (go (loop []
        (println "waiting for data...")
        (handle-data (<! ch-chsk))
        (recur)))
  (println "about to start server")
  (let [port (or (System/getenv "PORT") 8444)]
    (kit/run-server #'server {:port port})))

(println "server loaded")
;; (-main)
