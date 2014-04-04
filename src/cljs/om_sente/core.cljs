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

(def app-state (atom {:text "Hello world!"}))

(defn send-field [e text]
  (when (== (.-keyCode e) 13)
    (chsk-send! [:test/echo text])))

(defn field-view [app owner]
  (reify
    om/IInitState
    (init-state [this]
      {:text ""})
    om/IRenderState
    (render-state [this {:keys [text]}]
      (dom/input #js {:ref "data" :type "text" :value (om/value text)
                      :onChange #(om/set-state! owner :text
                                                (.. % -target -value))
                      :onKeyPress #(send-field % text)}))))

(defn data-view [app owner]
  (reify
    om/IInitState
    (init-state [this]
      {:text "none"})
    om/IWillMount
    (will-mount [this]
      (go (loop []
            (let [[op & args :as event] (<! ch-chsk)]
              (case op
                :chsk/recv
                (let [[ev-id & payload] (first args)]
                  (case ev-id
                    :test/reply (om/set-state! owner :text (first payload))
                    nil))
                nil))
            (recur))))
    om/IRenderState
    (render-state [this {:keys [text]}]
      (dom/div nil text))))

(defn app-view [app owner]
  (dom/div nil
           (dom/h1 nil "Test Sente")
           (om/build field-view app {})
           (om/build data-view app {})))

(om/root app-view
         app-state
         {:target (. js/document (getElementById "app"))})

