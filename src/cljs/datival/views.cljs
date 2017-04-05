(ns datival.views
  (:require [datival.conn :as conn]
            [datival.cljs :as dv]))

(def sub-panel
  (dv/make-ui conn/conn
              [{:root/auth [{:auth/current-user [:user/name]}]}]
              {:render (fn [[res]] [:p (str res)])}))

(def main-panel
  (dv/make-ui conn/conn
              [{:route/child [:db/id :route/title :route/params {:route/child [:db/id]}]}]
              {:id (fn [[id]] id)
               :render (fn [[{{:keys [db/id route/title route/params route/child]} :route/child}]]
                         [:div
                          [:p (str title " : " params)]
                          (if (:db/id child)
                            [main-panel id])])}))
