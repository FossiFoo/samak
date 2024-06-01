(ns samak.main
  (:gen-class)
  (:require [promesa.core   :as prom]
            [samak.storage  :as storage]
            [samak.cli-repl :as repl]))

(defn -main [& args]
  (let [[in out] (storage/start! args)
        f (first args)]
    (repl/start! [in out] f (rest args))))
