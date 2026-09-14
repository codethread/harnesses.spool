(ns ct.spools.harnesses.guidance-process-identity-test
  "Deterministic birth-identity reuse interleavings for preflight cleanup."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity])
  (:import [java.time Instant]))

(defn- fake-identity [role pid started-at]
  (let [state (atom {:alive true
                     :current-start started-at
                     :signals 0})]
    {:identity {:role role
                :pid pid
                :started-at started-at
                :handle nil
                :alive? #(true? (:alive @state))
                :current-start #(:current-start @state)
                :destroy! #(do (swap! state update :signals inc)
                               (swap! state assoc :alive false)
                               true)}
     :state state}))

(defn- with-interleave [hook f]
  (with-redefs-fn
    {(ns-resolve 'ct.spools.harnesses.internal.guidance-process-identity
                 'interleave!) hook}
    f))

(defn- deadline []
  (+ (System/nanoTime) 1000000000))

(defn- remaining [deadline]
  (- deadline (System/nanoTime)))

(deftest reuse-between-enumeration-and-correlation-is-not-authority
  (let [start (Instant/parse "2026-09-14T00:00:00Z")
        replacement-start (.plusSeconds start 1)
        {anchor :identity} (fake-identity "anchor" 40 start)
        {member :identity member-state :state}
        (fake-identity "member" 41 start)
        rows [{:pid 40 :pgid 40} {:pid 41 :pgid 40}]]
    (swap! member-state assoc :current-start replacement-start)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"identity disappeared"
         (identity/correlate! anchor [member] rows 40)))
    (is (zero? (:signals @member-state)))))

(deftest reuse-between-observation-and-signal-never-signals-replacement
  (let [start (Instant/parse "2026-09-14T00:00:00Z")
        replacement-start (.plusSeconds start 1)
        {retained :identity retained-state :state}
        (fake-identity "member" 41 start)
        {replacement :identity replacement-state :state}
        (fake-identity "replacement" 41 replacement-start)]
    (with-interleave
      (fn [phase observed]
        (when (and (= :before-signal phase)
                   (identical? retained observed))
          (swap! retained-state assoc
                 :current-start replacement-start)))
      #(is (nil? (identity/signal! retained))))
    (is (zero? (:signals @retained-state)))
    (is (zero? (:signals @replacement-state)))
    (is (identity/live? replacement))))

(deftest reuse-between-signal-and-join-never-adopts-replacement
  (let [start (Instant/parse "2026-09-14T00:00:00Z")
        replacement-start (.plusSeconds start 1)
        {retained :identity retained-state :state}
        (fake-identity "member" 41 start)
        {replacement :identity replacement-state :state}
        (fake-identity "replacement" 41 replacement-start)
        phases (atom [])]
    (with-interleave
      (fn [phase observed]
        (when (identical? retained observed)
          (swap! phases conj phase)))
      #(do
         (is (= retained (identity/signal! retained)))
         (is (= retained (identity/join! retained (deadline) remaining)))))
    (is (= [:before-signal :after-signal :before-join :after-join]
           @phases))
    (is (= 1 (:signals @retained-state)))
    (is (zero? (:signals @replacement-state)))
    (is (identity/live? replacement))))

(deftest anchor-and-pgid-reuse-cannot-authorize-a-replacement-group
  (let [start (Instant/parse "2026-09-14T00:00:00Z")
        replacement-start (.plusSeconds start 1)
        {anchor :identity anchor-state :state}
        (fake-identity "anchor" 40 start)
        {replacement-anchor :identity replacement-state :state}
        (fake-identity "replacement-anchor" 40 replacement-start)
        {member :identity member-state :state}
        (fake-identity "replacement-member" 41 replacement-start)
        rows [{:pid 40 :pgid 40} {:pid 41 :pgid 40}]]
    (swap! anchor-state assoc :current-start replacement-start)
    (testing "the original anchor fence fails before numerical correlation"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"anchor disappeared"
           (identity/correlate! anchor
                                [replacement-anchor member]
                                rows 40))))
    (doseq [state [anchor-state replacement-state member-state]]
      (is (zero? (:signals @state))))))
