(ns ct.spools.harnesses.guidance-process-identity-test
  "Deterministic birth-identity reuse interleavings for preflight cleanup."
  (:require [clojure.test :refer [deftest is testing]]
            [ct.spools.harnesses.internal.guidance-process-cleanup :as cleanup]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity])
  (:import [java.time Instant]))

(defn- fake-identity
  ([role pid started-at]
   (fake-identity role pid started-at nil))
  ([role pid started-at parent-birth]
   (let [state (atom {:alive true
                      :current-start started-at
                      :children []
                      :parent-birth parent-birth
                      :signals 0})]
     {:identity {:role role
                 :pid pid
                 :started-at started-at
                 :handle nil
                 :alive? #(true? (:alive @state))
                 :current-start #(:current-start @state)
                 :visit-children!
                 (fn [visit!]
                   (doseq [child (:children @state)]
                     (visit! child)))
                 :parent-birth #(:parent-birth @state)
                 :destroy! #(do (swap! state update :signals inc)
                                (swap! state assoc :alive false)
                                true)}
      :state state})))

(defn- with-interleave [hook f]
  (with-redefs-fn
    {(ns-resolve 'ct.spools.harnesses.internal.guidance-process-identity
                 'interleave!) hook}
    f))

(defn- deadline []
  (+ (System/nanoTime) 1000000000))

(defn- remaining [deadline]
  (- deadline (System/nanoTime)))

(deftest first-child-acquisition-requires-stable-original-parent-provenance
  (let [parent-start (Instant/parse "2026-09-14T00:00:00Z")
        child-start (.plusMillis parent-start 10)
        replacement-start (.plusSeconds child-start 1)
        {parent :identity parent-state :state}
        (fake-identity "parent" 40 parent-start)
        {original :identity original-state :state}
        (fake-identity "original" 41 child-start
                       {:pid 40 :started-at parent-start})
        {replacement :identity replacement-state :state}
        (fake-identity "replacement" 41 replacement-start
                       {:pid 99 :started-at parent-start})]
    (testing "an exited original child cannot be replaced at first acquisition"
      (swap! parent-state assoc :children [])
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"provenance is unavailable"
           (identity/retain-child parent 41 "anchor"))))
    (testing "matching PID and group numbers do not replace parent provenance"
      (swap! parent-state assoc :children [replacement])
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"does not belong"
           (identity/retain-child parent 41 "anchor"))))
    (testing "missing birth or parent evidence is rejected"
      (let [{missing-birth :identity}
            (fake-identity "missing-birth" 41 nil
                           {:pid 40 :started-at parent-start})
            {missing-parent :identity}
            (fake-identity "missing-parent" 41 child-start)]
        (swap! parent-state assoc :children [missing-birth])
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"start identity is unavailable"
             (identity/retain-child parent 41 "helper")))
        (swap! parent-state assoc :children [missing-parent])
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"does not belong"
             (identity/retain-child parent 41 "helper")))))
    (testing "changed and ambiguous first acquisitions grant no authority"
      (swap! parent-state assoc :children [original])
      (with-interleave
        (fn [phase observed]
          (when (and (= :after-child-acquisition phase)
                     (= 41 (:pid observed))
                     (= child-start (:started-at observed)))
            (swap! parent-state assoc :children [replacement])))
        #(is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"provenance changed"
              (identity/retain-child parent 41 "anchor"))))
      (swap! parent-state assoc :children [original original])
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"ambiguous"
           (identity/retain-child parent 41 "anchor"))))
    (doseq [state [original-state replacement-state]]
      (is (zero? (:signals @state))))))

(deftest state-pid-is-not-added-before-child-provenance-succeeds
  (let [parent-start (Instant/parse "2026-09-14T00:00:00Z")
        child-start (.plusMillis parent-start 10)
        replacement-start (.plusSeconds parent-start 1)
        {parent :identity parent-state :state}
        (fake-identity "supervisor" 40 parent-start)
        {original :identity original-state :state}
        (fake-identity "original" 41 child-start
                       {:pid 40 :started-at parent-start})
        {replacement :identity replacement-state :state}
        (fake-identity "replacement" 42 replacement-start
                       {:pid 99 :started-at parent-start})]
    (testing "a wrong state PID retains only independently proven cleanup"
      (let [ownership (atom {:supervisor parent})]
        (swap! parent-state assoc :children [original])
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"provenance is unavailable"
             (identity/remember-child! ownership :anchor :supervisor 42
                                       "ownership-anchor")))
        (is (nil? (:anchor @ownership)))
        (is (= [(select-keys original [:pid :started-at])]
               (mapv #(select-keys % [:pid :started-at])
                     (:proven-children @ownership))))
        (let [executor (java.util.concurrent.Executors/newSingleThreadExecutor)]
          (is (= #{40 41}
                 (set (cleanup/cleanup-owned!
                       ownership executor [] nil nil nil nil
                       (+ (System/nanoTime) 1000000000) remaining)))))))
    (testing "a replacement with wrong parent provenance gains no authority"
      (swap! parent-state assoc :alive true)
      (let [ownership (atom {:supervisor parent})]
        (swap! parent-state assoc :children [replacement])
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"does not belong"
             (identity/remember-child! ownership :anchor :supervisor 42
                                       "ownership-anchor")))
        (is (= #{:supervisor} (set (keys @ownership))))))
    (is (= 1 (:signals @original-state)))
    (is (zero? (:signals @replacement-state)))))

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

(deftest anchor-loss-immediately-before-promotion-blocks-all-group-authority
  (let [start (Instant/parse "2026-09-14T00:00:00Z")
        replacement-start (.plusSeconds start 1)
        {anchor :identity anchor-state :state}
        (fake-identity "anchor" 40 start)
        {member :identity member-state :state}
        (fake-identity "member" 41 start)
        rows [{:pid 40 :pgid 40} {:pid 41 :pgid 40}]
        confirmed (atom [])]
    (with-interleave
      (fn [phase _]
        (when (= :before-member-promotion phase)
          (swap! anchor-state assoc :current-start replacement-start)))
      #(let [{:keys [errors]} (identity/correlate-members!
                               anchor [member] rows 40
                               (fn [retained]
                                 (swap! confirmed conj retained)))]
         (is (re-find #"anchor changed" (ex-message (first errors))))))
    (is (empty? @confirmed))
    (is (zero? (:signals @member-state)))))

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
