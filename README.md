# parallel-test

A Leiningen plugin to run your tests in parallel

By default, parallel-test will search your project for tests flagged
^:parallel with metadata. It will first execute all tests not flagged such, then, in
parallel, execute all the tests flagged as such.

## Usage

Put `[parallel-test "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your project.clj.

    $ lein parallel-test

## Configuring

The default behavior described above is consequent to default
configuration and may be overridden. To override the config, it is
important to understand the concepts involved around categories,
pools, and sequencing.

### Categories

Categories are categorizations of tests, classified by metadata on the
vars holding those tests. Similar to leiningen's default test
framework's test selectors, categories create logical groupings of
work. The default categorizations are :parallel and :serial, and are
determined solely by the presence of a true value for the :parallel
key in a var's metadata. Categorization is done with a function of
test metadata; the default function is:

```clojure
    (fn [meta] (if (:parallel meta) :parallel :serial))
```

### Pools

Pools are pools of threads consuming work from categories. The default
configuration has two pools; the :serial pool has a single thread,
which executes each test in series. The :parallel pool scales its
number of threads to the number of cores reported by the JVM. It
executes tests in parallel until all tests are exhausted. Pools must
correspond one to one with categories, and provide a function
returning an integer. This function will be invoked once at test time,
and a number of threads corresponding to the integer it returns will
be created to consume tests from that pool. The default pooling
configuration is:

```clojure
    {:serial (constantly 1)
     :parallel (fn [] (.availableProcessors (Runtime/getRuntime)))}
```

### Sequencing

Sequencing specifies an ordering of categories. When a category is present in
sequencing data, all tests from that category must complete prior to the
next category beginning to test. Once all specified categories are
exhausted, all of the remaining categories will execute in
parallel. The default sequencing configuration is:

```clojure
    [:serial]
```

## Caveats

Running tests in parallel means exactly that; parallel-test does not
tackle the subjects of isolation or concurrent access for you. If your
tests or their fixtures make use of resources such as global
vars, databases, tcp ports, or other points of contention you will
need to roll your own strategy for concurrent access, or for siloing
these resources per test.

parallel-test parallelizes on a per-test basis, not on a per-namespace
basis. This means:

1. If you use test-ns-hook, it will be ignored. Parallel-test must
   adopt responsibility for executing your tests.
2. Your :once fixtures may be run more than once. They will be run no
   more than once per pool per category. Given the semantics of
   fixtures, they do need to be run in each thread running in each pool.

## License

Source Copyright © 2014 Alex Redington. Portions Copyright © 2009-2014 Phil Hagelberg, Alex Osborne, Dan Larkin, and contributors. Portions Copyright © Rich Hickey.

Distributed under the Eclipse Public License, the same as Clojure uses.
