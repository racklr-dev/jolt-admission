;; jolt-admission: weighted budget-permit admission control.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.admission
  "Weighted admission control over a resource budget for the jolt control plane.

  Grants and reclaims Budget Permits (jolt-contracts SPEC.md §5) against a
  configured budget. acquire! is weighted: each request's :cost is multiplied
  by its resource weight and charged against the budget; :priority orders the
  wait queue and :timeout-ms bounds how long a request waits before denial.")

(defn new-admission
  "Open an admission controller over opts, a map with :budget (resource ->
  amount, e.g. {:cpu 16 :mem-mb 8192}) and optional :weights (resource ->
  weight, default 1). Returns an opaque controller handle."
  [opts]
  (throw (ex-info "not implemented: new-admission" {:opts opts})))

(defn acquire!
  "Block up to :timeout-ms for a Budget Permit for req, a map with :owner,
  :cost (resource -> amount), :priority and :timeout-ms. Returns the granted
  permit (SPEC.md §5) or throws on deny."
  [ctl req]
  (throw (ex-info "not implemented: acquire!" {:req req})))

(defn release!
  "Release the Budget Permit permit back to the budget. Returns nil."
  [ctl permit]
  (throw (ex-info "not implemented: release!" {:permit permit})))

(defn stats
  "Return {:budget {...} :used {...} :granted n :waiting n} for the controller."
  [ctl]
  (throw (ex-info "not implemented: stats" {})))

(defn close!
  "Close the controller. Idempotent."
  [ctl]
  (throw (ex-info "not implemented: close!" {})))
