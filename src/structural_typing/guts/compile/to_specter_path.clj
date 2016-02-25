(ns ^:no-doc structural-typing.guts.compile.to-specter-path
  (:use structural-typing.clojure.core)
  (:refer-clojure :exclude [compile])
  (:require [com.rpl.specter :as specter]
            [com.rpl.specter.protocols :as sp]
            [clojure.core.reducers :as r]
            [such.readable :as readable]
            [structural-typing.guts.self-check :as self :refer [returns-many built-like]]
            [structural-typing.guts.explanations :as explain]
            [structural-typing.guts.exval :as exval]
            [structural-typing.guts.expred :as expred]
            [structural-typing.guts.preds.wrap :as wrap]
            [structural-typing.assist.oopsie :as oopsie]
            [slingshot.slingshot :refer [throw+ try+]]))

(defn no-transform! []
  (boom! "structural-typing does not use transform"))

;; NOTE: Specter requires `extend-type/extend-protocol` instead of
;; defining the protocol functions in the deftype. It's an
;; implementation detail.













(defrecord KeywordVariantType [keyword])


(defn- associative-select* [accessor this structure next-fn]
  (cond (map? structure)
        (next-fn (get structure (accessor this)))

        ;; This code could return `nil` immediately, rather than calling `next-fn`.
        ;; That would (I think) provide an easier way of handling cases like this:
        ;;    (built-like {[:k ALL] required-path} {}) => (just (err:required :k))
        ;; ... than the current method, which relies on `add-implied-required-paths`.
        ;; However, that code was already added back when I was using Specter's
        ;; extension of `clojure.lang.Keyword` rather than rolling my own. It's easier
        ;; to keep it than take it out.
        ;;
        ;; Also this would allow something like `{[ALL :k some-VERY-peculiar-predicate] ...}`
        ;; to do something like, oh, replacing the nth `nil` with its count.
        (nil? structure)
        (next-fn nil)

        :else
        (boom! "%s is not a map" structure)))

(extend-type KeywordVariantType
  sp/StructurePath
  (select* [& args] (apply associative-select* :keyword args))
  (transform* [& _] (no-transform!)))

(defmethod clojure.core/print-method KeywordVariantType [o, ^java.io.Writer w]
  (.write w (str (.-keyword o))))

;;
(defrecord StringVariantType [string])

(extend-type StringVariantType
  sp/StructurePath
  (select* [& args] (apply associative-select* :string args))
  (transform* [& _] (no-transform!)))

(defmethod clojure.core/print-method StringVariantType [o, ^java.io.Writer w]
  (.write w (pr-str (.-string o))))

;;
(deftype IntegerVariantType [value])
  
(extend-type IntegerVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (cond (nil? structure)
          (next-fn nil)
          
          (not (sequential? structure))
          (boom! "%s is not sequential" structure)

          :else
          (try+
            (next-fn (nth structure (.-value this)))
            (catch IndexOutOfBoundsException ex
              (next-fn nil)))))
  (transform* [& _] (no-transform!)))

(defmethod clojure.core/print-method IntegerVariantType [o, ^java.io.Writer w]
  (.write w (str (.-value o))))




                                 ;;; ALL, RANGE, etc.

(defn pursue-multiple-paths [subcollection-fn collection next-fn]
  (into [] (r/mapcat next-fn (subcollection-fn collection))))


;;; ALL
(deftype AllVariantType [])

(extend-type AllVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (into [] (r/mapcat next-fn structure)))
  (transform* [& _] (no-transform!)))

(def ALL (->AllVariantType))

(defmethod clojure.core/print-method AllVariantType [o, ^java.io.Writer w] (.write w "ALL"))
(readable/instead-of ALL 'ALL)



;;; RANGE
(defn desired-range [{:keys [inclusive-start exclusive-end]} sequence]
  (let [desired-count (- exclusive-end inclusive-start)
        subseq (->> sequence
                    (drop inclusive-start)
                    (take desired-count)
                    vec)
        actual-count (count subseq)
        result (if (= actual-count desired-count)
                 subseq
                 (into subseq
                       (map vector
                            (drop (+ inclusive-start actual-count) (range))
                            (repeat (- desired-count actual-count) nil))))]
    result))

(defrecord RangeVariantType [inclusive-start exclusive-end])

(extend-type RangeVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (into [] (r/mapcat next-fn (desired-range this structure))))
  (transform* [& _] (no-transform!)))

(defn RANGE
  "Use this in a path to select a range of values in a 
   collection. The first argument is inclusive; the second exclusive.
   
       (type! :ELEMENTS-1-AND-2-ARE-EVEN {[(RANGE 1 3)] even?})
"
  [inclusive-start exclusive-end]
  (->RangeVariantType inclusive-start exclusive-end))


(defmethod clojure.core/print-method RangeVariantType [o, ^java.io.Writer w]
  (.write w (format "(RANGE %s %s)" (:inclusive-start o) (:exclusive-end o))))



;;; ONLY
(deftype OnlyVariantType [])

(extend-type OnlyVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (cond (not (coll? structure))
          (boom! "%s is not a collection" structure)

          (not= 1 (count structure))
          (throw+ {:type :only-wrong-count, :interior-node structure})

          :else
          (next-fn (first structure))))
  (transform* [& _] (no-transform!)))

