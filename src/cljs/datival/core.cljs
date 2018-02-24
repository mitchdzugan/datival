(ns datival.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.data :as data]
            [datascript.core :as d]
            [posh.reagent :as posh]
            [reagent.ratom :as r]
            [reagent.core :as reagent]
            [reagent.impl.component :as rutil]
            [cljs.reader :refer [read-string]]
            [bidi.bidi :as bidi]
            [pushy.core :as pushy]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [ajax.core :as ajax]
            [goog.net.ErrorCode :as errors]))

(def tempid-atom (atom -1))
(defn tempid []
  (let [retval @tempid-atom]
    (swap! tempid-atom dec)
    retval))

(defn leaves
  ([m] (leaves m []))
  ([m l] (if (map? m) (reduce (fn [l [k v]] (concat l (leaves v))) l m) [m])))

(defn map-leaves
  [f m]
  (if (map? m) (->> m (map (fn [[k v]] [k (map-leaves f v)])) (into {})) (f m)))

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
                                       (use-path {:db/path  path-init
                                                  path-last eid})
                                       [{:db/id    (first path-init)
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
                    [{:db/id     (:db/id res)
                      :attribute curr}]
                    datoms))
                (if (:db/id res)
                  [{:db/id     (:db/id res)
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
(def log-group (if (.-group js/console)
                 (js/console.group.bind js/console)
                 (js/console.log.bind js/console)))
(def log-group-end (if (.-groupEnd js/console)
                     (js/console.groupEnd.bind js/console)
                     #()))

(defn transact!
  [debug? conn datoms]
  (let [debug? false
        id (or (:id debug?) [:db/role :anchor])
        selector (or (:selector debug?) '[*])
        orig-db (if (and debug?
                         (->> id (d/entity (d/db conn)) :db/id))
                  (d/pull (d/db conn) selector id))]
    (handle-transaction conn datoms)
    (if debug? [orig-db (d/pull (d/db conn)
                                (or (:pull debug?) '[*])
                                (or (:id debug?) [:db/role :anchor]))])))

(defn make-schema
  [schema]
  (->> (concat (map #(-> [% {:db/unique :db.unique/identity
                             :db/index  true}]) (:ident schema))
               (map #(-> [% {:db/valueType   :db.type/ref
                             :db/cardinality :db.cardinality/one
                             :db/isComponent true}]) (:single-ref schema))
               (map #(-> [% {:db/valueType   :db.type/ref
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
                                       k      (:db/id v)})
                                    (and
                                      (sequential? v)
                                      (every? #(and
                                                 (map? %)
                                                 (contains? % :db/id)) v))
                                    (concat (mapcat pull-res-to-datoms v)
                                            (map #(-> {:db/id id
                                                       k      (:db/id %)}) v))
                                    :else [{:db/id id
                                            k      v}]))))
               [])
       (into [])))

(defn process-pull-datoms-res [res]
  (->> [res]
       (mapcat pull-res-to-datoms)
       (into #{})
       (into [])))

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
            ;; (if (s/valid? (s/coll-of (fn [{:keys [db/id]}] (or (int? id) (= id [:db/role :anchor])))) datoms))
            (transact! true conn datoms))
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

(defn deep-merge [& m]
  (apply merge-with
         (fn [& v]
           (cond (every? map? v) (apply deep-merge v)
                 (every? coll? v) (apply concat v)
                 :else (last v))) m))

(defn log-diffs [tag s1 s2]
  (let [[only-before only-after] (data/diff s1 s2)
        changed? (or (some? only-before) (some? only-after))]
    (if changed?
      (do (log-group "sink diff for subsystem:" tag)
          (log "only before:" only-before)
          (log "only-after:" only-after)
          (log-group-end)))))


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

(defn route-builder [routes path]
  (if (coll? routes)
    (->> routes
         (map (fn [[k v]]
                (if-let [new-step (-> v meta keys first)]
                  [k (route-builder v (concat path [new-step]))]
                  [k (route-builder v path)])))
         (into {}))
    [routes path]))

(defn datascript-set-route-event-res
  [bidi-res]
  {:datascript [{:db/path      [[:db/role :anchor]]
                 :root/routing bidi-res}]})

;;; NEW STUFF

