(ns com.holychao.parallel-test)

(def VERSION "0.3.0-SNAPSHOT")

(def ^:dynamic *category*
  "Var holding the current category while tests are being run."
  nil)

(def ^:dynamic *index*
  "Var holding the current thread number while tests are being run."
  nil)
