(ns funnyqt.generic
  "Generic protocols extended upon many different types, and generic functions."
  (:require [funnyqt.internal :as i]))

;;# Describing Elements

(defprotocol IDescribe
  "A protocol for elements supporting describe."
  (describe [this]
    "Describes `this` attributed element or attributed element class."))

;;# Qualified Names

(defprotocol IQualifiedName
  "A protocol for qualified names."
  (qname [this]
    "Returns the qualified name of this named element's class or named element
  class as a symbol.  For collection domains, it returns a vector of symbols:
  [List Integer] where Integer is the base domain, or [Map Integer String]
  where Integer is the key domain and String is the value domain.  Of course,
  that may be recursive, so [Map Integer [List String]] corresponds to the java
  domain Map<Integer, List<String>>."))

;;# Unset properties

(defprotocol IUnset
  (unset? [this prop]
    "Returns true iff the property `prop` (given as keyword) is unset."))

;;# Instance Check

(defprotocol IInstanceOf
  "A protocol for checking if an element is an instance of some meta-class."
  (is-instance? [object class]
    "Returns true, iff `object` is an instance of `class`.")
  (has-type? [object spec]
    "Returns true, iff `object`s type matches `spec`."))

;;## type-case

(defmacro type-case
  "Takes an element `elem` (a GraphElement or EObject) and a set of `clauses`.
  Every clause is a pair of the form:

    type-spec result-expr

  The type-specs are tested one after the other, and if a type-spec matches the
  type of `elem`, the return value of type-case is the result-expr paired with
  the succeeding type-spec.

  A single default-expr may follow the pairs.  If no type-spec matches, the
  return value of type-case is the value of that default expression.  If there
  is no default expression and no type-spec matches, an
  IllegalArgumentException is thrown.

  Example:

    (type-case obj
      'TypeA (do-a-stuff obj)
      'TypeB (do-b-stuff obj)
      (do-default-stuff obj))"
  [elem & clauses]
  `(condp (fn [t# e#] (has-type? e# t#)) ~elem
     ~@clauses))

;;# Generic Attribute Value Access

(defprotocol IAttributeValueAccess
  "A protocol for generically accessing attributes on some object."
  (aval [el attr]
    "Returns the value of `el`s `attr` attribute.
  `attr` is the attribute name given as keyword.")
  (set-aval! [el attr val]
    "Sets the value of `el`s `attr` attribute to `val`.
  `attr` is the attribute name given as keyword."))

;;# Generic Access to Vertices and EObjects and Edges and EObject pairs

(defprotocol IElements
  (elements [model] [model type-spec]
    "Returns the lazy sequence of elements in `model` restricted by `type-spec`."))

(defprotocol IRelationships
  (relationships [model] [modes spec]
    "Returns the lazy seq of relationships en `model` restricted by `spec`.
  The value of spec is framework specific.  For TGraphs it's a symbol denoting
  an EdgeClass name, and for EMF it's a vector [src trg] of source and target
  reference names given as keyword, or source and target classes given as
  symbols.  Both may be nil meaning to accept any reference.  The return value
  is also framework specific.  For TGraphs it's a seq of Edges, for EMF it's a
  seq of [src-eobject trg-eobject] tuples."))

;;# Generic access to enumeration constants

(defprotocol IEnumConstant
  (enum-constant [m const]
    "Returns the enumeration constant with the qualified name `const` in the
  metamodel of model `m`.
  In case of EMF, `m` is ignored."))

;;# Generic creation of model elements

(defprotocol ICreateElement
  (create-element! [model cls] [model cls prop-map]
    "Creates a new element of type `cls` in `model`.
  Properties are set according to `prop-map`, a map from property name keywords
  to property values."))

(defprotocol ICreateRelationship
  (create-relationship!
    [model cls src-elem trg-elem]
    [model cls src-elem trg-elem attr-map]
    "Creates a new relationship of type `cls` in `model` connecting `src-elem`
  to `trg-elem`.  The valid values for `cls` are framework specific.  For
  TGraphs it is a symbol denoting an EdgeClass name, for EMF it is a keyword
  denoting an EReference name.  The return value is also framework specific.
  For TGraphs it is an Edge, for EMF it is the tuple [src-elem trg-elem].
  `attr-map` is a map from attribute names (as keywords) to values to be set.
  Clearly, this is unsupported by frameworks without explicit edges with
  attributes."))

;;# Type Matcher

(defprotocol ITypeMatcher
  (type-matcher [model type-spec]
    "Returns a type-matcher function based on the metamodel of model.
  A type-matcher function accepts one object and returns true if the object
  matches the `type-spec`, or false otherwise.

  A type-spec may be composed of:

    - nil                     Every type is accepted
    - a predicate p           Accepted if (p obj) is true
    - a qname symbol
      - Foo                   Accepts objects of type Foo and subtypes
      - Foo!                  Accepts objects of exact type Foo
      - !Foo                  Accepts objects not of type Foo or subtypes
      - !Foo!                 Accepts objects not of exact type Foo
    - a metamodel type        Accepts instances of that type
    - a vector of the form    op is a logical operator (:or, :and, :nand, :nor, :xor),
      [op ts1 ts2 ...]        and ts1, ts2, etc are type-specs.  Accepts objects
                              whose type matches the individual type-specs ts1, ts2,
                              etc with the respective semantics of the logical
                              operator."))

;;# Deletion

(defprotocol IDelete
  "A protocol for deleting elements."
  (delete! [this] [this recursive]
    "Deletes this element and returns it.  If `recursive` is true (default),
  delete also elements contained by `this`.  Of course, `recursive` has no
  meaning for edges.  Implementations are provided for Vertex, Edge, EObject,
  and collections thereof."))

(extend-protocol IDelete
  java.util.Collection
  (delete!
    ([this]
       (doseq [x this]
         (delete! x))
       this)
    ([this recursive]
       (doseq [x this]
         (delete! x recursive))
       this)))

;;# Adacencies

(defn adj
  "Traverses single-valued `role` and more `roles` starting at `elem`.
  Returns the target object.
  Errors if a role is undefined, intermediate targets are nil, or there are
  more elements that can be reached that way."
  [elem role & roles]
  (i/adj-internal elem (cons role roles)))

(defn adj*
  "Like `adj`, but doesn't error if some role is not defined.  In that case, it
  simply returns nil."
  [elem role & roles]
  (i/adj*-internal elem (cons role roles)))

(defn reducible-adjs
  "Traverses `role` and more `roles` starting at `elem`.
  Returns a reducible collection (see clojure.core.reducers).
  Errors if a role is undefined."
  [elem role & roles]
  (i/adjs-internal elem (cons role roles)))

(defn reducible-adjs*
  "Like `adjs`, but doesn't error if some role is not defined.  In that case,
  it simply returns the empty vector.
  Returns a reducible collection (see clojure.core.reducers)."
  [elem role & roles]
  (i/adjs*-internal elem (cons role roles)))

(defn adjs
  "Traverses `role` and more `roles` starting at `elem`.
  Returns a vector of target objects.
  Errors if a role is undefined."
  [elem role & roles]
  (into [] (i/adjs-internal elem (cons role roles))))

(defn adjs*
  "Like `adjs`, but doesn't error if some role is not defined.  In that case,
  it simply returns the empty vector."
  [elem role & roles]
  (into [] (i/adjs*-internal elem (cons role roles))))

;;# IModifyAdjacencies

(defprotocol IModifyAdjacencies
  (set-adj!  [obj role robj]
    "Sets the single-valued `role` of `obj` to `robj`.")
  (set-adjs! [obj role robjs]
    "Sets the multi-valued `role` of `obj` to `robjs` (a collection of model
  elements).")
  (add-adj!  [obj role robj]
    "Adds `robj` to `obj`s `role`.")
  (add-adjs! [obj role robjs]
    "Adds all `robjs` to `obj`s `role`."))

;;# IContainer

(defprotocol IContainer
  (container [this]
    "Returns the container of this element.
  A container is an element that references this element by some link with
  composition semantics."))

;;# (Meta-)Model Object predicates

(defprotocol IModelObject
  (model-object? [this]
    "Returns true if `this` is a supported model object."))

(extend-protocol IModelObject
  Object
  (model-object? [this] false)
  nil
  (model-object? [this] false))

(defprotocol IMetaModelObject
  (meta-model-object? [this]
    "Returns true if `this` is a supported meta model object."))

(extend-protocol IMetaModelObject
  Object
  (meta-model-object? [this] false)
  nil
  (meta-model-object? [this] false))

;;# Metamodel Protocols

(defprotocol IMMAbstract
  "A protocol for checking if an element class is abstract."
  (mm-abstract? [this]
    "Returns true, iff the element class is abstract.
  Implementations are provided for:

    - java.lang.Class: default impl in this namespace
    - de.uni_koblenz.jgralab.schema.GraphElementClass: funnyqt.tg
    - org.eclipse.emf.ecore.EClass: funnyqt.emf"))

(extend-protocol IMMAbstract
  java.lang.Class
  (mm-abstract? [this]
    (java.lang.reflect.Modifier/isAbstract (.getModifiers this))))

(defprotocol IMMClasses
  (mm-classes [cls]
    "Returns all classes in the metamodel containing `cls`."))

(defprotocol IMMClass
  (mm-class [model-element] [model mm-class-sym]
    "Returns the given model-element's metamodel class,
  or the metamodel class named mm-class-sym (a symbol)."))

(defprotocol IMMDirectSuperClasses
  (mm-direct-super-classes [metamodel-type]
    "Returns the direct superclasses of metamodel-type."))

(defprotocol IMMSuperClassOf
  (mm-super-class? [super sub]
    "Return true iff super is a direct or indirect super class of sub.
  (mm-super-class? c c) is false."))

(defprotocol IMMMultiValuedProperty
  (mm-multi-valued-property? [cls prop]
    "Returns true iff `prop` (given as keyword) is a multi-valued property
  of `cls`."))

(defprotocol IMMContainmentRef
  (mm-containment-ref? [mm-class ref]
    "Returns true if `ref` (given as keyword) is a containment reference,
  i.e., the target objects are contained my `mm-class`."))

