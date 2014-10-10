;; copyright (c) 2014 Sean Corfield
;;
;; small demo to show Om / Sente playing together
;;
;; no claim is made of best practices - feedback welcome

(ns om-sente.server
  (:require [clojure.core.async :as async
             :refer [<! <!! chan go thread]]
            [clojure.core.cache :as cache]
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

;; session cache to maintain authentication - so we can rely
;; entirely on socket communication instead of needing to login
;; to the application first: 5 minutes of inactive will log you out

(def session-map (atom (cache/ttl-cache-factory {} :ttl (* 5 60 1000))))

(defn keep-alive
  "Given a UID, keep it alive."
  [uid]
  (println "keep-alive" uid (java.util.Date.))
  (when-let [token (get @session-map uid)]
    (swap! session-map assoc uid token)))

(defn add-token
  "Given a UID and a token, remember it."
  [uid token]
  (println "add-token" uid token (java.util.Date.))
  (swap! session-map assoc uid token))

(defn get-token
  "Given a UID, retrieve the associated token, if any."
  [uid]
  (let [token (get @session-map uid)]
    (println "get-token" uid token (java.util.Date.))
    token))

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
       (GET  "/ws" req (#'ring-ajax-get-ws req))
       (POST "/ws" req (#'ring-ajax-post   req))
       (r/files "/" {:root (root "")})
       (r/not-found "<p>Page not found. I has a sad!</p>"))
      h/site))

(defmulti handle-event
  "Handle events based on the event ID."
  (fn [[ev-id ev-arg] ring-req] ev-id))

(defn session-status
  "Tell the server what state this user's session is in."
  [req]
  (when-let [uid (session-uid req)]
    (chsk-send! uid [:session/state (if (get-token uid) :secure :open)])))

;; Reply with the session state - either open or secure.

(defmethod handle-event :session/status
  [_ req]
  (session-status req))

;; Reply with authentication failure or success.
;; For a successful authentication, remember the login.

(defmethod handle-event :session/auth
  [[_ [username password]] req]
  (when-let [uid (session-uid req)]
    (let [valid (and (= "admin" username)
                     (= "secret" password))]
      (when valid
        (add-token uid (unique-id)))
      (chsk-send! uid [(if valid :auth/success :auth/fail)]))))

;; Reply with the same message, followed by the reverse of the message a few seconds later.
;; Also record activity to keep session alive.

(defmethod handle-event :test/echo
  [[_ msg] req]
  (when-let [uid (session-uid req)]
    (keep-alive uid)
    (chsk-send! uid [:test/reply msg])
    (Thread/sleep 3000)
    (chsk-send! uid [:test/reply (clojure.string/reverse msg)])))

;; When the client pings us, send back the session state:

(defmethod handle-event :chsk/ws-ping
  [_ req]
  (session-status req))

;; Handle unknown events.
;; Note: this includes the Sente implementation events like:
;; - :chsk/uidport-open
;; - :chsk/uidport-close

(defmethod handle-event :default
  [event req]
  nil)

(defn event-loop
  "Handle inbound events."
  []
  (go (loop [{:keys [client-uuid ring-req event] :as data} (<! ch-chsk)]
        (println "-" event)
        (thread (handle-event event ring-req))
        (recur (<! ch-chsk)))))

(defn -main
  "Start the http-kit server. Takes no arguments.
  Environment variable PORT can override default port of 8444."
  [& args]
  (event-loop)
  (let [port (or (System/getenv "PORT") 8444)]
    (println "Starting Sente server on port" port "...")
    (kit/run-server #'server {:port port})))
