(ns datival.example.example
  (:require [reagent.core :as reagent]
            [datascript.core :as d]
            [datival.core :as dv]
            [datival.example.views :as views]
            [datival.example.config :as config]
            [datival.example.conn :as c]))

(defn do-stuff []
  (let [t! (partial dv/transact! true)
        conn c/conn]
    (println [:dm (dv/deep-merge {:a [1 2] :b {:c :d} :g :h :j :k :l :m}
                                 {:a [3 4] :b {:e :f} :g :i :j [] :l {}})])
    (dv/sync-local-storage conn [{:root/auth [:db/id]}] "datoms")
    (t! conn [{:db/path [[:db/role :anchor] :root/auth :auth/current-user]
                        :user/name 124
                        :user/swag 9001}])
    (t! conn [{:db/retract-path [[:db/role :anchor] :root/auth :auth/current-user :current-user/swag]}])
    #_(dv/set-up-routing conn
                       [["/" [:root
                                {"index.html" :index
                                 "articles/" [:article-r
                                              {"index.html" :article-index
                                               [:id1 "/" :id2] :article}]}]]
                        ["asd" :asd]]
                       )
    (reagent/render [:div
                     [views/main-panel [:route/title :lead]]
                     [views/sub-panel]]
                    (.getElementById js/document "app"))))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root []
  (let [dispatch (dv/make-event-system true [(dv/route-system {"/" {"index.html" :index
                                                                    "articles/" ^:article {"index.html" :index}}})
                                             {:events {:event1 {:sources [:source1]
                                                                          :body (fn [state args] {:state (merge state args)
                                                                                                  :sink1 :sink-value})}}
                                                        :sources {:source1 (fn [_] :source-value)}
                                                        :sinks {:sink1 (fn [dispatch args] (println args) [1 2])}}])]
    (dispatch :event1 {:my-args :event-args}))
  (do-stuff)
  )

(defn ^:export init []
  (dev-setup)
  (mount-root))
