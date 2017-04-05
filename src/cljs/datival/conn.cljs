(ns datival.conn (:require [datival.cljs :as dv]))

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

(def conn (dv/set-up-db schema))
