(ns samak.caravan-test
  (:require [samak.caravan :as sut]
            [samak.api :as api]
            [samak.oasis :as oasis]
            [samak.code-db :as db]
            #?(:clj [clojure.test :as t :refer [deftest is]]
               :cljs [cljs.test :as t :include-macros true])))

(def all-things-samak
  (api/defexp 'test
    (api/fn-call (api/symbol '->)
                 [(api/map
                   {(api/keyword :test)
                    (api/vector
                     [(api/integer 0)
                      (api/fn-call (api/symbol '=) [(api/integer 1)])
                      (api/string "foo")
                      (api/symbol 'bar)
                      (api/float 23.1)
                      (api/key-fn :baz)])})])))

(deftest should-create-cell-list
  (is (= [{:type :caravan/func  :display "func"  :value "->"    :level 1 :counter 1}
          {:type :caravan/table :display "table" :value "###"   :level 2 :counter 2}
          {:type :caravan/kw    :display "kw"    :value ":test" :level 3 :counter 3}
          {:type :caravan/list  :display "list"  :value "---"   :level 4 :counter 4}
          {:type :caravan/int   :display "int"   :value "0"     :level 5 :counter 5}
          {:type :caravan/func  :display "func"  :value "="     :level 5 :counter 6}
          {:type :caravan/int   :display "int"   :value "1"     :level 6 :counter 7}
          {:type :caravan/str   :display "str"   :value "foo"   :level 5 :counter 8}
          {:type :caravan/sym   :display "sym"   :value "bar"   :level 5 :counter 9}
          {:type :caravan/float :display "float" :value "23.1"  :level 5 :counter 10}
          {:type :caravan/acc   :display "acc"   :value ":-baz" :level 5 :counter 11}]
         (sut/make-cell-list all-things-samak))))



(deftest should-find-cell
  (is (= {:samak.nodes/type :samak.nodes/string,
          :samak.nodes/value "foo"}
         (first (sut/find-cell (:samak.nodes/rhs all-things-samak) 8 0 nil 0)))))

(deftest should-find-position
  (is (= "foo" (:samak.nodes/value (first (sut/add-cell-internal all-things-samak 8))))))

(def builtins ['-> 'bar '=])

(deftest should-add-cell
  (let [db (db/create-empty-db)
        builtin (db/parse-tree->db! db (map #(api/defexp % (api/builtin %)) builtins))]
    (sut/init db)
    (sut/repl-eval all-things-samak)
    (with-redefs-fn {#'sut/add-node (fn [s f]
                                      (is (= "test" s))
                                      (let [args (get-in f [:samak.nodes/rhs :samak.nodes/arguments])
                                            i (get-in args [1 :samak.nodes/node])]
                                        (is (= 2 (count args)))
                                        (is (= :samak.nodes/integer (:samak.nodes/type i)))))}
      #((sut/add-cell) {:sym "test" :cell 1 :type :integer}))))

(deftest should-add-map-cell
  (let [db (db/create-empty-db)
        builtin (db/parse-tree->db! db (map #(api/defexp % (api/builtin %)) builtins))]
    (sut/init db)
    (sut/repl-eval all-things-samak)
    (with-redefs-fn {#'sut/add-node (fn [s f]
                                      (is (= "test" s))
                                      (let [m (get-in f [:samak.nodes/rhs :samak.nodes/arguments 0 :samak.nodes/node])
                                            assert-map (is (= :samak.nodes/map (:samak.nodes/type m)))]
                                        (is (= 2 (count (:samak.nodes/mapkv-pairs m))))
                                        (is (= :samak.nodes/integer (get-in m [:samak.nodes/mapkv-pairs 1 :samak.nodes/mapvalue :samak.nodes/type])))))}
      #((sut/add-cell) {:sym "test" :cell 2 :type :integer}))))

(deftest should-add-map-cell-from-key
  (let [db (db/create-empty-db)
        builtin (db/parse-tree->db! db (map #(api/defexp % (api/builtin %)) builtins))]
    (sut/init db)
    (sut/repl-eval all-things-samak)
    (with-redefs-fn {#'sut/add-node (fn [s f]
                                      (is (= "test" s))
                                      (let [m (get-in f [:samak.nodes/rhs :samak.nodes/arguments 0 :samak.nodes/node])
                                            assert-map (is (= :samak.nodes/map (:samak.nodes/type m)))]
                                        (is (= 2 (count (:samak.nodes/mapkv-pairs m))))
                                        (is (= :samak.nodes/integer (get-in m [:samak.nodes/mapkv-pairs 1 :samak.nodes/mapvalue :samak.nodes/type])))))}
      #((sut/add-cell) {:sym "test" :cell 3 :type :integer}))))

(deftest should-swap-order
  (is (= [{:order 1 :a 1} {:order 0 :a 2}]
         (sut/change-order [{:order 0 :a 1} {:order 1 :a 2}] 0 1))))

(deftest should-remove-arg
  (is (= [{:order 0 :a 2} {:order 1 :a 3}]
         (sut/remove-arg [{:order 1 :a 1} {:order 0 :a 2} {:order 2 :a 3}] 1))))

(deftest should-cut-cell
  (let [db (db/create-empty-db)
        builtin (db/parse-tree->db! db (map #(api/defexp % (api/builtin %)) builtins))]
    (sut/init db)
    (sut/repl-eval all-things-samak)
    (with-redefs-fn {#'sut/add-node (fn [s f]
                                      (is (= "test" s))
                                      (let [m (get-in f [:samak.nodes/rhs :samak.nodes/arguments 0 :samak.nodes/node])
                                            l (get-in m [:samak.nodes/mapkv-pairs 0 :samak.nodes/mapvalue])
                                            assert-list (is (= :samak.nodes/vector (:samak.nodes/type l)))]
                                        (is (= 5 (count (:samak.nodes/children l))))))}
      #(is (= :done ((sut/cut-cell) {:sym "test" :cell-idx 5}))))))
