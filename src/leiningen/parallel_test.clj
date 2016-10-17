(ns leiningen.parallel-test
  "Run the project's tests in parallel."
  (:refer-clojure :exclude [test])
  (:require [clojure.java.io :as io]
            [bultitude.core :as b]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project]
            [com.holychao.parallel-test :as ptest])
  (:import (java.io File PushbackReader)))

(def ^:dynamic *exit-after-tests* true)

(def ^:private default-config
  '{:categorizer (fn [meta] (if (:parallel meta) :parallel :serial))
    :pools {:serial (constantly 1)
            :parallel (fn [] (.availableProcessors (Runtime/getRuntime)))}
    :sequence [:serial]})

(def ^:private parallel-test-profile
  {:dependencies [['org.clojure/core.async "0.2.391"]
                  ['com.holychao/parallel-test ptest/VERSION]
                  ['robert/hooke "1.3.0"]]})

;; TODO: make this an arg to form-for-testing-namespaces in 3.0.
(def ^:private ^:dynamic *monkeypatch?* true)

(defn form-for-testing-namespaces
  "Return a form that when eval'd in the context of the project will test each
namespace and print an overall summary."
  [config namespaces & [selectors]]
  `(com.holychao.parallel-test.runner/parallel-test ~config
                                                    '~namespaces
                                                    ~selectors
                                                    ~*monkeypatch?*
                                                    ~*exit-after-tests*))

(defn- split-selectors [args]
  (let [[nses selectors] (split-with (complement keyword?) args)]
    [nses
     (loop [acc {} [selector & selectors] selectors]
       (if (seq selectors)
         (let [[args next] (split-with (complement keyword?) selectors)]
           (recur (assoc acc selector (list 'quote args))
                  next))
         (if selector
           (assoc acc selector ())
           acc)))]))

(defn- partial-selectors [project-selectors selectors]
  (for [[k v] selectors
        :let [selector-form (k project-selectors)]
        :when selector-form]
    [selector-form v]))

(def ^:private only-form
  ['(fn [ns & vars]
      ((set (for [v vars]
              (-> (str v)
                  (.split "/")
                  (first)
                  (symbol))))
       ns))
   '(fn [m & vars]
      (some #(let [var (str "#'" %)]
               (if (some #{\/} var)
                 (= var (-> m ::var str))
                 (= % (ns-name (:ns m)))))
            vars))])

(defn- convert-to-ns [possible-file]
  (if (and (.endsWith possible-file ".clj") (.exists (io/file possible-file)))
    (str (second (b/ns-form-for-file possible-file)))
    possible-file))

(defn ^:internal read-args [args project]
  (let [args (->> args (map convert-to-ns) (map read-string))
        [nses given-selectors] (split-selectors args)
        nses (or (seq nses)
                 (sort (b/namespaces-on-classpath
                        :classpath (map io/file (distinct (:test-paths project)))
                        :ignore-unreadable? false)))
        selectors (partial-selectors (merge {:all '(constantly true)}
                                            {:only only-form}
                                            (:test-selectors project))
                                     given-selectors)
        selectors (if (and (empty? selectors)
                           (:default (:test-selectors project)))
                    [[(:default (:test-selectors project)) ()]]
                    selectors)]
    (when (and (empty? selectors)
               (seq given-selectors))
      (main/abort "Please specify :test-selectors in project.clj"))
    [nses selectors]))

(defn parallel-test
  "Run the project's tests in parallel."
  [project & tests]
  (binding [main/*exit-process?* (if (= :leiningen (:eval-in project))
                                   false
                                   main/*exit-process?*)
            *exit-after-tests* (if (= :leiningen (:eval-in project))
                                 false
                                 *exit-after-tests*)
            *monkeypatch?* (:monkeypatch-clojure-test project true)]
    (let [profile (or (:parallel-test (:profiles project)) parallel-test-profile)
          project (-> project
                      (project/add-profiles {:parallel-test profile})
                      (project/merge-profiles [:leiningen/test :test :parallel-test]))
          [nses selectors] (read-args tests project)
          config (merge default-config
                        (:parallel-test project))
          form (form-for-testing-namespaces config nses (vec selectors))]
      (try (when-let [n (eval/eval-in-project project form
                                              '(do (require 'clojure.test)
                                                   (require 'com.holychao.parallel-test.runner)))]
             (when (and (number? n) (pos? n))
               (throw (ex-info "Tests Failed" {:exit-code n}))))
           (catch clojure.lang.ExceptionInfo e
             (main/abort "Tests failed."))))))
