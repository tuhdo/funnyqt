(ns funnyqt.in-place
  "Match elements in a structure, and act on them."
  (:use [funnyqt.utils :only [errorf pr-identity]])
  (:use [funnyqt.query :only [the for* pred-succ-seq]])
  (:use funnyqt.macro-utils)
  (:use funnyqt.protocols)
  (:require [funnyqt.tg :as tg]
            [funnyqt.query.tg :as tgq]
            [funnyqt.emf :as emf])
  (:require clojure.set)
  (:use [funnyqt.query :only [member?]])
  (:require [clojure.tools.macro :as m]))

;;# Matching

(defn- bindings-to-arglist [bindings]
  (loop [p bindings l []]
    (if (seq p)
      (cond
       ;; Handle :let [x y, z a]
       (= :let (first p)) (recur (rest (rest p))
                                 (vec (concat l
                                              (loop [ls (first (rest p)) bs []]
                                                (if (seq ls)
                                                  (recur (rest (rest ls))
                                                         (conj bs (first ls)))
                                                  bs)))))
       ;; Ignore :when (exp ...)
       (keyword? (first p)) (recur (rest (rest p)) l)
       ;; A vector destructuring form
       (vector? (first p)) (recur (rest (rest p)) (vec (concat l (first p))))
       ;; Anothen destructuring form
       (coll? (first p)) (errorf "Only vector destructuring is permitted outside :let, got: %s"
                                 (first p))
       ;; That's a normal binding
       :default (recur (rest (rest p)) (conj l (first p))))
      (vec l))))

(defn- shortcut-let-vector [lv]
  (mapcat (fn [[s v]]
            [:let [s v]
             :when s])
          (partition 2 lv)))

(defn- shortcut-bindings [bindings]
  (loop [p bindings, nb []]
    (if (seq p)
      (if (= :let (first p))
        (recur (rest (rest p))
               (vec (concat nb (shortcut-let-vector (fnext p)))))
        (recur (rest (rest p)) (conj (conj nb (first p)) (second p))))
      (vec nb))))

