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
            [cljs.core.async :as async :refer [<! >! chan]]
            [strokes :refer [d3]]))

(enable-console-print!)

(strokes/bootstrap)

;; create the Sente web socket connection stuff when we are loaded:

(let [{:keys [chsk ch-recv send-fn]}
      (s/make-channel-socket! "/ws" {} {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))

(defn field-change
  "Generic input field updater. Keeps state in sync with input."
  [e owner field]
  (let [value (.. e -target -value)]
    (om/set-state! owner field value)))

(defn send-text-on-enter
  "When user presses ENTER, send the value of the field to the server
  and clear the field's input state."
  [e owner state]
  (when (== (.-keyCode e) 13)
    (chsk-send! [:test/echo (:text state)])
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
                  (dom/input #js {:type "text" :value (:text state) :size 32 :maxLength 32
                                  :onChange #(field-change % owner :text)
                                  :onKeyPress #(send-text-on-enter % owner state)}))))

(defn make-color
  "Given a value (height) make a color for it. Returns #hex string."
  [v]
  (let [r v
        g (int (- 150 (/ v 3)))
        b (int (/ v 2))
        hex (fn [n] (str (when (< n 16) "f") (.toString n 16)))]
    (str "#" (hex r) (hex g) (hex b))))

(defn make-target
  "Turn a string into a sequence of its characters' ASCII values."
  [s]
  (map #(.charCodeAt %) s))

(def animation-tick 75)
(def animation-factor 15)
(def graph-scale 4)
(def graph-bar-width 30)
(def graph-height 500)

(defn animated-bar-graph
  "Component that displays the data (text) returned by the server after processing."
  [app owner]
  (reify
    om/IWillMount
    (will-mount [this]
                (js/setTimeout (fn tick []
                                 (let [target-data (make-target (:data/text @app))
                                       cur-data    (or (om/get-state owner :data) [])
                                       next-data   (if (< (count cur-data) (count target-data))
                                                     (vec (take (count target-data) (concat cur-data (repeat 0))))
                                                     (vec (take (count target-data) cur-data)))]
                                   (om/set-state! owner :data
                                                  (mapv (fn [d y]
                                                          (if (< d y)
                                                            (min y (+ (/ y animation-factor) d))
                                                            (max y (- (/ y animation-factor) d))))
                                                        next-data
                                                        target-data))
                                   (js/setTimeout tick animation-tick)))
                               animation-tick))
    om/IRenderState
    (render-state [this {:keys [data]}]
                  (let [s (:data/text app)
                        t (make-target s)]
                    (dom/div nil
                             (dom/p nil (str "The string '" s "' represented as a bar chart:"))
                             (apply dom/svg #js {:id "display" :width "100%" :height graph-height}
                                    (map (fn [v1 v2 o]
                                           (let [h (* graph-scale v1)]
                                             (dom/rect #js {:fill (make-color v2)
                                                            :width graph-bar-width
                                                            :height h
                                                            :x (* (inc graph-bar-width) o)
                                                            :y (- graph-height h)})))
                                         data
                                         t
                                         (range))))))))

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
  (om/set-state! owner :session/state :secure))

(defn test-session
  "Ping the server to update the sesssion state."
  [owner]
  (chsk-send! [:session/status]))

(defn event-loop
  "Handle inbound events."
  [app owner]
  (go (loop [[op arg] (<! ch-chsk)]
        (println "-" op)
        (case op
          :chsk/recv (handle-event arg app owner)
          ;; we ignore other Sente events
          (test-session owner))
        (recur (<! ch-chsk)))))

(defn attempt-login
  "Handle the login event - send credentials to the server."
  [e app owner]
  (let [username (-> (om/get-node owner "username") .-value)
        password (-> (om/get-node owner "password") .-value)]
    (om/update! app [:notify/error] nil)
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
                  (dom/div #js {:style #js {:margin "auto" :width "175"
                                            :border "solid blue 1px" :padding 20}}
                           (if-let [error (:notify/error app)]
                             (dom/div #js {:style #js {:color "red"}}
                                      error))
                           (dom/h1 nil "Login")
                           (dom/form #js {:onSubmit #(attempt-login % app owner)}
                                     (dom/div nil
                                              (dom/p nil "Username")
                                              (dom/input #js {:ref "username" :type "text" :value (:username state)
                                                              :onChange #(field-change % owner :username)}))
                                     (dom/div nil
                                              (dom/p nil "Password")
                                              (dom/input #js {:ref "password" :type "password" :value (:password state)
                                                              :onChange #(field-change % owner :password)}))
                                     (dom/div nil
                                              (dom/input #js {:type "submit" :value "Login"})))))))

(defn secured-application
  "Component that represents the secured portion of our application."
  [app owner]
  (reify
    om/IRender
    (render [this]
            (dom/div #js {:style #js {:margin "auto" :width "1000"
                                      :border "solid blue 1px" :padding 20}}
                     (dom/h1 nil "Test Sente")
                     (om/build text-sender app {})
                     (om/build animated-bar-graph app {})))))

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
                  (dom/div #js {:style #js {:width "100%"}}
                           (case (:session/state state)
                             :secure
                             (om/build secured-application app {})
                             :open
                             (om/build login-form app {})
                             :unknown
                             (dom/div nil "Loading..."))))))

(def app-state
  "Our very minimal application state - a piece of text that we display."
  (atom {:data/text "Enter a string and press RETURN!"}))

(om/root application
         app-state
         {:target (. js/document (getElementById "app"))})

