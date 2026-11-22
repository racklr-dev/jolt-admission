;; jolt-admission stub test: pins the public API surface.
;; Run: cd jolt-admission && jolt run test/jolt/admission_test.clj
(ns jolt.test.admission
  (:require [jolt.admission :as adm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn stub-err [thunk]
  (try (thunk) ::no-throw
       (catch Exception e (ex-message e))))

(defn stub? [msg]
  (boolean (re-find #"^not implemented" (or msg ""))))

(def sample-req
  {:owner "thr_1" :cost {:cpu 1 :mem-mb 512} :priority 0 :timeout-ms 30000})

(check "new-admission defined" true (fn? adm/new-admission))
(check "new-admission stub" true (stub? (stub-err #(adm/new-admission {:budget {:cpu 16 :mem-mb 8192}}))))
(check "acquire! stub" true (stub? (stub-err #(adm/acquire! nil sample-req))))
(check "release! stub" true (stub? (stub-err #(adm/release! nil sample-req))))
(check "stats stub" true (stub? (stub-err #(adm/stats nil))))
(check "close! stub" true (stub? (stub-err #(adm/close! nil))))
