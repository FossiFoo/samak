(ns samak.emit
  (:require [clojure.string           :as str]
            [#?(:cljs cljs.pprint
                :clj  clojure.pprint) :as pp]))


(defmulti emit :kind)

(defmethod emit :program [node]
  (mapv emit (:definitions node)))

(defmethod emit :def [node]
  (list 'def (-> node :name symbol) (-> node :rhs emit)))

(defmethod emit :fn-call [node]
  (list* (-> node :name symbol)
         (->> node
              :arguments
              (sort-by :order)
              (map emit))))

(defmethod emit :vector [node]
  (->> node :children (sort-by :order) (map emit) vec))

(defmethod emit :integer [node]
  (:value node))

(defmethod emit :keyword [node]
  (-> node :value keyword))

(defmethod emit :string [node]
  (:value node))

(defmethod emit :var [node]
  (-> node :value symbol))

(defmethod emit :default [node]
  (str "No emit value for: " (:kind node)))

(defmethod emit :map [node]
  (into {} (for [item (:value node)]
             (mapv emit item))))

(defmethod emit :pipe-def [{:keys [from to transducers]}]
  (list* 'std/link (map emit [from (assoc transducers :kind :vector) to])))

(defmethod emit :handler [{:keys [channel field-id]}]
  (let [{:keys [id field]} field-id]
    `(fn [& args#] (~'std/fire! ~(symbol channel) (aget (js/document.getElementById ~id) ~field)))))

(defmethod emit :fn-literal [{:keys [value]}]
  (if (= :map (:kind value))
    `(~'std/map->fn ~(emit value))
    `(~'std/vec->fn ~(emit value))))

(defmethod emit :const-literal [{:keys [value]}]
  `(constantly ~(emit value)))

(defmethod emit :chan-declare [{:keys [chans]}]
  `(do
     ~@(for [chan chans]
         `(def ~(symbol chan) (~'std/pipe (~'chan))))))

(def ops->fns
  {"|" 'std/link})

(defmethod emit :bin-op [{:keys [lhs rhs operator]}]
  (list (ops->fns operator) (emit lhs) (emit rhs)))

(defmethod emit :expression-root [{:keys [value]}]
  (emit value))

(defn clj->str [form]
  (with-out-str
    (pp/with-pprint-dispatch pp/code-dispatch
      (pp/pprint form))))

(def header '(ns samak.app
               (:require [samak.stdlib :as std]
                         [samak.pipes  :as pipes]
                         [samak.core   :refer [map* reductions*]]
                         [ui.components   :as ui]
                         [cljs.core.async :as a :refer [put! chan <! >! timeout close!]])))

(defn prepend-header [forms]
  (cons header forms))

(defn append-footer [forms]
  (-> forms vec (conj '(std/start))))

(def emit-expression (comp clj->str emit))

(defn emit-clj [program]
  (if (:reason program)
    "Parse error"
    (->> program
         emit
         prepend-header
         append-footer
         (map clj->str)
         (interpose \newline)
         (apply str))))