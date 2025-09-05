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
              [samak.helpers :as helpers]
              [samak.halef :as halef]
              [samak.caravan  :as caravan]
              [samak.runtime.stores :as stores]
              [samak.pipes :as pipes]
              [samak.packet :as packet]
              [samak.oasis :as oasis]
              )
    (:import java.io.ByteArrayOutputStream)]
   :cljs
   [(:require [promesa.core :as p]
              [clojure.core.async :as a :refer [<! >! put! chan close!]]
              [cljs.reader :as edn]
              [samak.pipes :as pipes])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])]))

(def layout-mock-symbols
  {'pipes/layout :blank})

(def ui-mock-symbols
  {'modules/ui :blank
   'pipes/ui :blank
   'pipes/events :blank
   'pipes/mouse :blank
   'pipes/keyboard :blank})

(def builtins
  (merge builtins/samak-symbols
         std/pipe-symbols
         halef/samak-symbols
         term/samak-symbols
         ui-mock-symbols
         layout-mock-symbols
         caravan/symbols))

(def store (atom nil))
(def out-chan (atom (chan 100)))
(def c (atom [(pipes/sink (chan)) (pipes/source @out-chan)]))
(def cb (atom {}))

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
            bod (when raw-bod (t/read (t/reader raw-bod :json)))]
        (println "root in:" bod (type bod))
        (if (nil? bod)
          (http/send! channel {:status 404})
          (let [id (packet/get-id bod)
                packet (packet/assert-type-content :samak.runtime/store bod)
                prom (p/deferred)]
            (swap! cb assoc id prom)
            (put! (pipes/in-port in) bod)
            (deref (p/handle prom (fn [a e]
                                    (if e
                                      (http/send! channel {:status 500 :body e})
                                      (http/send! channel (transit-out a)))
                                    (println "root done" a e))))))))))

(defn start-dispatch []
  "Starts a dispatcher that listens to responses from the storage"
  (let [[in out] @c
        from-store (chan 100)
        port (pipes/out-port out)]
    (go-loop []
      (let [i (<! from-store)]
        (if (nil? i)
          (a/untap (pipes/out-port out) from-store)
          (do
            (when-let [prom (get @cb (packet/get-id i))]
              (p/resolve! prom i))
            (recur)))))
    (a/tap port from-store)))

(defn start! [& args]
  (p/let [store (stores/make-local-store "root")
          [in out] @c]
    (stores/load-builtins! store (keys builtins))
    ;; (oasis/store store)
    (stores/serve-store store (pipes/in-port in) @out-chan "root")
    (start-dispatch)
    (http/run-server async-handler {:port 8888})
    [in out]))