(def ONLY (->OnlyVariantType))

(defmethod clojure.core/print-method OnlyVariantType [o, ^java.io.Writer w] (.write w "ONLY"))
(readable/instead-of ONLY 'ONLY)


;;; SOME
(deftype SomeVariantType [])

(extend-type SomeVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (into [] (r/mapcat next-fn structure)))
  (transform* [& _] (no-transform!)))

(def SOME (->SomeVariantType))

(defmethod clojure.core/print-method SomeVariantType [o, ^java.io.Writer w] (.write w "SOME"))
(readable/instead-of SOME 'SOME)



;;;;; 


(defn will-match-many? [elt]
  (or (#{ALL} elt)
      (instance? RangeVariantType elt)))

;; A pseudo-predicate to short-circuit processing with an error when a non-sequential is
;; to be given to RANGE. Note: although `nil` is actually non-sequential, it is allowed
;; because it typically represents a too-short sequence, which should get a different error.
(defn- range-requires-sequential! [x]
  (when (and (not (sequential? x))
             (not (nil? x)))
    (throw+ {:type :bad-range-target :interior-node x}))
  true)

(defn- short-circuit-on-nil [x]
  (not (nil? x)))

(defn- all-requires-collection! [x]  ;; assumes nil has already been filtered out.
  (when (or (map? x)
            (not (coll? x)))
    (throw+ {:type :bad-all-target :interior-node x}))
  true)

(defn- some-must-be-non-empty! [x]
  (when (empty? x)
    (throw+ {:type :some-wrong-count :interior-node x}))
  true)


(defn- surround-with-index-collector [elt]
  (vector (specter/view #(map-indexed vector %))
          elt
          (specter/collect-one specter/FIRST)
          specter/LAST))

(defn- prefix-with-elt-collector [original-elt dispatch-version-of-elt]
  (vector (specter/putval original-elt) dispatch-version-of-elt))


(defn- munge-path-appropriately [original-path]
  (loop [[elt & remainder] original-path
         specter-path []]
    (if (nil? elt)
      specter-path
      (let [new-path
            (cond (= ALL elt)
                  (into [short-circuit-on-nil all-requires-collection!]
                        (surround-with-index-collector elt))

                  (= SOME elt)
                  (into [some-must-be-non-empty!]
                        (surround-with-index-collector elt))

                  (instance? RangeVariantType elt)
                  (into [range-requires-sequential!]
                        (surround-with-index-collector elt))

                  (keyword? elt)
                  (prefix-with-elt-collector elt (->KeywordVariantType elt))

                  (string? elt)
                  (prefix-with-elt-collector elt (->StringVariantType elt))

                  (integer? elt)
                  (prefix-with-elt-collector elt (->IntegerVariantType elt))

                  :else
                  (prefix-with-elt-collector elt elt))]
        (recur remainder (into specter-path new-path))))))

;; The way we use Specter, it returns a sequence of entities. Each is a path with the
;; leaf value tacked on to the end.
(let [leaf-part last
      ;; `(butlast [x])` is nil, not []. Sigh.
      path-part (fn [x] (or (butlast x) []))
      lift-to-exvals (fn [leaves-with-paths whole-value]
                       (mapv #(exval/->ExVal (leaf-part %) (path-part %) whole-value)
                             leaves-with-paths))
      ;; In this context, the only kind of oopsie we'll get back is due to a Specter traversal
      ;; failure.
      traversal-oopsie? wrap/oopsie?]

  (defn compile [original-path]
    (let [compiled-path (apply specter/comp-paths (munge-path-appropriately original-path))]
      (fn [whole-value]
        ;; We instruct Specter to put accumulate each path element so as to make it available for
        ;; constructing the eventual ExVal. However, that doesn't work when the original path
        ;; is empty - `select` processing doesn't know to create the empty path-so-far. So we
        ;; fake it by wrapping the whole-value in a vector. That's the return value - which
        ;; happens to contain the correct empty path.
        (let [selectable-whole-value (if (empty? original-path) [whole-value] whole-value)
              [traversal-oopsies leaves-with-paths] (->> selectable-whole-value
                                                         (specter/compiled-select compiled-path)
                                                         (bifurcate traversal-oopsie?))]
          (vector traversal-oopsies
                  (lift-to-exvals leaves-with-paths whole-value)))))))

(defn mkfn:whole-value->oopsies [original-path lifted-preds]
  (let [path-traverser (compile original-path)]
    (fn [whole-value]
      (try+
       (let [[traversal-oopsies exvals] (path-traverser whole-value)]
         (into traversal-oopsies (mapcat lifted-preds exvals)))

       (catch [:type :bad-range-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-range-target original-path whole-value interior-node))

        (catch [:type :bad-all-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-all-target original-path whole-value interior-node))

        (catch [:type :bad-range-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-range-target original-path whole-value interior-node))

        (catch [:type :only-wrong-count] {:keys [interior-node]}
          (explain/as-oopsies:only-wrong-count original-path whole-value interior-node))

        (catch [:type :some-wrong-count] {:keys [interior-node]}
          (explain/as-oopsies:some-wrong-count original-path whole-value interior-node))

        (catch Exception ex
          (explain/as-oopsies:notpath original-path whole-value))))))