(defmacro with-match
  "Establish bindings as specified in `bindings`, and execute `body`.
  `bindings` is a vector of bindings with the syntax of `for*`.

  If a match could be found, that is, all symbols in `bindings` can be bound to
  non-nil values, `body` is executed with the established bindings and `body`s
  value is the return value.  If no match is found, nil is returned."
  ;; Nicer arglist in doc
  {:arglists '([[bindings*] & body])}
  [bindings & body]
  (when (not= 0 (mod (count bindings) 2))
    (errorf "bindings has to be var-exp pairs"))
  (let [arglist (bindings-to-arglist bindings)
        sbindings (shortcut-bindings bindings)
        r `r#]
    `(when-let [~r (first (for* ~sbindings ~arglist))]
       ;; We want no matches with nil values
       (when (every? (complement nil?) ~r)
         ;; Rip out the individual values of the result and let-bind them.
         ;; Alternatively, one could create a (apply (fn [args] body) result),
         ;; but that consumes much more stack space in plain recursive rules
         ;; (i.e., when one cannot use recur because it is recursed more than
         ;; once).
         (let ~(loop [a arglist, i 0, res []]
                 (if (seq a)
                   (recur (rest a) (inc i) (concat res [(first a) `(~r ~i)]))
                   (vec res)))
           ~@body)))))

;;# Patterns and Rules

(defn- verify-match-vector
  "Ensure that the match vector `match` and the arg vector `args` are disjoint.
  Throws an exception if they overlap, else returns `match`."
  [match args]
  (if (seq (clojure.set/intersection
            (set (bindings-to-arglist match))
            (set args)))
    (errorf "Arglist and match vector overlap!")
    match))

(defn- symbol-and-type [sym]
  (if-let [[_ s t] (re-matches #"(?:<-|-)?([a-zA-Z0-9]+)?(?::([a-zA-Z_0-9]+))?(?:-|->)?"
                               (name sym))]
    [(and s (symbol s)) (and t `'~(symbol t))]
    (errorf "No valid pattern symbol: %s" sym)))

(defn- edge-sym? [sym]
  (or (re-matches #"<-[a-zA-Z:_0-9]*-" (name sym))
      (re-matches #"-[a-zA-Z:_0-9]*->" (name sym))))

(defn- edge-dir [esym]
  (if (edge-sym? esym)
    (if (re-matches #"<-.*" (name esym))
      :in
      :out)
    (errorf "%s is not edge symbol." esym)))

(defn- blank-sym? [sym]
  (re-matches #"[a-zA-Z0-9]" (name sym)))

(defn- decl-sym [p sym n graph]
  (println p sym n)
  (letfn [(alpha-omega [p]
            (if (= (edge-dir p) :out)
              `(tg/omega ~(first (symbol-and-type p)))
              `(tg/alpha ~(first (symbol-and-type p)))))]
    (if (or
         ;; `for` special keywords, pass them straight thru
         (#{:when :let :while} sym)
         (#{:when :let :while} p)
         ;; Embedded normal decls: a (vseq ...)
         (and (symbol? sym) (blank-sym? sym) (list? n))
         (and (symbol? p)   (blank-sym? p)   (list? sym)))
      [sym]
      (let [[s t] (symbol-and-type sym)]
        (cond
         ;; No previous symbol
         (nil? p) (if (edge-sym? sym)
                    `[~s (tgq/eseq ~graph ~t)]
                    `[~s (tgq/vseq ~graph ~t)])
         ;; -x:E-> z:V, a (a was declared before), or
         (and (blank-sym? sym) (not (edge-sym? p)))
         nil ;; nothing to do here
         ;; -x:E-> a (a was declared before), or
         (and (blank-sym? sym) (edge-sym? p))
         `[:when (= ~sym ~(alpha-omega p))]
         ;; -a:E-> b:V (node after edge)
         (and (edge-sym? p) (not (edge-sym? sym)))
         `[:let [~s ~(alpha-omega p)]
           ~@(when t
               `[:when (has-type? ~s ~t)])]
         ;; b:V -a:E-> (edge after node)
         (and (not (edge-sym? p)) (edge-sym? sym))
         `[~s (tgq/iseq ~@(symbol-and-type p) ~(edge-dir sym))]
         ;; b:V, c:V (node after node)
         (and (not (edge-sym? p)) (not (edge-sym? sym)))
         `[~s (tgq/vseq ~graph ~t)]
         :else (errorf "Cannot handle pattern part '%s %s' (previous, current)."
                       p sym))))))

(defn- transform-match-vector
  "Transforms patterns like a:X -:role-> b:Y to `for` syntax."
  [match args]
  ;; NOTE: the first element in args must be the graph/model symbol...
  #_(let [m (first args)]
    (vec (mapcat (fn [[p s n]] (decl-sym p s n m)) (pred-succ-seq match)))
    )
  (verify-match-vector match args))

(comment
  (transform-match-vector '[v1:V1 -e1:E1-> v2:V2 <-e2:E2- v1
                            :when (not (= e1 e2))] '[g])
  [a (funnyqt.query.tg/vseq g (quote A))
   b (funnyqt.query.tg/iseq a (quote A) :out)
   :let [c (funnyqt.tg/omega b)]
   :when (funnyqt.protocols/has-type? c (quote (quote C)))
   d (funnyqt.query.tg/iseq c (quote C) :out)
   :when (clojure.core/= a (funnyqt.tg/omega d))]
)

(defmacro defpattern
  "Defines a pattern with `name`, optional `doc-string`, optional `attr-map`,
  an `args` vector, and a `match` vector.  When invoked, it returns a lazy seq
  of all matches of `match`.

  Usually, you use this to specify a pattern that occurs in the match pattern
  of many rules.  So instead of writing a match vector like

    [a (vseq g), b (iseq a) :let [c (that b)], ...]

  in several rules, you do

    (defpattern abc [g] [a (vseq g), b (iseq a) :let [c (that b)]])

  and then

    [[a b c] (abc g), ...]

  in the rules."
  {:arglists '([name doc-string? attr-map? [args] [match]]
                 [name doc-string? attr-map? ([args] [match])+])}
  [name & more]
  (let [[name more] (m/name-with-attributes name more)
        convert (fn [[a m]]
                  `(~a
                    (for ~(transform-match-vector m a)
                      ~(bindings-to-arglist m))))]
    `(~@(expansion-context-defn-maybe name)
      ~@(if (seq? (first more))
          (map convert more)
          (convert more)))))

(def ^{:dynamic true
       :doc "A function that is invoked when a rule matches,
  mainly for debugging purposes.
  The function gets the following arguments: [r args match]
    - r is a symbol denoting the current matched rule
    - args is the vector of the rule's input arguments
    - match is the vector of the elements matched by the rule"}
  *on-matched-rule-fn* nil)

(defmacro defrule-internal
  [debug name more]
  (let [[name more] (m/name-with-attributes name more)
        convert (fn [s]
                  (let [args  (first s)
                        more (next s)]
                    (if (vector? (first more))
                      ;; match vector given
                      (let [match (first more)
                            body (next more)]
                        `(~args
                          (with-match ~(transform-match-vector match args)
                            ~@(when debug
                                `((when *on-matched-rule-fn*
                                    (*on-matched-rule-fn* '~name ~args ~(bindings-to-arglist match)))))
                            ~@body)))
                      ;; No match given
                      `(~args ~@more))))]
    `(~@(expansion-context-defn-maybe name)
      ~@(if (seq? (first more))
          (map convert more)
          (convert more)))))

(defmacro defrule
  "Defines a rule with `name`, optional doc-string', optional `attr-map?',
  an `args` vector, an optional `match` vector, and following `body` code.
  Just like `defn`, overloading is supported as well.  The `match` vector is
  actually optional.  If no version has a match, then you should use `defn`
  directly.

  `match` specifies what the rule matches (a vector with the syntax of `for`).
  The match vector is optional.  The purpose of this optionality is mainly
  overloading, i.e., you can have a rule like this:

    (defrule foobar
      \"Matches a, b, and c, and performs actions on them.\"
      ([g] [a ..., b ..., c ...] (foobar g a b c))
      ([g a] [b ..., c ...]      (foobar g a b c))
      ([g a b] [c ...]           (foobar g a b c))
      ([g a b c]  ;; No match vector, just actions
        (action1 a)
        (action2 b)
        (action3 c)
        (action4 a c)))

  `body` is applied on the match.

  If the rule could be applied, then it returns the value of the last form in
  body, and any logical true value means the rule succeeded.  Thus, one should
  take care to always return logical true if the rule was applied.

  It it a compile error, if the `args` contain vars that occur also in
  `match`."
  {:arglists '([name doc-string? attr-map? [args] [match] & body]
                 [name doc-string? attr-map? ([args] [match] & body)+])}
  [name & more]
  `(defrule-internal false ~name ~more))

(defmacro defrule-debug
  "Exactly the same as `defrule`, but expands into a rule that calls
`*on-matched-rule-fn*', if that's bound."
  {:arglists '([name doc-string? attr-map? [args] [match] & body]
                 [name doc-string? attr-map? ([args] [match] & body)+])}
  [name & more]
  `(defrule-internal true ~name ~more))

;;# Transformations

(defmacro deftransformation
  {:doc "Defines a match-replace transformation named `name` with optional
  `doc-string?', optional `meta-map?, a mandatory `args` vector, and a `body`.
  The `body` must consist of arbitrary many `defpattern` and `defrule` forms,
  and exactly one other form, the main entry point of the transformation.  This
  form is evaluated when the transformation is called.

  All patterns, rules, and the main form of the transformation have access to
  the `args` of the transformation."
   :arglists '([name doc-string? meta-map? [args] & body])}
  [tname & more]
  (let [[tname more] (m/name-with-attributes tname more)
        args (first more)
        body (next more)]
    ;; Validate
    (when-not (vector? args)
      (errorf "No args vector specified for transformation %s." args))
    (let [[rules-and-patterns main-form]
          ((juxt filter remove)
           #(let [x (first %)]
              (and (symbol? x)
                   (let [var (resolve x)]
                     (or (= var #'defpattern)
                         (= var #'defrule)
                         (= var #'defrule-debug)))))
           body)]
      (when (not= (count main-form) 1)
        (errorf "There must be exactly one main form in a transformation but got %d: %s"
                (count main-form) (print-str main-form)))
      (binding [*expansion-context* :internal]
        ;; Ok, here we go.
        `(defn ~tname ~(meta tname)
           ~args
           (letfn [~@(map macroexpand rules-and-patterns)]
             ~(the main-form)))))))

;;# Higher order rules

(defn iteratively
  "Applies the rule `r` with `args` as long as it returns logical true.
  Returns the number of successful applications or nil if it couldn't be
  applied at least once."
  [r & args]
  (loop [val (apply r args), i 0]
    (if val
      (recur (apply r args) (inc i))
      (when-not (zero? i) i))))

(defn iteratively*
  "Applies the rule `r` as long as it returns logical true.
  On the first application, `r` receives `args`.  The second till last
  application receive the value of the previous successful application.
  Returns the number of successful applications, or nil, if it couldn't be
  applied at least once."
  [r & args]
  (loop [val (apply r args), i 0]
    (if val
      (recur (apply r val) (inc i))
      (when-not (zero? i) i))))

(defn ntimes
  "Applies the rule `r` at most `n` times and returns the number of successfull
  applications.  Stops as soon as `r` fails."
  [n r & args]
  (loop [n n, succs 0]
    (if (and (pos? n) (apply r args))
      (recur (dec n) (inc succs))
      succs)))

(defn choose
  "Randomly chooses one of the given `rules` and applies it.
  Returns that fun's return value or nil, if no fun was applicable."
  [& rules]
  (let [r (rand-nth rules)
        v (r)]
    (or v (recur (remove #(= r %) rules)))))