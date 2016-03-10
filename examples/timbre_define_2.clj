(ns timbre-define-2
  "Using an Either monad to separate mistyped from valid values"
  (:require [structural-typing.type :as type]
            [structural-typing.preds :as preds]
            [structural-typing.assist.oopsie :as oopsie]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [taoensso.timbre :as timbre])
  ;; I know it's unfashionable, but in this case a separate `use` is clearer than :refer :all
  (:use [structural-typing.type :exclude [built-like all-built-like
                                          <>built-like <>all-built-like
                                          built-like?]]))

(timbre/set-level! :info)

;; Example 2: print a stack trace following the last error.

(def pprint-to-string #(with-out-str (pprint %)))

(defn error-explainer [oopsies]
  (timbre/info "While checking this:")
  (-> (first oopsies) ; the error handler is always given at least one oopsie.
      :whole-value    ; the original candidate being built-like
      pprint-to-string
      str/trimr       ; be tidy by getting rid of pprint's trailing newline
      timbre/info)
  (doseq [e (oopsie/explanations oopsies)] (timbre/info e))
  (timbre/error "Boundary type check failed"))

(def type-repo
  (-> empty-type-repo
      (named :Point
             (requires :x :y)
             {:x integer? :y integer?})
      (replace-error-handler error-explainer)))

;; Define namespace-local versions of the standard functions that default to this type-repo.
;; The standard functions are `built-like`, `all-built-like`, `<>built-like`,
;; `<>all-built-like`, and `built-like?`.

(type/ensure-standard-functions type-repo)

;; For example, clients can use this:
;;     (mytypes/built-like :Point x)
