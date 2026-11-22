;; jolt-admission smoke: minimal critical-path gate for CI.
;; Topic (board nc-1BDA): standalone acquire/release round-trip + deny on full
;; budget. Fail-fast: the first mismatch prints and exits 1.
;; Run: cd jolt-admission && jolt run test/jolt/admission_smoke.clj
(ns jolt.smoke.admission
  (:require [jolt.admission :as adm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn denied? [e]
  (boolean (and (instance? Exception e)
                (re-find #"denied|exceeds|closed|timeout" (ex-message e)))))

(let [ctl (adm/new-admission {:budget {:cpu 2 :mem-mb 1024}})]
  (let [p1 (adm/acquire! ctl {:owner "thr_1" :cost {:cpu 1 :mem-mb 512} :timeout-ms 0})]
    (check "grant returns status granted" "granted" (:status p1))
    (check "grant charges used" {:cpu 1 :mem-mb 512} (:used (adm/stats ctl)))
    (let [p2 (adm/acquire! ctl {:owner "thr_2" :cost {:cpu 1 :mem-mb 512} :timeout-ms 0})]
      (check "second grant fills budget" {:cpu 2 :mem-mb 1024} (:used (adm/stats ctl)))
      (check "granted count is 2" 2 (:granted (adm/stats ctl)))
      (let [e (try (adm/acquire! ctl {:owner "thr_3" :cost {:cpu 1 :mem-mb 512} :timeout-ms 120})
                   ::no-throw
                   (catch Exception ex ex))]
        (check "over budget times out to deny" true (denied? e)))
      (let [r (adm/release! ctl p1)]
        (check "release returns released status" "released" (:status r))
        (check "release frees budget" {:cpu 1 :mem-mb 512} (:used (adm/stats ctl))))
      (adm/release! ctl p2)
      (check "full release drains budget" {:cpu 0 :mem-mb 0} (:used (adm/stats ctl))))
  (adm/close! ctl)))

(println "ADMISSION SMOKE PASSED")
