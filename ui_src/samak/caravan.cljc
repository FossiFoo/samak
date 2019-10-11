(ns samak.caravan
  #?@
  (:clj
   [(:require [clojure.string :as s]
              [clojure.walk   :as w]
              [clojure.core.async :as a :refer [<! <!! put! chan go go-loop close!]]
              [samak.api      :as api]
              [samak.lisparser :as p]
              [samak.runtime  :as rt]
              [samak.runtime.servers  :as servers]
              [samak.pipes    :as pipes]
              [samak.builtins :as builtins]
              [samak.stdlib   :as std]
              [samak.tools :as tools]
              [samak.trace :as trace]
              [samak.nodes :as nodes]
              [clojure.string :as str])]
   :cljs
   [(:require [clojure.string :as s]
              [clojure.walk   :as w]
              [clojure.core.async :as a :refer [<! put! chan close!]]
              [samak.oasis :as oasis]
              [samak.api      :as api]
              [samak.lisparser :as p]
              [samak.runtime  :as rt]
              [samak.runtime.servers  :as servers]
              [samak.pipes    :as pipes]
              [samak.builtins :as builtins]
              [samak.stdlib   :as std]
              [samak.trace :as trace]
              [samak.nodes :as nodes]
              [samak.tools :as tools])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])]))

(def rt-conn (atom {}))
;; (def rt-preview (atom {}))
(def fns (atom {}))
(def net (atom {}))

(declare symbols)

(defmulti handle-node :samak.nodes/type)

(defmethod handle-node
  :samak.nodes/fn-call
  [node]
  (println (str "func: " node))
  {:type :caravan/func
   :display "func"
   :value (str (or (get-in node [:samak.nodes/fn-expression :samak.nodes/fn :samak.nodes/name])
                   (get-in node [:samak.nodes/fn-expression :samak.nodes/fn 1])))})

(defmethod handle-node
  :samak.nodes/integer
  [node]
  {:type :caravan/int
   :display "int"
   :value (str (:samak.nodes/value node))})

(defmethod handle-node
  :samak.nodes/string
  [node]
  {:type :caravan/str
   :display "str"
   :value (str (:samak.nodes/value node))})

(defmethod handle-node
  :samak.nodes/keyword
  [node]
  {:type :caravan/kw
   :display "kw"
   :value (str (:samak.nodes/value node))})

(defn make-sym
  [value]
  {:type :caravan/sym
   :display "sym"
   :value value})

(defmethod handle-node
  :samak.nodes/symbol
  [node]
  (make-sym (str (:samak.nodes/value node))))

(defmethod handle-node
  :samak.nodes/float
  [node]
  {:type :caravan/float
   :display "float"
   :value (str (:samak.nodes/value node))})

(defmethod handle-node
  :samak.nodes/map
  [node]
  {:type :caravan/table
   :display "table"
   :value "###"})

(defmethod handle-node
  :samak.nodes/vector
  [node]
  {:type :caravan/list
   :display "list"
   :value "---"})

(defmethod handle-node
  :samak.nodes/key-fn
  [node]
  {:type :caravan/acc
   :display "acc"
   :value (str ":-" (name (:samak.nodes/value node)))})

(defmethod handle-node
  :samak.nodes/builtin
  [node]
  (println (str "builtin?: " node))
  {:type :caravan/func
   :display "lib"
   :value (str (get-in node [:samak.nodes/value]))})

(defmethod handle-node
  :default
  [node]
  (if (and (vector? node) (= (count node) 2) (= :samak.nodes/name (first node)))
    (make-sym (str (second node)))
    (do (println (str "!!! unknown node: " (get node :samak.nodes/type) " - " node))
        {:type (str "unknown: " (get node :samak.nodes/type))})))

(defn get-child-key
  ""
  [node]
  (cond
    (api/is-vector? node) :samak.nodes/children
    (api/is-map? node)  :samak.nodes/mapkv-pairs
    (api/is-entry? node) :samak.nodes/mapvalue
    (api/is-fn-call? node) :samak.nodes/arguments))

