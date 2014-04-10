;; copyright (c) 2014 Sean Corfield
;;
;; small demo to show Om / Sente playing together
;;
;; no claim is made of best practices - feedback welcome

(ns om-sente.server
  (:require [clojure.core.async :as async
             :refer [<! <!! chan go]]
            [compojure.core :refer [defroutes GET POST routes]]
            [compojure.handler :as h]
            [compojure.route :as r]
            [org.httpkit.server :as kit]
            [taoensso.sente :as s]))

;; create the Sente web socket connection stuff when we are loaded:

(let [{:keys [ch-recv send-fn ajax-post-fn
              ajax-get-or-ws-handshake-fn] :as sente-info}
      (s/make-channel-socket! {})]
  (def ring-ajax-post   ajax-post-fn)
  (def ring-ajax-get-ws ajax-get-or-ws-handshake-fn)
  (def ch-chsk          ch-recv)
  (def chsk-send!       send-fn))

(defn root
  "Return the absolute (root-relative) version of the given path."
  [path]
  (str (System/getProperty "user.dir") path))

(defn unique-id
  "Return a really unique ID (for an unsecured session ID).
  No, a random number is not unique enough. Use a UUID for real!"
  []
  (rand-int 10000))

(defn session-uid
  "Convenient to extract the UID that Sente needs from the request."
  [req]
  (get-in req [:session :uid]))

(defn index
  "Handle index page request. Injects session uid if needed."
  [req]
  {:status 200
   :session (if (session-uid req)
              (:session req)
              (assoc (:session req) :uid (unique-id)))
   :body (slurp "index.html")})

;; minimal set of routes to handle:
;; - home page request
;; - web socket GET/POST
;; - general files (mainly JS)
;; - 404

(defroutes server
  (-> (routes
       (GET  "/"   req (#'index req))
       (GET  "/qw" req (#'ring-ajax-get-ws req))
       (POST "/qw" req (#'ring-ajax-post   req))
       (r/files "/" {:root (root "")})
       (r/not-found "<p>Page not found. I has a sad!</p>"))
      h/site))

(defmulti handle-event
  "Handle events based on the event ID."
  (fn [[ev-id ev-arg] ring-req] ev-id))

;; Reply with the session state - either open or secure.
;; Note: this doesn't work yet since :token is never added to the session!

(defmethod handle-event :session/status
  [_ req]
  (chsk-send! (session-uid req)
              [:session/state (if (get-in req [:session :token])
                                :secure
                                :open)]))

;; Reply with authentication failure or success.
;; This should update the session with :token but Sente doesn't allow session updates.

(defmethod handle-event :session/auth
  [[_ [username password]] req]
  (let [valid (and (= "admin" username)
                   (= "secret" password))]
    (chsk-send! (session-uid req)
                [(if valid :auth/success :auth/fail)])))

;; Reply with the message in angle brackets.

(defmethod handle-event :test/echo
  [[_ msg] req]
  (chsk-send! (session-uid req) [:test/reply (str "<" msg ">")]))

;; Handle unknown events.
;; Note: this includes the Sente implementation events like:
;; - :chsk/ping
;; - :chsk/uidport-open
;; - :chsk/uidport-close

(defmethod handle-event :default
  [event req]
  nil)

(defn event-loop
  "Handle inbound events."
  []
  (go (loop [{:keys [client-uuid ring-req event] :as data} (<! ch-chsk)]
        (handle-event event ring-req)
        (recur (<! ch-chsk)))))

(defn -main
  "Start the http-kit server. Takes no arguments.
  Environment variable PORT can override default port of 8444."
  [& args]
  (event-loop)
  (let [port (or (System/getenv "PORT") 8444)]
    (println "Starting Sente server on port" port "...")
    (kit/run-server #'server {:port port})))
