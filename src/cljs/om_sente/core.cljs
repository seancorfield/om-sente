(ns om-sente.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [taoensso.sente :as s]
            [cljs.core.async :as async :refer [<! >! chan]]))

(enable-console-print!)

(let [{:keys [chsk ch-recv send-fn]}
      (s/make-channel-socket! "/ws" {} {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn))

(def app-state (atom {:text "Hello world!"}))

(om/root (fn [app owner]
           (dom/h1 nil (:text app)))
         app-state
         {:target (. js/document (getElementById "app"))})