(defn ui-dispatch
  [dispatch]
  {:dispatch (fn [events]
               (fn []
                 (->> events
                      (map #(-> [% (fn [args] (dispatch % args))]))
                      (into {}))))})

(defn ui-datascript
  [conn]
  {:datascript (fn [{:keys [id query] :or {id [:db/role :anchor]}}]
                 (fn [] @(posh/pull conn query id)))})

(def ui-state
  {:state (fn [args]
            (let [state (reagent/atom args)]
              (fn [] {:val @state
                      :set (fn [v] (reset! state v))
                      :swap (fn [f] (swap! state f))})))})

(defn req->xhrio-options
  [ajax-cache
   {:keys [dispatch-success
           dispatch-failure]}
   {:as   request
    :keys [on-success on-failure]
    :or   {on-success [:http-no-on-success]
           on-failure [:http-no-on-failure]}}]
  (let [[_ args-succ] on-success
        [_ args-fail] on-failure
        api (new js/goog.net.XhrIo)]
    (-> request
        (assoc
         :api api
         :handler (partial ajax-xhrio-handler
                           (fn [res]
                             (let [dispatch-id (dispatch-success args-succ)]
                               (swap! ajax-cache assoc dispatch-id res)))
                           (fn [err]
                             (let [dispatch-id (dispatch-failure args-fail)]
                               (swap! ajax-cache assoc dispatch-id err)))
                           api))
        (dissoc :on-success :on-failure))))

(def init-ajax
  (let [ajax-cache (atom {})]
    {:sources {:ajax (fn [{:keys [args dispatch-id]}]
                       (let [id (if (= args :dispatcher)
                                  dispatch-id
                                  args)]
                         (let [res (get @ajax-cache id)]
                           (swap! ajax-cache dissoc id)
                           res)))}
     :sinks {:ajax {:dispatchers (fn [{[event-succ] :on-success
                                       [event-fail] :on-failure}]
                                   {:dispatch-success (or event-succ :http-no-on-success)
                                    :dispatch-failure (or event-fail :http-no-on-failure)})
                    :body        (fn [{:keys [args dispatchers]}]
                                   (let [seq-request-maps (if (sequential? args) args [args])]
                                     (doseq [request seq-request-maps]
                                       (->> request
                                            (req->xhrio-options ajax-cache dispatchers)
                                            ajax/ajax-request))
                                     nil))}}}))

(defn init-datascript
  [conn]
  {:sources {:datascript (fn [{{:keys [query id] :or {id [:db/role :anchor]} :as dsargs} :args}]
                           (if (= dsargs :raw)
                             @conn
                             @(posh/pull conn query id)))}
   :sinks {:datascript (fn [{:keys [args]}] (transact! false conn args))}})


(defn init-routing
  ([routes] (init-routing routes {}))
  ([routes {:keys [from-location make-set-route-event-res]}]
   (let [routes (route-builder routes [])
         leaves (leaves routes)
         leaf-to-sym (fn [[leaf path]] (symbol (str leaf (reduce str "" (map str path)))))
         leaves (->> leaves (map (fn [leaf] [(leaf-to-sym leaf) leaf])) (into {}))
         routes (map-leaves leaf-to-sym routes)
         routes (mapcat identity routes)
         from-location (or from-location #(-> % .-hash (subs 1)))
         make-set-route-event-res (or make-set-route-event-res
                                      (fn [args]
                                        {:state {:routing {:current-route (merge args
                                                                                 {:handler (get leaves {:handler args})})}}}))]
     {:events      {:set-route {:body (fn [{:keys [args]}]
                                        (make-set-route-event-res (update args :handler #(get leaves %))))}}
      :sinks       {:start-pushy {:dispatchers [:set-route]
                                  :body        (fn [{routes :args {dispatch :set-route} :dispatchers}]
                                                 (pushy/start!
                                                  (pushy/pushy dispatch
                                                               (fn []
                                                                 (bidi/match-route routes
                                                                                   (from-location (.-location js/window)))))))}}
      :start-pushy routes})))

(def init-dispatch
  {:sinks {:dispatch {:dispatchers (fn [[event]] {event event})
                      :body        (fn [{:keys [dispatchers] [event args] :args}]
                                     ((dispatchers event) args))}
           :dispatch-after {:dispatchers (fn [[_ event]] {event event})
                            :body        (fn [{:keys [dispatchers] [ms event args] :args}]
                                           (js/setTimeout #((dispatchers event) args) ms))}}})
(defn handle-event-res
  [dispatch state res]
  (if (map? res)
    (let [state (deep-merge state (:state res))
          sinks (->> res
                     keys
                     (sort-by #{:sources :sinks :events})
                     reverse)]
      (reduce (fn [state sink]
                (let [args (res sink)
                      {:keys [dispatchers body]} (or (get-in state [:_config :sinks sink])
                                                     {:dispatchers []
                                                      :body (fn [] {})})
                      dispatchers (if (fn? dispatchers)
                                    (->> (dispatchers args)
                                         (map (fn [[k e]]
                                                [k #(dispatch e %)]))
                                         (into {}))
                                    (->> dispatchers
                                         (map (fn [e]
                                                [e #(dispatch e %)]))
                                         (into {})))]
                  (handle-event-res dispatch
                                    state
                                    (body {:args args
                                           :dispatchers dispatchers}))))
              state
              sinks))
    state))

(defn make-default-sink
  [type]
  (let [optional-key ({:sources :dependencies
                       :sinks :dispatchers
                       :events :sources} type)]
    {type {optional-key []
           :body (fn [{things :args}]
                   {:state {:_config {type (->> things
                                                (map (fn [[k v]]
                                                       (if (map? v)
                                                         [k (merge {optional-key []} v)]
                                                         [k {optional-key [] :body v}])))
                                                (into {}))}}})}}))

(defn build-event-args
  [dispatch-id args state sources]
  (merge
   (->> sources
        (mapcat (fn [[source source-args]]
                  (conj (->> [:_config :sources source :dependencies]
                             (get-in state)
                             (map #(-> [% source-args])))
                        [source source-args])))
        (reduce (fn [{:keys [args cache]} [source source-args]]
                  (let [res (or (get cache [source source-args])
                                ((or (get-in state [:_config :sources source :body])
                                     (fn [] {})) (merge args {:args source-args})))]
                    {:args (merge args {source res})
                     :cache (merge cache {[source source-args] res})}))
                {:cache {} :args {:state state :dispatch-id dispatch-id}})
        :args)
   {:args args}))

(defn create-event-system
  [initials]
  (let [dispatch-id-atom (atom 0)
        c (chan)
        state {:_config {:events {}
                         :sources {:state {:dependencies {}}}
                         :sinks (->> [:events :sources :sinks]
                                     (map make-default-sink)
                                     (reduce merge {}))}}

        dispatch (fn [& args]
                   (let [dispatch-id @dispatch-id-atom]
                     (swap! dispatch-id-atom inc)
                     (go (>! c [dispatch-id args]))
                     dispatch-id))
        state (reduce (partial handle-event-res dispatch)
                      state
                      initials)]
    (go-loop [state state]
      (let [[dispatch-id [event args]] (<! c)
            {:keys [sources body]} (or (get-in state [:_config :events event])
                                       {:sources {}
                                        :body (fn [] {})})]

        (recur (handle-event-res dispatch
                                 state
                                 (body (build-event-args dispatch-id args state sources))))))
    dispatch))

(defn props-from-component
  [comp]
  (let [[_ & props] (.-argv (.-props comp))] props))

(defn create-ui-system
  [enhancers]
  (let [enhancers (reduce merge {} enhancers)]
    (fn [enhancer-func react-methods]
      (let [args-converter {:component-will-receive-props
                            (fn [comp [_ & next-props]]
                              [(props-from-component comp) next-props])
                            :component-will-update
                            (fn [comp [_ & next-props]]
                              [(props-from-component comp) next-props])
                            :component-did-update
                            (fn [comp [_ & prev-props]]
                              [prev-props (props-from-component comp)])
                            :reagent-render (fn [args] [args])}
            react-methods (if (fn? react-methods)
                            {:reagent-render react-methods}
                            react-methods)
            component (reagent/create-class
                       (->> react-methods
                            (filter (fn [[k v]] (args-converter k)))
                            (map (fn [[k v]]
                                   [k (fn [& args]
                                        (apply v (apply (args-converter k) args)))]))
                            (into {})))]

        (fn [args]
          (let [final-enhancers (->> (enhancer-func args)
                                     (map (fn [[k v]]
                                            [k ((or (enhancers k)
                                                    (fn [] (fn [] {}))) v)]))
                                     (into {}))]
            (fn [args]
              [component (merge
                          (->> final-enhancers
                               (map (fn [[k v]] [k (v)]))
                               (into {}))
                          {:args args})])))))))

