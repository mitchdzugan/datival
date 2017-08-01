(ns datival.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.data :as data]
            [datascript.core :as d]
            [posh.reagent :as posh]
            [reagent.ratom :as r]
            [clojure.spec :as s]
            [cljs.reader :refer [read-string]]
            [bidi.bidi :as bidi]
            [pushy.core :as pushy]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [ajax.core :as ajax]
            [goog.net.ErrorCode :as errors]))

(defn leaves
  ([m] (leaves m []))
  ([m l] (if (map? m) (reduce (fn [l [k v]] (concat l (leaves v))) l m) [m])))

(defn map-leaves
  [f m]
  (if (map? m) (->> m (map (fn [[k v]] [k (map-leaves f v)])) (into {})) (f m)))

(defn make-ui [conn query funcs]
  (let [funcs (merge {:id (fn [_] [:db/role :anchor])
                      :setup (fn [_] nil)}
                     funcs)]
    (fn [& args]
      ((:setup funcs) args)
      (fn [& args]
        (let [res @(posh/pull conn query ((:id funcs) args))]
          ((:render funcs) (conj args res)))))))

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



(def log (js/console.log.bind js/console))
(def log-group (if (.-group js/console)         ;; console.group does not exist  < IE 11
                  (js/console.group.bind js/console)
                  (js/console.log.bind   js/console)))
