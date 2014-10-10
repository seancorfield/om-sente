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

#_(enable-console-print!)

;; create the Sente web socket connection stuff when we are loaded:

(let [{:keys [chsk ch-recv send-fn state]}
      (s/make-channel-socket! "/ws" {:type :auto})]
  (def chsk       chsk)
  (def ch-chsk    ch-recv)
  (def chsk-send! send-fn)
  (def chsk-state chsk-state))

(defn field-change
  "Generic input field updater. Keeps state in sync with input."
  [e owner field]
  (let [value (.. e -target -value)]
    (om/set-state! owner field value)))

(defn send-text-on-enter
  "When user presses ENTER, send the value of the field to the server
  and clear the field's input state."
  [e owner state]
  (let [kc (.-keyCode e)
        w (.-which e)]
    (when (or (== kc 13) (== w 13))
      (chsk-send! [:test/echo (:text state)])
      (om/set-state! owner :text ""))))

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
(def graph-scale 3)
(def graph-bar-width 30)
(def graph-height 400)
(def bar-gap 1)

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
                    (html [:div {:style {:height (+ 100 graph-height)}}
                           [:p (str "The string '" s "' represented as a bar chart:")]
                           (into [:svg {:id "display" :width "100%" :height graph-height}]
                                 (map (fn [v1 v2 o]
                                        (let [h (* graph-scale v1)]
                                          [:rect {:fill (make-color (max v1 v2))
                                                  :width graph-bar-width
                                                  :height h
                                                  :x (* (+ bar-gap graph-bar-width) o)
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
  #_(println "UNKNOWN EVENT" event))

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
  (go (loop [[op arg] (:event (<! ch-chsk))]
        #_(println "-" op)
        (case op
          :chsk/recv (handle-event arg app owner)
          ;; we ignore other Sente events
          (test-session owner))
        (recur (:event (<! ch-chsk))))))

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

(defn line-graph
  "Example from http://www.janwillemtulp.com/2011/04/01/tutorial-line-chart-in-d3/"
  [raw-data]
  (let [h 380 w 480 m 30
        d (clj->js raw-data)
        y-scale (.. js/d3 -scale linear
                    (domain #js [(apply max raw-data) 0])
                    (range #js [(+ 0 m) (- h m)]))
        x-scale (.. js/d3 -scale linear
                    (domain #js [0 (count raw-data)])
                    (range #js [(+ 0 m) (- w m)]))
        svg (.. js/d3 (select "#d3-node") (append "svg")
                (attr #js {:width w :height h}))
        g (.. svg (append "g"))
        l (.. js/d3 -svg line (x (fn [d i] (x-scale i)))
              (y (fn [d] (y-scale d))))]
    ;; actual line
    (.. g (append "path") (attr "d" (l d)))
    ;; x-axis
    (.. g (append "line") (attr "x1" (x-scale 0))
        (attr "y1" (y-scale 0))
        (attr "x2" (x-scale (count raw-data)))
        (attr "y2" (y-scale 0)))
    ;; y-axis
    (.. g (append "line") (attr "x1" (x-scale 0))
        (attr "y1" (y-scale 0))
        (attr "x2" (x-scale 0))
        (attr "y2" (y-scale (apply max raw-data))))
    ;; x-label
    (.. g (selectAll ".xLabel") (data (.ticks x-scale 5))
        enter (append "text")
        (attr "class" "xLabel")
        (text js/String)
        (attr "x" (fn [d] (x-scale d)))
        (attr "y" (- h 5))
        (attr "text-anchor" "middle"))
    ;; y-label
    (.. g (selectAll ".yLabel") (data (.ticks y-scale 4))
        enter (append "text")
        (attr "class" "yLabel")
        (text js/String)
        (attr "x" (/ m 1.5))
        (attr "y" (fn [d] (y-scale d)))
        (attr "text-anchor" "end"))
    ;; x-ticks
    (.. g (selectAll "xTicks") (data (.ticks x-scale 5))
        enter (append "line")
        (attr "class" "xTicks")
        (attr "x1" (fn [d] (x-scale d)))
        (attr "y1" (y-scale 0))
        (attr "x2" (fn [d] (x-scale d)))
        (attr "y2" (+ (y-scale 0) 5)))
    ;; y-ticks
    (.. g (selectAll "yTicks") (data (.ticks y-scale 4))
        enter (append "line")
        (attr "class" "yTicks")
        (attr "x1" (- (x-scale 0) 5))
        (attr "y1" (fn [d] (y-scale d)))
        (attr "x2" (x-scale 0))
        (attr "y2" (fn [d] (y-scale d))))))

(defn graph-data-changing
  [old new]
  (not= (:data/text old) (:data/text new)))

(defn d3-test
  "Component that tests D3 / Om / React integration."
  [app owner]
  (reify
    om/IDidMount
    (did-mount [this]
               (line-graph (vec (make-target (:data/text app)))))
    om/IDidUpdate
    (did-update [this prev-props prev-state]
                (when (graph-data-changing prev-props app)
                  (.remove (.-firstChild (om/get-node owner "d3-node")))
                  (line-graph (vec (make-target (:data/text app))))))
    om/IRender
    (render [this]
            (dom/div #js {:style #js {:height 400 :float "right" :width "50%"}
                          :react-key "d3-node" ;; ensure React knows this is non-reusable
                          :ref "d3-node"       ;; label it so we can retrieve it via get-node
                          :id "d3-node"}))))   ;; set id so D3 can find it!

(defn nv-line-graph
  "Draw a graph of the supplied data using NVD3."
  [raw-data]
  (let [chart (.. js/nv -models lineChart
                  (margin #js {:left 100})
                  (useInteractiveGuideline true)
                  (transitionDuration 350)
                  (showLegend true)
                  (showYAxis true)
                  (showXAxis true))]
    (.. chart -xAxis (axisLabel "Character") (tickFormat (.format js/d3 ",r")))
    (.. chart -yAxis (axisLabel "ASCII") (tickFormat (.format js/d3 ",r")))
    (.. js/d3 (select "#nv-node svg")
        (datum #js [
                    #js {:values (clj->js raw-data)
                         :key "Text Data"
                         :color "red"
                         }
                    ])
        (call chart))))

(defn nvd3-test
  "Component that tests NVD3 graph."
  [app owner]
  (reify
    om/IDidMount
    (did-mount [this]
               (nv-line-graph (mapv (fn [a b] {:y a :x b}) (make-target (:data/text app)) (range))))
    om/IDidUpdate
    (did-update [this prev-props prev-state]
                (when (graph-data-changing prev-props app)
                  ;; no need to remove the SVG node, we can just pour new data into it
                  (nv-line-graph (mapv (fn [a b] {:y a :x b}) (make-target (:data/text app)) (range)))))
    om/IRender
    (render [this]
            (dom/div #js {:style #js {:height 400 :float "left" :width "50%"}
                          :react-key "nv-node"
                          :id "nv-node"}
                     ;; add the SVG node once, NVD3 updates the data via transition
                     (dom/svg nil)))))

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
                   (om/build d3-test app {})
                   (om/build nvd3-test app {})
                   [:div {:style {:clear "both"}}]]))))

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

