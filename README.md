# jolt-admission — weighted budget-permit admission control

One concrete mechanism: grants and reclaims Budget Permits (jolt-contracts
SPEC.md §5) against a configured resource budget, so the control plane can
cap concurrent sandbox cost. Weighted acquire with priority and timeout:
each request's cost is weighted per resource and charged against the budget;
higher priority jumps the queue; a request that cannot be granted within
:timeout-ms is denied.

Standalone: no control-plane dependency. The Budget Permit shape is a plain
EDN map here; serialize it to the JSON contract (SPEC.md §5) at a boundary.

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-admission
        {:git/url "https://github.com/racklr-dev/jolt-admission"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.admission :as adm])

(def ctl (adm/new-admission {:budget {:cpu 16 :mem-mb 8192}}))
(def permit (adm/acquire! ctl {:owner "thr_1" :cost {:cpu 1 :mem-mb 512}
                               :priority 0 :timeout-ms 30000}))
(adm/release! ctl permit)
(adm/stats ctl)
(adm/close! ctl)
```

## Status

Scaffold — public API surface pinned, all bodies throw :not-implemented. The
stub test is green (jolt run test/jolt/admission_test.clj). Implemented next
(board nc-C548): weighted acquire/release with priority and timeout.

## License

EPL-2.0
