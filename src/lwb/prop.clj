; lwb Logic WorkBench -- Propositional Logic

; Copyright (c) 2014 - 2017 Burkhardt Renz, THM. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.

(ns lwb.prop
  "Syntax, models and evaluation in propositional logic."
  (:require [clojure.spec :as s]
            [clojure.string :as str]
            [clojure.java.browse :as browse]
            [clojure.walk :refer (postwalk)]
            [clojure.set :refer (union intersection subset?)]
            [clojure.math.numeric-tower :refer (expt)]
            [clojure.math.combinatorics :refer (selections)]
            [lwb.vis :as vis]))

(defn man
  "Manual"
  []
  (browse/browse-url "https://github.com/esb-dev/lwb/wiki/prop"))

;; # Propositional logic

;; The namespace `lwb.prop` provides

;; * syntax
;;     - operators of propositional logic
;;     - checking whether a formula is well-formed
;; * models and evaluation
;;     - specification of a model in propositional logic
;;     - evaluation of formulas in a given model
;;     - the truth table for a formula
;; * normal forms
;;     - conjunctive normal form (cnf) 
;;     - disjunctive normal form (dnf)


;; ## Syntax of formulas of propositional logic

;; The propositional atoms are represented by Clojure symbols, 
;; e.g. `P` or `Q`. Operators, the constants `true` and `false`, and
;; symbols beginning with `ts-` are not allowed as atoms.

;; The propositional constants for truth (_verum_) and falsity (_falsum_) are represented
;; by `true` and `false`, respectively.

;; A propositional formula is an atom or a constant, or an expression composed
;; of boolean operators, propositional atoms and constants in the usual
;; lispy syntax, e.g. `(impl (and P (not P)) Q)`.

;; ### The operators of propositional logic

;; * `not`:   Negation - unary
;; * `and`:   Conjunction  -- n-ary
;; * `or`:    Disjunction  -- n-ary
;; * `impl`:  Implication  --  binary
;; * `equiv`: Equivalence  -- binary
;; * `xor`:   Exclusive or -- binary
;; * `ite`:  If-then-else  -- ternary

