(ns structural-typing.pred-writing.testutil
  (:require [structural-typing.pred-writing.shapes.exval :as exval]
            [structural-typing.pred-writing.shapes.oopsie :as oopsie]
            [structural-typing.pred-writing.lifting :as lifting]
            [such.readable :as readable]))

(defn exval
  ([leaf-value path whole-value]
     (exval/boa leaf-value path whole-value))
  ([leaf-value path]
     (exval leaf-value path (hash-map path leaf-value)))
  ([leaf-value]
     (exval leaf-value [:x])))

(defn explain-lifted
  "Note that it's safe to use this on an already-lifted predicate"
  [pred exval]
  (oopsie/explanations ((lifting/lift pred) exval)))

;; Don't use Midje checkers to avoid dragging in all of its dependencies

(defn oopsie-for [leaf-value & {:as kvs}]
  (let [expected (assoc kvs :leaf-value leaf-value)]
    (fn [actual]
      (= (select-keys actual (keys expected)) expected))))

(defn both-names [pred]
  (let [plain (readable/fn-string pred)
        lifted (readable/fn-string (lifting/lift pred))]
    (if (= plain lifted)
      plain
      (format "`%s` mismatches `%s`" plain lifted))))