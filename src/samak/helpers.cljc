(ns samak.helpers
  (:refer-clojure :exclude [uuid])
  (:require
   #?@ (:clj [[promesa.core :as p]
              [hasch.core :as hasch]
              [clojure.walk :as w]
              [clojure.data.json :as json]
              [clj-time.core :as time]
              [clj-time.format :as time-format]
              [clj-time.coerce :as time-coerce]]
        :cljs [[goog.async.nextTick]
               [promesa.core :as p]
               [hasch.core :as hasch]
               [clojure.walk :as w]
               [cljs-time.core :as time]
               [cljs-time.format :as time-format]
               [cljs-time.coerce :as time-coerce]])))

(defn now
  ""
  []
  (time/now))


(defn past
  ""
  [millis]
  (time/minus (now) (time/millis millis)))

(defn future-ms
  ""
  [amount]
  (time/from-now (time/millis amount)))


(defn duration
  ""
  [from to]
  (time/in-millis (time/interval from to)))

(defn past?
  ""
  [timeout]
  (time/after? (now) timeout))


(defn serialize-timestamp
  ""
  [time]
  (time-coerce/to-date time))

(defn parse-timestamp
  ""
  [datetime]
  (time-coerce/from-date datetime))

(def form (time-format/formatters :date-time))

(defn print-ISO
  ""
  [time]
  (time-format/unparse form time))

(defn to-epoch
  ""
  [time]
  (time-coerce/to-long time))

(defn compare-timestamp
  ""
  [a b]
  (time/before? a b))

(defn uuid
  "Return a random UUID."
  ([] (hasch/uuid))
  ([x] (hasch/uuid x)))

(defn hex
  []
  #?(:clj (Integer/toHexString (rand-int 16))
     :cljs (.toString (rand-int 16) 16)))


(defn make-span
  ""
  []
  (str (hex) (hex) (hex) (hex)
       (hex) (hex) (hex) (hex)))

(defn str-len
  ""
  [s]
  #?(:clj (count s)
     :cljs (.-length s)))


(defn substring
  ""
  [s n]
  (str
   (if (> (str-len s) n)
     (str (subs (str s) 0 (max 0 (- n 3))) "...")
     s)))

(defn fixstring [s n]
  (apply str (concat (take n (concat (substring s n) (repeat " "))))))

(defn to-json [x]
  #?(:cljs (clj->js x)
     :clj (json/write-str x)))

(defn debounce
  ""
  [f]
  #?(:cljs (goog.async.nextTick #(p/do (f)))
     :clj (p/do (f))))

(defn str-to-int [s]
  #?(:clj (try (Integer/parseInt s) (catch Exception e nil))
     :cljs (js/parseInt s)))

(defn pwalk
  ""
  [inner outer form]
  (cond
    (list? form) (do (println "list")(outer (apply list (map inner form))))
    (seq? form) (do (println "seq")(outer (map inner form)))
    (coll? form) (do (println "col")(outer (into (empty form) (map inner form))))
    :else (do (println "else")(outer form))))

(defn pwalk2
  [inner outer form]
  (cond
    (list? form) (outer (with-meta (apply list (map inner form)) (meta form)))
    (instance? clojure.lang.IMapEntry form) (outer (clojure.lang.MapEntry/create (inner (key form)) (inner (val form))))
    (seq? form) (outer (with-meta (doall (map inner form)) (meta form)))
    (instance? clojure.lang.IRecord form) (outer (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))

(defn ppostwalk
  ""
  [f form]
  (println "post " form)
  (pwalk2 (partial ppostwalk f) f form))

(defn make-meta
  ""
  [specific]
  (merge {:samak.pipes/created (now)
          :samak.pipes/span (make-span)
          :samak.pipes/parent (make-span)
          :samak.pipes/cancel (uuid)
          :samak.pipes/uuid (uuid)} specific))

(defn make-paket
  ""
  ([event source]
   {:samak.pipes/meta (make-meta {:samak.pipes/source source})
    :samak.pipes/content event})
  ([event source uuid]
   {:samak.pipes/meta (make-meta {:samak.pipes/uuid uuid :samak.pipes/source source})
    :samak.pipes/content event}))
