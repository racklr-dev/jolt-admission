# jolt-admission — weighted budget-permit admission control

Admission control for a jolt-based control plane: it grants and reclaims Budget
Permits ([faraday-contracts](https://github.com/racklr-dev/faraday/blob/main/contracts/SPEC.md)
§5) against a configured resource budget, so a plane can cap concurrent
sandbox cost. Each request's cost is weighted per resource and charged against
the budget; higher `:priority` wins under contention; a request that cannot be
granted within `:timeout-ms` is denied.

Standalone: no control-plane dependency and no runtime deps
(`{:paths ["src"]}`). The permit is a plain EDN map here; serialize it to the
JSON contract at a process boundary.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests

## Build

Nothing to compile. Run the suite:

```sh
jolt run test/jolt/admission_test.clj
jolt run test/jolt/admission_smoke.clj
```

## Usage

```clojure
{:deps {racklr-dev/jolt-admission
        {:git/url "https://github.com/racklr-dev/jolt-admission"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.admission :as adm])

(def ctl (adm/new-admission {:budget {:cpu 16 :mem-mb 8192}
                             :weights {:cpu 1}}))

(def permit (adm/acquire! ctl {:owner "thr_1"
                               :cost {:cpu 1 :mem-mb 512}
                               :priority 0
                               :timeout-ms 30000}))
;; => {:v 1 :permit-id "perm_..." :owner "thr_1"
;;     :cost {:cpu 1 :mem-mb 512} :priority 0 :timeout-ms 30000
;;     :status "granted"}

(adm/stats ctl)           ; => {:budget ... :used {:cpu 1 :mem-mb 512} :granted 1 :waiting 0}
(adm/release! ctl permit) ; => the permit with :status "released"
(adm/close! ctl)
```

When the budget cannot cover a request, `acquire!` queues it (priority order,
then FIFO) until `:timeout-ms`, then throws `:admission-denied`; a request
whose single resource exceeds the budget is denied immediately.

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-admission` | `opts` | controller handle | throws on a bad `:budget`/`:weights` |
| `acquire!` | `ctl req` | permit (`:v 1 :status "granted"`) | throws `:admission-denied` on over-budget, timeout, or close |
| `release!` | `ctl permit` | the permit with `:status "released"` | throws on a non-map permit |
| `stats` | `ctl` | `{:budget :used :granted :waiting}` | — |
| `close!` | `ctl` | nil | wakes blocked waiters (they throw) |

`req` keys: `:owner` (non-empty string), `:cost` (resource → amount map),
`:priority` (higher wins, default 0), `:timeout-ms` (0 = block forever),
`:permit-id` (optional; defaulted). `acquire!`/`release!`/`close!` are
concurrency-safe behind a per-controller monitor.

## Status

alpha — weighted acquire/release, priority + timeout deny, over-budget deny,
and close-wakes-waiters are tested. Fairness across owners under sustained
contention is untested.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-contracts](https://github.com/racklr-dev/faraday/blob/main/contracts/SPEC.md) — SPEC.md §5, the Budget Permit shape
- [faraday-core](https://github.com/racklr-dev/faraday/blob/main/core) — the plane that composes this as its admission seam
