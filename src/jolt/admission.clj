;; jolt-admission: weighted budget-permit admission control.
(ns jolt.admission
  "Weighted admission control over a resource budget for the jolt control plane.

  Grants and reclaims Budget Permits (jolt-contracts SPEC.md §5) against a
  configured budget. acquire! is weighted: each request's :cost is multiplied
  by its resource weight and charged against the budget; :priority orders the
  wait queue (higher wins under contention) and :timeout-ms bounds how long a
  request waits before denial. 0 = block forever, absent = 0.

  acquire!/release!/close! are concurrency-safe: a per-controller monitor
  serializes them, and acquire! blocks (up to :timeout-ms) while the budget
  cannot cover a request's weighted charge."
  (:import [java.util PriorityQueue]))

(defrecord Admission [opts used granted waiters seq closed lock])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :jolt.admission/error true))))

(defn- waiter-cmp
  "Order waiters: higher :priority first, then FIFO by :seq."
  [a b]
  (let [p (- (:priority b) (:priority a))]
    (if (zero? p)
      (- (:seq a) (:seq b))
      p)))

(defn- weighted-charge
  "The effective charge of cost: each resource amount times its weight
  (default 1)."
  [ctl cost]
  (let [weights (:weights (:opts ctl))]
    (reduce-kv (fn [m r amt] (assoc m r (* amt (get weights r 1)))) {} cost)))

(defn- affordable?
  "True iff granting charge keeps every used resource within budget."
  [ctl charge]
  (let [budget (:budget (:opts ctl))
        used @(:used ctl)]
    (every? (fn [[r amt]] (<= (+ (get used r 0) amt) (get budget r 0))) charge)))

(defn- over-budget?
  "True iff any single resource in charge exceeds budget outright."
  [ctl charge]
  (let [budget (:budget (:opts ctl))]
    (boolean (some (fn [[r amt]] (> amt (get budget r 0))) charge))))

(defn- validate-req! [req]
  (when-not (map? req)
    (fail! "request must be a map" {:req req}))
  (when-not (and (string? (:owner req)) (seq (:owner req)))
    (fail! "request requires a non-empty string :owner" {:req req}))
  (when-not (and (map? (:cost req)) (seq (:cost req)))
    (fail! "request requires a non-empty :cost map" {:req req})))

(defn- check-open! [ctl]
  (when @(:closed ctl)
    (fail! "admission controller is closed" {:type :admission-denied})))

(defn- make-waiter [ctl req charge]
  (let [t (or (:timeout-ms req) 0)
        s (swap! (:seq ctl) inc)]
    {:permit-id (or (:permit-id req) (str "perm_" (System/nanoTime) "_" s))
     :owner (:owner req)
     :cost (:cost req)
     :charge charge
     :priority (or (:priority req) 0)
     :timeout-ms t
     :deadline (when (pos? t) (+ (System/nanoTime) (* 1000000 t)))
     :seq s}))

(defn- remaining-ms [waiter]
  (when-let [t (:timeout-ms waiter)]
    (when (pos? t)
      (long (/ (- (:deadline waiter) (System/nanoTime)) 1000000.0)))))

(defn- grant! [ctl waiter]
  (swap! (:used ctl) #(merge-with + % (:charge waiter)))
  (swap! (:granted ctl) inc)
  {:v 1
   :permit-id (:permit-id waiter)
   :owner (:owner waiter)
   :cost (:cost waiter)
   :priority (:priority waiter)
   :timeout-ms (:timeout-ms waiter)
   :status "granted"})

(defn new-admission
  "Open an admission controller over opts, a map with :budget (resource ->
  amount, e.g. {:cpu 16 :mem-mb 8192}) and optional :weights (resource ->
  weight, default 1). Returns an opaque controller handle."
  [opts]
  (let [opts (or opts {})
        budget (:budget opts)
        weights (or (:weights opts) {})]
    (when-not (and (map? budget) (seq budget))
      (fail! ":budget must be a non-empty map" {:budget budget}))
    (when-not (every? number? (vals budget))
      (fail! ":budget values must be numbers" {:budget budget}))
    (when-not (every? number? (vals weights))
      (fail! ":weights values must be numbers" {:weights weights}))
    (->Admission (assoc opts :budget budget :weights weights)
                 (atom (zipmap (keys budget) (repeat 0)))
                 (atom 0)
                 (PriorityQueue. 11 waiter-cmp)
                 (atom 0)
                 (atom false)
                 (Object.))))

(defn acquire!
  "Block up to :timeout-ms for a Budget Permit for req, a map with :owner,
  :cost (resource -> amount), :priority and :timeout-ms. Returns the granted
  permit (SPEC.md §5) or throws on deny."
  [ctl req]
  (validate-req! req)
  (locking (:lock ctl)
    (check-open! ctl)
    (let [charge (weighted-charge ctl (:cost req))]
      (when (over-budget? ctl charge)
        (fail! "request exceeds budget" {:type :admission-denied :req req}))
      (let [waiter (make-waiter ctl req charge)]
        (if (and (.isEmpty (:waiters ctl)) (affordable? ctl charge))
          (grant! ctl waiter)
          (do (.add (:waiters ctl) waiter)
              (loop []
                (cond
                  @(:closed ctl)
                  (do (.remove (:waiters ctl) waiter)
                      (fail! "admission controller closed" {:type :admission-denied :req req}))
                  (and (= (.peek (:waiters ctl)) waiter) (affordable? ctl charge))
                  (do (.remove (:waiters ctl) waiter)
                      (grant! ctl waiter))
                  :else
                  (let [rem (remaining-ms waiter)]
                    (if (and rem (not (pos? rem)))
                      (do (.remove (:waiters ctl) waiter)
                          (fail! "timeout waiting for permit" {:type :admission-denied :req req}))
                      (do (try (.wait (:lock ctl) (or rem 0))
                               (catch InterruptedException e
                                 (.interrupt (Thread/currentThread))
                                 (.remove (:waiters ctl) waiter)
                                 (throw (RuntimeException. "interrupted acquiring permit" e))))
                          (recur))))))))))))

(defn release!
  "Release the Budget Permit permit back to the budget. Returns the permit
  with :status released."
  [ctl permit]
  (when-not (map? permit)
    (fail! "permit must be a map" {:permit permit}))
  (locking (:lock ctl)
    (let [charge (weighted-charge ctl (:cost permit))]
      (swap! (:used ctl) #(merge-with - % charge))
      (swap! (:granted ctl) dec)
      (.notifyAll (:lock ctl))
      (assoc permit :status "released"))))

(defn stats
  "Return {:budget {...} :used {...} :granted n :waiting n} for the controller."
  [ctl]
  (locking (:lock ctl)
    {:budget (:budget (:opts ctl))
     :used @(:used ctl)
     :granted @(:granted ctl)
     :waiting (.size (:waiters ctl))}))

(defn close!
  "Close the controller, waking any blocked acquire! (which then throws).
  Idempotent."
  [ctl]
  (locking (:lock ctl)
    (reset! (:closed ctl) true)
    (.notifyAll (:lock ctl))
    nil))
