(ns ^:no-doc structural-typing.guts.compile.to-specter-path
  (:use structural-typing.clojure.core)
  (:refer-clojure :exclude [compile])
  (:require [com.rpl.specter :as specter]
            [com.rpl.specter.protocols :as sp]
            [clojure.core.reducers :as r]
            [such.readable :as readable]
            [structural-typing.guts.self-check :as self :refer [returns-many]]
            [structural-typing.guts.explanations :as explain]
            [structural-typing.guts.exval :as exval]
            [structural-typing.guts.expred :as expred]
            [structural-typing.guts.preds.wrap :as wrap]
            [structural-typing.assist.oopsie :as oopsie]
            [slingshot.slingshot :refer [throw+ try+]]))


;; NOTE: Specter requires `extend-type/extend-protocol` instead of
;; defining the protocol functions in the deftype. It's an
;; implementation detail.

(deftype KeywordVariantType [keyword])

(extend-type KeywordVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (cond (map? structure)
          (next-fn (get structure (.-keyword this)))

          (nil? structure)
          (next-fn nil)

          :else
          (boom! "%s is not a map" structure)))
  (transform* [this structure next-fn] (boom! "structural-typing does not use transform")))

(defmethod clojure.core/print-method KeywordVariantType [o, ^java.io.Writer w]
  (.write w (str (.-keyword o))))

;;
(deftype StringVariantType [string])

(extend-type StringVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (cond (map? structure)
          (next-fn (get structure (.-string this)))

          (nil? structure)
          (next-fn nil)

          :else
          (boom! "%s is not a map" structure)))
  (transform* [this structure next-fn] (boom! "structural-typing does not use transform")))

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
  (transform* [kw structure next-fn] (boom! "structural-typing does not use transform")))

(defmethod clojure.core/print-method IntegerVariantType [o, ^java.io.Writer w]
  (.write w (str (.-value o))))




                                 ;;; ALL, RANGE, etc.

(defn pursue-multiple-paths [subcollection-fn collection next-fn]
  (cond (nil? collection)
        (next-fn nil)

        (not (coll? collection))
        (boom! "%s is not a collection" collection)

        :else
        (into [] (r/mapcat next-fn (subcollection-fn collection)))))


;;; ALL
(deftype AllVariantType [])

(extend-type AllVariantType
  sp/StructurePath
  (select* [this structure next-fn] (pursue-multiple-paths identity structure next-fn))
  (transform* [kw structure next-fn] (boom! "structural-typing does not use transform")))

(def ALL (->AllVariantType))

(defmethod clojure.core/print-method AllVariantType [o, ^java.io.Writer w] (.write w "ALL"))
(readable/instead-of ALL 'ALL)



;;; RANGE
(defn mkfn:range-element-selector [{:keys [inclusive-start exclusive-end]}]
  (fn [sequence]
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
      result)))

(defrecord RangeVariantType [inclusive-start exclusive-end])

(extend-type RangeVariantType
  sp/StructurePath
  (select* [this structure next-fn]
    (if (or (map? structure) (set? structure))
      (boom! "Cannot take a map or a set")
      (pursue-multiple-paths (mkfn:range-element-selector this) structure next-fn)))
  (transform* [kw structure next-fn] (boom! "structural-typing does not use transform")))

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
          (throw+ {:type :only, :interior-node structure})

          :else
          (next-fn (first structure))))

  (transform* [kw structure next-fn] (boom! "structural-typing does not use transform")))

(def ONLY (->OnlyVariantType))

