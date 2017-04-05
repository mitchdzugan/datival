(ns datival.cljs
  (:require [clojure.data :as data]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [datival.events]
            [datival.subs]
            [datival.views :as views]
            [datival.config :as config]
            [datascript.core :as d]
            [posh.reagent :as posh]
            [reagent.ratom :as r]
            [clojure.spec :as s]
            [cljs.reader :refer [read-string]]
            [bidi.bidi :as bidi]
            [accountant.core :as accountant]
            )
  (:import goog.history.Html5History))

(defonce history (Html5History.))

(def tempid-atom (atom -1))
(defn tempid []
  (let [retval @tempid-atom]
    (swap! tempid-atom dec)
    retval))

(defn handle-transaction [conn datoms]
  (let [path-cache (atom {})]
    (letfn [(lift [x] [x])
            (reduce-pull-path [acc curr] (assoc {} curr [acc]))
            (pull-path
              [eid-root path]
              (let [sel (->> path rest reverse (reduce reduce-pull-path :db/id) lift)]
                (d/pull (d/db conn) sel eid-root)))
            (use-path
              [datom]
              (if-let [path (:db/path datom)]
                (if-let [id (get @path-cache path)]
                  [(-> datom (dissoc :db/path) (assoc :db/id id))]
                  (let [eid-root (first path)
                        pull-res (pull-path eid-root path)
                        eid (or (get-in pull-res (concat (rest path) [:db/id]))
                                (tempid))
                        datoms (conj
                                (if-not pull-res
                                  (let [path-init (-> path reverse rest reverse)
                                        path-last (-> path reverse first)]
                                    (if (> (count path-init) 1)
                                      (use-path {:db/path path-init
                                                 path-last eid})
                                      [{:db/id (first path-init)
                                        path-last eid}])))
                                (-> datom (assoc :db/id eid) (dissoc :db/path)))]
                    (swap! path-cache assoc path (-> datoms last :db/id))
                    datoms))
                [datom]))
            (get-retract-ids
              [[curr & path] res]
              (if curr
                (let [datoms (mapcat #(get-retract-ids path %)
                                     (flatten [(get res curr)]))]
                  (if (and (:db/id res)
                           (= [] datoms))
                    [{:db/id (:db/id res)
                      :attribute curr}]
                    datoms))
                (if (:db/id res)
                  [{:db/id (:db/id res)
                    :attribute nil}]
                  [])))
            (use-retract-path
              [datom]
              (if-let [path (:db/retract-path datom)]
                (let [eid-root (first path)
                      reduce-pull-path #(assoc {} %2 [%1])
                      retract-ids (->> path
                                       butlast
                                       (pull-path eid-root)
                                       (or (pull-path eid-root path))
                                       (get-retract-ids (rest path)))]
                  (map (fn [{:keys [db/id attribute]}]
                         (if attribute
                           [:db.fn/retractAttribute id attribute]
                           [:db.fn/retractEntity id])) retract-ids))
                [datom]))]
      (let [new-datoms (->> datoms
                            (mapcat use-path)
                            (mapcat use-retract-path))]
        (d/transact! conn new-datoms)))))

(defn transact!
  ([debug? conn datoms] (transact! debug? :no-tag conn datoms))
  ([debug? tag conn datoms]
   (let [log (js/console.log.bind js/console)
         log-group (if (.-group js/console)         ;; console.group does not exist  < IE 11
                     (js/console.group.bind js/console)
                     (js/console.log.bind   js/console))
         log-group-end (if (.-groupEnd js/console)        ;; console.groupEnd does not exist  < IE 11
                         (js/console.groupEnd.bind js/console)
                         #())
         orig-db (if (and debug?
                          (->> [:db/role :anchor]
                               (d/entity (d/db conn))
                               :db/id))
                   (d/pull (d/db conn) '[*] [:db/role :anchor]))
         ]
     (handle-transaction conn datoms)
     (let [[only-before only-after] (if debug? (data/diff orig-db (d/pull (d/db conn) '[*] [:db/role :anchor])))
           db-changed? (or (some? only-before) (some? only-after))]
       (if db-changed?
         (do (log-group "datascript diff for:" tag)
             (log "only before:" only-before)
             (log "only after :" only-after)
             (log-group-end))
         (log "no datascript changes caused by:" tag))))))

(defn make-schema
  [schema]
  (->> (concat (map #(-> [% {:db/unique :db.unique/identity
                             :db/index true}]) (:ident schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/isComponent true}]) (:single-ref schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many
                             :db/isComponent true}]) (:many-ref schema)))
       (into {})))

(defn add-id-to-pull
  [sel]
  (->> sel
       (map #(if (map? %)
               (->> %
                    (map (fn [[k v]] [k (add-id-to-pull v)]))
                    (into {}))
               %))
       (#(conj % :db/id))
       (into #{})
       (into [])))

(defn pull-res-to-datoms
  [{:keys [db/id] :as res}]
  (->> res
       (filter (fn [[k v]] (not= :db/id k)))
       (reduce (fn [datoms [k v]]
                 (into [] (concat datoms
                                  (cond
                                    (and
                                     (map? v)
                                     (contains? v :db/id))
                                    (conj
                                     (pull-res-to-datoms v)
                                     {:db/id id
                                      k (:db/id v)})
                                    (and
                                     (sequential? v)
                                     (every? #(and
                                               (map? %)
                                               (contains? % :db/id)) v))
                                    (concat (mapcat pull-res-to-datoms v)
                                            (map #(-> {:db/id id
                                                       k (:db/id %)}) v))
                                    :else [{:db/id id
                                            k v}]))))
               [])
       (into [])))

