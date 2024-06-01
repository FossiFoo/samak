(ns samak.storage
  #?@
  (:clj
   [(:require [promesa.core :as p]
              [clojure.core.async :as a :refer [<! >! put! chan go go-loop close!]]
              [clojure.java.io :as jio]
              [org.httpkit.server :as http]
              [cognitect.transit :as t]
              [ring.util.request :as r]
              ;; [metosin.transit.dates :as d]
              [samak.builtins :as builtins]
              [samak.stdlib   :as std]
              [samak.terminal :as term]
              [samak.halef :as halef]
              ;; [samak.caravan  :as caravan]
              [samak.runtime.stores :as stores]
              [samak.pipes :as pipes])
    (:import java.io.ByteArrayOutputStream)]
   :cljs
   [(:require [promesa.core :as p]
              [clojure.core.async :as a :refer [<! >! put! chan close!]]
              [cljs.reader :as edn]
              [samak.pipes :as pipes])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])]))

(def builtins
  (merge builtins/samak-symbols
         std/pipe-symbols
         halef/samak-symbols
         term/samak-symbols))

(def store (atom nil))
(def out-chan (atom (chan)))
(def c (atom [(pipes/sink (chan)) (pipes/source @out-chan)]))

(defn transit-out [data]
  (let [out (ByteArrayOutputStream. 4096)
        writer (t/writer out :json ;; {:handlers d/writers}
                         )]
    (t/write writer data)
    (.toString out)))

(defn async-handler [ring-request]
  (http/with-channel ring-request channel
    (if (http/websocket? channel)
      (http/on-receive channel (fn [data] (http/send! channel data)))
      (let [[in out] @c
            from-store (chan)
            raw-bod (:body ring-request)
            bod (when raw-bod (t/read (t/reader raw-bod :json)))
            id (:id (:args bod))]
        (println "root in:" id bod (type bod) (:args bod))
        (if (nil? bod)
          (http/send! channel {:status 404})
          (do
            (a/tap (pipes/out-port out) from-store)
            (go-loop []
              (when-let [i (<! from-store)]
                (println "root got" i)
                (if (and (= (:cmd i) :resolve-name) (= (:id (:args i)) id))
                  (do
                    (println "root req resolve in" id "-" i)
                    (http/send! channel (transit-out i)))
                  (recur))))
            (put! (pipes/in-port in) bod)))))))

(defn start! [& args]
  (reset! store (stores/make-local-store "root"))
  (stores/load-builtins! @store (keys builtins))
  (let [[in out] @c]
    (stores/serve-store @store (pipes/in-port in) @out-chan "root")
    (http/run-server async-handler {:port 8888})
    [in out]))
