;; copyright (c) 2014 Sean Corfield
;;
;; small demo to show Om / Sente playing together
;;
;; no claim is made of best practices - feedback welcome

(ns om-sente.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [taoensso.sente :as s]
            [cljs.core.async :as async :refer [<! >! chan]]))

(enable-console-print!)

;; create the Sente web socket connection stuff when we are loaded:

(let [{:keys [chsk ch-recv send-fn]}
      (s/make-channel-socket! "/qw" {} {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))

(defn field-change
  "Generic input field updater. Keeps state in sync with input."
  [e owner field]
  (let [value (.. e -target -value)]
    (om/set-state! owner field value)))

(defn send-text-on-enter
  "When user presses ENTER, send the reversed value of the field to the server
  and clear the field's input state."
  [e owner state]
  (when (== (.-keyCode e) 13)
    (chsk-send! [:test/echo (clojure.string/reverse (:text state))])
    (om/set-state! owner :text "")))

(defn text-sender
  "Component that displays a text field and sends it to the server when ENTER is pressed."
  [app owner]
  (reify
    om/IInitState
    (init-state [this]
                {:text ""})
    om/IRenderState
    (render-state [this state]
                  (dom/input #js {:type "text" :value (:text state)
                                  :onChange #(field-change % owner :text)
                                  :onKeyPress #(send-text-on-enter % owner state)}))))

(defn data-view
  "Component that displays the data (text) returned by the server after processing."
  [app owner]
  (reify
    om/IRender
    (render [this]
            (dom/div nil (:data/text app)))))

(defmulti handle-event
  "Handle events based on the event ID."
  (fn [[ev-id ev-arg] app owner] ev-id))

;; Process the server's reply by updating the application state:

(defmethod handle-event :test/reply
  [[_ msg] app owner]
  (om/update! app :data/text msg))

;; Ignore unknown events (we just print to the console):

(defmethod handle-event :default
  [event app owner]
  (println "UNKNOWN EVENT" event))

;; Remember the session state in the application component's local state:

(defmethod handle-event :session/state
  [[_ state] app owner]
  (om/set-state! owner :session/state state))

;; Handle authentication failure (we just set an error message for display):

(defmethod handle-event :auth/fail
  [_ app owner]
  (om/update! app [:notify/error] "Invalid credentials"))

;; Handle authentication success (clear the error message; update application session state):

(defmethod handle-event :auth/success
  [_ app owner]
  (om/update! app [:notify/error] nil)
  (om/set-state! owner :session/state :secure))

(defn test-session
  "If we don't know the session state, ask the server for it."
  [owner]
  (when (= :unknown (om/get-state owner :session/state))
    (chsk-send! [:session/status])))

(defn event-loop
  "Handle inbound events."
  [app owner]
  (go (loop [[op arg] (<! ch-chsk)]
        (case op
          :chsk/recv (handle-event arg app owner)
          ;; we ignore other Sente events
          nil)
        (test-session owner)
        (recur (<! ch-chsk)))))

(defn attempt-login
  "Handle the login event - send credentials to the server."
  [e app owner]
  (let [username (-> (om/get-node owner "username") .-value)
        password (-> (om/get-node owner "password") .-value)]
    (chsk-send! [:session/auth [username password]]))
  ;; suppress the form submit:
  false)

(defn login-form
  "Component that provides a login form and submits credentials to the server."
  [app owner]
  (reify
    om/IInitState
    (init-state [this]
                {:username "" :password ""})
    om/IRenderState
    (render-state [this state]
                  (dom/div #js {:style {:align "center" :width "25%"}}
                           (if-let [error (:notify/error app)]
                             (dom/div #js {:style {:color "red"}}
                                      error))
                           (dom/h1 nil "Login")
                           (dom/form #js {:onSubmit #(attempt-login % app owner)}
                                     (dom/div #js {:style {:width "100%"}}
                                              (dom/p nil "Username")
                                              (dom/input #js {:ref "username" :type "text" :value (:username state)
                                                              :onChange #(field-change % owner :username)}))
                                     (dom/div #js {:style {:width "100%"}}
                                              (dom/p nil "Password")
                                              (dom/input #js {:ref "password" :type "password" :value (:password state)
                                                              :onChange #(field-change % owner :password)}))
                                     (dom/div #js {:style {:width "50%" :align "center"}}
                                              (dom/input #js {:type "submit" :value "Login"})))))))

(defn secured-application
  "Component that represents the secured portion of our application."
  [app owner]
  (reify
    om/IRender
    (render [this]
            (dom/div nil
                     (dom/h1 nil "Test Sente")
                     (om/build text-sender app {})
                     (om/build data-view app {})))))

(defn application
  "Component that represents our application. Maintains session state.
  Selects views based on session state."
  [app owner]
  (reify
    om/IInitState
    (init-state [this]
                {:session/state :unknown})
    om/IWillMount
    (will-mount [this]
                (event-loop app owner))
    om/IRenderState
    (render-state [this state]
                  (dom/div #js {:style {:width "100%"}}
                           (case (:session/state state)
                             :secure
                             (om/build secured-application app {})
                             :open
                             (om/build login-form app {})
                             :unknown
                             (dom/div nil "Loading..."))))))

(def app-state
  "Our very minimal application state - a piece of text that we display."
  (atom {:data/text "none"}))

(om/root application
         app-state
         {:target (. js/document (getElementById "app"))})

