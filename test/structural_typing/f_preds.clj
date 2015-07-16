(ns structural-typing.f-preds
  (:require [structural-typing.preds :as subject]
            [structural-typing.api.oopsie :as oopsie]
            [structural-typing.mechanics.lifting-predicates :refer [lift]])
  (:require [blancas.morph.monads :as e]
            [such.readable :as readable])
  (:use midje.sweet))

(fact member
  (fact "member produces a predicate"
    ( (subject/member [1 2 3]) 2) => true
    ( (subject/member [1 2 3]) 5) => false)

  (let [lifted (lift (subject/member [1 2 3]))]
    (future-fact "a nice name"
      (readable/fn-string (subject/member [1 2 3])) => "(member [1 2 3])"
      (readable/fn-string (subject/member [even? odd?])) => "(member [even? odd?])"
      (readable/fn-string lifted) => "(member [1 2 3])")

    (fact "nice error messages"
      (oopsie/explanation (e/run-left (lifted {:leaf-value 8 :path [:x]})))
      => ":x should be a member of `[1 2 3]`; it is `8`")))

(fact exactly
  (fact "produces a predicate"
    ( (subject/exactly 1) 1) => true
    ( (subject/exactly 3) 5) => false)

  (let [lifted (lift (subject/exactly 3))]
    (future-fact "a nice name"
      (readable/fn-string (subject/exactly [even? odd?])) => "(exactly [even? odd?])"
      (readable/fn-string lifted) => "(member [1 2 3])")

    (fact "nice error messages"
      (oopsie/explanation (e/run-left (lifted {:leaf-value 8 :path [:x]})))
      => ":x should be exactly `3`; it is `8`")))
