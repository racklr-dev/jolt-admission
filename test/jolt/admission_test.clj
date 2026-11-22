;; jolt-admission behavior test: weighted acquire/release, priority, timeout,
;; close, validation. Fail-fast: first mismatch prints and exits 1.
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

(defn denied? [e]
  (and (instance? Exception e)
       (boolean (re-find #"denied|exceeds|closed|timeout" (ex-message e)))))

(defn async-acquire [ctl req]
  (let [p (promise)
        t (Thread. (fn [] (deliver p (try (adm/acquire! ctl req) (catch Exception e e)))))]
    (.start t)
    p))

;; -- grant + release + timeout ---------------------------------------------
(let [ctl (adm/new-admission {:budget {:cpu 4}})
      p (adm/acquire! ctl {:owner "thr_1" :cost {:cpu 3} :timeout-ms 0})]
  (check "grant returns status granted" "granted" (:status p))
  (check "grant charges used" {:cpu 3} (:used (adm/stats ctl)))
  (check "granted count is 1" 1 (:granted (adm/stats ctl)))
  (let [e (try (adm/acquire! ctl {:owner "thr_2" :cost {:cpu 3} :timeout-ms 120})
               ::no-throw
               (catch Exception ex ex))]
    (check "over budget times out to deny" true (denied? e)))
  (let [r (adm/release! ctl p)]
    (check "release returns released status" "released" (:status r))
    (check "release frees budget" {:cpu 0} (:used (adm/stats ctl))))
  (let [p2 (adm/acquire! ctl {:owner "thr_3" :cost {:cpu 4} :timeout-ms 0})]
    (check "released budget grants again" "granted" (:status p2))
    (adm/release! ctl p2))
  (adm/close! ctl))

;; -- weighted cost: weight 2 charges double ---------------------------------
(let [ctl (adm/new-admission {:budget {:cpu 10} :weights {:cpu 2}})
      p (adm/acquire! ctl {:owner "o" :cost {:cpu 4} :timeout-ms 0})]
  (check "resource weight applies" {:cpu 8} (:used (adm/stats ctl)))
  (adm/release! ctl p)
  (adm/close! ctl))

;; -- priority: higher-priority waiter wins freed budget ---------------------
(let [ctl (adm/new-admission {:budget {:cpu 1}})
      holder (adm/acquire! ctl {:owner "holder" :cost {:cpu 1} :timeout-ms 0})
      low (async-acquire ctl {:owner "low" :cost {:cpu 1} :priority 0 :timeout-ms 5000})
      _ (Thread/sleep 200)
      high (async-acquire ctl {:owner "high" :cost {:cpu 1} :priority 10 :timeout-ms 5000})
      _ (Thread/sleep 200)]
  (adm/release! ctl holder)
  (let [h (deref high 5000 ::timeout)]
    (check "high-priority waiter grants first" "high" (:owner h))
    (let [l (deref low 200 ::still-waiting)]
      (check "low-priority still waits" ::still-waiting l))
    (adm/release! ctl h)
    (let [l (deref low 5000 ::timeout)]
      (check "low-priority grants after high releases" "low" (:owner l))
      (adm/release! ctl l)))
  (adm/close! ctl))

;; -- close! wakes a blocked waiter ------------------------------------------
(let [ctl (adm/new-admission {:budget {:cpu 1}})
      holder (adm/acquire! ctl {:owner "holder" :cost {:cpu 1} :timeout-ms 0})
      w (async-acquire ctl {:owner "w" :cost {:cpu 1} :timeout-ms 0})
      _ (Thread/sleep 200)]
  (adm/close! ctl)
  (let [r (deref w 2000 ::timeout)]
    (check "close wakes blocked acquire with closed error" true
           (and (instance? Exception r)
                (boolean (re-find #"closed" (ex-message r)))))))

;; -- validation: over-budget denied immediately ------------------------------
(let [ctl (adm/new-admission {:budget {:cpu 1}})]
  (let [e (try (adm/acquire! ctl {:owner "x" :cost {:cpu 100} :timeout-ms 0})
               ::no-throw
               (catch Exception ex ex))]
    (check "over-budget request denied immediately" true (denied? e)))
  (adm/close! ctl))

(println "ADMISSION TESTS PASSED")
