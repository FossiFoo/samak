(ns samak.combiparser-test
  (:require [clojure.test                     :refer [deftest is]]
            [samak.combiparser                :as cp]
            [blancas.kern.core                :as p]
            [blancas.kern.lexer.haskell-style :as hs]))

(deftest literal-parser-test
  (is (= (p/value cp/p-accessor ":-foo")
         #:samak.nodes{:type  :samak.nodes/accessor
                       :value :foo}))
  (is (= (p/value cp/p-keyword ":foo")
         #:samak.nodes{:type  :samak.nodes/keyword
                       :value :foo}))
  (is (= (p/value cp/p-integer "815")
         #:samak.nodes{:type  :samak.nodes/integer
                       :value 815}))
  (is (= (p/value cp/p-float "0.815")
         #:samak.nodes{:type  :samak.nodes/float
                       :value 0.815}))
  (is (= (p/value cp/p-string "\"Foo\\\"bar\\\"\"")
         #:samak.nodes{:type  :samak.nodes/string
                       :value "Foo\"bar\""}))
  (is (= (p/value cp/p-symbol "foo")
         #:samak.nodes{:type  :samak.nodes/symbol
                       :value 'foo}))
  (is (= (p/value cp/p-symbol "!")
         #:samak.nodes{:type  :samak.nodes/symbol
                       :value '!}))
  (is (= (p/value cp/p-identifier "foo-bar")
         "foo-bar"))
  (is (= (p/value cp/p-symbol "foo.bar/baz-baf")
         #:samak.nodes{:type  :samak.nodes/symbol
                       :value 'foo.bar/baz-baf}))
  (is (= (p/value cp/p-map "{:foo 1}")
         #:samak.nodes{:type :samak.nodes/map
                       :kv-pairs
                       [[#:samak.nodes {:value :foo
                                        :type  :samak.nodes/keyword}
                         #:samak.nodes {:value 1
                                        :type  :samak.nodes/integer}]]}))
  (is (= (p/value cp/p-vector "[1 2 foo :bar]")
         #:samak.nodes{:type :samak.nodes/vector
                       :children
                       [{:samak.nodes/value 1
                         :samak.nodes/type  :samak.nodes/integer
                         :order             0}
                        {:samak.nodes/value 2
                         :samak.nodes/type  :samak.nodes/integer
                         :order             1}
                        {:samak.nodes/value 'foo
                         :samak.nodes/type  :samak.nodes/symbol
                         :order             2}
                        {:samak.nodes/value :bar
                         :samak.nodes/type  :samak.nodes/keyword
                         :order             3}]})))

(deftest should-parse-multiple-pipes
  (is (= {:value
          [#:samak.nodes{:op :samak.nodes/pipe,
                         :type :samak.nodes/binop,
                         :arguments
                         [{:samak.nodes/value (symbol "bar"),
                           :samak.nodes/type :samak.nodes/symbol,
                           :order 0}
                          {:samak.nodes/value (symbol "baz"),
                           :samak.nodes/type :samak.nodes/symbol,
                           :order 1}]}]}
         (cp/parse "bar | baz | quux"))))

(deftest operator-test
  (is (= (p/value cp/p-compose "a . b")
         #:samak.nodes{:op   :samak.nodes/compose
                       :type :samak.nodes/binop
                       :arguments
                       [{:samak.nodes/value 'a
                         :samak.nodes/type  :samak.nodes/symbol
                         :order             0}
                        {:samak.nodes/value 'b
                         :samak.nodes/type  :samak.nodes/symbol
                         :order             1}]}))
  (is (= (p/value cp/p-pipe "a | b")
         #:samak.nodes{:op   :samak.nodes/pipe
                       :type :samak.nodes/binop
                       :arguments
                       [{:samak.nodes/value 'a
                         :samak.nodes/type  :samak.nodes/symbol
                         :order             0}
                        {:samak.nodes/value 'b
                         :samak.nodes/type  :samak.nodes/symbol
                         :order             1}]})))

(deftest program-test
  (is (= (p/value cp/p-fn-call "f {:a 1}")
         #:samak.nodes{:type         :samak.nodes/fn-call
                       :fn           #:samak.nodes {:value 'f
                                                    :type :samak.nodes/symbol}
                       :argument
                       #:samak.nodes {:type :samak.nodes/map
                                      :kv-pairs
                                      [[#:samak.nodes {:value :a
                                                       :type
                                                       :samak.nodes/keyword}
                                        #:samak.nodes {:value 1
                                                       :type
                                                       :samak.nodes/integer}]]}}))
  (is (= (p/value cp/p-def "foo = 1")
         #:samak.nodes{:type         :samak.nodes/def
                       :name         {:samak.nodes/value #:samak.nodes{:value 'foo :type :samak.nodes/symbol}}
                       :rhs
                       #:samak.nodes {:value 1
                                      :type  :samak.nodes/integer}}))

  (is (= (p/value cp/p-fn-call "foo 5")
         #:samak.nodes{:type         :samak.nodes/fn-call
                       :fn
                       #:samak.nodes{:value 'foo, :type :samak.nodes/symbol}
                       :argument
                       #:samak.nodes {:value 5 :type :samak.nodes/integer}}))

  (is (= (p/value cp/p-grouped "(bar 5)")
         #:samak.nodes{:type         :samak.nodes/fn-call
                       :fn
                       #:samak.nodes{:value 'bar, :type :samak.nodes/symbol}
                       :argument
                       #:samak.nodes {:value 5 :type :samak.nodes/integer}}))

  (is (= (cp/parse "foo = 1 \n bar | baz")
         {:value
          [#:samak.nodes{:type         :samak.nodes/def
                         :name         {:samak.nodes/value #:samak.nodes{:value 'foo :type :samak.nodes/symbol}}
                         :rhs
                         #:samak.nodes {:value 1
                                        :type  :samak.nodes/integer}}
           #:samak.nodes{:op   :samak.nodes/pipe
                         :type :samak.nodes/binop
                         :arguments
                         [{:samak.nodes/value 'bar
                           :samak.nodes/type  :samak.nodes/symbol
                           :order             0}
                          {:samak.nodes/value 'baz
                           :samak.nodes/type  :samak.nodes/symbol
                           :order             1}]}]})))
