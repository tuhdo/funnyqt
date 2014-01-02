(ns funnyqt.pmatch
  "Pattern Matching."
  (:require clojure.set
            clojure.string
            [clojure.tools.macro :as m]
            [funnyqt.protocols   :as p]
            [funnyqt.query       :as q]
            [funnyqt.utils       :as u]
            [funnyqt.tg          :as tg]
            [funnyqt.query       :as q]
            [funnyqt.query.tg    :as tgq]
            [funnyqt.emf         :as emf]
            [funnyqt.query.emf   :as emfq]))

;; TODO: Patterns and rules should support ^:perf-stat metadata which records
;; the number of nodes of the types occuring in the pattern in the host graph.
;; Then users can check if their pattern is anchored at the right node, or if
;; they should reformulate it to speed up things.

;;# Pattern to pattern graph

(defn ^:private vertex-sym? [sym]
  (and (symbol? sym)
       (re-matches #"[a-zA-Z0-9_]*(<[a-zA-Z0-9._!]*>)?" (name sym))))

(defn ^:private edge-sym? [sym]
  (and
   (symbol? sym)
   (re-matches #"<?-[!a-zA-Z0-9_]*(<[a-zA-Z0-9._!]*>)?->?" (name sym))
   (or (re-matches #"<-.*-" (name sym))
       (re-matches #"-.*->" (name sym)))))

(defn ^:private name-and-type [sym]
  (if (or (vertex-sym? sym) (edge-sym? sym))
    (let [[_ s t] (re-matches #"(?:<-|-)?([!a-zA-Z0-9_]+)?(?:<([.a-zA-Z0-9_!]*)>)?(?:-|->)?"
                              (name sym))]
      [(and (seq s) (symbol s)) (and (seq t) (symbol t))])
    (u/errorf "No valid pattern symbol: %s" sym)))

(defn ^:private neg-edge-sym? [sym]
  (and (edge-sym? sym)
       (= '! (first (name-and-type sym)))))

(defn ^:private edge-dir [esym]
  (if (edge-sym? esym)
    (if (re-matches #"<-.*" (name esym))
      :in
      :out)
    (u/errorf "%s is not edge symbol." esym)))

(defonce ^:private pattern-schema
  (tg/load-schema (clojure.java.io/resource "pattern-schema.tg")))

(defn call-binding-vars
  "Returns the symbols bound by a :call in pattern p."
  [p]
  (loop [p p, r []]
    (if (seq p)
      (if (= :call (first p))
        (recur (nnext p) (into r (flatten (map first (partition 2 (fnext p))))))
        (recur (next p) r))
      r)))

(defn pattern-to-pattern-graph [pname argvec pattern]
  (let [callbounds (into #{} (call-binding-vars pattern))
        argset (into #{} argvec)
        pg (let [g (tg/new-graph pattern-schema)]
             (tg/set-value! g :patternName (if pname (name pname) "--anonymous--"))
             g)
        get-by-name (fn [n]
                      (first (filter #(= (name n) (tg/value % :name))
                                     (concat (tg/vseq pg 'APatternVertex)
                                             (tg/eseq pg '[PatternEdge ArgumentEdge])))))
        check-unique (fn [n t]
                       (when (and n t (get-by-name n))
                         (u/errorf "A pattern element with name %s is already declared!" n))
                       (when (and t (argset n))
                         (u/errorf "The pattern declares %s although that's an argument already!" n)))
        get-or-make-v (fn [n t]
                        (if-let [v (and n (get-by-name n))]
                          v
                          (let [v (tg/create-vertex! pg (cond
                                                         (argset n)     'ArgumentVertex
                                                         (callbounds n) 'CallBoundVertex
                                                         :else          'PatternVertex))]
                            (when n (tg/set-value! v :name (name n)))
                            (when t (tg/set-value! v :type (name t)))
                            v)))]
    (loop [pattern pattern, lv (tg/create-vertex! pg 'Anchor)]
      (when (seq pattern)
        (cond
         ;; Constraints and non-pattern binding forms ;;
         (#{:when :let :when-let :while :call} (first pattern))
         (let [v (tg/create-vertex! pg 'ConstraintOrBinding)]
           (tg/set-value! v :form
                          (if (= :call (first pattern))
                            (str (pr-str (fnext pattern)) "]")
                            (str "[" (str (pr-str (first pattern))  " ")
                                 (pr-str (fnext pattern)) "]")))
           (doseq [ex-v (remove #(= v %) (tg/vseq pg))]
             (tg/create-edge! pg 'Precedes ex-v v))
           (recur (nnext pattern) v))
         ;; Edge symbols ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (edge-sym? (first pattern)) (let [sym (first pattern)
                                           [n t] (name-and-type sym)
                                           nsym (second pattern)
                                           [nvn nvt] (name-and-type nsym)
                                           _ (check-unique nvn nvt)
                                           nv (get-or-make-v nvn nvt)]
                                       (let [e (apply tg/create-edge!
                                                      pg (cond
                                                          (= '! n)   'NegPatternEdge
                                                          (argset n) 'ArgumentEdge
                                                          :else      'PatternEdge)
                                                      (if (= :out (edge-dir sym))
                                                        [lv nv]
                                                        [nv lv]))]
                                         (when (and n (not (p/has-type? e 'NegPatternEdge)))
                                           (tg/set-value! e :name (name n)))
                                         (when t (tg/set-value! e :type (name t))))
                                       (recur (nnext pattern) nv))
         ;; Vertex symbols ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (vertex-sym? (first pattern)) (let [sym (first pattern)
                                             [n t] (name-and-type sym)
                                             v (get-or-make-v n t)]
                                         (when (zero? (tg/ecount pg 'HasStartPatternVertex))
                                           (tg/create-edge! pg 'HasStartPatternVertex
                                                            (q/the (tg/vseq pg 'Anchor)) v))
                                         (recur (rest pattern) v))
         :else (u/errorf "Don't know how to handle pattern part: %s" (first pattern)))))
    ;; Anchor disconnected components at the anchor.
    (let [vset (u/oset (tg/vseq pg))
          a (q/the (tg/vseq pg 'Anchor))
          reachables #(tgq/reachables % [q/p-* tgq/<->])]
      (loop [disc (filter #(p/has-type? % 'PatternVertex)
                          (clojure.set/difference vset (reachables a)))]
        (when (seq disc)
          (tg/create-edge! pg 'HasStartPatternVertex a (first disc))
          (recur (clojure.set/difference vset (reachables a))))))
    (when-let [argv (seq (tg/vseq pg 'ArgumentVertex))]
      (let [hfpv (q/the (tg/eseq pg 'HasStartPatternVertex))]
        (when (p/has-type? (tg/omega hfpv) '!ArgumentVertex)
          (println
           (format "The pattern %s could perform better by anchoring it at an argument node."
                   (tg/value pg :patternName))))))
    pg))

;;# Pattern comprehension

(defn ^:private shortcut-when-let-vector [lv]
  (letfn [(whenify [s]
            (if (coll? s)
              (mapcat (fn [v] [:when v]) s)
              [:when s]))]
    (mapcat (fn [[s v]]
              (concat [:let [s v]]
                      (whenify s)))
            (partition 2 lv))))

(defn ^:private shortcut-when-let-bindings
  "Converts :when-let [x (foo), y (bar)] to :let [x (foo)] :when x :let [y (bar)] :when y."
  [bindings]
  (loop [p bindings, nb []]
    (if (seq p)
      (if (= :when-let (first p))
        (recur (rest (rest p))
               (vec (concat nb (shortcut-when-let-vector (fnext p)))))
        (recur (rest (rest p)) (conj (conj nb (first p)) (second p))))
      (vec nb))))

(defmacro pattern-for
  [seq-exprs body-expr]
  (let [seq-exprs (shortcut-when-let-bindings seq-exprs)
        [bind exp] seq-exprs]
    (condp = bind
      :let `(let ~exp
              (pattern-for ~(vec (rest (rest seq-exprs)))
                           ~body-expr))
      :when `(when ~exp
               (pattern-for ~(vec (rest (rest seq-exprs)))
                            ~body-expr))
      ;; default
      (if (seq seq-exprs)
        `(for ~seq-exprs
           ~body-expr)
        (sequence nil)))))

;;# Patter graph to pattern comprehension

(defn ^:private enqueue-incs
  ([cur stack done]
     (enqueue-incs cur stack done false))
  ([cur stack done only-out]
     (into stack (remove done
                         (tg/riseq cur nil (when only-out :out))))))

(defn ^:private conj-done [done & elems]
  (into done (mapcat #(if (tg/edge? %)
                        (vector % (tg/inverse-edge %))
                        (vector %))
                     elems)))

(defn ^:private get-name [elem]
  (when-let [n (tg/value elem :name)]
    (symbol n)))

(defn ^:private anon? [elem]
  (or (p/has-type? elem 'NegPatternEdge)
      (not (get-name elem))))

(defn ^:private get-type [elem]
  (when (p/has-type? elem '[PatternVertex PatternEdge NegPatternEdge])
    (when-let [t (tg/value elem :type)]
      `'~(symbol t))))

(defn ^:private anon-vec [startv done]
  (loop [cur startv, done done, vec []]
    (if (and cur (anon? cur))
      (cond
       (tg/edge? cur)   (recur (tg/that cur)
                               (conj-done done cur)
                               (conj vec cur))
       (tg/vertex? cur) (recur (let [ns (remove done (tg/iseq cur 'PatternEdge))]
                                 (if (> (count ns) 1)
                                   (u/errorf "Must not happen!")
                                   (first ns)))
                               (conj-done done cur)
                               (conj vec cur))
       :else (u/errorf "Unexpected %s." cur))
      (if cur
        (conj vec cur)
        vec))))

(defn ^:private validate-bf [bf done pg]
  (when-let [missing (seq (remove done (concat (tg/vseq pg) (tg/eseq pg))))]
    (u/errorf "Some pattern elements were not reached: %s" missing))
  bf)

(defn ^:private do-anons [anon-vec-transformer-fn startsym av done]
  (let [target-node (last av)]
    (cond
     (anon? target-node)
     [:when `(seq ~(anon-vec-transformer-fn startsym av))]
     ;;---
     (done target-node)
     [:when `(q/member? ~(get-name target-node)
                        ~(anon-vec-transformer-fn startsym av))]
     ;;---
     ;; Not already done ArgumentVertex, so declare it!
     (p/has-type? target-node 'ArgumentVertex)
     [:when-let `[~(get-name target-node) ~(get-name target-node)]
      :when `(q/member? ~(get-name target-node)
                        ~(anon-vec-transformer-fn startsym av))]
     ;;---
     (p/has-type? target-node 'CallBoundVertex)
     [:when `(q/member? ~(get-name target-node)
                        ~(anon-vec-transformer-fn startsym av))]
     ;;---
     :normal-v
     [(get-name target-node)
      `(q/no-dups ~(anon-vec-transformer-fn startsym av))])))

(defn ^:private deps-defined?
  "Returns true if all nodes defined before the COB cob have been processed."
  [done cob]
  ;;(println cob done)
  (q/forall? done (map tg/that (tg/iseq cob 'Precedes :in))))

(defn pattern-graph-to-pattern-for-bindings-tg [argvec pg]
  (let [gsym (first argvec)
        anon-vec-to-for (fn [start-sym av]
                          (let [[v r]
                                (loop [cs start-sym, av av, r []]
                                  (if (seq av)
                                    (let [el (first av)
                                          ncs (gensym)]
                                      (recur ncs
                                             (rest av)
                                             (if (tg/vertex? el)
                                               (into r `[:let [~ncs (tg/that ~cs)]
                                                         ~@(when-let [t (get-type el)]
                                                             [:when `(p/has-type? ~ncs ~t)])])
                                               (into r `[~ncs (tg/iseq ~cs ~(get-type el)
                                                                       ~(if (tg/normal-edge? el)
                                                                          :out :in))]))))
                                    [cs r]))]
                            `(for ~r ~v)))]
    (loop [stack [(q/the (tg/vseq pg 'Anchor))]
           done #{}
           bf []]
      (if (seq stack)
        (let [cur (peek stack)]
          (if (done cur)
            (recur (pop stack) done bf)
            (case (p/qname cur)
              Anchor
              (recur (enqueue-incs cur (pop stack) done)
                     (conj-done done cur)
                     bf)
              HasStartPatternVertex
              (recur (conj (pop stack) (tg/that cur))
                     (conj-done done cur)
                     bf)
              PatternVertex
              (recur (enqueue-incs cur (pop stack) done)
                     (conj-done done cur)
                     (into bf `[~(get-name cur) (tg/vseq ~gsym ~(get-type cur))]))
              ArgumentVertex
              (recur (enqueue-incs cur (pop stack) done)
                     (conj-done done cur)
                     (if (done cur) bf (into bf `[:when-let [~(get-name cur) ~(get-name cur)]])))
              CallBoundVertex  ;; They're bound by ConstraintOrBinding/Preceedes
              (recur (enqueue-incs cur (pop stack) done)
                     (conj-done done cur)
                     bf)
              PatternEdge
              (if (anon? cur)
                (let [av (anon-vec cur done)
                      target-node (last av)
                      done (conj-done done cur)]
                  ;;(println av)
                  (recur (enqueue-incs target-node (pop stack) done)
                         (apply conj-done done av)
                         (into bf (do-anons anon-vec-to-for (get-name (tg/this cur)) av done))))
                (let [trg (tg/that cur)
                      done (conj-done done cur)]
                  (recur (enqueue-incs trg (pop stack) done)
                         (conj-done done trg)
                         (apply conj bf `~(get-name cur)
                                `(tg/iseq ~(get-name (tg/this cur)) ~(get-type cur)
                                          ~(if (tg/normal-edge? cur) :out :in))
                                (cond
                                 (done trg) [:when `(= ~(get-name trg) (tg/that ~(get-name cur)))]
                                 (anon? trg) (do-anons anon-vec-to-for
                                                       `(tg/that ~(get-name cur))
                                                       (anon-vec trg done) done)
                                 ;;---
                                 (p/has-type? trg 'ArgumentVertex)
                                 [:when-let [(get-name trg) (get-name trg)]
                                  :when `(= ~(get-name trg) (tg/that ~(get-name cur)))]
                                 ;;---
                                 :else (concat
                                        [:let `[~(get-name trg) (tg/that ~(get-name cur))]]
                                        (when-let [t (get-type trg)]
                                          `[:when (p/has-type? ~(get-name trg) ~t)])))))))
              ArgumentEdge
              (let [src (tg/this cur)
                    trg (tg/that cur)]
                (recur (enqueue-incs trg (pop stack) done)
                       (conj-done done cur trg)
                       (apply conj bf :when `(= ~(get-name src) (tg/this ~(get-name cur)))
                              (cond
                               (done trg) [:when `(= ~(get-name trg) (tg/that ~(get-name cur)))]
                               ;;---
                               (p/has-type? trg 'ArgumentVertex)
                               [:when-let [(get-name trg) (get-name trg)]
                                :when `(= ~(get-name trg) (tg/that ~(get-name cur)))]
                               ;;---
                               :else (concat
                                      [:let `[~(get-name trg) (tg/that ~(get-name cur))]]
                                      (when-let [t (get-type trg)]
                                        `[:when (p/has-type? ~(get-name trg) ~t)]))))))
              NegPatternEdge
              (let [src (tg/this cur)
                    trg (tg/that cur)
                    done (conj-done done cur)]
                (if (done trg)
                  (recur (enqueue-incs trg (pop stack) done)
                         (conj-done done trg)
                         (into bf `[:when (empty? (filter
                                                   #(= ~(get-name trg) (tg/that %))
                                                   (tg/iseq ~(get-name src) ~(get-type cur)
                                                            ~(if (tg/normal-edge? cur) :out :in))))]))
                  (recur (enqueue-incs trg (pop stack) done)
                         (conj-done done trg)
                         (into bf `[~@(when-not (anon? trg)
                                        `[~(get-name trg) (tg/vseq ~gsym ~(get-type trg))])
                                    :when (empty? (tg/iseq ~(get-name src) ~(get-type cur)
                                                           ~(if (tg/normal-edge? cur) :out :in)))]))))
              Precedes
              (let [cob (tg/omega cur)]
                (if (deps-defined? done cob)
                  (recur (pop stack)
                         (apply conj-done done cob (tg/iseq cob 'Precedes :in))
                         (into bf (read-string (tg/value cob :form))))
                  (recur (pop stack)
                         (conj-done done cur)
                         bf))))))
        (validate-bf bf done pg)))))

(defn eget-1
  "Only for internal use."
  [eo r]
  (when-let [x (emf/eget-raw eo r)]
    (if (instance? java.util.Collection x) x [x])))

(defn pattern-graph-to-pattern-for-bindings-emf [argvec pg]
  (let [gsym (first argvec)
        get-edge-type (fn [e]
                        (when-let [t (get-type e)]
                          (keyword (second t))))
        anon-vec-to-for (fn [start-sym av]
                          (let [[v r]
                                (loop [cs start-sym, av av, r []]
                                  (if (seq av)
                                    (let [el (first av)
                                          ncs (if (tg/vertex? el) cs (gensym))]
                                      (recur ncs
                                             (rest av)
                                             (if (tg/vertex? el)
                                               (into r (when-let [t (get-type el)]
                                                         [:when `(p/has-type? ~ncs ~t)]))
                                               (into r `[~ncs ~(if-let [t (get-edge-type el)]
                                                                 `(eget-1 ~cs ~t)
                                                                 `(emf/erefs ~cs))]))))
                                    [cs r]))]
                            `(for ~r ~v)))]
    ;; Check there are only anonymous edges.
    (when-not (every? anon? (tg/eseq pg 'APatternEdge))
      (u/errorf "Edges mustn't be named for EMF: %s"
                (mapv p/describe (remove anon? (tg/eseq pg 'APatternEdge)))))
    (loop [stack [(q/the (tg/vseq pg 'Anchor))]
           done #{}
           bf []]
      (if (seq stack)
        (let [cur (peek stack)]
          (if (done cur)
            (recur (pop stack) done bf)
            (case (p/qname cur)
              Anchor
              (recur (enqueue-incs cur (pop stack) done true)
                     (conj-done done cur)
                     bf)
              HasStartPatternVertex
              (recur (conj (pop stack) (tg/that cur))
                     (conj-done done cur)
                     bf)
              PatternVertex
              (recur (enqueue-incs cur (pop stack) done true)
                     (conj-done done cur)
                     (into bf `[~(get-name cur) (emf/eallobjects ~gsym ~(get-type cur))]))
              ArgumentVertex
              (recur (enqueue-incs cur (pop stack) done true)
                     (conj-done done cur)
                     (if (done cur) bf (into bf `[:when-let [~(get-name cur) ~(get-name cur)]])))
              CallBoundVertex  ;; Actually bound by ConstraintOrBinding/Precedes
              (recur (enqueue-incs cur (pop stack) done true)
                     (conj-done done cur)
                     bf)
              PatternEdge
              (if (anon? cur)
                (let [av (anon-vec cur done)
                      target-node (last av)
                      done (conj-done done cur)]
                  (recur (enqueue-incs target-node (pop stack) (apply conj-done done av) true)
                         (apply conj-done done cur av)
                         (into bf (do-anons anon-vec-to-for
                                            (get-name (tg/this cur)) av done))))
                (u/errorf "Edges mustn't be named for EMF: %s" (p/describe cur)))
              NegPatternEdge
              (let [src (tg/this cur)
                    trg (tg/that cur)
                    done (conj-done done cur)]
                (if (done trg)
                  (recur (enqueue-incs trg (pop stack) done)
                         (conj-done done trg)
                         (into bf `[:when (not (q/member? ~(get-name trg)
                                                          ~(if-let [t (get-edge-type cur)]
                                                             `(eget-1 ~(get-name src) ~t)
                                                             `(emf/erefs ~(get-name src)))))]))
                  (recur (enqueue-incs trg (pop stack) done)
                         (conj-done done trg)
                         (into bf `[~@(when-not (anon? trg)
                                        `[~(get-name trg) (emf/eallobjects
                                                           ~gsym ~(get-type trg))])
                                    :when (empty? ~(if-let [t (get-edge-type cur)]
                                                     `(eget-1 ~(get-name src) ~t)
                                                     `(emf/erefs ~(get-name src))))]))))
              ArgumentEdge
              (u/errorf "There mustn't be argument edges for EMF: %s" (p/describe cur))
              Precedes
              (let [cob (tg/omega cur)]
                (if (deps-defined? done cob)
                  (recur (pop stack)
                         (apply conj-done done cob (tg/iseq cob 'Precedes :in))
                         (into bf (read-string (tg/value cob :form))))
                  (recur (pop stack)
                         (conj-done done cur)
                         bf))))))
        (validate-bf bf done pg)))))

(defn bindings-to-arglist
  "Rips out the symbols declared in `bindings`.
  `bindings` is a binding vector with the syntax of `for`."
  [bindings]
  (loop [p bindings, l []]
    (if (seq p)
      (cond
       ;; Handle :let [x y, [u v] z]
       (or (= :let (first p))
           (= :call (first p))
           (= :when-let (first p)))
       (recur (rest (rest p))
              (vec (concat l
                           (loop [ls (first (rest p)) bs []]
                             (if (seq ls)
                               (recur (rest (rest ls))
                                      (let [v (first ls)]
                                        (if (coll? v)
                                          (into bs v)
                                          (conj bs v))))
                               bs)))))
       ;; Ignore :when (exp ...)
       (keyword? (first p)) (recur (rest (rest p)) l)
       ;; A vector destructuring form
       (vector? (first p)) (recur (rest (rest p)) (vec (concat l (first p))))
       ;; Another destructuring form
       (coll? (first p))
       (u/errorf "Only vector destructuring is permitted outside :let, got: %s"
                 (first p))
       ;; That's a normal binding
       :default (recur (rest (rest p)) (conj l (first p))))
      (vec l))))

(defn ^:private verify-pattern-binding-form
  "Ensure that the pattern vector doesn't declare bindings twice, which would
  be a bug."
  [pattern args]
  (let [blist (bindings-to-arglist pattern)]
    (if-let [double-syms (seq (mapcat (fn [[sym freq]]
                                        (when (> freq 1)
                                          (str "- " sym " is declared " freq " times\n")))
                                      (frequencies blist)))]
      (u/errorf "These symbols are declared multiple times:\n%s"
                (clojure.string/join double-syms))
      pattern)))

(def ^:dynamic *pattern-expansion-context*
  "Defines the expansion context of a pattern, i.e., if a pattern expands into
  a query on a TGraph or an EMF model.  The possible values are :tg or :emf.

  Usually, you won't bind this variable directly (using `binding`) but instead
  you specify the expansion context for a given pattern using the `attr-map` of
  a `defpattern` or `letpattern` form, or you declare the expansion context for
  a complete namespace using `:pattern-expansion-context` metadata for the
  namespace."
  nil)

(def pattern-graph-transform-function-map
  "A map from techspace to pattern graph transformers."
  {:emf pattern-graph-to-pattern-for-bindings-emf
   :tg  pattern-graph-to-pattern-for-bindings-tg})

(defn ^:private get-and-remove-from-vector [v key get-val]
  (loop [nv [], ov v]
    (if (seq ov)
      (if (= key (first ov))
        (if get-val
          [(vec (concat nv (nnext ov))) (fnext ov)]
          [(vec (concat nv (next ov))) (first ov)])
        (recur (conj nv (first ov)) (rest ov)))
      [nv nil])))

(defn transform-pattern-vector
  "Transforms patterns like [a<X> -<role>-> b<Y>] to a binding vector suitable
  for `pattern-for`.  That vector contains metadata :distinct and :as.

(Only for internal use.)"
  [name pattern args]
  (let [[pattern distinct] (get-and-remove-from-vector pattern :distinct false)
        [pattern result] (get-and-remove-from-vector pattern   :as       true)
        pgraph (pattern-to-pattern-graph name args pattern)
        ;;_ (future (funnyqt.visualization/print-model pgraph :gtk))
        transform-fn (pattern-graph-transform-function-map *pattern-expansion-context*)]
    (if transform-fn
      (with-meta (transform-fn args pgraph)
        {:distinct distinct
         :as       result})
      (u/errorf "The pattern expansion context is not set.\n%s"
                "See `*pattern-expansion-context*` in the pmatch namespace."))))

(defn ^:private convert-spec [name args-and-pattern]
  (when (> (count args-and-pattern) 2)
    (u/errorf "Pattern %s has too many components (should have only args and pattern vector)." name))
  (let [[args pattern] args-and-pattern]
    (when-not (and (vector? args) (vector? pattern))
      (u/errorf "Pattern %s is missing the args or pattern vector. Got %s." name args-and-pattern))
    (let [bf (transform-pattern-vector name pattern args)
          iteration-code `(pattern-for ~bf ~(or (:as (meta bf)) (bindings-to-arglist bf)))]
      (verify-pattern-binding-form bf args)
      `(~args
        ~(if (:distinct (meta bf))
           `(q/no-dups ~iteration-code)
           iteration-code)))))

(defmacro defpattern
  "Defines a pattern with `name`, optional `doc-string`, optional `attr-map`,
  an `args` vector, and a `pattern` vector.  When applied to a model, it
  returns the lazy seq of all matches.

  `pattern` is a vector of symbols for nodes and edges.

    v<V>            ; A node of type V identified as v
    v<V> -e<E>-> v  ; An edge of type E starting and ending at node v of type V

  Both the identifiers (v and e above) and the types enclosed in angle brackets
  are optional.  So this is a valid pattern, too.

    [v --> <V> -<E>-> <> --> x<X>] ; An arbitrary node that is connected to
                                   ; an X-node x via some arbitrary forward
                                   ; edge leading to some V-node from which
                                   ; an E-edge leads some arbitrary other
                                   ; node from which another arbitrary edge
                                   ; leads to x.

  Such sequences of anonymous paths, i.e., edges and nodes without identifier,
  must be anchored at named nodes like above (v and x).  Note that anonymous
  paths result in fewer matches than if the intermediate nodes/edges were
  named.  E.g., in a model with four nodes n1, n2, n3, and n4, and the edges n1
  --> n2, n1 --> n3, n2 --> n4, and n3 --> n4, the pattern

    [a --> b --> c]

  has two matches [n1 n2 n4], and [n1 n3 n4].  In contrast, the pattern

    [a --> <> --> c]

  has only one match [n1 n4], because the anonymous intermediate node only
  enforces the existence of a path from a to c without creating a match for
  every such path.

  Patterns may also include the arguments given to the defpattern, in which
  case those are assumed to be bound to one single node or edge, depending on
  their usage in the pattern, e.g., arg must be a node and -arg-> must be an
  edge.

  Patters may further include arbitrary constraints that must hold for a valid
  match using :when clauses:

    [v --> w
     :when (pred1? v)
     :when (not (pred2? w))]

  Patterns may contain negative edges indicated by edge symbols with name !.
  Those must not exist for a match to succeed.  For example, the following
  declares that there must be a Foo edge from v to w, but w must have no
  outgoing edges at all, and v and w must not be connected with a forward Bar
  edge.

    [v -<Foo>-> w -!-> <>
     v -!<Bar>-> w]

  Moreover, a pattern may bind further variables using :let and :when-let.

    [v --> w
     :let [a (foo v), b (bar v)]
     :when-let [c (baz w)]]

  Hereby, the variables bound by :let (a and b) are taken as-is whereas the
  variables bound by :when-let must be logically true in order to match.  That
  is, in the example above, a and b could be nil, but c has to be logically
  true (i.e., not nil and not false) for a match to occur.

  Patterns may also include calls to other patterns and usual comprehension
  binding forms using :call, i.e., pairs of variables and expressions.

    [v --> w
     :call [u (reachables w [p-seq [p-+ [p-alt <>-- [<--- 'SomeEdgeType]]]])]]

  By default, the matches of a pattern are represented as vectors containing
  the matched elements in the order of their declaration in the pattern.

    [v --> w
     :when-let [u (foobar w)]]

  So in the example above, each match is represented as a vector of the form [v
  w u]. However, the :as clause allows to define a custom match representation:

    [v --> w
     :when-let [u (foobar w)]
     :as {:u u, :v v, :w w}]

  In that example, the matches are represented using a map from pattern
  variable name to matched element.  Note that matches don't need to contain
  only the matched elements.  They could also return, e.g., attribute values of
  those.

  Finally, a pattern may contain a :distinct modifier.  If there is one, the
  lazy seq of matches which is the result of a applying the pattern won't
  contain duplicates (where \"duplicates\" in defined by the :as clause).
  Let's clarify that with an example.  Consider a model with only two nodes n1
  and n2.  There are the following three edges: n1 --> n1, n1 --> n2, n1 -->
  n2, and n2 --> n1.  Then the effects of :distinct (in combination with :as)
  are as follows:

    [x --> y]    => 4 matches: [n1 n1], [n1 n2], [n1 n2], [n2 n1]

    [x --> y     => 3 matches: [n1 n1], [n1 n2], [n2 n1]
     :distinct]

    [x --> y     => 2 matches: #{n1 n1}, #{n1 n2}
     :as #{x y}
     :distinct]

  The expansion of a pattern, i.e., if it expands to a query on TGraphs or EMF
  models, is controlled by the option `:pattern-expansion-context` with
  possible values `:tg` or `:emf` which can be specified in the `attr-map`
  given to `defpattern`.  Instead of using that option for every rule, you can
  also set `:pattern-expansion-context` metadata to the namespace defining
  patterns, in which case that expansion context is used.  Finally, it is also
  possible to bind `*pattern-expansion-context*` to `:tg` or `:emf` otherwise.
  Note that this binding has to be available at compile-time."

  {:arglists '([name doc-string? attr-map? [args] [pattern]]
                 [name doc-string? attr-map? ([args] [pattern])+])}
  [name & more]
  (let [[name more] (m/name-with-attributes name more)]
    (binding [*pattern-expansion-context* (or (:pattern-expansion-context (meta name))
                                              *pattern-expansion-context*
                                              (:pattern-expansion-context (meta *ns*)))]
      `(defn ~name ~(meta name)
         ~@(if (vector? (first more))
             (convert-spec name more)
             (mapv (partial convert-spec name) more))))))

(defmacro letpattern
  "Establishes local patterns just like `letfn` establishes local functions.
  Every pattern in the `patterns` vector is specified as:

    (pattern-name [args] [pattern-spec])

  For the syntax and semantics of patterns, see the `defpattern` docs.

  Following the patterns vector, an `attr-map` may be given for specifying the
  `*pattern-expansion-context*` in case it's not bound otherwise (see that
  var's documentation and `defpattern`)."
  {:arglists '([[patterns] attr-map? & body])}
  [patterns attr-map & body]
  (when-not (vector? patterns)
    (u/errorf "No patterns vector in letpattern!"))
  (let [body (if (map? attr-map) body (cons attr-map body))]
    (binding [*pattern-expansion-context* (or (:pattern-expansion-context attr-map)
                                              *pattern-expansion-context*
                                              (:pattern-expansion-context (meta *ns*)))]
      `(letfn [~@(map (fn [[n & more]]
                        `(~n ~@(if (vector? (first more))
                                 (convert-spec n more)
                                 (mapv (partial convert-spec n) more))))
                   patterns)]
         ~@body))))

(defmacro pattern
  "Creates an anonymous patterns just like `fn` creates an anonymous functions.
  The syntax is

    (pattern pattern-name? attr-map? [args] [pattern-spec])
    (pattern pattern-name? attr-map? ([args] [pattern-spec])+)

  For the syntax and semantics of patterns, see the `defpattern` docs.

  The `*pattern-expansion-context*` may be given as metadata to the pattern
  name in case it's not bound otherwise (see that var's documentation and
  `defpattern`)."

  {:arglists '([name? attr-map? [args] [pattern]]
                 [name? attr-map? ([args] [pattern])+])}
  [& more]
  (let [[name more] (if (symbol? (first more))
                      [(first more) (next more)]
                      [nil more])
        [attr-map more] (if (map? (first more))
                          [(first more) (next more)]
                          [nil more])
        [name more] (if name
                      (m/name-with-attributes name more)
                      [name more])]
    (binding [*pattern-expansion-context* (or (:pattern-expansion-context attr-map)
                                              *pattern-expansion-context*
                                              (:pattern-expansion-context (meta *ns*)))]
      `(fn ~@(when name [name])
         ~@(if (vector? (first more))
             (convert-spec name more)
             (mapv (partial convert-spec name) more))))))
