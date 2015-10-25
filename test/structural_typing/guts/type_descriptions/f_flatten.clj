(ns structural-typing.guts.type-descriptions.f-flatten
  (:require [structural-typing.guts.type-descriptions.flatten :as subject]
            [structural-typing.guts.type-descriptions.type-expander :as type-expander])
  (:use midje.sweet
        structural-typing.assist.testutil
        structural-typing.assist.special-words))

(defn sort-map-vals [kvs]
  (reduce (fn [so-far [k v]]
            (assoc so-far k (sort v)))
          {}
          kvs))

(facts "about flattening a condensed path into N paths"
  (fact "convert a simple path into paths"
    (subject/uncondense-path []) => [[]]
    (subject/uncondense-path [:a]) => [ [:a] ]
    (subject/uncondense-path [:a :b]) => [ [:a :b] ])

  (fact "forking paths"
    (fact "some synonyms"
      (subject/uncondense-path [:a (subject/->Fork [ [:b1] [:b2]]) :c]) => [ [:a :b1 :c] [:a :b2 :c] ]
      (subject/uncondense-path [:a (subject/through-each :b1 :b2) :c]) => [ [:a :b1 :c] [:a :b2 :c] ]
      (subject/uncondense-path [:a (subject/each-of :b1 :b2) :c]) => [ [:a :b1 :c] [:a :b2 :c] ])
    
    (fact "can stand alone as a path itself"
      (subject/uncondense-path (subject/each-of :b1 :b2)) => [ [:b1] [:b2] ])
    
    (fact "forks within forks"
      (subject/uncondense-path [:a
                                (subject/through-each [:b1 (subject/through-each :b2a :b2b)]
                                                      :c)
                                :d])
      => [ [:a :b1 :b2a :d]
           [:a :b1 :b2b :d]
           [:a :c :d] ]))
  
  (fact "uncondensing paths that contain paths from maps"
    (fact "an explicit map is just substituted"
      (let [point {[:x] [integer?], [:y] [integer?]}]
        (subject/uncondense-path [:a (subject/paths-of point)]) => [ [:a :x] [:a :y] ]))
    (fact "nested maps are flattened"
      (let [nested {:a 1 :b {:c 2 :d 3}}]
        (subject/uncondense-path [(subject/paths-of nested) :z]) => [ [:a :z]
                                                                      [:b :c :z]
                                                                      [:b :d :z] ]))
    
    (fact "Context: most usually, such maps from from type substitution"
      (let [point {[:x] [integer?], [:y] [integer?]}
            
            as-written [:a (subject/paths-of :Point)]
            type-substituted (type-expander/expand-throughout {:Point point} as-written)]
        (subject/uncondense-path type-substituted) => [ [:a :x] [:a :y] ]))))


;;;

(fact "maps can be flattened into ones with one level of nesting"
  (subject/map->flatmap {}) => {}

  (fact "canonicalizes keys (paths) and values (predicates)"
    (subject/map->flatmap {:a 1}) => {[:a] [1]}
    (subject/map->flatmap {[:a] [1]}) => {[:a] [1]})
  
  (fact "flattens sub-maps"
    (subject/map->flatmap {:a {:b 1}}) => {[:a :b] [1]}
    (subject/map->flatmap {[:c :d] {:e 1}}) => {[:c :d :e] [1]}
    (subject/map->flatmap {:f {[:g :h] {:i [3 4]
                                       [:j :k] 5}}}) => {[:f :g :h :i] [3 4]
                                                         [:f :g :h :j :k] [5]})
             
  (fact "when flattening causes duplicate paths, values are merged"
    (let [result (subject/map->flatmap {[:a :b :c] 1
                                       :a {[:b :c] 2}
                                       [:a :b] {:c 3 :d 4}})]
      (keys result) => (just [:a :b :c] [:a :b :d] :in-any-order)
      (result [:a :b :c]) => (just [1 2 3] :in-any-order)
      (result [:a :b :d]) => [4]))

  (fact "internal forking is handled"
    (subject/map->flatmap {[:a (subject/through-each :b1 :b2) :c] 1
                           [ (subject/each-of :b1 :b2) ] {[(subject/each-of :d1 :d2)] 2}})
    => {[:a :b1 :c] [1]
        [:a :b2 :c] [1]
        [:b1 :d1] [2]
        [:b1 :d2] [2]
        [:b2 :d1] [2]
        [:b2 :d2] [2]}
    (fact "resulting duplicates paths have values merged"
      (let [result (subject/map->flatmap {[:a :b1 :c] [1]
                                          [:a (subject/through-each :b1 :b2) :c] [2]})]
        (sort-map-vals result) => {[:a :b1 :c] [1 2]
                                   [:a :b2 :c] [2]})))

  (fact "paths can have maps in them"
    (subject/map->flatmap {:a [{:b 1}]}) => {[:a :b] [1]}
    (subject/map->flatmap {:a [1 {:b {:c 2}}]}) => {[:a] [1]
                                                    [:a :b :c] [2]}))
