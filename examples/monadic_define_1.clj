(ns monadic-define-1
  "Using an Either monad to separate mistyped from valid values"
  (:require [structural-typing.type :as type]
            [structural-typing.preds :as preds]
            [structural-typing.assist.oopsie :as oopsie]
            [blancas.morph.monads :as m])
  ;; I know it's unfashionable, but in this case a separate `use` is clearer than :refer :all
  (:use [structural-typing.type :exclude [built-like all-built-like
                                          <>built-like <>all-built-like
                                          built-like?]]))

;; The following code is explained in the wiki at
;; https://github.com/marick/structural-typing/wiki/Using-the-Either-monad
;; See `monadic-use` for examples.

(def type-repo
  (-> empty-type-repo
      (named :Point
             (requires :x :y)
             {:x integer? :y integer?})
      (named :FormsTriangle
             {:x (complement zero?) :y (complement zero?)})
      (replace-success-handler m/right)
      (replace-error-handler (oopsie/mkfn:apply-to-explanation-collection m/left))))

(def built-like (partial type/built-like type-repo))
(def all-built-like (partial type/all-built-like type-repo))
(def <>built-like #(type/<>built-like %1 type-repo %2))
(def <>all-built-like #(type/<>all-built-like %1 type-repo %2))
(def built-like? (comp m/right? built-like))

