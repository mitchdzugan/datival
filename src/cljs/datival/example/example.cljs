(ns datival.example.example
  (:require [reagent.core :as reagent]
            [ajax.core :as ajax]
            [datascript.core :as d]
            [datival.core :as dv]
            [datival.example.views :as views]
            [datival.example.config :as config]
            [datival.example.conn :as c]
            ))

(enable-console-print!)
(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode ")))

(def counter (atom 0))

(def dispatch
  (dv/create-event-system [dv/init-dispatch
                           dv/init-ajax
                           (dv/init-datascript c/conn)
                           (dv/init-routing {"/" {"index.html" :index
                                                  "articles/" ^:article {"index.html" :index}}}
                                            {:make-set-route-event-res dv/datascript-set-route-event-res})
                           {:log {:stuff :xD}
                            :sources {:atom {:dependencies [:inc]
                                             :body (fn [{:keys [inc]}] (+ 1 inc))}
                                      :inc (fn [] @counter)}
                            :sinks {:log (fn [{:keys [args]}] (println args))}
                            :events {:ajax-fail {:sources {:ajax :dispatcher}
                                                 :body (fn [{:keys [ajax]}]
                                                         (println {:ajax-fail ajax}))}
                                     :ajax-succ {:sources {:ajax :dispatcher}
                                                 :body (fn [{:keys [ajax]}]
                                                         (println {:ajax-succ ajax}))}
                                     :ajax-test (fn []
                                                  {:ajax {:method :get
                                                          :response-format (ajax/raw-response-format)
                                                          :uri "http://localhost:3449"
                                                          :on-success [:ajax-succ]
                                                          :on-failure [:ajax-fail]}})
                                     :count {:sources {:atom nil}
                                             :body (fn [{:keys [atom]}]
                                                     {:log atom})}
                                     :spawn-log-state (fn [] {:dispatch [:log-state 1]
                                                              :dispatch-after [500 :log-state 5]})
                                     :log-state (fn [{:keys [args]}] {:log args})}}]))

(def ui (dv/create-ui-system
         [dv/ui-state
          (dv/ui-dispatch dispatch)
          (dv/ui-datascript c/conn)]))

(def test-view (ui (fn [a] {:state a})
                   (fn [{{:keys [val swap]} :state
                         a :args}]
                     [:button {:onClick #(swap inc)}
                      (str a " click me now " val)])))

(def dispatch-view (ui (fn [] {:dispatch [:log-state]})
                       (fn [{{:keys [log-state]} :dispatch}]
                         [:button {:onClick #(println (log-state "trollolol"))} "troll xD"])))

(def route-panel
  (ui (fn [] {:datascript {:query [{:root/routing [:handler]}]}})
      (fn [{:keys [datascript]}] [:p (str datascript)])))

(defn mount-root []
  (reagent/render [:div
                   [test-view 27]
                   [dispatch-view]
                   [route-panel]]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (dev-setup)
  (dispatch :ajax-test 1)
  (mount-root))

