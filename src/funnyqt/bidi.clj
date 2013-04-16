(ns funnyqt.bidi
  (:refer-clojure :exclude [==])
  (:use clojure.core.logic)
  (:use funnyqt.relational.util)
  (:require [funnyqt.relational.tg :as rtg]
            [funnyqt.relational.tmp-elem :as tmp]
            [funnyqt.tg :as tg]
            [funnyqt.utils :as u]))

;; Either :left or :right
(def ^:dynamic *target-direction*)

(defn sort-matches [matches]
  (into (sorted-set-by
         (fn [a b]
           (let [diff (- (count (filter tmp/tmp-element? (vals a)))
                         (count (filter tmp/tmp-element? (vals b))))]
             (if (zero? diff)
               (compare (vec a) (vec b))
               diff))))
        matches))

(defn select-best-match [matches]
  (first (sort-matches matches)))

(defn enforce-match [match]
  (let [tmps (filter tmp/tmp-element? (vals match))]
    (doseq [el tmps]
      (tmp/manifest el))))

(defmacro checkonly
  ([] `clojure.core.logic/s#)
  ([& goals] `(fn [a#]
                (binding [tmp/*make-tmp-elements* false]
                  (bind* a# ~@goals)))))

(defn ^:private make-kw-result-map [syms]
  (apply hash-map
         (mapcat (fn [qs]
                   [(keyword (name qs)) qs])
                 syms)))

(defn ^:private make-relation-binding-vector [syms]
  (vec (mapcat (fn [sym]
                 [sym `(or ~sym (lvar ~(name sym)))])
               syms)))

(defn ^:private make-destr-map
  ([syms]
     {:keys (vec (set syms))})
  ([syms as]
     {:keys (vec (set syms)) :as as}))

(defn ^:private qmark-sym? [sym]
  (and
   (symbol? sym)
   (= (first (clojure.core/name sym)) \?)))

(defn ^:private do-rel-body [trg map wsyms src-syms trg-syms]
  (let [src (if (= trg :right) :left :right)
        tm (gensym "trg-match")]
    `(doseq [~(make-destr-map (concat wsyms src-syms))
             (doall (run* [q#]
                      ~@(:when map)
                      ~@(get map src)
                      (== q# ~(make-kw-result-map src-syms))))]
       (let [~(make-destr-map trg-syms tm)
             (select-best-match
              (binding [tmp/*make-tmp-elements* true]
                (doall
                 (run* [q#]
                   ~@(:when map)
                   ~@(get map trg)
                   (== q# ~(make-kw-result-map trg-syms))))))]
         (enforce-match ~tm)
         ~@(:where map)))))

(defn convert-relation [[name & more]]
  (let [map (apply hash-map more)
        body (concat (:left map) (:right map))
        wsyms (distinct (filter qmark-sym? (flatten (:when map))))
        lsyms (distinct (filter qmark-sym? (flatten (:left map))))
        rsyms (distinct (filter qmark-sym? (flatten (:right map))))
        syms (distinct (concat lsyms rsyms))]
    `(~name [& ~(make-destr-map syms)]
            (let ~(make-relation-binding-vector syms)
              (if (= *target-direction* :right)
                ~(do-rel-body :right map wsyms lsyms rsyms)
                ~(do-rel-body :left  map wsyms rsyms lsyms))))))

(defmacro deftransformation [name [left right] & relations]
  (let [top-rels (filter #(:top (meta (first %))) relations)]
    (when (empty? top-rels)
      (u/error "There has to be at least one :top rule!"))
    `(defn ~name [~left ~right dir#]
       (when-not (or (= dir# :left) (= dir# :right))
         (u/errorf "Direction parameter must either be :left or :right but was %s."
                   dir#))
       (letfn [~@(map convert-relation relations)]
         (binding [*target-direction* dir#]
           ~@(map (fn [r] `(~(first r))) top-rels))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(rtg/generate-schema-relations "test/input/greqltestgraph.tg")
(def g1 (tg/load-graph "test/input/greqltestgraph.tg"))
(def g2 (tg/create-graph (tg/load-schema "test/input/greqltestgraph.tg")))

(deftransformation route-map2route-map [g1 g2]
  (^:top county2county
         :left [(+County g1 ?c1)
                (+name g1 ?c1 ?n)]
         :right [(+County g2 ?c2)
                 (+name g2 ?c2 ?n)]
         :where [(city2city :?county1 ?c1 :?county2 ?c2)])
  (city2city
   :left [(+ContainsLocality g1 ?hc1 ?county1 ?c1)
          (+City g1 ?c1)
          (+name g1 ?c1 ?n)]
   :right [(+ContainsLocality g2 ?hc2 ?county2 ?c2)
           (+City g2 ?c2)
           (+name g2 ?c2 ?n)]
   :where [(plaza2plaza :?loc1 ?c1 :?loc2 ?c2)])
  (plaza2plaza
   :left [(+ContainsCrossroad g1 ?cc1 ?loc1 ?plaza1)
          (+Plaza g1 ?plaza1)
          (+name g1 ?plaza1 ?n)]
   :right [(+ContainsCrossroad g2 ?cc2 ?loc2 ?plaza2)
           (+Plaza g2 ?plaza2)
           (+name g2 ?plaza2 ?n)]))

;(route-map2route-map g1 g2 :right)


