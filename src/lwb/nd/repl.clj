; lwb Logic WorkBench -- Natural deduction

; Copyright (c) 2015 Tobias Völzel, THM. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.

(ns lwb.nd.repl
  (:require [lwb.nd.deduction :as deduc]
            [lwb.nd.prereqs :refer :all]
            [lwb.nd.io :as io]
            [lwb.nd.printer :refer [pprint]]))

(io/import-rules "resources/nd/rules-prop-pred.clj")
;(io/import-rules "resources/nd/rules-ltl.clj")
(io/import-trivials "resources/nd/trivial-theorems.clj")
(io/import-theorems "resources/nd/theorems-prop.clj")
(io/import-theorems "resources/nd/theorems-pred.clj")
;(io/import-theorems "resources/nd/theorems-ltl.clj")

;; holds the actual state of the proof 
(def p (atom []))
;; holds the last steps since the beginning of the proof (for undo steps)
(def last_steps (atom []))

(defn show
  "Print the actual state of the proof"
  []
  (pprint @p))

(defn proof
  "Start a new proof"
  ([formula] (proof [] formula))
  ([premises formula]
    (reset! last_steps [])
    (reset! p (deduc/proof premises formula))
    (show)))

(defn step-f
  "Execute a forward step"
  [rule & lines]
  (swap! last_steps conj @p)
  (swap! p #(apply deduc/step-f (conj (conj lines rule) %)))
  (show))

(defn step-f-inside
  "Executes a forward step inside the chosen line"
  [rule line]
  (swap! last_steps conj @p)
  (swap! p #(apply deduc/step-f-inside (conj (list rule line) %)))
  (show))

(defn step-b
  "Execute a backward step"
  [rule & lines]
  (swap! last_steps conj @p)
  (swap! p #(apply deduc/step-b (conj (conj lines rule) %)))
  (show))

(defn choose-option
  "Choose an option in the chosen line"
  [line num]
  (swap! last_steps conj @p)
  (swap! p deduc/choose-option line num)
  (show))

(defn unify
  "Unifies symbols"
  [old new]
  (swap! last_steps conj @p)
  (swap! p deduc/unify old new)
  (show))

(defn trivial
  "Apply the trivial-theorems inside the chosen line"
  [line]
  (swap! last_steps conj @p)
  (swap! p deduc/trivial line)
  (show))

(defn export-theorem
  "Export the solved proof to a file as a theorem"
  [filename name]
  (io/export-theorem 
    @p
    filename
    name))

(defn undo
  "Undo the last change (you can't go further than the last state)"
  []
  (if (empty? @last_steps)
    (println "You reached the starting point, there is nothing more to undo")
    (do
      (reset! p (last @last_steps))
      (swap! last_steps #(into [] (drop-last %)))
      (show))))

(defn show-rules []
  (for [rule (sort @io/rules)]
    (let [name (subs (str (key rule)) 1)
          given (:given (val rule))
          conclusion (:conclusion (val rule))
          forward? (:forwards (val rule))
          backward? (:backwards (val rule))
          usage (case [forward? backward?]
                  [true true] "step-f and step-b"
                  [true nil ] "step-f only"
                  [nil  true] "step-b only")]
      (println (str name ": \t" given " -> " conclusion " - " usage)))))

(defn show-theorems []
  (for [rule (sort @io/theorems)]
    (let [name (subs (str (key rule)) 1)
          given (:given (val rule))
          conclusion (:conclusion (val rule))]
      (println (str name ": \t" given " -> " conclusion)))))
