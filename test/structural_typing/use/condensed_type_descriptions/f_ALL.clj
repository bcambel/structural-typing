(ns structural-typing.use.condensed-type-descriptions.f-ALL
  (:require [structural-typing.preds :as pred])
  (:use midje.sweet
        structural-typing.type
        structural-typing.global-type
        structural-typing.clojure.core
        structural-typing.assist.testutil))

(start-over!)

(fact "error messages show indexes"
  (type! :A-has-evens {[:a ALL] even?})
  (check-for-explanations :A-has-evens {:a [1 2]}) => [(err:shouldbe [:a 0] "even?" 1)]

  (type! :DoubleNested {[:a ALL :b ALL] even?})  
  (check-for-explanations :DoubleNested {:a [{:b [4 8]} {:b [0 2]} {:b [1 2 4]}]})
  => [(err:shouldbe [:a 2 :b 0] "even?" 1)])

(fact "When traversing paths reveals that location of ALL is not a collection"
  (type! :Points {[ALL :x] integer?
                  [ALL :y] integer?})
  (check-for-explanations :Points 3) => (just #"\[ALL :x] is not a path into `3`"
                                              #"\[ALL :y] is not a path into `3`")

  (future-fact "Failure is annoying side effect of there being no distinction between a present nil and a missing key"

    (check-for-explanations :Points [1 2 3]) => (just #"\[ALL :x] is not a path into `3`"
                                                      #"\[ALL :y] is not a path into `3`"))

  (fact "works for partial collections"
    (type! :Figure (requires :color [:points ALL (each-of :x :y)]))
    (check-for-explanations :Figure {:points 3})
    => (just (err:required :color)
             #"\[:points ALL :x\] is not a path into `\{:points 3\}`"
             #"\[:points ALL :y\] is not a path into `\{:points 3\}`")))

(fact "A solitary ALL"
  (type! :IntArray {[ALL] integer?})
  (described-by? :IntArray [1 2 3 4]) => true
  (check-for-explanations :IntArray [1 :a 2 :b])
  => (just (err:shouldbe [1] "integer?" :a)
           (err:shouldbe [3] "integer?" :b)))


(fact "ALL following ALL"
  (type! :D2 {[ALL ALL] integer?})
  (check-for-explanations :D2 [  [0 :elt-0-1] [:elt-1-0] [] [0 0 :elt-3-2]])
  => (just (err:shouldbe [0 1] "integer?" :elt-0-1)
           (err:shouldbe [1 0] "integer?" :elt-1-0)
           (err:shouldbe [3 2] "integer?" :elt-3-2))

  (type! :Nesty {[:x ALL ALL :y] integer?})
  (check-for-explanations :Nesty {:x [ [{:y 1}] [{:y :notint}]]})
  => [(err:shouldbe [:x 1 0 :y] "integer?" :notint)]

  (check-for-explanations :Nesty {:x [1]})
  => (just #"\[:x ALL ALL :y\] is not a path"))


(start-over!)
