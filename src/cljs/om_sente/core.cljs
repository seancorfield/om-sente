(ns om-sente.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [taoensso.sente :as s]
            [cljs.core.async :as async :refer [<! >! chan]]))

(enable-console-print!)

(let [{:keys [chsk ch-recv send-fn]}
      (s/make-channel-socket! "/qw" {} {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))

(defn send-field [e owner state]
  (when (== (.-keyCode e) 13)
    (chsk-send! [:test/echo (clojure.string/reverse (:text state))])
    (om/set-state! owner :text "")))

(defn field-change [e owner state]
  (let [value (.. e -target -value)]
    (om/set-state! owner :text value)))

(defn field-view [app owner opts]
  (reify
    om/IInitState
    (init-state [this]
      {:text ""})
    om/IRenderState
    (render-state [this state]
      (dom/input #js {:ref (:name opts) :type (or (:type opts) "text") :value (:text state)
                      :onChange #(field-change % owner state)
                      :onKeyPress #(send-field % owner state)}))))

(defmulti handle-event (fn [event app owner] (first event)))

(defmethod handle-event :test/reply
  [[_ msg] app owner]
  (om/set-state! owner :text msg))

(defmethod handle-event :default
  [_ app owner]
  nil)

(defmethod handle-event :session/state
  [[_ state] app owner]
  (js/alert (str "session/state: " (name state)))
  (om/set-state! owner :session/state state))

(defmethod handle-event :auth/fail
  [_ app owner]
  (om/update! app [:notify/error] "Invalid credentials"))

(defmethod handle-event :auth/success
  [_ app owner]
  (om/update! app [:notify/error] nil)
  (om/set-state! owner :session/state :secure))

(defn event-loop [app owner]
  (go (loop []
        (let [[op arg] (<! ch-chsk)]
          (case op
            :chsk/recv (handle-event arg app owner)
            nil))
        (recur))))

(defn data-view [app owner]
  (reify
    om/IInitState
    (init-state [this]
      {:text "none"})
    om/IRenderState
    (render-state [this {:keys [text]}]
      (dom/div nil text))))

(defn login [e app owner]
  nil)

(defn login-view [app owner]
  (reify
    om/IRender
    (render [this]
            (dom/div #js {:style {:align "center" :width "25%"}}
                     (if-let [error (:notify/error app)]
                       (dom/div #js {:style "color: red;"}
                                error))
                     (dom/h1 nil "Login")
                     (dom/div #js {:style {:width "100%"}}
                             (dom/p nil "Username")
                             (om/build field-view app {:opts {:name "username"}}))
                     (dom/div #js {:style {:width "100%"}}
                             (dom/p nil "Password")
                             (om/build field-view app {:opts {:name "password"
                                                              :type "password"}}))
                     (dom/div #js {:style {:width "50%" :align "center"}}
                             (dom/button #js {:onClick #(login % app owner)}
                                        "Login"))))))

(defn test-session [owner]
  (when (= :unknown (om/get-state owner :session/state))
    (js/alert "will-mount send session/status query")
    (chsk-send! [:session/status])))

(defn app-view [app owner]
  (reify
    om/IInitState
    (init-state [this]
      {:session/state :unknown})
    om/IWillMount
    (will-mount [this]
      (event-loop app owner))
    om/IDidMount
    (did-mount [this]
      (test-session owner))
    om/IRenderState
    (render-state [this state]
      (case (:session/state state)
        :secure
        (dom/div nil
                 (dom/h1 nil "Test Sente")
                 (om/build field-view app {:opts {:name "data"}})
                 (om/build data-view app {}))
        :open
        (om/build login-view app {})
        :unknown
        (dom/div nil "Loading...")
        ;;(om/build login-view app {})
        ))))

(def app-state (atom {}))

(om/root app-view
         app-state
         {:target (. js/document (getElementById "app"))})

