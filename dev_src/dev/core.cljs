(ns dev.core
  (:require [cljsjs.react]
            [clojure.core.async :as a :refer [<! >! chan close! put!]]
            [cljs-http.client :as http]
            [cognitect.transit :as t]
            [metosin.transit.dates :as d]
            [promesa.core :as p]
            [dev.render :as render]
            [samak.runtime :as run]
            [samak.packet :as packet]
            [samak.helpers :as helpers]
            [samak.builtins :as builtins])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn update-bar
  ""
  [a]
  (when-let [elem (-> js/document
                      (.getElementById "bar"))]
    (-> elem
        (.-style)
        (.-width)
        (set! (str a "%")))))

(defn handle-update
  ""
  [c done]
  (go-loop []
    (let [p (<! c)]
      (do
        (update-bar p)
        (when (= p 100)
          (println "done core")
          (done))))
    (recur)))

(defn handle-send
  ""
  [worker c]
  (let [w (t/writer :json {:handlers d/writers})]
    (go-loop []
      (let [p (<! c)
            before (helpers/now)]
        ;; (println "to worker" p)
        (.postMessage worker (t/write w p))
        (render/trace ::render-out
                    (helpers/duration before (helpers/now))
                    (:samak.runtime/content p)))
      (recur))))

(def json-reader (t/reader :json {:handlers d/readers}))
(defn make-handler
  ""
  [load to-main to-worker]
  (fn
    [event]
    (let [data (t/read json-reader (.-data event))]
      ;; (println "recv from w" data)
      (condp = (:target data)
        :load (put! load (:data data))
        :bootstrap (put! to-worker :init)
        (if (= (:samak.runtime/type data) :samak.runtime/store)
          (go-loop []
            (let [res (<! (http/post "/api" {:transit-params data}))
                  b (:body res)]
              (if (= 200 (:status res))
                (put! to-worker (t/read json-reader (:body res)))
                (recur))))
          (put! to-main data))))))

(defn handle-broadcasts [from-main to-worker to-main]
  (let [c (chan)]
    (a/tap from-main c)
    (go-loop []
      (let [p (<! c)]
        ;; (println "broadcast" p (packet/get-type p))
        (condp = (packet/get-type p)
          :samak.runtime/store (let [res (<! (http/post "/api" {:transit-params p}))]
                                 (if (= (:status res) 200)
                                   (put! to-main (t/read json-reader (:body res)))
                                   (put! to-main (packet/make-error :samak.runtime/store (str "error:" res)))))
          (put! to-worker p)))
      (recur))))

(defn init
  ""
  []
  (println "INIT")
  (p/let [in-main (chan)
          out-main (chan)
          out-mult (a/mult out-main)
          in-worker (chan)
          in-preview (chan)
          loading (chan)]
    (handle-broadcasts out-mult in-worker in-main)
    (render/start-render-runtime loading in-main out-main)
    (let [w (js/Worker. "/shadowcljs/js/oasis-out/worker.js")]
      (handle-send w in-worker)
      (aset w "onmessage" (make-handler loading in-main in-worker))
      (handle-update loading
                     (fn [] (p/do! ;; (a/tap out-mult in-preview)
                                   ;; (render/start-preview-runtime in-preview in-main)
                                   (render/start-main loading)))))
    ))

;; (init)
