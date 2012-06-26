(ns funnyqt.operational
  "Stuff for writing QVT Operational Mappings like transformations."
  (:use [funnyqt.utils :only [error]])
  (:use funnyqt.macro-utils)
  (:use [funnyqt.query :only [the]])
  (:require [clojure.tools.macro :as m]))

;;# Mappings

(def ^{:dynamic true
       :doc "A map from mapping rule to map from source to target objects."}
  *traceability-mappings*)

(def ^{:dynamic true
       :doc "Actions deferred to the end of the transformation."}
  *deferred-actions*)

;; TODO: Man will vermutlich ne Variante, bei der man bestimmen kann, für
;; welche Parameter Trace-Links erzeugt werden.
(defmacro defmapping
  "Defines a mapping function named `name` with optional `doc-string?',
  an argument vector `args`, and a `body`.  The syntax is the same as for
  `defn`, except that currently overloading is not supported.

  Every mapping function must return the object it has created, and a
  traceability mapping from the value of the mapping's first argument to that
  new object is persisted and can later be resolved using `resolve-in` and
  `resolve-all-in`.  If the mapping has no arguments, then no traceability
  mapping is established."

  {:arglists '([name doc-string? [args] & body]
                 ;; FIXME: Also support overloading.
                 ;; [name doc-string? ([args] & body)+]
                 )}
  [name & more]
  (let [[name more] (m/name-with-attributes name more)
        args (first more)
        body (next more)]
    (if (vector? args)
      `(~@(expansion-context-defn-maybe name)
        ~args
        ~(if (seq args)
           `(let [result# (do ~@body)]
              (swap! *traceability-mappings* assoc-in [~name ~(first args)] result#)
              result#)
           `(do ~@body)))
      (error (format "Invalid defmapping form: expected arg vector but got %s."
                     args)))))

;;# Helpers

(defmacro defhelper
  "Defines a helper function.
  The syntax is the same as for `defn`."

  {:arglists '([name doc-string? [args] & body]
                 [name doc-string? ([args] & body)+])}
  [name & more]
  (let [[name more] (m/name-with-attributes name more)]
    `(~@(expansion-context-defn-maybe name)
      ~@more)))

;;# Deferring

(defmacro deferred
  "Captures a thunk (closure) that evaluates `body` as the last step of the
  transformation."
  [& body]
  `(swap! *deferred-actions* conj (fn [] ~@body)))

;;# Resolving

(defn resolve-in
  "Returns the target object created for `obj` in `mapping`."
  [mapping obj]
  (get (get @*traceability-mappings* mapping) obj))

(defn resolve-all-in
  "Returns a seq of target objects created for `objs` in `mapping`."
  [mapping objs]
  (into (empty objs)
        (map #(resolve-in mapping %)
             objs)))

;;# Transformations

(defmacro deftransformation
  "Defines an operational transformation named `name` with optional
  `doc-string?', optional `meta-map?, a mandatory `args` vector, and a `body`.
  The `body` must consist of arbitrary `defmapping` and `defhelper` forms, and
  exactly one other form, the main entry point of the transformation.  This
  form is evaluated when the transformation is called.

  All helpers, mappings, and the main form of the transformation have access to
  the `args` of the transformation."

  {:arglists '([name doc-string? meta-map? [args] & body])}
  [tname & more]
  (let [[tname more] (m/name-with-attributes tname more)
        args (first more)
        body (next more)]
    ;; Validate
    (when-not (vector? args)
      (error (format "No args vector specified for transformation %s."
                     args)))
    (let [[mappings-and-helpers main-form]
          ((juxt filter remove)
           #(let [x (first %)]
              (and (symbol? x)
                   (let [var (resolve x)]
                     (or (= var #'defmapping)
                         (= var #'defhelper)))))
           body)]
      (when (not= (count main-form) 1)
        (error (format "There must be exactly one main form in a transformation but got %d: %s"
                       (count main-form) (print-str main-form))))
      (binding [*expansion-context* :internal]
        ;; Ok, here we go.
        `(defn ~tname ~(meta tname)
           ~args
           (letfn [~@(map macroexpand-1 mappings-and-helpers)]
             (binding [*traceability-mappings* (atom {})
                       *deferred-actions* (atom [])]
               (let [r# ~(the main-form)]
                 (doseq [da# @*deferred-actions*]
                   (da#))
                 r#))))))))

