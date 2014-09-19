(ns com.holychao.parallel-test.runner
  (:require [com.holychao.parallel-test :as ptest]
            [clojure.core.async :as async]
            [robert.hooke :as hook]
            [clojure.test :as test]
            [clojure.set :as set]))

(def test-ns-hook-delays (atom {}))

(defn- monkeypatch-clojure-test-report
  [failures]
  (hook/add-hook #'test/report
                 (fn [report m & args]
                   (cond
                    (#{:fail :error} (:type m)) (when-let [first-var (-> test/*testing-vars* first meta)]
                                                  (swap! failures conj (ns-name (:ns first-var)))
                                                  (test/with-test-out
                                                    (locking *out*
                                                      (newline)
                                                      (println "lein parallel-test :only"
                                                               (str (ns-name (:ns first-var))
                                                                    "/"
                                                                    (:name first-var)))
                                                      (apply report m args))))
                    (= :begin-test-category (:type m)) (test/with-test-out
                                                         (locking *out*
                                                           (newline)
                                                           (println "Testing category" (:category m))))
                    (= :end-test-category (:type m)) nil
                    (= :begin-test-ns (:type m)) (test/with-test-out
                                                   (locking *out*
                                                     (newline)
                                                     (println "lein parallel-test" (ns-name (:ns m)) "# on thread " (:runner m))))
                    :else (test/with-test-out
                            (locking *out*
                              (apply report m args)))))))

(defn- monkeypatch-clojure-test-inc-report-counter
  []
  (hook/add-hook #'test/inc-report-counter
                 (fn [broken name & args]
                   (when test/*report-counters*
                     (dosync
                      (commute test/*report-counters*
                               update-in [name] (fnil inc 0)))))))

(defn select-clause
  [var [selector args]]
  (apply (if (vector? selector)
           (second selector)
           selector)
         (merge (-> var meta :ns meta)
                (assoc (meta var) :leiningen.parallel-test/var var))
         args))

(defn- select-namespaces
  [namespaces selectors]
  (distinct (for [ns namespaces
                  [_ var] (ns-publics ns)
                  :when (some (partial select-clause var) selectors)] ns)))

(defn test-ns-vars
  "Run the test described in var, and continue to run additional tests
  from test-source, provided the tests are in the same ns as var. When
  a test is returned from test-source not in the same ns, returns it
  without running it. If test-source is closed, returns nil."
  [index category test-source var]
  (when var
    (with-local-vars [different-var nil
                      current-ns (-> var meta :ns)]
      (binding [ptest/*category* category
                ptest/*index* index]
        (test/do-report {:type :begin-test-ns :ns @current-ns :category category :runner index})
        (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta @current-ns)))
              each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta @current-ns)))]
          (once-fixture-fn
           (fn []
             (loop [v var]
               (when (:test (meta v))
                 (each-fixture-fn (fn [] (test/test-var v)))
                 (let [new-v (async/<!! test-source)
                       ns-of (comp :ns meta)]
                   (cond (nil? new-v) nil
                         (= (ns-of var) (ns-of new-v)) (recur new-v)
                         :else (var-set different-var new-v))))))))
        (test/do-report {:type :end-test-ns :ns @current-ns :category category :runner index})
        @different-var))))

(defn parallel-test-vars
  "Groups vars by their namespace and runs test-vars on them with
appropriate fixtures applied."
  [category threads vars]
  (let [test-sink (async/chan threads)
        testers (doall (for [index (range threads)]
                         (async/go-loop [new-ns-var (test-ns-vars index category test-sink (async/<! test-sink))]
                           (when new-ns-var
                             (recur (test-ns-vars index category test-sink new-ns-var))))))]
    (doseq [var vars]
      (async/>!! test-sink var))
    (async/close! test-sink)
    (loop [running-testers testers]
      (when-not (empty? running-testers)
        (let [[_ completed-tester] (async/alts!! (vec running-testers))]
          (recur (disj (set running-testers) completed-tester)))))))

(defn- test-summary
  [{:keys [categorizer sequence pools] :as config} namespaces selectors]
  (let [vars (if (seq selectors)
               (->> namespaces
                    (mapcat (comp vals ns-interns))
                    (filter (fn [var] (-> var meta :test)))
                    (filter (fn [var] (some (partial select-clause var) selectors)))))
        categories (->> vars
                        (group-by #(-> % meta categorizer))
                        (map (fn [[k v]] [k (sort-by (comp ns-name :ns meta) v)]))
                        (into {}))
        dregs (set/difference (set (keys categories)) (set sequence))]
    (apply merge-with +
           (for [category (concat sequence dregs)]
             (binding [test/*test-out* *out*
                       test/*report-counters* (ref test/*initial-report-counters*)] 
               (test/do-report {:type :begin-test-category :category category})
               (parallel-test-vars category ((pools category)) (categories category))
               (test/do-report {:type :end-test-category :category category})
               @test/*report-counters*)))))

(defn parallel-test
  [config nses selectors monkeypatch? exit-after-tests?]
  (let [namespaces (reduce (fn [acc [f args]]
                             (if (vector? f)
                               (filter (fn* [p1]
                                            (apply (first f)
                                                   p1
                                                   args))
                                       acc)
                               acc))
                           nses
                           selectors)]
    (when (seq namespaces)
      (apply require :reload namespaces))
    (let [failures (atom #{})
          selected-namespaces (select-namespaces namespaces selectors)
          _ (when monkeypatch?
              (monkeypatch-clojure-test-report failures)
              (monkeypatch-clojure-test-inc-report-counter))
          summary (test-summary config selected-namespaces selectors)]
      (test/do-report (assoc summary :type :summary))
      (spit ".lein-failures"
            (if monkeypatch?
              (pr-str (deref failures))
              "#<disabled :monkeypatch-clojure-test>"))
      (if exit-after-tests?
        (java.lang.System/exit (+ (:error summary) (:fail summary)))
        (+ (:error summary) (:fail summary))))))
