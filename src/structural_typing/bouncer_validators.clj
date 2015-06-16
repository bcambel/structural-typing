(ns structural-typing.bouncer-validators
  "A facade over bouncer, decoupling (somewhat) this library from its details"
  (:require [structural-typing.frob :as frob]))




(defn nested-map->path-map
  "In single-argument form, converts a nested map into a flat one where the keys
   a vectors with a path representing the existing nested structure. Keys that
   are already vectors terminate the descent. In the two-arg form, the resulting
   paths have the `parent-path` prepended"
  ([kvs]
     (nested-map->path-map [] kvs))
  ([parent-path kvs]
     (reduce (fn [so-far [k v]]
                 (cond (vector? k)
                       (assoc so-far (into parent-path k) v)

                       (map? v)
                       (merge so-far (nested-map->path-map (conj parent-path k) v))

                       :else
                       (assoc so-far (conj parent-path k) v)))
          {}
          kvs)))

(defn flatten-path-representation
  "Convert an atom into a vector of that sequential.
   Convert a sequential into a flattened vector.
   A vector with a subvector of length N produces N flattened vectors."
  ([v]
     (if (sequential? v)
       (flatten-path-representation [[]] v)
       (vector v)))
       
  ([parent-paths v]
     (cond (empty? v)
           parent-paths

           (sequential? (first v))
           (let [extended (for [pp parent-paths, elt (first v)]
                            (conj pp elt))]
             (flatten-path-representation (vec extended) (rest v)))

           :else
           (let [extended (for [pp parent-paths] (conj pp (first v)))]
             (flatten-path-representation (vec extended) (rest v))))))


(defn flatten-N-path-representations [v]
  (vec (mapcat flatten-path-representation v)))

