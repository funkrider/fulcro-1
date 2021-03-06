(ns fulcro.client.impl.parser-spec
  (:require
    [fulcro.client :as fc]
    [fulcro.client.impl.parser :as parser]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [fulcro.client.impl.application :as app]
    [fulcro.client.mutations :as m]
    [fulcro.test-helpers :as th]))

(specification "query->ast"
  (behavior "preserve meta"
    (assertions
      "don't add the :meta key when meta is absent"
      (-> (prim/query->ast [:query])
        (contains? :meta))
      => false

      "on query root"
      (-> (prim/query->ast ^:marked [:query])
        :meta)
      => {:marked true}

      "on joins"
      (-> (prim/query->ast [^:marked {:join [:foo]}])
        :children first :meta)
      => {:marked true}

      "on join subquery"
      (-> (prim/query->ast [{:join ^:marked [:foo]}])
        :children first :query meta)
      => {:marked true}

      "on calls"
      (-> (prim/query->ast [(with-meta '(call {:x "y"}) {:marked true})])
        :children first :meta)
      => {:marked true})))

(defn meta-round-trip [query]
  (-> (prim/query->ast query)
    (prim/ast->query)
    (th/expand-meta)))

(specification "meta persistence"
  (behavior "keep meta on round trip"
    (assertions
      "on root"
      (meta-round-trip ^:marked [:query])
      => (th/expand-meta ^:marked [:query])

      "on root with component"
      (meta-round-trip ^{:marked true :component "X"} [:query])
      => (th/expand-meta ^{:marked true :component "X"} [:query])

      "on join"
      (meta-round-trip [^:marked {:join [:foo]}])
      => (th/expand-meta [^:marked {:join [:foo]}])

      "on join query"
      (meta-round-trip [{:join ^:marked [:foo]}])
      => (th/expand-meta [{:join ^:marked [:foo]}])

      "on calls"
      (meta-round-trip [(with-meta '(call {:x "y"}) {:marked true})])
      => (th/expand-meta [(with-meta '(call {:x "y"}) {:marked true})])

      ;"on call joins"
      (meta-round-trip [{'(call {:x "y"}) ^:mark ['*]}]) => (th/expand-meta [{'(call {:x "y"}) ^:mark ['*]}])

      "on unions"
      (meta-round-trip [^:marked {:union ^:marked {:a [:x] :b [:y]}}])
      => (th/expand-meta [^:marked {:union ^:marked {:a [:x] :b [:y]}}])

      "on recursion"
      (meta-round-trip [:x :y {:children ^:marked '...}])
      => (th/expand-meta [:x :y {:children ^:marked '...}])

      "on a crazy example"
      (meta-round-trip ^:marked [:x :y (with-meta 'a {:x 1})
                                 ^:marked {^:marked [:ident 123] ^{:marked    true
                                                                   :component "X"} [:query]}
                                 {:join ^:mark [:lets ^:mark {:nested ^:marked [:query]}]}
                                 ^:mark {:children ^:marked '...}])
      => (th/expand-meta ^:marked [:x :y (with-meta 'a {:x 1})
                                   ^:marked {^:marked [:ident 123] ^{:marked    true
                                                                     :component "X"} [:query]}
                                   {:join ^:mark [:lets ^:mark {:nested ^:marked [:query]}]}
                                   ^:mark {:children ^:marked '...}]))))

(m/defmutation sample-mutation-1 [params]
  (action [{:keys [state]}]
    (swap! state assoc :update [1]))
  (refresh [env] [:x :y]))

(m/defmutation sample-mutation-2 [params]
  (action [{:keys [state]}]
    (swap! state update :update conj 2))
  (refresh [env] [:a :b]))

(def parser (prim/parser {:read (partial app/read-local (constantly nil)) :mutate m/mutate}))

(specification "Parser reads"
  (let [state-db   {:top         [:table 1]
                    :prop        1
                    :name-in-use :no
                    :table       {1 {:id 1 :value :v}}}
        state-atom (atom state-db)
        env        {:state state-atom}]
    (assertions
      "accepts properties"
      (parser env [:prop]) => {:prop 1}
      "allows parameters on properties"
      (parser env '[(:name-in-use {:name "tony"})]) => {:name-in-use :no}
      "can produce query results"
      (parser env [{:top [:value]}]) => {:top {:value :v}})))

(specification "Mutations"
  (let [state  (atom {})
        env    {:state state}
        result (parser env `[(sample-mutation-1 {}) (sample-mutation-2 {})])]
    (assertions
      "Runs the actions of the mutations, in order"
      @state => {:update [1 2]}
      "Includes a refresh set on the metadata of the result."
      (-> result meta ::prim/refresh) => #{:x :y :a :b})))

(specification "substitute-root-path-for-ident"
  (let [node-ident  [:id "singleton"]
        query       [{node-ident [:id {:input [:fulcro.democards.react16-cards/id :my-value]} :ui/local-value]}]
        replacement [:ROOT]
        data-tree   (with-meta
                      {node-ident (with-meta
                                    {:ui/local-value "From inside"
                                     :input          (with-meta
                                                       {:fulcro.democards.react16-cards/id #uuid "3627a8a5-ac4a-4ee8-8d3e-8e22d2ade810", :my-value "B"}
                                                       {::parser/data-path [node-ident :input]})}
                                    {::parser/data-path [node-ident]})}
                      {::parser/data-path []})
        result      (get (parser/substitute-root-path-for-ident data-tree replacement query) node-ident)]
    (assertions
      "Replaces root path"
      (-> result meta ::parser/data-path) => [:ROOT]
      "Replaces nested path"
      (-> result (get :input) meta ::parser/data-path) => [:ROOT :input])))

(specification "parser on ident-based queries with replacement root path" :focused
  (let [node-ident   [:table :id]
        query        [{node-ident [{:input [:my-value]} :ui/local-value]}]
        state-map    {:input/by-id {1 {:my-value "b"}}
                      :table       {:id {:input [:input/by-id 1]}}}
        replacement  [:x :y :z]
        local-parser (parser/parser {:read (partial app/read-local (constantly false))})
        env          {:replacement-root-path replacement
                      :state                 (atom state-map)}
        result       (local-parser env query)]
    (assertions
      "Replaces metadata for root path"
      (-> result (get node-ident) meta ::parser/data-path) => [:x :y :z]
      "Replaces metadata for nested path"
      (-> result (get-in [node-ident :input]) meta ::parser/data-path) => [:x :y :z :input])))

(defn path-of [data path]
  (-> (get-in data path) meta ::parser/data-path))

(specification "path-meta"
  (assertions
    "Can assign the path of a nested map"
    (path-of (parser/path-meta {:a {:b 1}} [] [{:a [:b]}]) [:a]) => [:a]
    "Can assign the path of a to-many nested map"
    (path-of (parser/path-meta {:a [{:b 1} {:b 2}]} [] [{:a [:b]}]) [:a 0]) => [:a 0]
    (path-of (parser/path-meta {:a [{:b 1} {:b 2}]} [] [{:a [:b]}]) [:a 1]) => [:a 1]
    "Can assign the path of data that used a recursive query"
    (path-of (parser/path-meta {:a {:a 1}} [] '[{:a ...}]) [:a]) => [:a]
    (path-of (parser/path-meta {:a {:children {:a 1}}} [] '[{:a [{:children ...}]}]) [:a]) => [:a]
    (path-of (parser/path-meta {:a {:children {}}} [] '[{:a [{:children ...}]}]) [:a :children]) => [:a :children]
    (path-of (parser/path-meta {:label "a" :children {:label "b" :children [{:label "c"}]}} [] '[:label {:children ...}]) [:children :children 0]) => [:children :children 0]
    "Can assign through a union"
    (path-of (parser/path-meta {:a {:subitems {:a 2}}} [] '[{:a {:x [:name {:subitems [:a]}] :y [:label {:children [:b]}]}}]) [:a]) => [:a]
    (path-of (parser/path-meta {:a {:subitems {:a 2}}} [] '[{:a {:x [:name {:subitems [:a]}] :y [:label {:children [:b]}]}}]) [:a :subitems]) => [:a :subitems]
    (path-of (parser/path-meta {:a {:children {:b 2}}} [] '[{:a {:x [:name {:subitems [:a]}] :y [:label {:children [:b]}]}}]) [:a :children]) => [:a :children]))
