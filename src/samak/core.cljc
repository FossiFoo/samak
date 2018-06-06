(ns samak.core
  (:require [samak.stdlib             :as std]
            [samak.transduction-tools :as tt]
            [samak.protocols          :as p]
            [net.cgrand.xforms        :as x]))

(defn if* [pred then else]
  (let [pred* (p/eval-as-fn pred)
        then* (p/eval-as-fn then)
        else* (p/eval-as-fn else)]
    (fn [x]
      (if (pred* x)
        (then* x)
        (else* x)))))

(defn or* [& args]
  (fn [x]
    (some #(% x) (map p/eval-as-fn args))))

(defn and* [& args]
  (fn [x]
    (every? #(% x) (map p/eval-as-fn args))))

(defn when* [pred]
  (let [pred* (p/eval-as-fn pred)]
    (fn [x]
      (when (pred* x)
        x))))

(defn chain [& args]
  (->> args reverse (map p/eval-as-fn) (apply comp)))

(defn curry1fn [f]
  (fn [x]
    (let [x* (p/eval-as-fn x)]
      (fn [y]
        (f x* y)))))

(defn curry1 [f]
  (fn [x]
    (fn [y]
      (f x y))))

(defn mapcatv [f]
  (fn [x]
    (vec (mapcat f x))))

(def sum (partial apply +))

(def samak-symbols
  {'|>     chain
   'id     identity
   'map    (curry1fn map)
   'filter (curry1fn filter)
   'only   #(if* % identity tt/ignore)
   'remove (curry1fn filter)
   'mapcat mapcatv
   'repeat (curry1 repeat)
   'take   (curry1 take)
   'drop   (curry1 drop)
   '=      (curry1 =)
   'many   tt/many
   'ignore tt/ignore
   'inc    inc
   'odd?   odd?
   'even?  even?
   'sum    sum
   'or     or*
   'and    and*
   'if     if*
   'const  constantly
   'when   when*
   '!      '!})
