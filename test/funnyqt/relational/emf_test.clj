(ns funnyqt.relational.emf-test
  (:refer-clojure :exclude [==])
  (:use clojure.core.logic
        funnyqt.relational.emf
        funnyqt.relational
        [funnyqt.emf-test :only [family-model]])
  (:require [funnyqt.emf     :as emf]
            [funnyqt.query   :as q]
            [funnyqt.generic :as g]
            [clojure.test    :as t]))

(generate-ecore-model-relations "test/input/Families.ecore"
                                test.relational.families.emf families +)

(t/deftest test-eobjecto
  (t/is (= (emf/eallcontents family-model)
           (run* [q]
             (eobjecto family-model q)))))

(t/deftest test-eobjecto-with-type
  (t/is (= (emf/eallcontents family-model 'Member)
           (run* [q]
             (eobjecto family-model q 'Member))
           (run* [q]
             (families/+Member family-model q))))
  (t/is (= (emf/eallcontents family-model '!Member)
           (run* [q]
             (eobjecto family-model q '!Member))
           (run* [q]
             (families/+!Member family-model q)))))

(t/deftest test-valueo
  (t/is (= (map (fn [e]
                  [e (emf/eget e :firstName)])
                (emf/eallcontents family-model 'Member))
           (run* [q]
             (with-fresh
               (valueo family-model ?elem :firstName ?val)
               (== q [?elem ?val])))
           (run* [q]
             (with-fresh
               (families/+firstName family-model ?elem ?val)
               (== q [?elem ?val]))))))

(t/deftest test-refo
  (let [fam-carter (q/the #(= "Carter" (emf/eget % :lastName))
                          (emf/eallcontents family-model 'Family))]
    (t/is (= (g/adjs fam-carter :daughters)
             (run* [q]
               (refo family-model fam-carter :daughters q))
             (run* [q]
               (families/+->daughters family-model fam-carter q))))))
