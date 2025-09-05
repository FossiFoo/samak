(ns samak.repl
  #?@
  (:clj
   [(:require
     [clojure.edn :as edn]
     [clojure.string :as str]
     [clojure.core.async :as a :refer [<! >! chan go go-loop close! put!]]
     [promesa.core :as prom]
     [samak.lisparser :as p]
     [samak.runtime :as run]
     [samak.builtins :as builtins]
     [samak.stdlib :as std]
     [samak.caravan :as caravan]
     [samak.api :as api]
     [samak.tools :as t]
     [samak.trace :as trace]
     [samak.core :as core]
     [samak.emit :as emit]
     [samak.runtime.servers :as servers])]
   :cljs
   [(:require
     [cljs.reader :as edn]
     [clojure.string :as str]
     [clojure.core.async :as a :refer [<! >! chan close! put!]]
     [promesa.core :as prom]
     [samak.lisparser :as p]
     [samak.runtime :as run]
     [samak.stdlib :as std]
     [samak.builtins :as builtins]
     [samak.caravan :as caravan]
     [samak.api :as api]
     [samak.tools :as t]
     [samak.trace :as trace]
     [samak.core :as core]
     [samak.runtime.servers :as servers])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])]))

(def ^:dynamic *default-timeout* 0)
(def config {:tracer {:backend :none}})

(def out-pipe (atom nil))

(defn output [& x]
  (let [o @out-pipe]
    (if (nil? o)
      (println x)
      (put! o (apply str x)))))

(defn catch-errors [ast]
  (if-let [error (:error ast)]
    (output "There was a parse error: " error)
    (:value ast)))

(defn parse-samak-string [s]
  (some-> s
          p/parse-all
          catch-errors))

(defn eval-exp
  [runtime expression]
  (let [new-symbols (:defined-ids (run/eval-expression! runtime expression :broken))]
    (when-let [latest (:latest new-symbols)]
      (print "EVALED:" latest))
    new-symbols))

(defn fire-event-into-named-pipe
  [runtime pipe-name event]
  (prom/let [arg (edn/read-string event)
             res (run/fire-into-named-pipe runtime :repl (symbol pipe-name) arg *default-timeout*)]
    ;; (output "###fire" pipe-name event)
    (if (:error res)
      (output (:error res)))))

(def repl-prefixes
  {\h ["Prints help on available commands"
       (fn [in _]
         (output "Available commands:\n")
         (doall (map #(output (str "  " (first %) ": " (first (second %)))) repl-prefixes))
         (prom/resolved nil))]
   \f ["Fires an event into the given pipe"
       (fn [in rt] (let [[pipe-name event] (str/split in #" " 2)]
                     (fire-event-into-named-pipe rt pipe-name event)))]
   \e ["List all defined symbols"
       (fn [_ rt] (prom/resolved (let [p (->> rt
                                              :server
                                              servers/get-defined
                                              t/pretty)]
                                   (output "Defined symbols:\n" p)
                                   (println "sym " p)
                                   p)))]
   \q ["Load and print the given symbol or id"
       (fn [in rt] (prom/let [id (run/resolve-name rt (symbol in))
                              res (run/load-ast rt (or id in))]
                     (if res
                       (do
                         (output res)
                         (output (emit/emit res)))
                       (output "unknown: " in))))]
   \l ["List all existing links"
       (fn [_ rt] (prom/resolved (let [l (run/links rt)] (run! output l) l)))]
   \p ["Parse the given samak expression"
       (fn [in _] (prom/resolved (let [s (parse-samak-string in)] (output s) s)))]})

(defn run-repl-cmd [s rt]
  (let [[_ dispatch & rst] s]
    (let [repl-cmd (repl-prefixes dispatch)]
      (if repl-cmd
        ((second repl-cmd) (->> rst (apply str) str/trim) rt)
        (prom/rejected (ex-info (str "no valid command, see !h for help: " s) {}))))))

(defn eval-line
  "Evals some input line in the context of the defined symbols,
  and returns a new map of symbols"
  [input runtime]
  (if (= "" (str/trim input))
    runtime
    (do
      ;; (output "line" input (or (:id runtime) runtime))
      (condp #(str/starts-with? %2 %1) input
        "!" (prom/let [a (run-repl-cmd input runtime)]
              (prom/resolved runtime))
        ";" (do (output "ignored:" input) (prom/resolved runtime))
        (prom/let [parsed (parse-samak-string input)
                   prt (prom/resolved {:rt runtime :cnt 0})
                   red (reduce (fn [rt exp] (prom/handle rt (fn [res err] (when err (throw err))
                                                              (prom/let [rt (run/eval-expression! (:rt res) [exp] :repl)]
                                                                {:rt rt :cnt (inc (:cnt res))}))))
                               prt parsed)
                   new (:rt red)]
          ;; (output "resolved" new)
          (prom/resolved new))))))

(defn special-line? [line]
  (or (str/starts-with? line "!") (str/starts-with? line ";")))

(defn group-repl-cmds [lines]
  (->> lines
       (partition-by special-line?)
       (mapcat (fn [lines] (if (-> lines first special-line?)
                            lines
                            [(str/join " " lines)])))))

(defn eval-lines [lines runtime]
  (prom/let [rt runtime
             evals (reduce (fn [rt exp] (prom/handle rt (fn [res err] (if err (do (output err) (throw err)) (eval-line exp res))))) (prom/resolved rt) (group-repl-cmds lines))]
    evals))

(defn init [rt out args]
  (prom/let [init? (run/get-definition-by-name rt :repl 'init)]
    (caravan/init rt)
    (reset! out-pipe out)
    (when init?
      (fire-event-into-named-pipe rt 'init args))))