(defmethod clojure.core/print-method OnlyVariantType [o, ^java.io.Writer w] (.write w "ONLY"))
(readable/instead-of ONLY 'ONLY)



;;;;; 


(defn will-match-many? [elt]
  (or (#{ALL} elt)
      (instance? RangeVariantType elt)))

(defn replace-with-indices [path indices]
  (loop [result []
         [p & ps] path
         indices indices]
    (cond (nil? p)
          result

          (will-match-many? p)
          (recur (conj result (first indices))
                 ps
                 (rest indices))

          :else
          (recur (conj result p)
                 ps
                 indices))))


;; A pseudo-predicate to short-circuit processing with an error when a non-sequential is
;; to be given to RANGE. Note: although `nil` is actually non-sequential, it is allowed
;; because it typically represents a too-short sequence, which should get a different error.
(defn- range-requires-sequential! [x]
  (when (and (not (sequential? x))
             (not (nil? x)))
    (throw+ {:type :bad-range-target :interior-node x}))
  true)

(defn- all-may-not-be-nil! [x]
  (when (nil? x)
    (throw+ {:type :nil-all}))
  true)

(defn- all-requires-collection! [x]
  (when-not (coll? x)
    (boom! "Temporarily make this a 'notpath' error"))
    ;(throw+ {:type :bad-all-target :interior-node x}))
  true)


(defn- surround-with-index-collector [elt]
  [(specter/view (partial map-indexed vector))
   elt
   (specter/collect-one specter/FIRST)
   specter/LAST])

(defn compile [original-path]
   (loop [[elt & remainder] original-path
          specter-path []
          path-type :constant-path]
     (cond (nil? elt)
           [(apply specter/comp-paths specter-path) path-type]


           (= ALL elt)
           (recur remainder
                  (-> specter-path
                      ;; This is probably not the right way to accomplish this.
                      ;; (conj all-may-not-be-nil!)
                      (conj all-requires-collection!)
                      (into (surround-with-index-collector elt)))
                  :indexed-path)

           (instance? RangeVariantType elt)
           (recur remainder
                  (-> specter-path
                      (conj range-requires-sequential!)
                      (into (surround-with-index-collector elt)))
                  :indexed-path)

           (keyword? elt)
           (recur remainder
                  (conj specter-path (->KeywordVariantType elt))
                  path-type)

           (string? elt)
           (recur remainder
                  (conj specter-path (->StringVariantType elt))
                  path-type)

           (integer? elt)
           (recur remainder
                  (conj specter-path (->IntegerVariantType elt))
                  path-type)

           :else
           (recur remainder (conj specter-path elt) path-type))))

(def constant-path-exval-maker exval/->ExVal)
(defn constant-path-postprocessor [specter-result oopsie] oopsie)
(defn indexed-path-exval-maker [specter-result original-path whole-value]
  (exval/->ExVal (last specter-result) original-path whole-value))
(defn indexed-path-postprocessor [specter-result raw-oopsie]
  (update raw-oopsie :path #(replace-with-indices % (butlast specter-result))))

(defn processors [path-type]
  (case path-type
    :constant-path [constant-path-exval-maker constant-path-postprocessor]
    :indexed-path [indexed-path-exval-maker indexed-path-postprocessor]
    (boom! "%s is an invalid path-type (neither constant nor indexed)" path-type)))

(defn mkfn:whole-value->oopsies [original-path lifted-preds]
  (let [[compiled-path path-type] (compile original-path)
        [exval-maker path-postprocessor] (processors path-type)]
    (fn [whole-value]
      (try+
        (let [specter-results (specter/compiled-select compiled-path whole-value)]
          (for [result specter-results
                raw-oopsie (lifted-preds (exval-maker result original-path whole-value))]
            (path-postprocessor result raw-oopsie)))

        (catch [:type :bad-range-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-range-target original-path whole-value interior-node))

        (catch [:type :bad-all-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-all-target original-path whole-value interior-node))

        (catch [:type :bad-range-target] {:keys [interior-node]}
          (explain/as-oopsies:bad-range-target original-path whole-value interior-node))

        (catch [:type :nil-all] {}
          (explain/as-oopsies:nil-all original-path whole-value))

        (catch [:type :only] {:keys [interior-node]}
          (explain/as-oopsies:only original-path whole-value interior-node))

        (catch Exception ex
          (explain/as-oopsies:notpath original-path whole-value))))))
