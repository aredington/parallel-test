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
                    (= :begin-test-ns (:type m)) (test/with-test-out
                                                   (locking *out*
                                                     (newline)
                                                     (println "lein parallel-test" (ns-name (:ns m)) "# on thread" (:runner m))))
                    :else (test/with-test-out
                            (locking *out*
                              (apply report m args)))))))

(defmethod test/report :begin-test-category [m]
  (test/with-test-out
    (locking *out*
      (newline)
      (println "Testing category" (:category m)))))

(defmethod test/report :end-test-category [m])

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
  [index category test-source]
  (binding [ptest/*category* category
            ptest/*index* index]
    (let [first-var (async/<!! test-source)
          this-ns (-> first-var meta :ns)]
      (when first-var
        (test/do-report {:type :begin-test-ns :ns this-ns :category category :runner index})
        (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta this-ns)))
              each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta this-ns)))]
          (once-fixture-fn
           (fn []
             (loop [v first-var]
               (when (:test (meta v))
                 (each-fixture-fn (fn [] (test/test-var v)))
                 (let [new-v (async/<!! test-source)]
                   (when new-v (recur new-v))))))))
        (test/do-report {:type :end-test-ns :ns this-ns :category category :runner index})))))

(defn parallel-test-vars
  "Groups vars by their namespace and runs test-vars on them with
appropriate fixtures applied."
  [category threads vars]
  (let [test-sources (->> vars
                          (group-by (comp :ns meta))
                          (map (fn [[_ v]] (let [ch (async/chan 1)]
                                             (async/onto-chan ch v)
                                             ch))))
        open-chan (async/chan)
        close-chan (async/chan threads)
        coordinator (async/go-loop [sources test-sources]
                      (if (seq sources)
                        (recur (async/alt! [[open-chan (first sources)]] (concat (rest sources)
                                                                                 (list (first sources)))
                                           close-chan ([closed-chan] (remove #(= closed-chan %) sources))))
                        (async/close! open-chan)))
        testers (doall (for [index (range threads)]
                         (async/go-loop [source (async/<! open-chan)]
                           (when source
                             (test-ns-vars index category source)
                             (async/put! close-chan source)
                             (recur (async/<! open-chan))))))]
    (async/<!! coordinator)
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
