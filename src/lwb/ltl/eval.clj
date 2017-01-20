; lwb Logic WorkBench -- Linear Temporal Logic: Evaluation aka Model Checking

; Copyright (c) 2016 Burkhardt Renz, THM. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.

(ns lwb.ltl.eval
  (:require [lwb.ltl :refer :all]
            [lwb.ltl.buechi :as ba]
            [lwb.ltl.kripke :as ks]
            [clojure.spec :as s]
            [clojure.set :as set])
  (:import (gov.nasa.ltl.graph degenSynchronousProduct SCCReduction Simplify SFSReduction Graph)) 
  )

;; # Evaluation in linear temporal logic LTL

;; Models in LTL are Kripke structures      
;; A formula of LTL is true in a Kripke structure, if it's true for all
;; paths beginning at the initial state of the Kripke structure


;; Evaluation of a LTL formula in a Kripke structure is model checking.     

;; The steps for checking if the Kripke structure `ks` fulfills the formula `phi` are:

;; 1. Construct a Büchi automaton corresponding to the given Kripke structure `ks`.
;; 2. Take the neagation `(not phi)` of the formula and translate it to a Büchi automaton.
;; 3. Build the synchronized product of the two automaton. A successful run
;;    in the product correspond to the initial runs of `ks`  satisfying `(not phi)`.
;;    Thus: if the product is empty, `phi` is true in `ks` otherwise the product
;;    automaton gives a counterexample.

(defn- nodes
  "Nodes of the product, where `ba1` is coming from the Kripke structure and `ba2` 
   from the LTL formula."
 [ba1 ba2]
  (for [n1 (:nodes ba1) n2 (:nodes ba2)]
    (let [init (if (and (:init n1) (:init n2)) {:init true})
          acc  (if (and (:accepting n1) (:accepting n2)) {:accepting true})
          node  (hash-map :id [(:id n1) (:id n2)])]
      (merge node init acc))))

(defn- edges
  "Edges of the product, where `ba1` is coming from the Kripke structure and `ba2` 
   from the LTL formula."
  [ba1 ba2]
  (let [edges' (for [e1 (:edges ba1) e2 (:edges ba2)]
    (if (set/subset? (:guard e2) (:guard e1))
      (hash-map :from [(:from e1) (:from e2)] :to [(:to e1) (:to e2)] :guard (:guard e1))))]
    (filter some? edges')))

(defn eval-phi
  "Checks whether the LTL formula `phi` is true in the Kripke structure `ks`.      
   Requires: the atoms in `phi` are a subset of `:atoms` in `ks`.     
   Returns: boolean or a counterexample if `mode` is `:counter-expl`.     
   The counterexample is a vector of ints indexing the nodes in the given
   Kripke structure beginning with index `1`."
  ([ks phi]
   (eval-phi ks phi :bool))
  ([ks phi mode]
   (let [ba1 (ba/ks->ba ks)
         ba2 (ba/ba (list 'not phi))
         bap (hash-map :nodes (vec (nodes ba1 ba2)) 
                      :edges (vec (edges ba1 ba2)))
         paths (ba/paths bap)]
     (if (= mode :bool)
       (if (empty? paths) true false)
       (vec (rest (map first (first paths))))))))

(defn- params-ok?
  "Is the pre condition of `eval-phi` fulfilled?      
   Validating the function `eval-phi` we get a map according to the definition
   of `:args`."
  [params]
  (set/subset? (atoms-of-phi (:phi params)) (:atoms (:ks params))))

(s/fdef eval-phi
        :args (s/& (s/alt :2-args (s/cat :ks ::ks/model :phi wff?)
                          :3-args (s/cat :ks ::ks/model :phi wff? :mode #{:bool :counter-expl})) #(params-ok? (second %)))
        :ret (s/alt :bool boolean? :counter-expl (s/coll-of int? :kind vector?)))

