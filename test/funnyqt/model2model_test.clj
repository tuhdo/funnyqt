(ns funnyqt.model2model-test
  (:require [funnyqt.emf :as emf])
  (:require [funnyqt.query.emf :as emfq])
  (:require [funnyqt.visualization :as viz])
  (:use funnyqt.protocols)
  (:use funnyqt.tg)
  (:use funnyqt.query)
  (:use funnyqt.model2model)
  (:use clojure.test))

(defn family
  "Returns the main family of member m."
  [m]
  (or (emf/eget m :familyFather) (emf/eget m :familyMother)
      (emf/eget m :familySon)    (emf/eget m :familyDaughter)))

(defn male?
  "Returns true, iff member m is male."
  [m]
  (or (emf/eget m :familyFather)
      (emf/eget m :familySon)))

(defn parents-of
  "Returns the set of parent members of m."
  [m]
  (emfq/reachables
   m [p-seq
      [p-alt :familySon :familyDaughter]
      [p-alt :father :mother]]))

(defn wife
  "Returns the wife member of member m."
  [m]
  (when-let [w (seq (emfq/reachables
                     m [p-seq :familyFather :mother]))]
    (the w)))

(deftransformation families2genealogy [[in] [out]]
  (some-test-fn [x] :foo)
  (make-address
   :from [street town]
   :to [adr 'Address]
   (set-value! adr :street street)
   (set-value! adr :town   town))
  (^:top member2person
         :from [m]
         :disjuncts [member2male member2female :result p]
         (set-value! p :fullName
                     (str (emf/eget m :firstName) " "
                          (emf/eget (family m) :lastName)))
         (set-value! p :ageGroup (enum-constant p (if (< (emf/eget m :age) 18)
                                                    'AgeGroup.CHILD
                                                    'AgeGroup.ADULT)))
         (set-adj! p :address (make-address (emf/eget (family m) :street)
                                            (emf/eget (family m) :town)))
         (when-let [ps (seq (parents-of m))]
           (set-adjs! p :parents (map member2person ps))))
  (member2male
   :from [m 'Member]
   :when (male? m)
   :to   [p 'Male :model out]
   (when-let [w (wife m)]
     (add-adj! p :wife (member2female w))))
  (member2female
   :from [m 'Member]
   :when (not (male? m))
   :to   [p 'Female]))

(deftest test-transformation
  (let [_ (emf/load-metamodel "test/input/Families.ecore")
        in (emf/load-model "test/input/example.families")
        out-schema (load-schema "test/input/genealogy-schema.tg")
        ng (create-graph out-schema)
        trace (time (families2genealogy in ng))]
    #_(viz/print-model ng :gtk)
    (is (== 13 (vcount ng 'Person)))
    (is (==  7 (vcount ng 'Female)))
    (is (==  6 (vcount ng 'Male)))
    (is (==  3 (ecount ng 'HasSpouse)))
    (is (== 18 (ecount ng 'HasChild)))
    (is (== 3  (count (vseq ng 'Address))))
    #_(clojure.pprint/pprint trace)))