(defn get-children
  ""
  [node]
  (cond
    (api/is-vector? node) (map :samak.nodes/node (sort-by :order (get node :samak.nodes/children)))
    (api/is-map? node) (get node :samak.nodes/mapkv-pairs)
    (api/is-entry? node) [(get node :samak.nodes/mapvalue)]
    (api/is-fn-call? node) (map :samak.nodes/node (sort-by :order (get node :samak.nodes/arguments)))))


(defn parse-node
  ""
  [ast lvl]
  (if-not ast
    []
    (let [children (get-children ast)
          result (mapv #(parse-node % (inc lvl)) children)]
      (into [(assoc (handle-node (or (:samak.nodes/mapkey ast) ast)) :level lvl)] result))))


(defn make-cell-list
  ""
  [src]
  (into [] (map-indexed (fn [i c] (assoc c :counter (inc i)))
                (flatten (parse-node (:samak.nodes/rhs src) 1)))))

(defn notify-source
  ""
  [src]
  (std/notify-source {(:caravan/name src) src}))


(defn is-sink?
  ""
  [exp]
  (let [rhs-fn (get-in exp [:samak.nodes/rhs :samak.nodes/fn-expression :samak.nodes/fn])
        has-name (get rhs-fn :samak.nodes/name)
        fn-name (str (:samak.nodes/name rhs-fn))
        is-stdlib (s/starts-with? fn-name "pipes/")
        is-reductions (= "pipes/reductions" fn-name)]
    (and (api/is-def? exp)
         has-name
         is-stdlib
         (not is-reductions))))

(defn reset-rt
  ""
  [rt]
  (reset! rt (rt/make-runtime (merge builtins/samak-symbols
                                             symbols
                                             std/pipe-symbols)))
  (reset! rt (reduce rt/eval-expression! @rt (vals @net)))
  (let [p (rt/get-definition-by-name @rt (symbol "start"))
        r (when p (pipes/fire! p 1 ::init))]
    (println (str "fire2! " p " - " r))))


(defn add-node
  ""
  [sym fn]
  (println (str "function cache: " (keys @fns)))
  (swap! fns assoc sym fn)
  (swap! rt-conn rt/eval-expression! fn)
  ;; (swap! rt-preview rt/link-storage (:store @rt-conn))
  ;; (reset-rt rt-preview)
  (let [type (if (is-sink? fn) :caravan/sink :caravan/func)
        ast (make-cell-list fn)]
    (if (empty? ast)
      (println (str "ERROR: no ast for: " sym " - " fn))
      (notify-source {:caravan/type type
                      :caravan/name (str sym)
                      :caravan/ast ast}))))

(defn name-of-node
  ""
  [node]
  (let [fun (or (get-in node [:samak.nodes/fn])
                (get-in node [:samak.nodes/fn-expression :samak.nodes/fn]))]
    (or (if (= (:samak.nodes/type node) :samak.nodes/def) (:samak.nodes/name node))
        (if (= (:samak.nodes/type fun) :samak.nodes/def) (:samak.nodes/name fun))
        (when-let [named (second fun)] (str ))
        (str "anon-" (rand-int 100000)))))

(defn make-pipe-key
  [source func sink]
  (str source "-" func "-" sink))


(defn add-pipe
  ""
  [pipe]
  (println (str "add pipe " pipe))
  (let [source (name-of-node (:samak.nodes/from pipe))
        func (name-of-node (:samak.nodes/xf pipe))
        sink (name-of-node (:samak.nodes/to pipe))
        pipe-name (str source "-" func "-" sink)]
    (println (str "adding pipe from " source " with " func " to " sink))
    (when (and source func sink)
      (swap! net assoc key pipe)
      ;; (swap! rt-preview rt/link-storage (:store @rt-conn))
      (println (str "def: " source " - " (rt/get-definition-by-name @rt-conn source)))
      (swap! rt-conn rt/eval-expression! pipe)
      ;; (reset-rt rt-preview)
      (notify-source {:caravan/type :caravan/pipe
                      :caravan/name pipe-name
                      :caravan/source source
                      :caravan/func func
                      :caravan/sink sink}))))


(defn load-ast
  "loads an ast given by its entity id from the database"
  [rt id]
  (w/postwalk (fn [form]
                (if-let [sub-id (when (and (map? form) (= (keys form) [:db/id]))
                                  (:db/id form))]
                 (rt/load-by-id rt sub-id)
                 form))
              (rt/load-by-id rt id)))


(defn persist!
  ""
  [rt tx-records]
  (rt/store! (:store rt) tx-records))


(defn single!
  ""
  [exp]
  (let [loaded (persist! @rt-conn [(assoc exp :db/id -1)])
        ast (load-ast @rt-conn (:db/id (first loaded)))]
    ast))

(defn repl-eval
  [exp]
  (if (api/is-pipe? exp)
    (add-pipe exp)
    (let [loaded (single! exp)]
      (add-node (symbol (str (:samak.nodes/name exp))) loaded))))


(defn find-cell-internal
  [src cell counter parent parent-idx]
  ;; (println (str "find: " src ",  " cell ",  " counter ",  " parent "\n\n"))
  (if (>= counter cell)
    (do
      {:i counter :result [src parent parent-idx]})
    (reduce (fn [{i :i result :result} child]
              (if result
                (do
                  {:i i :result result})
                (let [{subcount :i subresult :result}
                      (find-cell-internal child cell (+ counter i 1) src counter)]
                  {:i (+ subcount i 1) :result subresult})))
            {:i 0 :result nil}
            (get-children src))))

(defn find-cell
  ""
  [src cell]
  (:result (find-cell-internal src cell 0 nil 0)))


(defn add-cell-internal
  ""
  [src cell]
  (let [root (:samak.nodes/rhs src)]
    (find-cell root cell)))

(defn content-from-type
  ""
  [x]
  (case x
    :string (api/string "")
    :integer (api/integer 0)
    :float (api/float 0.0)
    :symbol (api/symbol 'id)
    :keyword (api/keyword :div)
    :table (api/map {})
    :list (api/vector [])
    :accessor (api/key-fn :test)
    :function (api/fn-call (api/symbol 'id) [])))


(defn is-listy-node
  ""
  [cell]
  (contains? #{:samak.nodes/vector :samak.nodes/map :samak.nodes/fn-call}
             (:samak.nodes/type cell)))

(defn is-map-node
  ""
  [cell]
  (= (:samak.nodes/type cell) :samak.nodes/map))

(defn is-mapish
  ""
  [cell]
  (or (is-map-node cell)
      (contains? cell :samak.nodes/mapkey)))



(defn add-map
  ""
  [target key content]
  (let [wrap (api/map-entry [(api/integer key) content])]
    (update target :samak.nodes/mapkv-pairs #(into %2 %1) [wrap]))
)


(defn add-list
  ""
  [target content]
  (let [target-key (get-child-key target)
        target-args (get target target-key)
        updated (update target target-key conj {:db/id -1 :order (count target-args) :samak.nodes/node content})]
    updated))


(defn add-cell
  ""
  [{:keys [sym cell type] :as x}]
  (println (str "adding: " x))
  (let [src (get @fns (symbol sym))
        idx (dec cell)]
    (when (and sym src idx type)
      (let [[cell par par-idx] (add-cell-internal src idx)
            _ (println (str "cell: " cell))
            _ (println (str "par: " par))
            root-id (:db/id src)
            content (content-from-type type)
            updated (if (is-mapish cell)
                      (add-map (if (is-map-node cell) cell par) (- idx 1 par-idx) content)
                      (add-list (if (is-listy-node cell) cell par) content))]
        (let [write (persist! @rt-conn [updated])
              exp (load-ast @rt-conn root-id)]
          (println (str "res: " exp))
          (add-node (symbol sym) exp)
          :done)))))

(defn value-from-type
  ""
  [cell value]
  (println (str "type: " cell " - " value))
  (case (:samak.nodes/type cell)
    :samak.nodes/fn-call (assoc cell :samak.nodes/fn-expression (api/symbol (symbol value)))
    :samak.nodes/keyword (assoc cell :samak.nodes/value (keyword value))
    (assoc cell :samak.nodes/value value)))


(defn edit-cell
  ""
  [{:keys [sym cell value] :as x}]
  (println (str "editing: " x))
  (let [src (get @fns (symbol sym))
        idx (dec cell)]
    (when (and sym src idx value)
      (let [[cell par] (add-cell-internal src idx)
            root-id (:db/id src)
            updated (value-from-type cell value)]
        (let [write (persist! @rt-conn [updated])
              exp (load-ast @rt-conn root-id)]
          (println (str "res: " exp))
          (add-node (symbol sym) exp))))))


(defn change-order
  "switch the :order value inside the children at the two given indexes"
  [v from to]
  (let [fi (update-in v [from :order] (constantly to))
        scd (update-in fi [to :order] (constantly from))]
    scd))


(defn swap-cell
  ""
  [{:keys [:sym :cell-idx :target] :as x}]
  (println (str "swap: " x))
  (let [src (get @fns (symbol sym))
        idx (dec cell-idx)]
    (when (and sym src idx target)
      (let [[cell par par-idx] (add-cell-internal src idx)
            root-id (:db/id src)
            arg-source-idx (- idx 1 par-idx)
            arg-target-idx (- target 2 par-idx)
            sorted-args (vec (sort-by :order (get par (get-child-key par)))) ;; need to make a copy because sort-by is inplace sometimes
            changed (change-order sorted-args arg-source-idx arg-target-idx)
            node (assoc par (get-child-key par) changed)]
        (let [write (persist! @rt-conn [node])
              exp (load-ast @rt-conn root-id)]
          (println (str "res: " exp))
          (add-node (symbol sym) exp))
        ))))

(defn remove-arg
  ""
  [v idx]
  (vec (for [elem v
             :when (not (= (:order elem) idx))]
         (if (> (:order elem) idx)
           (update elem :order dec)
           elem))))


(defn cut-cell
  ""
  [{:keys [sym cell-idx] :as x}]
  (println (str "cut: " x))
  (let [src (get @fns (symbol sym))
        idx (dec cell-idx)]
    (when (and sym src idx)
      (let [[cell par par-idx] (add-cell-internal src idx)
            root-id (:db/id src)
            arg-idx (- idx 1 par-idx)
            removed-args (remove-arg (get par (get-child-key par)) arg-idx)
            updated (assoc par (get-child-key par) removed-args)
            target-node (some #(when (= (:order %) arg-idx) %) (get par (get-child-key par)))
            retract [:db/retract (:db/id par) (get-child-key par) (:db/id target-node)]]
        (let [write (persist! @rt-conn [updated retract])
              exp (load-ast @rt-conn root-id)]
          (println (str "res: " exp))
          (add-node (symbol sym) exp)
          :done)))))

(defn indent-cell
  ""
  [{:keys [sym cell-idx] :as x}]
  (println (str "indent: " x))
  (let [src (get @fns (symbol sym))
        idx (dec cell-idx)]
    (when (and sym src idx type)
      (let [[cell par par-idx] (add-cell-internal src idx)
            root-id (:db/id src)
            own-arg (some #(when (= (:db/id (:samak.nodes/node %)) (:db/id cell)) %) (get par (get-child-key par)))
            own-order (:order own-arg)
            ;; _ (println (str "own-order: " own-order))
            target-node (some #(when (= (:order %) (dec own-order)) %) (get par (get-child-key par)))
            ;; _ (println (str "target-node: " target-node))
            target (:samak.nodes/node target-node)
            ;; _ (println (str "target: " target))
            ]
        (when (and target (get-child-key target))
          (let [insertion {:db/id (:db/id target) (get-child-key target) [{:db/id -1 :order (count (get target (get-child-key target))) :samak.nodes/node cell}]}
                ;; _ (println (str "insertion: " insertion))
                later-sibs (filterv #(> (:order %) own-order) (get par (get-child-key par)))
                ;; _ (println (str "later-sibs: " later-sibs))
                fixup {:db/id (:db/id par) (get-child-key par) (map #(update % :order dec) later-sibs)}
                ;; _ (println (str "fixup: " fixup))
                retract [:db/retract (:db/id par) (get-child-key par) (:db/id own-arg)]
                ;; _ (println (str "retract: " retract))
                ]
            (let [write (persist! @rt-conn [insertion fixup retract])
                  exp (load-ast @rt-conn root-id)]
              (println (str "res: " exp))
              (add-node (symbol sym) exp)
              :done)))))))

(defn create-sink
  ""
  []
  (fn [x]
    (println "create sink: " x)
    (let [pipe-name (:name x)
          sym (str pipe-name "-" (rand-int 1000000000))
          exp (api/defexp (symbol sym) (api/fn-call (api/symbol (symbol (str "pipes/" pipe-name))) nil))
          ast (single! exp)]
      (println (str "res: " ast))
      (add-node (symbol sym) ast)
      :okay)))

(defn disconnect
  ""
  []
  (println "disconnect"))

(defn connect
  ""
  [source connector sink]
  (let [fn (api/defexp (symbol connector) (api/fn-call (api/symbol '|>) [(api/symbol '_)]))
        fn-ast (single! fn)
        pipe (api/pipe (api/symbol (symbol source))
                       (api/symbol (symbol connector))
                       (api/symbol (symbol sink)))]
    (add-node (symbol connector) fn-ast)
    (add-pipe pipe)
    ;; [fn-ast pipe]
    :okay))


(defn link
  ""
  []
  (fn [{:keys [:source :sink] :as x}]
    (println "connect: " x)
    (let [connector  (str "c/" source "-" sink)
          pipe-key (make-pipe-key source connector sink)
          existing (contains? @net pipe-key)]
      (when (and sink source (not= sink source) )
        (if existing
          (disconnect)
          (connect source connector sink))))))

(defn find-tests
  ""
  [samak-map]
  (let [val (:samak.nodes/mapkv-pairs samak-map)
        tests (first (filter #(= :tests (:samak.nodes/value (:samak.nodes/mapkey %))) val))
        actual (:samak.nodes/mapvalue tests)]
    (when actual (nodes/eval-node actual))))

(defn attach-assert
  ""
  [verify ast]
  (let [source (get-in ast [:samak.nodes/from :samak.nodes/fn :samak.nodes/name])
        xf (get-in ast [:samak.nodes/xf])
        verify-name (str "assert-" (rand-int 1000000))
        verify-exp (api/defexp (symbol verify-name) (api/fn-call (api/symbol 'pipes/debug) []))
        verify-ast (single! verify-exp)
        ident-name (str "ident-" (rand-int 1000000))
        ident-exp (api/defexp (symbol ident-name) (api/fn-call (api/symbol '|>) [(api/symbol '_)]))
        ident-ast (single! ident-exp)

        assert-pipe (api/pipe (api/symbol (symbol source))
                              xf
                              (api/symbol (symbol verify-name)))
        verify-pipe (api/pipe (api/symbol (symbol verify-name))
                              ident-ast
                              (api/symbol (symbol verify)))]
    (println (str "--------------------- test setup"))
    (add-node (symbol verify-name) verify-ast)
    (add-node (symbol ident-name) ident-ast)
    (add-pipe assert-pipe)
    (add-pipe verify-pipe)
    ))


(defn add-pipe-net
  ""
  [verify config ast]
  (let [sink (get-in ast [:samak.nodes/to :samak.nodes/fn :samak.nodes/name])
        test-ref (ffirst config)
        _ (println (str "sink: " sink " = " test-ref))]
    (when (= (str sink) test-ref)
      (attach-assert verify ast))
    (add-pipe ast)))


(defn setup-verify
  ""
  []
  (let [verify-name (symbol (str "verify-" (rand-int 1000000)))
        verify-exp (api/defexp verify-name (api/fn-call (api/symbol 'pipes/debug) []))
        verify-ast (single! verify-exp)
        ]
    (add-node verify-name verify-ast)
    verify-name))


(defn load-source
  ""
  [sym config verify]
  (let [source (rt/load-network @rt-conn sym)
        nodes (distinct (flatten (concat [sym]
                                         (map :ends (vals source))
                                         (map :xf (vals source)))))
        asts (map #(load-ast @rt-conn %1) nodes)
        _ (doall (map #(add-node (symbol (name-of-node %)) %) asts))
        pipes (map :db/id (flatten (map :pipes (vals source))))
        pipe-asts (map #(load-ast @rt-conn %1) pipes)
        _ (doall (map #(add-pipe-net verify (:then config) %) pipe-asts))
        ]
    source))

(defn load-bundle
  ""
  [sym test]
  (let [verify (setup-verify)
        ]
    (doall (map #(load-source %1 test verify) (rt/load-bundle @rt-conn sym)))
    verify))

(defn load-oasis
   ""
   []
  (load-bundle 'oasis-ns))

(defn persist-tl-net
  ""
  []
  (persist! @rt-conn [(api/defexp 'tl (api/map {(api/keyword :source) (api/map {(api/keyword :main) (api/symbol 'in)})
                                                (api/keyword :tests) (api/map {(api/keyword ::test)
                                                                               (api/map {(api/keyword :when) (api/map {(api/string "in")
                                                                                                                       (api/vector [(api/integer 1)])})
                                                                                         (api/keyword :then) (api/map {(api/string "out")
                                                                                                                       (api/vector [(api/integer 1) (api/integer 2)])
                                                                                                                       ;; (api/keyword "out2")
                                                                                                                       ;; (api/vector [(api/integer 1) (api/integer 2)])
                                                                                                                       })})})}))]))

(defn load-chuck
  ""
  []
  (persist! @rt-conn [(api/defexp 'chuck (api/map {(api/keyword :source) (api/map {(api/keyword :main) (api/symbol 'in)
                                                                                   (api/keyword :ui) (api/symbol 'ui-in)
                                                                                   (api/keyword :http) (api/symbol 'http-in)
                                                                                   })}))])
  (load-bundle 'chuck))


(def tl4
  ["(def in (pipes/debug))"
   "(def out (pipes/log))"
   "(| in (pipes/reductions (-> (+ :-next :-state)) 0) out)"
   ])

(def tl
  ["(def in (pipes/debug))"
   "(def out (pipes/log))"
   "(def out2 (pipes/log))"
   "(def incinc (|> (inc _) (inc _)))"
   "(| in incinc out2)"
   "(| in incinc out)"])


(def tl3
  ["(def in (pipes/debug))"
   "(def out (pipes/log))"
   "(| in (|> (inc _) (inc _) out)"])


(def chuck
  ["(def in (pipes/debug))"
   "(def ui-in (pipes/ui))"
   "(def ui-out (pipes/ui))"
   "(def http-in (pipes/http))"
   "(def http-out (pipes/debug))"
   "(def render-joke [:li (str :-id \": \" :-joke)])"
   "(def render-ui (|> [:div
                [:h1 \"The grand Chuck Norris Joke fetcher!\"]
                [:h2 \"Enter any joke id and press enter\"]
                [:form {:on-submit :submit}
                 [:input {:on-change :change}]]
                (into [:ul] (map inc _))]))"
   "(def joke-input-state (pipes/reductions
                       (if (= (-> :-next :-data) :change)
                         {:event :change
                          :value (-> :-next :-event :-target :-value)}
                         {:event :submit
                          :value (-> :-state :-value)})
                       {:event :change
                        :value \"\"}))"

   "(def handle-ev-in (|> _))"
   "(| ui-in handle-ev-in joke-input-state)"
   "(def handle-input (|> (if (= :submit :-event)
         {:url (str \"http://api.icndb.com/jokes/\" :-value)}
         ignore)))"
   "(| joke-input-state handle-input http-out)"


   "(def joke-list (pipes/reductions (conj :-state :-next) []))"

   "(def handle-http (|> (if (= \"success\" :-type)
              :-value
              {:id -1 :joke \"Failed fetching joke\"})))"
   "(| http-in handle-http joke-list)"

   "(def handle-in (|> _))"
   "(| in handle-in joke-list)"

   "(| joke-list render-ui ui-out)"
   ])


(defn load-trivial
  ""
  []
  (let [ id (rt/load-by-sym @rt-conn 'in)
        _ (println "in: " id)
        loaded (load-source (:db/id id))
        _ (println "loaded: " loaded)]
    :done))

(defn trace-dump
  ""
  []
  (trace/init-tracer @rt-conn)
  (trace/dump))


(defn persist-tl
  ""
  []
  (let [parsed (p/parse-all (s/join " " tl))
        _ (rt/persist-to-ids! (:store @rt-conn) (:value parsed))
        _ (persist-tl-net)
         ]
    :done))


(defn run-event
  ""
  [pipe pipe-name content]
  (let [source-name (str "test/" pipe-name)
        paket (pipes/make-paket content source-name)]
    (println (str "f! " content))
    (trace/trace source-name 0 paket)
    (pipes/fire-raw! pipe paket)
    ))


(defn run-test
  ""
  [config [name tst]]
  (println (str "test " name " - " tst))

  (let [verify (load-bundle 'tl tst)]
    (go (let [pipe (rt/get-definition-by-name @rt-conn verify)
              listener (chan 1)]
          (a/tap (.out-port pipe) listener)
          (println (str "verify: " (<!! listener)))))
    (doall (map (fn [[pipe-name values]]
                  (println (str "pipe " (symbol pipe-name) " values: " values))
                  (let [pipe (rt/get-definition-by-name @rt-conn (symbol pipe-name))]
                    (doall (map #(run-event pipe pipe-name %) values))))
                (:when tst)))))


(defn run-testsuite
  ""
  [net]
  (let [config (:samak.nodes/rhs net)
        tests (find-tests config)
        _ (println "test: " tests)]
    (doall (map #(run-test config %) tests))))


(defn test-net
  ""
  []
  (persist-tl)
  (let [net (rt/load-by-sym @rt-conn 'tl)]
    (run-testsuite net))
  ;; (trace-dump)
  )


(defn init
  [rt]
  (reset! rt-conn rt)
  ;; (reset! rt-preview (rt/make-runtime (merge builtins/samak-symbols
  ;;                                            symbols
  ;;                                            std/pipe-symbols)))
  )

(defn caravan-pipe
  ""
  []
  (let [caravan-chan (chan)]
    (go-loop []
      (when-let [x (<! caravan-chan)]
        (when-let [call (:call x)]
          (do
            (tools/log "caravan: " call)
            (case (:action call)
              :insert (add-cell (:arguments call))
              :edit (edit-cell (:arguments call))
              :cut (cut-cell (:arguments call))
              :swap (swap-cell (:arguments call))
              :indent (indent-cell (:arguments call))
              (tools/log "actions unknown: " call))))
        (recur)))
    (pipes/sink caravan-chan ::caravan)))


(def symbols
  {'create-sink create-sink
   'load-node test-net
   'pipes/caravan caravan-pipe
   'connect link})
