(ns datival.core
  (:require [reagent.core :as reagent]
            [datival.views :as views]
            [datival.config :as config]
            [datascript.core :as d]
            [datival.cljs :as dv]
            [datival.conn :as c]
            )
  )

(defn do-stuff []
  (let [t! (partial dv/transact! true)
        conn c/conn]
    (dv/sync-local-storage conn [{:root/auth [:db/id]}] "datoms")
    (t! :set-124 conn [{:db/path [[:db/role :anchor] :root/auth :auth/current-user]
                        :user/name 124
                        :user/swag 9001}])
    (t! :retract-test conn [{:db/retract-path [[:db/role :anchor] :root/auth :auth/current-user :current-user/swag]}])
    (dv/set-up-routing conn [["/" [:root
                                {"index.html" :index
                                 "articles/" [:article-r
                                              {"index.html" :article-index
                                               [:id "/article.html"] :article}]}]]
                             ["asd" :asd]])
    (reagent/render [:div
                     [views/main-panel [:route/title :lead]]
                     [views/sub-panel]]
                    (.getElementById js/document "app"))))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root [] (do-stuff))

(defn ^:export init []
  (dev-setup)
  (mount-root))
