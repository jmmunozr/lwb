; lwb Logic WorkBench -- Boolean Cardinality Constraints

; Copyright (c) 2014 Burkhardt Renz, THM. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.


(ns lwb.prop.cardinality
  (:require [clojure.math.combinatorics :refer (combinations)] )
)  

(defn min-kof 
  "(min-kof k coll) -> a seq of clauses expressing that 
   at least k of the symbols in coll are true."
  [k coll]
  {:pre [(<= 1 k (count coll))]}
  (map #(cons 'or %)(combinations coll (inc (- (count coll) k)))))

;(min-kof 1 '[p q])
        

(defn max-kof 
  "(max-kof k coll) -> a seq of clauses expressing that 
   at most k of the symbols in coll are true."
  [k coll]
  {:pre [(<= 0 k (dec (count coll)))]}
  (if (= k 0)
    (map #(list 'not %) coll))
          (for [s (combinations coll (inc k))]
            (cons 'or (map #(list 'not %) s))))

;(max-kof 0 '[p q])
;(max-kof 1 '[p q])

;(max-kof 0 '[p q r])
;(max-kof 1 '[p q r])
;(max-kof 2 '[p q r])

(defn kof
  "(kof k coll) -> a seq of clauses expressing that
   exactly k of the symbols in coll are true."
  [k coll]
  (condp = k
    0            (max-kof 0 coll)
    (count coll) (min-kof k coll)
    (concat (min-kof k coll) (max-kof k coll))))

;(kof 1 '[p q])
;(kof 2 '[p q])
;(kof 0 '[p q])
;(kof 1 '[p q r])
;(kof 2 '[p q r])

(defn oneof
  "(oneof k coll) -> a seq of clauses expressing that
   exactly 1 symbol in coll ist true."
  [coll]
  (if (empty? coll)
    false
    (kof 1 coll)))

;(oneof '[p q])
;(oneof '[p q r])
;(oneof '[p q])