(def log-group-end (if (.-groupEnd js/console)        ;; console.groupEnd does not exist  < IE 11
                      (js/console.groupEnd.bind js/console)
                      #()))

(defn transact!
  [debug? conn datoms]
  (let [orig-db (if (and debug?
                         (->> [:db/role :anchor]
                              (d/entity (d/db conn))
                              :db/id))
                  (d/pull (d/db conn)

                          '[*]
                          [:db/role :anchor]))]
    (handle-transaction conn datoms)
    (if debug? [orig-db (d/pull (d/db conn) '[*] [:db/role :anchor])])))

(defn make-schema
  [schema]
  (->> (concat (map #(-> [% {:db/unique :db.unique/identity
                             :db/index true}]) (:ident schema))
               (map #(-> [% {:db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/one
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
              (transact! true conn datoms)))
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
         (transact! true conn (-> % .-newValue read-string)))))))

(defn set-up-db [schema]
  (let [c (-> schema
                 (update :ident #(conj % :db/role))
                 (update :ident #(conj % :route/title))
                 (update :single-ref #(conj % :route/child))
                 (update :single-ref #(conj % :route/dependency))
                 make-schema
                 d/create-conn)]
    (posh/posh! c)
    (transact! true c [{:db/id (tempid) :db/role :anchor}])
    c))

(defn add-dependecy
  ([c t r] (add-dependecy c t r nil))
  ([conn title routes dependency]
   (let [child-id (tempid)]
     (transact! true
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
  (transact! true conn [{:db/id (tempid)
                                                :route/title :error}])
  (doseq [{:keys [dependency title struct]} (make-route-structures routes)]
    (add-dependecy conn title struct dependency))
  (pushy/start! (pushy/pushy
                 (partial transact! true conn)
                 (fn [_]
                   (let [path (-> js/window .-location .-hash (subs 1))
                         nav-datoms (get-nav-datoms conn path)]
                     (if (every? #(not= % :dne) nav-datoms)
                       nav-datoms
                       [{:db/id [:route/title :lead]
                         :route/child [:route/title :error]}]))))))

(defn deep-merge [m1 m2]
  (merge-with
   (fn [v1 v2]
     (cond (every? map? [v1 v2]) (deep-merge v1 v2)
           (every? coll? [v1 v2]) (concat v1 v2)
           :else v2)) m1 m2))

(defn log-diffs [tag s1 s2]
  (let [[only-before only-after] (data/diff s1 s2)
        changed? (or (some? only-before) (some? only-after))]
    (if changed?
      (do (log-group "sink diff for subsystem:" tag)
          (log "only before:" only-before)
          (log "only-after:" only-after)
          (log-group-end))
      (log "no sink change for subsystem:" tag))))


(defn ajax-xhrio-handler
  "ajax-request only provides a single handler for success and errors"
  [on-success on-failure xhrio [success? response]]
  ; see http://docs.closure-library.googlecode.com/git/class_goog_net_XhrIo.html
  (if success?
    (on-success response)
    (let [details (merge
                    {:uri             (.getLastUri xhrio)
                     :last-method     (.-lastMethod_ xhrio)
                     :last-error      (.getLastError xhrio)
                     :last-error-code (.getLastErrorCode xhrio)
                     :debug-message   (-> xhrio .getLastErrorCode (errors/getDebugMessage))}
                    response)]
      (on-failure details))))


(defn request->xhrio-options
  [dispatch
   {:as   request
    :keys [on-success on-failure]
    :or   {on-success      [:http-no-on-success]
           on-failure      [:http-no-on-failure]}}]
  ; wrap events in cljs-ajax callback
  (let [api (new js/goog.net.XhrIo)]
    (-> request
        (assoc
          :api     api
          :handler (partial ajax-xhrio-handler
                            #(dispatch (conj on-success %))
                            #(dispatch (conj on-failure %))
                            api))
        (dissoc :on-success :on-failure))))

(def ajax-system
  {:sinks {:ajax (fn [dispatch request]
                   (let [seq-request-maps (if (sequential? request) request [request])]
                     (doseq [request seq-request-maps]
                       (-> request (request->xhrio-options dispatch) ajax/ajax-request))))}})

(defn datascript-system
  [debug? conn]
  {:sources {:datascript (fn [_] @conn)}
   :sinks {:datascript (fn [dispatch datoms] (transact! debug? conn datoms))}})

(def dispatch-system
  {:sinks {:dispatch (fn [dispatch [event args]] (dispatch event args))
           :dispatch-after (fn [dispatch [ms event args]] (js/setTimeout #(dispatch event args) ms))}})

(defn route-builder [routes path]
  (if (coll? routes)
    (->> routes
         (map (fn [[k v]]
                (if-let [new-step (-> v meta keys first)]
                  [k (route-builder v (concat path [new-step]))]
                  [k (route-builder v path)])))
         (into {}))
    [routes path]))

(defn route-system
  [routes]
  (let [routes (route-builder routes [])
        leaves (leaves routes)
        leaf-to-sym (fn [[leaf path]] (symbol (str leaf (reduce str "" (map str path)))))
        leaves (->> leaves (map (fn [leaf] [(leaf-to-sym leaf) leaf])) (into {}))
        routes (map-leaves leaf-to-sym routes)
        routes (mapcat identity routes)]
    {:events {:setup-routing {:body (fn [state args] {:start-pushy routes})}
              :set-route {:body (fn [state bidi-res]
                                  {:state (deep-merge state
                                                      {:_routing {:current-route
                                                                  (merge bidi-res {:handler
                                                                                   (get leaves (:handler bidi-res))})}})})}}
     :sinks {:start-pushy (fn [dispatch routes]
                            (pushy/start! (pushy/pushy (partial dispatch :set-route)
                                                       (fn [_] (let [path (-> js/window .-location .-hash (subs 1))]
                                                                 (bidi/match-route routes path))))))}
     :initial-dispatching [[:setup-routing]]}))


(defn make-event-system
  [debug? configs]
  (let [c (chan)
        initial-state {:_config (reduce deep-merge
                                        (if debug? {:debug []} {})
                                        (->> configs
                                             (map (fn [config] (-> config
                                                                   (dissoc :initial-dispatching)
                                                                   (update :events #(->> %
                                                                                         (map (fn [[k v]] [k (merge {:sources []} v)]))
                                                                                         (into {}))))))))}
        dispatch (fn [event args] (go (>! c [event args])))]

    (defn process-event
      [state event-tag args]
      (let [event (get-in state [:_config :events event-tag])
            all-sources (get-in state [:_config :sources])
            all-sinks (get-in state [:_config :sinks])
            args (merge args (into {} (map #(-> [% ((% all-sources) args)]) (:sources event))))
            result (merge {:state state} ((:body event) state args))
            ]
        [(:state result) (->> (dissoc result :state)
                              (map (fn [[sink args]] [sink ((sink all-sinks) dispatch args)])))]))

    (defn process-event-wrapper
      [state event-tag args]
      (let [[new-state sink-results] (process-event state event-tag args)]
        (if debug? (do
                     (log-group "Event subsystem  changes for event:" event-tag args)
                     (log-diffs :state state new-state)
                     (doseq [[sink [s1 s2]] sink-results]
                       (if (or s1 s2) (log-diffs sink s1 s2)))
                     (log-group-end)))
        new-state))

    (let [post-initial-dispatching-state (->> configs
                                              (mapcat :initial-dispatching)
                                              (reduce (fn [state [event-tag args]]
                                                        (process-event-wrapper state event-tag args))
                                                      initial-state))]
      (go-loop [state post-initial-dispatching-state]
        (let [[event-tag args] (<! c)] (recur (process-event-wrapper state event-tag args)))))
    dispatch))