(defmacro impl
  "Logical implication."
  [phi psi]
  (list 'or
        (list 'not phi) psi))

(defmacro equiv
  "Logical equivalence."
  [phi psi]
  (list 'and
        (list 'impl phi psi)
        (list 'impl psi phi)))

(defmacro xor
  "Logical exclusive or."
  [phi psi]
  (list 'not
        (list 'equiv phi psi)))

(defmacro ite
  "Logical if-then-else."
  [i t e]
  (list 'or (list 'and i t)
        (list 'and (list 'not i) e)))

;; *Remark*: The definitions of the operators may not be changed to functions.
;; In the transformation of a formula to cnf it is useful and necessary that the operators
;; are defined as macros

;; ### Well-formed formulas

;; We can see a formula from the syntactic perspective as a finite sequence of
;; atoms, operators and parentheses that conform to the grammar of propositional logic.

(defn op?
  "Is `symb` an operator of propositional logic?"
  [symb]
  (contains? #{'not 'and 'or 'impl 'equiv 'xor 'ite} symb))

(s/fdef op?
  :args any?
  :ret  boolean?)

(def tseitin-prefix
  "Prefix for tseitin symbols."
  "ts-")

(defn atom?
  "Is `symb` an atomar proposition?"
  [symb]
  (and (symbol? symb) (not (op? symb))))

;; *Remark*     
;; Atoms beginning with `ts-` are not allowed, since such atoms are generated
;; during the Tseitin transformation of a formula.      
;; But: we do not check this, because `atom?` is internally used too, and there
;; atoms like `ts-1` are allowed!

(s/fdef atom?
        :args any?
        :ret  boolean?)

(defn arity
  "Arity of operator `op`.   
   -1 means n-ary.      
   nil if `op` is not an operator."
  [op]
  (cond
    ('#{not} op)            1
    ('#{impl equiv xor} op) 2
    ('#{ite} op)            3
    ('#{and or} op)        -1))

(s/fdef arity
        :args (s/cat :op any?)
        :ret  #{-1 1 2 3})

(defn nary?
  "Is `op` nary?"
  [op]
  (= -1 (arity op)))

(s/fdef nary?
        :args (s/cat :op any?)
        :ret  boolean?)

;; #### Definition of the grammar of propositional logic

;; A simple expression is an atom or a boolean constant.
(s/def ::simple-expr (s/or :bool boolean?
                           :atom atom?))

;; A compound expression is a list of an operator together with
;; several formulas as arguments whose number matches the arity of the operator.

(defn- arity-ok? [{:keys [op params]}]
  (let [arity (arity op)]
    (if (= arity -1) true
                     (= arity (count params)))))

(s/def ::compl-expr (s/and list? (s/& (s/cat :op op? :params (s/* ::fml)) arity-ok?)))

(s/def ::fml (s/or :simple-expr ::simple-expr
                   :compl-expr  ::compl-expr))

(defn wff?
  "Is the propositional formula `phi` well-formed?       
   `(wff? phi)` returns true or false.       
   `(wff? phi :msg)` returns true or a message describing the error in `phi`."
  ([phi]
   (wff? phi :bool))
  ([phi mode]
   (let [result (s/valid? ::fml phi)]
     (or result (if (= mode :msg) (s/explain-str ::fml phi) result)))))

(s/fdef wff?
  :args (s/alt :1-args (s/cat :phi any?)
               :2-args (s/cat :phi any? :mode #{:bool :msg}))
  :ret (s/alt :bool boolean? :msg string?))

;; ## Models and evaluation

;; ### Models in propositional logic

;; A model for a formula is a function from the atoms of the formula into the set of the
;; boolean values {true, false}.

;; We represent a model as a map of [atom boolean] entries.

(s/def ::model (s/map-of atom?  boolean?))

;; ### Evaluation of a formula of propositional logic with respect to a model

(defn eval-phi
  "Evaluates the formula `phi` with the given model.        
  `model` must be a valuation `{'atom1 true, 'atom2 false, ...}` for the
  propositional atoms of `phi`."
  [phi model]
  (let [mv (vec (interleave (keys model) (vals model)))]
    (binding [*ns* (find-ns 'lwb.prop)]
      (eval `(let ~mv ~phi)))))

;; Specification of the function `eval-phi`:
;; the first argument must be a well-formed formula, the second a model.
;; Furthermore the atoms in the formula must be a subset of the atoms in the model.

(defn atoms-of-phi
  "Sorted set of the propositional atoms of formula `phi`."
  [phi]
  (if (coll? phi)
    (apply sorted-set
           (filter #(not (or (op? %) (boolean? %))) (flatten phi)))
    (if (boolean? phi)
      #{}
      #{phi})))

(defn- atoms-in-model
  "Set of atoms in destructured model"
  [model]
  (set (keys model)))

(s/fdef eval-phi
        :args (s/and (s/cat :phi wff? :model (s/spec ::model))
                     #(subset? (atoms-of-phi (:phi %)) (set (keys (:model %)))))
        :ret boolean?)

;; ### Truth table for a formula of propostional logic

;; The truth table of a proposition `phi` is represented as a map
;; with the keys:

;; `:phi` with the proposition itself
;; `:header` a vector of the atoms and the last entry
;;  named `:result`.          
;; `:table` a vector of vectors of boolean assignments to the 
;; corresponding atom in the header as well as the result of the evaluation.

(s/def ::phi wff?)

(s/def ::header (s/and vector? #(and (every? atom? (butlast %)) (= :result (last %)))))

(defn- vector-of-boolean? [vec]
  (every? boolean? vec))

(s/def ::table (s/coll-of (s/coll-of boolean? :kind vector?) :kind vector?))

;; Remark: There are more constraints on the truth table:

;; 1. The atoms in `:header` are just the unique atoms in `:phi`
;; 2.`:table` has 2^n elements for n the number of atoms (<= in mode :true-only or :false-only)
;; 3. The length of the vectors in `:table` equals the length of `:header`

(defn- truth-table-ok?
  [{:keys [phi header table]}]
  (let [atoms (atoms-of-phi phi)
        atoms' (set (butlast header))
        row-cnt (expt 2 (count atoms))
        col-cnt (count header)]
    (and (= atoms atoms')
         (= (count table) row-cnt)
         (every? #(= col-cnt (count %)) table))))

(defn- truth-table-ok'?
  [{:keys [phi header table]}]
  (let [atoms (atoms-of-phi phi)
        atoms' (set (butlast header))
        row-cnt (expt 2 (count atoms))
        col-cnt (count header)]
    (and (= atoms atoms')
         (<= (count table) row-cnt)
         (every? #(= col-cnt (count %)) table))))

(s/def ::truth-table (s/and truth-table-ok? (s/keys :req-un [::phi ::header ::table])))
(s/def ::truth-table' (s/and truth-table-ok'? (s/keys :req-un [::phi ::header ::table])))

;; #### Calculation of the truth table

(defn truth-table
  "Truth table of `phi`.   
   If `mode` is `:true-only` the table contains only the valuations where
   `phi` evaluates to `true`.   
   For `mode` of `:false-only` accordingly."
  ([phi]
   (let [atoms (atoms-of-phi phi)]
     (if (> (count atoms) 10)
       (throw (IllegalArgumentException.
                (str "This formula has more than 10 variables."
                     \newline
                     "The truth table would consist of more than 1024 rows,"
                     "so you might want to use `sat`.")))
       (let [all-combs (selections [true false] (count atoms))
             assign-maps (for [comb all-combs] (zipmap atoms comb))]
         {:phi    phi
          :header (conj (vec atoms) :result)
          :table  (vec (for [assign-map assign-maps]
                         (conj (vec (vals assign-map))
                               (eval-phi phi assign-map))))}))))
  ([phi mode]
   (let [tt (truth-table phi)]
     (condp = mode
       :true-only (assoc tt :table (filterv #(true? (last %)) (:table tt)))
       :false-only (assoc tt :table (filterv #(false? (last %)) (:table tt)))
       tt))))

(s/fdef truth-table
        :args (s/alt :1-args (s/cat :phi wff?)
                     :2-args (s/cat :phi wff? :mode #{:true-only :false-only}))
        :ret (s/alt ::truth-table ::truth-table'))

(defn- print-table
  "Pretty prints vector `header` and vector of vectors `table`.   
   Pre: header and row in the table have the same no of item,
        the size of items in header is >= size of items in the rows."
  ; inspired from clojure.pprint
  [header table]
  (let [table' (vec (map #(replace {true "T" false "F"} %) table))
        widths (map #(count (str %)) header)
        spacers (map #(str/join (repeat % "-")) widths)
        fmts (map #(str "%" % "s") widths)
        fmt-row (fn [leader divider trailer row]
                  (str leader
                       (str/join divider
                                 (for [[col fmt] (map vector row fmts)]
                                   (format fmt (str col))))
                       trailer))]
    (println)
    (println (fmt-row "| " " | " " |" header))
    (println (fmt-row "|-" "-+-" "-|" spacers))
    (doseq [row table']
      (println (fmt-row "| " " | " " |" row)))))

(defn print-truth-table
  "Pretty prints truth-table."
  [{:keys [prop header table]}]
  (do
    (println "Truth table")
    (println prop)
	  (print-table header table)))

(s/fdef print-truth-table
        :args (s/cat :tt ::truth-table')
        :ret  nil?)

(defn ptt
  "Pretty prints truth-table for `phi`."
  [phi]
  (->> phi
       truth-table
       print-truth-table))

(s/fdef ptt
        :args (s/cat :phi wff?)
        :ret  nil?)

;; ## Normal forms

;; ### Transformation to conjunctive normal form

;; (Standardized) conjunctive normal form     
;; Conjunctive normal form in lwb is defined as a formula of the form
;; `(and (or ...) (or ...) (or ...) ...)` where the clauses contain
;; only literals, no constants --
;; or the trivially true or false formula.

;; I.e. in lwb when transforming formula to cnf, we reduce it to this
;; standard form.

;; Specification of a literal
(s/def ::literal (s/or :simple-expr ::simple-expr
                       :neg (s/and list? (s/cat :not #{'not} :simple-expr ::simple-expr))))

;; Specification of a clause
(s/def ::clause (s/and list? (s/cat :or #{'or} :literals (s/* ::literal))))

;; Specification of conjunctive normal form cnf
(s/def ::cnf (s/and list? (s/cat :and #{'and} :clauses (s/* ::clause))))

;; Caveat reader!
;; at the first sight `(apply list ...)` and `(list* ...)` seems to give the
;; same result -- BUT
;; the types are different:        
;; `(list? (apply list [:a :b])) => true`       
;; `(list? (list* [:a :b]))      => false`      
;; since we check `list?` in the specs we have to be precise with the types
;; our functions are returning!

;; One may argue that in the spirit of Clojure it's better to work with
;; sequences whatever their implementation would be.       
;; But: we want to use the data structure of a formula as code
;; and as such it has to be a list!

(defn literal?
  "Checks whether `phi` is a literal, i.e. a propositional atom or its negation."
  [phi]
  (s/valid? ::literal phi))

(s/fdef literal?
        :args (s/cat :phi wff?)
        :ret  boolean?)

(defn impl-free 
  "Normalize formula `phi` such that just the operators `not`, `and`, `or` are used."
  [phi]
  (if (literal? phi)
    phi
	  (let [op (first phi)]
      (if (contains? #{'and 'or 'not} op) 
        (apply list op (map impl-free (rest phi)))
        (let [exp-phi (macroexpand-1 phi)]
          (apply list (first exp-phi) (map impl-free (rest exp-phi))))))))

(s/fdef impl-free
        :args (s/cat :phi wff?)
        :ret  wff?)

(defn nnf
  "Transforms an impl-free formula `phi` into negation normal form."
  [phi]
  (if (literal? phi) 
    phi
    (let [[op & more] phi]
      (if (contains? #{'and 'or} op)
        (apply list op (map nnf more))
        (let [[second-op & second-more] (second phi)]
          (if (contains? #{'and 'or} second-op)
            (nnf (apply list (if (= 'and second-op) 'or 'and) (map #(list 'not %) second-more)))
            (nnf (first second-more))))))))

(s/fdef nnf
        :args (s/cat :phi wff?)
        :ret  wff?)

(defn- distr
  "Application of the distributive laws to the given formulas"
  ([phi] phi)
  ([phi-1 phi-2]
    (cond
      (and (not (literal? phi-1)) (= 'and (first phi-1)))
        (apply list 'and (map #(distr % phi-2) (rest phi-1)))
      (and (not (literal? phi-2)) (= 'and (first phi-2)))
        (apply list 'and (map #(distr phi-1 %) (rest phi-2)))
      :else
        (list 'or phi-1 phi-2)))
  ([phi-1 phi-2 & more]
    (reduce distr (distr phi-1 phi-2) more)))

(defn nnf->cnf 
  "Transforms the formula `phi` from nnf to cnf."
  [phi]
  (cond
    (literal? phi) (list 'and (list 'or phi))
    (= '(or) phi) '(and (or))
    (= 'and (first phi)) (apply list 'and (map nnf->cnf (rest phi)))
    (= 'or (first phi)) (apply distr (map nnf->cnf (rest phi)))))

(s/fdef nnf->cnf
        :args (s/cat :phi wff?)
        :ret  wff?)

(defn flatten-ops
  "Flattens the formula `phi`.      
   Nested binary applications of n-ary operators will be transformed to the n-ary form,
   e.g. `(and (and a b) c) => (and a b c)`."
  [phi]
  (let [flat-step (fn [sub-phi]
						        (if (and (coll? sub-phi) (nary? (first sub-phi)))
						          (let [op (first sub-phi)
                            args (rest sub-phi)
						                flat-filter (fn [op arg] (if (coll? arg) (= op (first arg)) false))
						                flat (map rest (filter (partial flat-filter op) args))
						                not-flat (filter (partial (complement flat-filter) op) args)]
						            (apply concat `((~op) ~@flat ~not-flat)))
					             sub-phi))]
        (postwalk flat-step phi)))

; Caveat: The function flatten-ops is used for propositional logic and predicate logic
; Thus the following spec is not possible, since it would fail in predicate logic
#_(s/fdef flatten-ops
        :args (s/cat :phi wff?)
        :ret  wff?)

(defn- clause->sets
  "Transforms a clause `(or ...)` into a map of the sets of `:pos` atoms 
   and `:neg` atoms in the clause."
  [cl]
  (apply merge-with union
         (for [literal (rest cl)]
					  (if (or (symbol? literal) (instance? Boolean literal))
					      {:pos #{literal}}
					      {:neg #{(second literal)}}))))

(defn- red-clmap 
  "Reduces a clause in the form `{:pos #{...} :neg #{...}`    
   by considering:     
   (1) :pos contains true  -> clause is trivially true,   
   (2) :neg contains false -> clause is trivially true,   
   (3) :pos contains false -> false can be deleted,   
   (4) :neg contains true  -> true can be deleted."
  [{:keys [pos neg]}]
  (cond
    (pos? (count (intersection pos neg))) true
    (contains? pos 'true) true
    (contains? neg 'false) true
    :else
      (let [pos' (filter #(not= 'false %) pos), neg' (filter #(not= 'true %) neg)]
        (conj (apply list (concat (apply list pos') (map #(list 'not %) neg'))) 'or))))

(defn- red-cnf
  "Reduces a formula `ucnf` of the form `(and (or ...) (or ...) ...)`."
  [ucnf]
  (let [result (filter #(not= 'true %) (map #(red-clmap (clause->sets %)) (rest ucnf)))]
    (cond
      (some #{'(or)} result) false
      (empty? result) true
      :else (conj (apply list (distinct result)) 'and))))

(defn cnf
  "Transforms `phi` to (standardized) conjunctive normal form cnf."
  [phi]
  (-> phi impl-free nnf nnf->cnf flatten-ops red-cnf))

;; Specification of function `cnf`
;; `:ret` is in cnf and equivalent to the argument `:phi`.
(s/fdef cnf
        :args (s/cat :phi wff?)
        :ret (s/alt :cnf ::cnf :bool boolean?))

(comment
  ;the spec of the function has a cyclic dependency as a consequence!
  ;:fn #(lwb.prop.sat/valid? (list 'equiv (-> % :args :phi) (-> % :ret))))
  )

(defn cnf?
  "Is `phi` in (standardized) conjunctive normal form?
   `(cnf? phi)` returns true or false.       
   `(cnf? phi :msg)` returns true or a message describing the error in `phi`."
  ([phi]
   (cnf? phi :bool))
  ([phi mode]
   (let [result (s/valid? ::cnf phi)]
     (or result (if (= mode :msg) (s/explain-str ::cnf phi) result)))))

(s/fdef cnf?
        :args (s/alt :1-args (s/cat :phi wff?)
                     :2-args (s/cat :phi wff? :mode #{:bool :msg}))
        :ret (s/alt :bool boolean? :msg string?))

;; ### Transformation to disjunctive normal form

;; Specification of monom
(s/def ::monom (s/and list? (s/cat :and #{'and} :literals (s/* ::literal))))

;; Specification of disjunctive normal form dnf
(s/def ::dnf (s/and list? (s/cat :or #{'or} :monoms (s/* ::monom))))

; helper to transform (cnf (not phi)) to (dnf phi)
(defn- mapdnfi
  "maps clause to monom in transformation to dnf."
  [inner]
  (cond
    (= inner 'or) 'and
    (list? inner) (second inner)
    :else (list 'not inner)))

(defn- mapdnf
  [outer]
  (if (= outer 'and) 'or
                     (apply list (map mapdnfi outer))))

(defn dnf
  "Transforms `phi` to disjunctive normal form dnf."
  [phi]
  (let [cnf (cnf (list 'not phi))]
    ; border case
    (if (boolean? cnf)
      (not cnf)
      (apply list (map mapdnf cnf)))))

;; Specification of function `dnf`
;; `:ret` is in dnf and equivalent to the argument `:phi`.
(s/fdef dnf
        :args (s/cat :phi wff?)
        :ret (s/or :dnf ::dnf :bool boolean?))

(defn dnf?
  "Is `phi` in (standardized) disjunctive normal form?
   `(dnf? phi)` returns true or false.       
   `(dnf? phi :msg)` returns true or a message describing the error in `phi`."
  ([phi]
   (dnf? phi :bool))
  ([phi mode]
   (let [result (s/valid? ::dnf phi)]
     (or result (if (= mode :msg) (s/explain-str ::dnf phi) result)))))

(s/fdef dnf?
        :args (s/alt :1-args (s/cat :phi wff?)
                     :2-args (s/cat :phi wff? :mode #{:bool :msg}))
        :ret (s/alt :bool boolean? :msg string?))

;; ## Visualisation of a formula

(defn texify
  "Generates TeX code for TikZ or a pdf file if filename given.        
   Requires: TeX installation, commands texipdf and open"
  ([phi]
   (vis/texify phi))
  ([phi filename]
   (vis/texify phi filename)))

(s/fdef texify
        :args (s/alt :1-args (s/cat :phi wff?)
                     :2-args (s/cat :phi wff? :filename string?))
        :ret  nil?)

