(ns funnyqt.test.emf
  (:use [funnyqt.emf])
  (:use [funnyqt.query])
  (:use [funnyqt.protocols])
  (:use [ordered.set])
  (:use [ordered.map])
  (:use [clojure.test])
  (:import
   [org.eclipse.emf.ecore.xmi.impl XMIResourceImpl]
   [org.eclipse.emf.common.util URI EList]
   [org.eclipse.emf.ecore EPackage EObject EModelElement]))

(deftest test-load-metamodel
  (let [mm (load-metamodel "test/input/Families.ecore")]
    (is (instance? funnyqt.emf.EcoreModel mm))
    ;; Restricting to our custom one by its nsURI...
    (with-ns-uris ["http://families/1.0"]
      (is (== 1 (count (epackages)))))))

(def family-mm (load-metamodel "test/input/Families.ecore"))
(def family-model (load-model "test/input/example.families"))

(deftest test-eclassifiers
  (with-ns-uris ["http://families/1.0"]
    (is (== 3 (count (eclassifiers))))))

(deftest test-eclassifier
  (let [fmodel (eclassifier 'FamilyModel)
        family (eclassifier 'Family)
        person (eclassifier 'Member)]
    (is fmodel)
    (is family)
    (is person)))

(defn- make-uniqueelist
  []
  (let [ul (org.eclipse.emf.common.util.UniqueEList.)]
    (doseq [i [0 1 2 3 4 1 5 6 7 7 3 2 8 1 0 0 9 0]]
      (.add ul i))
    ul))

(defn- make-elist
  []
  (let [el (org.eclipse.emf.common.util.BasicEList.)]
      (doseq [item [0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9]]
        (.add el item))
      el))

(defn- make-emap
  []
  (let [em (org.eclipse.emf.common.util.BasicEMap.)]
    (doseq [[k v] [[:a "a"] [:b "b"] [:c "c"] [:d "d"]]]
      (.put em k v))
    em))

(deftest test-emf2clj-conversion
  ;; UniqueEList -> OrderedSet
  (let [uel (make-uniqueelist)
        clj-uel (emf2clj uel)]
    (is (instance? ordered.set.OrderedSet clj-uel))
    (is (== (count uel) (count clj-uel)))
    (is (= (seq uel) (seq clj-uel))))
  ;; EList -> vector
  (let [el (make-elist)
        clj-el (emf2clj el)]
    (is (vector? clj-el))
    (is (== (count el) (count clj-el)))
    (is (= (seq el) clj-el)))
  ;; EMap -> IPersistentMap
  (let [^org.eclipse.emf.common.util.EMap em (make-emap)
        clj-em (emf2clj em)]
    (is (map? clj-em))
    (is (== (count em) (count clj-em)))
    (doseq [k (keys clj-em)]
      (is (.containsKey em k))
      (is (= (.get em k) (clj-em k))))))

(deftest test-econtents-eallcontents
  (let [all   (eallobjects family-model)
        mems  (eallcontents family-model 'Member)
        fams  (eallcontents family-model 'Family)
        fmods (eallobjects family-model 'FamilyModel)]
    (is (== 17 (count all)))
    (is (== 1  (count fmods)))
    (is (== 3  (count fams)))
    (is (== 13 (count mems)))
    ;; The FamilyModel is the container of all Members and Families.
    (doseq [x (concat mems fams)]
      (is (the fmods) (econtainer x)))
    ;; In this concrete case, this is true
    (is (= (eallcontents family-model '!FamilyModel)
           (econtents (econtents family-model))))
    (is (= (eallcontents family-model 'FamilyModel)
           (econtents family-model    'FamilyModel)))
    (is (= (eallcontents family-model 'Member)
           (econtents (econtents family-model) 'Member)))
    (is (= (eallcontents family-model 'Family)
           (econtents (econtents family-model) 'Family)))))

(deftest test-ecrossrefs
  (let [fsmith (first (eallobjects family-model 'Family))]
    (is (= (ecrossrefs fsmith)
           (ecrossrefs fsmith [:father :mother :sons :daughters])))
    (is (== 1
            (count (ecrossrefs fsmith :father))
            (count (ecrossrefs fsmith :mother))
            (count (ecrossrefs fsmith :daughters))))
    (is (== 3 (count (ecrossrefs fsmith :sons))))))

(deftest test-inv-erefs
  (let [[f1 f2 f3] (eallobjects family-model 'Family)]
    (are [x y z cnt] (and (== cnt (count x) (count y) (count z))
                        (= (apply hash-set x)
                           (apply hash-set y)
                           (apply hash-set z)))
         ;; 7, cause the FamilyModel is also included
         (erefs f1)
         (inv-erefs f1)
         (inv-erefs f1 nil family-model)
         7
         ;; Here, it's not included (cross-refs only)
         (ecrossrefs f1)
         (inv-ecrossrefs f1)
         (inv-ecrossrefs f1 nil family-model)
         6
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f1 :father)
         (inv-erefs f1 :familyFather)
         (inv-erefs f1 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f1 :father)
         (inv-ecrossrefs f1 :familyFather)
         (inv-ecrossrefs f1 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f1 [:mother :father])
         (inv-erefs f1 [:familyMother :familyFather])
         (inv-erefs f1 [:familyMother :familyFather] family-model)
         2
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f1 [:mother :father])
         (inv-ecrossrefs f1 [:familyMother :familyFather])
         (inv-ecrossrefs f1 [:familyMother :familyFather] family-model)
         2
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f2 :father)
         (inv-erefs f2 :familyFather)
         (inv-erefs f2 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f2 :father)
         (inv-ecrossrefs f2 :familyFather)
         (inv-ecrossrefs f2 :familyFather family-model)
         1
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f3 :sons)
         (inv-erefs f3 :familySon)
         (inv-erefs f3 :familySon family-model)
         0
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f3 :sons)
         (inv-ecrossrefs f3 :familySon)
         (inv-ecrossrefs f3 :familySon family-model)
         0
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (erefs f3 [:daughters])
         (inv-erefs f3 :familyDaughter)
         (inv-erefs f3 :familyDaughter family-model)
         3
         ;;;;;;;;;;;;;;;;;;;;;;;;;;;
         (ecrossrefs f3 [:daughters])
         (inv-ecrossrefs f3 :familyDaughter)
         (inv-ecrossrefs f3 :familyDaughter family-model)
         3)))

(deftest test-epairs
  (is (== 31 (count (eallpairs family-model))))
  (is (== 15 (count (ecrosspairs family-model))))
  (is (== 16 (count (econtentpairs family-model))))

  (is (== 16 (count (eallpairs family-model :model nil))))
  (is (==  3 (count (eallpairs family-model nil :families))))
  (is (==  3 (count (eallpairs family-model :model :families))))

  (is (==  3 (count (eallpairs family-model nil nil 'FamilyModel 'Family))))
  (is (== 18 (count (eallpairs family-model nil nil nil 'Family))))
  (is (==  3 (count (eallpairs family-model :model nil nil 'Family)))))

(deftest test-eget
  (let [fm (the (econtents family-model))
        fsmith (first (econtents fm 'Family))]
    (is (= (econtents fm)
           (concat (eget fm :families)
                   (eget fm :members))))
    (is (= (econtents fm 'Family)
           (eget fm :families)))
    (is (= (econtents fm 'Member)
           (eget fm :members)))))

(deftest test-erefs-and-ecrossrefs
  (let [fm (the (econtents family-model))
        fsmith (first (econtents fm 'Family))]
    (are [x] (= (eget fm x) (erefs fm x))
         :families
         :members)
    (are [x] (= (let [r (eget fsmith x)]
                  (if (coll? r) r [r]))
                (erefs fsmith x)
                (ecrossrefs fsmith x))
         :father
         :mother
         :sons
         :daughters)
    ;; Those are all crossrefs, so erefs and ecrossrefs should equal
    (are [x] (= (erefs fsmith x) (ecrossrefs fsmith x))
         :father
         :mother
         :sons
         :daughters
         [:father :mother]
         [:sons :daughters]
         [:father :sons]
         [:mother :daughters])))

(defn- make-test-familymodel
  "Creates a more or less random FamilyModel with `fnum` families and `mnum`
  members.  The references (father, mother, sons, daughters) are set randomly."
  [fnum mnum]
  (let [fm (ecreate! 'FamilyModel)
        make-family (fn [i]
                      (doto (ecreate! 'Family)
                        (eset! :lastName (str "Family" i))
                        (eset! :street   (str "Some Street " i))
                        (eset! :town     (str i " Sometown"))))
        make-member (fn [i]
                      (doto (ecreate! 'Member)
                        (eset! :firstName (str "Member" i))
                        (eset! :age       (Integer/valueOf ^Long (mod i 80)))))
        random-free-member (fn [mems ref]
                             (loop [m (rand-nth mems)]
                               (if (eget m ref)
                                 (recur (rand-nth mems))
                                 m)))
        random-members (fn [mems]
                         (loop [r #{}, i (rand-int 7)]
                           (if (pos? i)
                             (recur (conj r (rand-nth mems)) (dec i))
                             r)))]
    (eset! fm :families
           (loop [fams [], i 1]
             (if (<= i fnum)
               (recur (conj fams (make-family i)) (inc i))
               fams)))
    (eset! fm :members
           (loop [mems [], i 1]
             (if (<= i mnum)
               (recur (conj mems (make-member i)) (inc i))
               mems)))
    (let [mems (vec (eget fm :members))]
      (loop [fams (eget fm :families), r []]
        (when (seq fams)
          (let [fam (first fams)]
            (eset! fam :father    (random-free-member mems :familyFather))
            (eset! fam :mother    (random-free-member mems :familyMother))
            (eset! fam :sons      (random-members mems))
            (eset! fam :daughters (random-members mems))
            (recur (rest fams) (conj r fam))))))
    fm))

(deftest test-ecreate
  (let [fm (make-test-familymodel 100 1000)]
    (are [c s] (== c
                   (count (eallcontents fm s)))
         ;; Note: The FamilyModel itself is not a content
         1100 nil
         0    'FamilyModel
         100  'Family
         1000 'Member
         1100 '[Family Member])
    ;; Every family has its father/mother refs set
    (is (forall? (fn [f]
                   (and (eget f :father)
                        (eget f :mother)))
                 (econtents fm 'Family)))))

(deftest test-eget-raw
  (let [i 1000
        fm (ecreate! 'FamilyModel)
        ^EList ms (eget-raw fm :members)]
    (print "Adding" i "Members (raw): \t")
    (time (dotimes [_ i]
            (.add ms (ecreate! 'Member))))
    (is (== i (count (econtents fm 'Member))))
    (print "Adding" i "Members (eset!): \t")
    (time (eset! fm :members (loop [ims (eget fm :members), x i]
                               (if (pos? x)
                                 (recur (conj ims (ecreate! 'Member)) (dec x))
                                 ims))))
    (is (== (* 2 i) (count (econtents fm 'Member))))))

#_(deftest test-stressy-add-remove
  (let [fm (new-model)
        root (ecreate! fm 'FamilyModel)
        f   (ecreate! 'Member)
        fam (ecreate! 'Family)
        s   (ecreate! 'Member)]
    (eadd! root :members f s)
    (eadd! root :families fam)
    (is (== 3 (count (eallpairs fm))))
    (is (== 3 (count (econtentpairs fm))))
    (is (zero? (count (ecrosspairs fm))))
    (dotimes [i 1000]
      (eadd! fam :sons s))
    (is (== 1003 (count (eallpairs fm))))
    (is (==    3 (count (econtentpairs fm))))
    (is (== 1000 (count (ecrosspairs fm))))
    ))

(deftest test-non-recursive-delete!
  (let [check (fn [m all mems fams]
                (is (== all  (count (eallobjects m))))
                (is (== mems (count (eallobjects m 'Member))))
                (is (== fams (count (eallobjects m 'Family)))))]
    (let [fm (clone-model family-model)]
      (is (== 17 (count (eallobjects fm))))
      (dotimes [i 13]
        (delete! (first (eallobjects fm 'Member)) nil)
        ;; inc, cause i iterates from 0
        (check fm (- 17 (inc i)) (- 13 (inc i)) 3)))
    (let [fm (clone-model family-model)]
      (is (== 17 (count (eallobjects fm))))
      (dotimes [i 13]
        ;; Delete actually recursively, but a member has no contents whatsoever.
        (delete! (first (eallobjects fm 'Member)))
        ;; inc, cause i iterates from 0
        (check fm (- 17 (inc i)) (- 13 (inc i)) 3)))
    ;; Check what happens if we delete the root FamilyModel non-recursive
    (let [fm (clone-model family-model)]
      (is (== 17 (count (eallobjects fm))))
      (add-eobjects! fm (econtents (delete! (the (eallobjects fm 'FamilyModel)) nil)))
      (is (== 16 (count (eallobjects fm))))
      ;;(save-model fm "faa.xmi")
      )))

(deftest test-recursive-delete!
  (let [fm (clone-model family-model)]
    (is (== 17 (count (eallobjects fm))))
    ;; Default is recursive
    (delete! (the (eallobjects fm 'FamilyModel)))
    (is (zero? (count (eallobjects fm))))))

(deftest test-deletion-while-iteration
  (let [fm (clone-model family-model)]
    (is (== 17 (count (eallobjects fm))))
    ;; Ok, deletion while iteration won't do, so we have to get rid of lazyness
    ;; doall.
    (doseq [o (doall (eallobjects fm))]
      (when-not (has-type? o 'FamilyModel)
        (delete! o)))
    (is (== 1 (count (eallobjects fm))))))