(defn process-pull-datoms-res [res]
  (->> [res]
       (mapcat pull-res-to-datoms)
       (into #{})
       (into  [])))

(defn pull-datoms
  [pull-many db selector eids]
  (->> [eids]
       flatten
       (pull-many db (add-id-to-pull selector))
       process-pull-datoms-res))

(defn pull-datoms-reaction
  [conn selector eid]
  (let [pull-ratom (posh/pull conn (add-id-to-pull selector) eid)]
    (r/reaction (->> @pull-ratom process-pull-datoms-res))))

(defn sync-local-storage
  ([conn selector storage-key]
   (sync-local-storage conn selector [:db/role :anchor] storage-key))
  ([conn selector eid storage-key]
   (let [root-id (-> conn d/db (d/pull [:db/id] [:db/role :anchor]) :db/id)]
     (try (let [datoms (->> storage-key (.getItem js/localStorage) read-string)]
            (if (s/valid? (s/coll-of (fn [{:keys [db/id]}] (or (int? id)
                                                               (= id [:db/role :anchor])))) datoms)
              (transact! true :storage-init conn datoms)))
          (catch js/Object e (println e)))
     (let [dratoms (pull-datoms-reaction conn selector eid)]
       (r/run! (->> @dratoms
                    (map (fn [{:keys [db/id] :as datom}]
                           (if (= id root-id)
                             (merge datom {:db/id [:db/role :anchor]})
                             datom)))
                    (.setItem js/localStorage storage-key))))
     (.addEventListener
      js/window
      "storage"
      #(if (=
            storage-key
            (.-key %))
         (transact! true :storage-listener conn (-> % .-newValue read-string)))))))

(defn set-up-db [schema]
  (let [conn (-> schema
                 (update :ident #(conj % :db/role))
                 (update :ident #(conj % :route/title))
                 (update :single-ref #(conj % :route/child))
                 (update :single-ref #(conj % :route/dependency))
                 make-schema
                 d/create-conn)]
    (posh/posh! conn)
    (transact! true :initialize-db conn [{:db/id (tempid) :db/role :anchor}])
    conn))

(defn add-dependecy
  ([c t r] (add-dependecy c t r nil))
  ([conn title routes dependency]
   (let [child-id (tempid)]
     (transact! true [:add-child-router
                      title
                      dependency
                      [routes]]
                conn [(->> {:db/id child-id
                            :route/title title
                            :route/struct routes
                            :route/dependency dependency}
                           (remove (comp nil? last))
                           (into {}))]))))

(defn get-nav-datoms
  ([conn path] (get-nav-datoms {} conn [:route/title :lead] path))
  ([rp conn eid path]
   (let [{:keys [route/struct route/dependency]}
         (d/pull (d/db conn) '[:route/struct
                               {:route/dependency [:db/id]}
                               ] eid)
         m-struct ["" struct]
         {handler :handler
          {rest :rest :as route-params} :route-params}
         (if path (bidi/match-route m-struct path) nil)]
     (if path
       (concat
        (if handler
          (get-nav-datoms (or (dissoc route-params :rest)
                              {}) conn [:route/title handler] rest)
          [:dne])
        [{:db/id (:db/id dependency)
          :route/child [:route/title (last eid)]}
         {:db/id eid
          :route/params rp}])
       [{:db/id (:db/id dependency)
         :route/child [:route/title (last eid)]}
        {:db/id eid
         :route/params rp}]))))

(defn make-route-structures
  ([routes] (make-route-structures nil :lead routes))
  ([dep title routes]
   (let [curr-routes (->> routes
                          (map (fn [[k v]]
                                 (if (sequential? v)
                                   [(if (sequential? k)
                                      (concat k [[#".*" :rest]])
                                      [k [#".*" :rest]])
                                    (first v)]
                                   [k v])))
                          (into {})
                          (#(-> {:title title :struct % :dependency dep})))]
     (let [other-routes
           (->> routes
                (filter (fn [[_ v]] (sequential? v)))
                (mapcat (fn [[_ [new-title routes]]]
                          (make-route-structures [:route/title title] new-title routes)))
                (concat (->> routes
                             (remove (fn [[_ v]] (sequential? v)))
                             (map (fn [[_ new-title]]
                                    {:title new-title
                                     :dependency [:route/title title]})))))]
       curr-routes
       (conj other-routes curr-routes)))))

(defn set-up-routing
  [conn routes]
  (transact! true [:setup-routing-error] conn [{:db/id (tempid)
                                                :route/title :error}])
  (doseq [{:keys [dependency title struct]} (make-route-structures routes)]
    (add-dependecy conn title struct dependency))
  (accountant/configure-navigation!
   {:nav-handler (fn [path]
                   (let [path (if (= (subs (.getToken history) 0 2) "/#")
                                (subs (.getToken history) 2)
                                (.getToken history))
                         nav-datoms (get-nav-datoms conn path)]
                     (if (every? #(not= % :dne) nav-datoms)
                       (transact! true :routing-path conn nav-datoms)
                       (transact! true :no-route conn [{:db/id [:route/title :lead]
                                                        :route/child [:route/title :error]}]))))
    :path-exists? (fn [path]
                    (every? #(not= % :dne) (get-nav-datoms conn (subs path 2)))
                    )})
  (accountant/dispatch-current!)
  )
