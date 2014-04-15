;; copyright (c) 2014 Sean Corfield
;;
;; small demo to show Om / Sente playing together
;;
;; no claim is made of best practices - feedback welcome

(ns om-sente.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [sablono.core :as html :refer-macros [html]]
            [taoensso.sente :as s]
            [cljs.core.async :as async :refer [<! >! chan]]))

(enable-console-print!)

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

(def text-length 32)

(defn text-sender
  "Component that displays a text field and sends it to the server when ENTER is pressed."
  [app owner]
  (reify
    om/IInitState
    (init-state [this]
                {:text ""})
    om/IRenderState
    (render-state [this state]
                  (html [:input {:type "text" :value (:text state) :size text-length :max-length text-length
                                 :on-change #(field-change % owner :text)
                                 :on-key-press #(send-text-on-enter % owner state)}]))))

(defn make-color
  "Given a value (height) make a color for it. Returns #hex string."
  [v]
  (let [r v
        g (Math/round (- 150 (/ v 3)))
        b (Math/round (/ v 2))
        hex (fn [n] (.substring (str (when (< n 16) "0") (.toString n 16)) 0 2))]
    (str "#" (hex r) (hex g) (hex b))))

(defn make-target
  "Turn a string into a sequence of its characters' ASCII values."
  [s]
  (take text-length (concat (map #(.charCodeAt %) s) (repeat 0))))

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
                                       next-data   (or (om/get-state owner :data) (vec (take text-length (repeat 0))))]
                                   (om/set-state! owner :data
                                                  (mapv (fn [d y]
                                                          (if (< d y)
                                                            (min y (+ (/ y animation-factor) d))
                                                            (max y (- d (max animation-factor (/ d animation-factor))))))
                                                        next-data
                                                        target-data))
                                   (js/setTimeout tick animation-tick)))
                               animation-tick))
    om/IRenderState
    (render-state [this {:keys [data]}]
                  (let [s (:data/text app)
                        t (make-target s)]
                    (html [:div
                           [:p (str "The string '" s "' represented as a bar chart:")]
                           (into [:svg {:id "display" :width "100%" :height graph-height}]
                                 (map (fn [v1 v2 o]
                                        (let [h (* graph-scale v1)]
                                          [:rect {:fill (make-color (max v1 v2))
                                                  :width graph-bar-width
                                                  :height h
                                                  :x (* (inc graph-bar-width) o)
                                                  :y (- graph-height h)}]))
                                      data
                                      t
                                      (range)))])))))

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
                  (html [:div {:style {:margin "auto" :width "175"
                                       :border "solid blue 1px" :padding 20}}
                         (when-let [error (:notify/error app)]
                           [:div {:style #js {:color "red"}} error])
                         [:h1 "Login"]
                         [:form {:on-submit #(attempt-login % app owner)}
                          [:div
                           [:p "Username"]
                           [:input {:ref "username" :type "text" :value (:username state)
                                    :on-change #(field-change % owner :username)}]]
                          [:div
                           [:p "Password"]
                           [:input {:ref "password" :type "password" :value (:password state)
                                    :on-change #(field-change % owner :password)}]]
                          [:div
                           [:input {:type "submit" :value "Login"}]]]]))))

(defn d3-test
  "Component that tests D3 / Om / React integration."
  [app owner]
  (reify
    om/IInitState
    (init-state [this]
                (println "d3 init")
                {:data (map vector (range 100) (repeatedly 100 #(rand-int 100)))})
    om/IDidMount
    (did-mount [this]
               (println "d3 did-mount")
               (let [svg (-> js/d3 (.select "#d3-node") (.append "svg")
                             (.attr #js {:width 960 :height 500}))]
                 (-> svg (.append "circle")
                     (.attr #js {:cx 350 :cy 200 :r 200 :class "left"}))
                 (-> svg (.append "circle")
                     (.attr #js {:cx 550 :cy 200 :r 200 :class "right"}))
                 (-> svg (.append "circle")
                     (.attr #js {:cx 450 :cy 300 :r 200 :class "bottom"}))))
    om/IRender
    (render [this]
            (println "d3 render state")
            (dom/div #js {:react-key "d3-test-graph" :id "d3-node"}))
))

(defn secured-application
  "Component that represents the secured portion of our application."
  [app owner]
  (reify
    om/IRender
    (render [this]
            (html [:div {:style {:margin "auto" :width "1000"
                                 :border "solid blue 1px" :padding 20}}
                   [:h1 "Test Sente"]
                   (om/build text-sender app {})
                   (om/build animated-bar-graph app {})
                   (om/build d3-test app {})]))))

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

