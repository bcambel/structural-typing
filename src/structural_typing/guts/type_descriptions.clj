(ns structural-typing.guts.type-descriptions
  (:use structural-typing.clojure.core)
  (:require [structural-typing.guts.type-descriptions.canonicalizing :as canon]
            [structural-typing.guts.type-descriptions.dc-type-maps :as dc-type-maps]
            [structural-typing.guts.type-descriptions.m-ppps :as ppp]
            [structural-typing.guts.preds.from-type-descriptions :as compile]))


(defn ->finished-ppps [condensed-type-descriptions]
  (mapcat ppp/condensed-description->ppps condensed-type-descriptions))

(defn canonicalize [condensed-type-descriptions type-map]
  (when (empty? condensed-type-descriptions)
    (boom! "Canonicalize was called with no type descriptions. Type-map: %s" type-map))
  (-> (dc-type-maps/substitute type-map condensed-type-descriptions)
      ->finished-ppps
      ppp/->type-description))

(defn lift [condensed-type-descriptions type-map]
  (-> condensed-type-descriptions
      (canonicalize type-map)
      compile/compile-type))