(ns structural-typing.guts.explanations
  "All the non-custom error messages, together with functions
   and oopsies that contain them."
  (:use structural-typing.clojure.core)
  (:require [structural-typing.guts.exval :as exval]
            [structural-typing.guts.expred :as expred]
            [structural-typing.assist.oopsie :as oopsie]))

(defn- structural-oopsie
  [original-path whole-value message]
  (merge (expred/->ExPred 'check-for-bad-structure
                          "bad structure detected"
                          (constantly message))
         (exval/->ExVal :halted-before-leaf-value-found
                        whole-value
                        original-path)))

(defn pluralize
  "It's convenient for callers to get back a singleton list of oopsies, rather
   than the single oopsie the `maker` returns."
  [maker]
  (fn [& args] (vector (apply maker args))))

;;; ---

(defn err:only
  "The error message produced by `ONLY` when a collection does not have only one element."
  [collection-with-bad-arity]
  (cl-format nil "`~S` is supposed to have exactly one element" collection-with-bad-arity))


(defn oopsie:only [original-path whole-value collection-with-bad-arity]
  (structural-oopsie original-path whole-value (err:only collection-with-bad-arity)))
(def as-oopsies:only (pluralize oopsie:only))

;;; ---

(defn err:notpath
  "Produces the same error messsage produced when the whole value is not the same \"shape\" as the path requires."
  [original-path whole-value]
  (cl-format nil "~A is not a path into `~S`"
             (oopsie/friendly-path {:path original-path})
             whole-value))

(defn oopsie:notpath [original-path whole-value]
  (structural-oopsie original-path whole-value (err:notpath original-path whole-value)))
(def as-oopsies:notpath (pluralize oopsie:notpath))