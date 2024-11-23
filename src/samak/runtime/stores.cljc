(ns samak.runtime.stores
  #?@
  (:clj
   [(:require [promesa.core  :as p]
              [clojure.core.async    :as a :refer [<! >! put! chan go go-loop close!]]
              [clojure.edn   :as edn]
              [samak.code-db :as db]
              [samak.api     :as api]
              [samak.pipes  :as pipes]
              [samak.packet :as packet])]
   :cljs
   [(:require [promesa.core :as p]
              [clojure.core.async    :as a :refer [<! >! put! chan close!]]
              [cljs.reader :as edn]
              [samak.code-db :as db]
              [samak.api :as api]
              [samak.pipes :as pipes]
              [samak.packet :as packet])
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])]))

(defprotocol SamakStore
  (init [this])
  (persist-tree! [this tree])
  (load-by-id [this id])
  (load-network [this id])
  (resolve-name [this db-name]))

(defrecord LocalSamakStore [db rt-id]
  SamakStore
  (init [_])
  (persist-tree! [_ tree]
    (p/resolved
     (-> (db/parse-tree->db! db tree)
         :tempids
         (dissoc :db/current-tx)
         vals)))
  (load-by-id [_ id]
    (p/resolved (db/load-recurse db id)))
  (load-network [_ id]
    (p/resolved (db/load-network db id)))
  (resolve-name [_ db-name]
    (p/resolved (db/resolve-name db db-name))))

(def resolve-cache (atom {}))

(defn fetch [{rt-id :rt-id cb :cb out :out} c f]
  (let [prom (p/deferred)
        p (packet/make-packet :samak.runtime/store c)
        id (packet/get-id p)]
    (swap! cb assoc id prom)
    (put! (pipes/in-port out) p)
    (p/then prom f)))

(defrecord RemoteSamakStore [db in out counter cb rt-id]
  SamakStore
  (persist-tree! [this tree]
    (fetch this
         {:cmd :persist-tree :args {:tree tree}}
         #(:ids (:args %))))

  (load-by-id [this db-id]
    (fetch this
         {:cmd :load-by-id :args {:db-id db-id}}
         #(:ast (:args %))))

  (load-network [this net-id]
    (fetch this
         {:cmd :load-network :args {:net-id net-id}}
         #(:net (:args %))))

  (resolve-name [this db-name]
    (if-let [e (find @resolve-cache db-name)]
      (p/resolved (val e))
      (fetch this
           {:cmd :resolve-name :args {:db-name db-name}}
           #(let [res (:ids (:args %))]
              (swap! resolve-cache assoc db-name res)
              res))))

  (init [_]
    (let [c (chan)]
      (a/tap (pipes/out-port in) c)
      (go-loop []
        (let [m (<! c)]
          (if (nil? m)
            (a/untap (pipes/out-port in) c)
            (do
              (when-let [prom (get @cb (packet/get-id m))]
                (let [res (packet/assert-type-content :samak.runtime/store m)]
                  (p/resolve! prom res)))
              (recur))))))))


(defn serve-store
  ""
  [store in out rt-id]
  (go-loop []
    (when-let [m (<! in)]
      ;; (println (str rt-id " serve " m))
      (let [id (packet/get-id m)
            i (packet/assert-type-content :samak.runtime/store m)]
        (condp = (:cmd i)
          :persist-tree
          (p/then (persist-tree! store (:tree (:args i)))
                  (fn [ids]
                    (println rt-id "result persist" ids)
                    (put! out (packet/make-packet id (:ok packet/status) :samak.runtime/store {:cmd :persist-tree :args {:id id :ids ids}}))))
          :load-by-id
          (p/then (load-by-id store (:db-id (:args i)))
                  (fn [ast]
                    ;; (if (nil? ast)
                    ;;   (println rt-id "result load not found for" (:db-id (:args i)))
                    ;;   (println rt-id "result load" ast))
                    (put! out (packet/make-packet id (:ok packet/status) :samak.runtime/store {:cmd :load-by-id :args {:id id :ast ast}}))))
          :load-network
          (p/then (load-network store (:net-id (:args i)))
                  (fn [net]
                    (println rt-id "result net" net)
                    (put! out (packet/make-packet id (:ok packet/status) :samak.runtime/store {:cmd :load-network :args {:id id :net net}}))))
          :resolve-name
          (p/then (resolve-name store (:db-name (:args i)))
                  (fn [ids]
                    (println rt-id "result resolve" (:db-name (:args i)) ids)
                    (put! out (packet/make-packet id (:ok packet/status) :samak.runtime/store {:cmd :resolve-name :args {:ids ids}}))))
          (let [msg (str "unknown store command: " i)] (println msg) (p/rejected (ex-info msg {})))))
      (recur)))
  store)


(defn load-builtins! [store builtins]
  (persist-tree! store (mapv (fn [s] (api/defexp s (api/builtin s))) builtins)))

(defn make-local-store [id]
  (LocalSamakStore. (db/create-empty-db) id))

(defn make-piped-store
  ""
  [id in out]
  (let [s (RemoteSamakStore. (db/create-empty-db) in out (atom 0) (atom {}) id)]
    (init s)
    s))
