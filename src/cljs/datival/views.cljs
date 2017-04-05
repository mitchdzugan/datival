(ns datival.views
  (:require [posh.reagent :as p]))

(defn main-panel [conn]
  (fn [conn]
    (let [route @(p/pull conn '[:db/id
                               :route/title
                               :route/params
                               {:route/child ...}]
                         [:route/title :lead])]
      [:div (str (:route/child route))])))
