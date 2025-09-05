(ns samak.main
  (:gen-class)
  (:require [promesa.core   :as prom]
            [samak.storage  :as storage]
            [samak.cli-repl :as repl]))

(defn -main [& args]
  (prom/let [[to-store from-store] (storage/start! args)
             f (first args)]
    (repl/start! [to-store from-store] f (rest args))
    ))
