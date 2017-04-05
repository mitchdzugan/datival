(ns datival.core
  (:require [reagent.core :as reagent]
            [datival.views :as views]
            [datival.config :as config]
            [datival.cljs :refer :all]
            )
  )
(def schema
  {:ident      [:user/name
                :comment/id
                :thread/id
                :markdown/hash
                :peer/glob-id]
   :single-ref [:root/auth
                :auth/current-user
                :root/polling
                :root/routing
                :root/render
                :comment/markdown]
   :many-ref   [:thread/peers
                :auth/users
                :polling/threads
                :thread/top-level-comments
                :comment/children]})

(defn do-stuff []
  (let [t! (partial transact! true)
        conn (set-up-db schema)]
    (sync-local-storage conn [{:root/auth [:db/id]}] "datoms")
    (t! :set-124 conn [{:db/path [[:db/role :anchor] :root/auth :auth/current-user]
                        :current-user/name 124
                        :current-user/swag 9001}])
    (t! :retract-test conn [{:db/retract-path [[:db/role :anchor] :root/auth :auth/current-user :current-user/swag]}])
    (set-up-routing conn [["/" [:root
                                {"index.html" :index
                                 "articles/" [:article-r
                                              {"index.html" :article-index
                                               [:id "/article.html"] :article}]}]]
                          ["asd" :asd]])
    (reagent/render [:a [views/main-panel conn]]
                    (.getElementById js/document "app"))))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn mount-root [] (do-stuff))

(defn ^:export init []
  (dev-setup)
  (mount-root))
