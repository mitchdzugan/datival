(ns datival.example.example
  (:require [reagent.core :as reagent]
            [datascript.core :as d]
            [datival.core :as dv]
            [datival.example.views :as views]
            [datival.example.config :as config]
            [datival.example.conn :as c]))

(defn do-stuff []
  )

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode ")))

(defn mount-root []
  (let [dispatch (dv/make-event-system true [dv/dispatch-system dv/ajax-system
                                             (dv/datascript-system {:sync-local-storage {:selector [{:root/routing [:handler]}]
                                                                                         :key "datoms"}} c/conn)
                                             (dv/route-system {"/" {"index.html" :index
                                                                    "articles/" ^:article {"index.html" :index}}}
                                                              {:make-set-route-event-res dv/datascript-set-route-event-res})
                                             {:events {:event1 {:sources [:source1]
                                                                          :body (fn [state args] {:state (merge state args)
                                                                                                  :sink1 :sink-value})}}
                                                        :sources {:source1 (fn [_] :source-value)}
                                                        :sinks {:sink1 (fn [dispatch args] (println args) [1 2])}}])]
    (dispatch :event1 {:my-args :event-args}))
  (reagent/render [:div
                   [views/sub-panel]]
                  (.getElementById js/document "app")))

(defn ^:export init []
  (dev-setup)
  (mount-root))
