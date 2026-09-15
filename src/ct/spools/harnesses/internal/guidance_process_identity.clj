(ns ct.spools.harnesses.internal.guidance-process-identity
  "Retained birth-fenced identities for native preflight processes."
  (:require [ct.spools.harnesses.internal.guidance-authority :as authority]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.lang ProcessHandle]))

(defn ^:dynamic ^:private interleave!
  "Test seam invoked at deterministic process-identity boundaries."
  [_phase _identity]
  nil)

(declare retain live? require-live! record-error! throw-errors!)

(defn- start-instant [^ProcessHandle handle]
  (.orElse (.startInstant (.info handle)) nil))

(defn- birth [^ProcessHandle handle]
  {:pid (.pid handle)
   :started-at (start-instant handle)})

(defn- visit-child-handles! [^ProcessHandle handle visit!]
  (with-open [children (.children handle)]
    (let [iterator (.iterator children)]
      (while (.hasNext iterator)
        (visit! (.next iterator))))))

(defn- parent-birth [^ProcessHandle handle]
  (some-> (.orElse (.parent handle) nil) birth))

;; The supported JDK's ProcessHandle implementation retains the native process
;; start time and supplies it to the native destroy operation. Keeping that same
;; handle therefore makes signaling birth-fenced; `started-at` is the separately
;; observed correlation identity.
(defn retain-direct
  "Retain authority over one directly created original process handle.

  This custody does not depend on supplementary start-time observation. The
  retained JDK handle itself remains bound to the process instance it created."
  ([^ProcessHandle handle role]
   (retain-direct handle role #(.destroyForcibly handle)))
  ([^ProcessHandle handle role destroy!]
   (when-not handle
     (fail! "Guidance directly created process handle is missing" {:role role}))
   {:role role
    :pid (.pid handle)
    :direct? true
    :handle handle
    :alive? #(.isAlive handle)
    :destroy! destroy!}))

(defn retain
  "Retain one actual process handle and its immutable start identity."
  [^ProcessHandle handle role]
  (when-not handle
    (fail! "Guidance process identity is missing" {:role role}))
  (let [started-at (start-instant handle)]
    (when-not started-at
      (fail! "Guidance process start identity is unavailable"
             {:role role :pid (.pid handle)}))
    {:role role
     :pid (.pid handle)
     :started-at started-at
     :handle handle
     :alive? #(.isAlive handle)
     :current-start #(start-instant handle)
     :visit-children! #(visit-child-handles! handle %)
     :parent-birth #(parent-birth handle)
     :destroy! #(.destroyForcibly handle)}))

(defn retain-pid
  "Resolve and retain `pid` once; never reacquire it for later authority."
  [pid role]
  (when-not (and (integer? pid) (pos? pid))
    (fail! "Guidance process identity has an invalid PID"
           {:role role :pid pid}))
  (retain (.orElse (ProcessHandle/of (long pid)) nil) role))

(defn- same-birth? [left right]
  (and left right
       (= (:pid left) (:pid right))
       (= (:started-at left) (:started-at right))))

(defn- observed-pid [observed]
  (if (map? observed) (:pid observed) (.pid ^ProcessHandle observed)))

(defn- retain-observed [observed role]
  (if (map? observed)
    (assoc observed :role role)
    (retain observed role)))

(defn- require-parent! [parent errors parent-valid? message]
  (when @parent-valid?
    (when-not (record-error! errors #(do (require-live! parent message) true))
      (reset! parent-valid? false)))
  @parent-valid?)

(defn retain-children!
  "Preserve each direct child immediately after complete parent proof.

  Return both independently retained births and per-child or enumeration
  failures. Parent loss stops new authority without erasing prior callbacks."
  [parent role confirmed!]
  (let [retained (atom [])
        errors (atom [])
        parent-valid? (atom true)]
    (require-parent! parent errors parent-valid?
                     "Guidance spawning parent is not live")
    (when @parent-valid?
      (try
        ((:visit-children! parent)
         (fn [observed]
           (when @parent-valid?
             (record-error!
              errors
              #(let [candidate (retain-observed observed role)]
                 (when-not (:started-at candidate)
                   (fail! "Guidance child process start identity is unavailable"
                          {:role role :pid (:pid candidate)
                           :parent-pid (:pid parent)}))
                 (require-live!
                  candidate "Guidance child changed during provenance validation")
                 (when-not (same-birth? parent ((:parent-birth candidate)))
                   (fail! "Guidance child process does not belong to its retained parent"
                          {:role role :pid (:pid candidate)
                           :parent-pid (:pid parent)}))
                 (try
                   (require-live!
                    parent
                    "Guidance spawning parent changed during child acquisition")
                   (catch Throwable error
                     (reset! parent-valid? false)
                     (throw error)))
                 (confirmed! candidate)
                 (swap! retained conj candidate)
                 (interleave! :after-child-promotion candidate)
                 (try
                   (require-live!
                    parent
                    "Guidance spawning parent changed after child acquisition")
                   (catch Throwable error
                     (reset! parent-valid? false)
                     (throw error))))))))
        (catch Throwable error
          (swap! errors conj error))))
    {:retained @retained :errors @errors}))

(defn retain-children
  "Retain every proven direct child or throw accumulated acquisition failures."
  [parent role]
  (let [{:keys [retained errors]}
        (retain-children! parent role (constantly nil))]
    (throw-errors! errors)
    retained))

(defn- unique-child! [parent pid role]
  (let [matches (atom [])
        errors (atom [])]
    (try
      ((:visit-children! parent)
       (fn [observed]
         (when (= pid (observed-pid observed))
           (record-error!
            errors #(swap! matches conj (retain-observed observed role))))))
      (catch Throwable error
        (swap! errors conj error)))
    (throw-errors! @errors)
    (when-not (= 1 (count @matches))
      (fail! "Guidance child process provenance is unavailable or ambiguous"
             {:role role :pid pid :matches (count @matches)
              :parent-pid (:pid parent)}))
    (first @matches)))

(defn retain-child!
  "Retain `pid` and publish it immediately after complete provenance proof."
  [parent pid role confirmed!]
  (when-not (and (integer? pid) (pos? pid))
    (fail! "Guidance child process identity has an invalid PID"
           {:role role :pid pid}))
  (require-live! parent "Guidance spawning parent is not live")
  (interleave! :before-child-acquisition parent)
  (let [candidate (unique-child! parent pid role)]
    (when-not (:started-at candidate)
      (fail! "Guidance child process start identity is unavailable"
             {:role role :pid pid :parent-pid (:pid parent)}))
    (interleave! :after-child-acquisition candidate)
    (require-live! parent "Guidance spawning parent changed during child acquisition")
    (require-live! candidate "Guidance child changed during provenance validation")
    (when-not (same-birth? parent ((:parent-birth candidate)))
      (fail! "Guidance child process does not belong to its retained parent"
             {:role role :pid pid :parent-pid (:pid parent)}))
    (let [confirmed (unique-child! parent pid role)]
      (when-not (and (same-birth? candidate confirmed)
                     (same-birth? parent ((:parent-birth confirmed))))
        (fail! "Guidance child process provenance changed during acquisition"
               {:role role :pid pid :parent-pid (:pid parent)})))
    (confirmed! candidate)
    candidate))

(defn retain-child
  "Retain `pid` only when it remains the original child of live `parent`."
  [parent pid role]
  (retain-child! parent pid role (constantly nil)))

(defn remember-child!
  "Add a state-discovered child only after provenance succeeds.

  On failure, retain independently proven direct children for cleanup without
  granting the reported PID authority."
  [ownership key parent-key pid role]
  (if-let [retained (get @ownership key)]
    (do
      (when-not (= pid (:pid retained))
        (fail! "Guidance preflight process identity changed"
               {:role role :expected (:pid retained) :actual pid}))
      (require-live!
       retained "Guidance preflight retained child identity is not live"))
    (let [parent (get @ownership parent-key)]
      (try
        (retain-child! parent pid role #(swap! ownership assoc key %))
        (catch Throwable error
          (let [{:keys [errors]}
                (retain-children!
                 parent "proven-child-for-cleanup"
                 #(swap! ownership update :proven-children
                         (fnil conj []) %))]
            (doseq [cleanup-error errors]
              (.addSuppressed error cleanup-error)))
          (throw error))))))

(defn live?
  "Return whether the retained original or birth-fenced identity is still live."
  [identity]
  (and identity
       ((:alive? identity))
       (or (:direct? identity)
           (= (:started-at identity) ((:current-start identity))))))

(defn require-live!
  "Return a live retained identity or fail without PID reacquisition."
  [identity message]
  (when-not (live? identity)
    (fail! message
           {:role (:role identity)
            :pid (:pid identity)
            :started-at (some-> (:started-at identity) str)}))
  identity)

(defn- record-error! [errors operation]
  (try
    (operation)
    (catch Throwable error
      (swap! errors conj error)
      nil)))

(defn- throw-errors! [errors]
  (when-let [error (first errors)]
    (doseq [suppressed (rest errors)]
      (.addSuppressed ^Throwable error ^Throwable suppressed))
    (throw error)))

(defn- require-anchor!
  [anchor rows-by-pid pgid errors authority? message]
  (when @authority?
    (when-not
     (record-error!
      errors
      #(do
         (require-live! anchor message)
         (when-not (= 1 (count (get rows-by-pid (:pid anchor))))
           (fail! "Guidance ownership scan omitted the original anchor"
                  {:anchor-pid (:pid anchor) :pgid pgid}))
         true))
      (reset! authority? false)))
  @authority?)

(defn retain-members
  "Retain each group discovery independently while the original anchor owns it.

  Returned identities are unconfirmed and have no signaling authority."
  [anchor rows pgid]
  (let [errors (atom [])
        retained (atom [])
        members (filterv #(= pgid (:pgid %)) rows)
        rows-by-pid (group-by :pid members)
        authority? (atom true)]
    (require-anchor! anchor rows-by-pid pgid errors authority?
                     "Guidance ownership anchor disappeared during scan")
    (doseq [row (remove #(= (:pid anchor) (:pid %)) members)
            :while @authority?]
      (when (require-anchor!
             anchor rows-by-pid pgid errors authority?
             "Guidance ownership anchor disappeared during member acquisition")
        (when-let [candidate
                   (record-error!
                    errors #(retain-pid (:pid row) "owned-group-member"))]
          (when (require-anchor!
                 anchor rows-by-pid pgid errors authority?
                 "Guidance ownership anchor changed during member acquisition")
            (swap! retained conj candidate)))))
    {:retained @retained :errors @errors}))

(defn correlate-members!
  "Validate retained members independently against one confirming scan.

  Invoke `confirmed!` immediately for each original birth that remains live and
  present. Failures do not discard earlier proof or stop later validation."
  [anchor identities rows pgid confirmed!]
  (let [errors (atom [])
        confirmed (atom [])
        by-pid (into {} (map (juxt :pid identity)) identities)
        members (filterv #(= pgid (:pgid %)) rows)
        rows-by-pid (group-by :pid members)
        authority? (atom true)]
    (require-anchor! anchor rows-by-pid pgid errors authority?
                     "Guidance ownership anchor disappeared during scan")
    (doseq [retained identities
            :while @authority?]
      (interleave! :before-member-correlation retained)
      (when (require-anchor!
             anchor rows-by-pid pgid errors authority?
             "Guidance ownership anchor changed before member correlation")
        (when (record-error!
               errors
               #(do
                  (when-not (= 1 (count (get rows-by-pid (:pid retained))))
                    (fail! "Guidance owned process identity disappeared"
                           {:pid (:pid retained) :pgid pgid}))
                  (require-live! retained
                                 "Guidance owned process identity disappeared")
                  true))
          (interleave! :before-member-promotion retained)
          (when (require-anchor!
                 anchor rows-by-pid pgid errors authority?
                 "Guidance ownership anchor changed before member promotion")
            (confirmed! retained)
            (swap! confirmed conj retained)))))
    (doseq [row members
            :when (and (not= (:pid anchor) (:pid row))
                       (not (contains? by-pid (:pid row))))]
      (record-error!
       errors
       #(fail! "Guidance ownership scan has ambiguous process identity"
               {:pid (:pid row) :pgid pgid})))
    {:confirmed @confirmed :errors @errors}))

(defn correlate!
  "Correlate every retained identity or throw all confirmation failures."
  [anchor identities rows pgid]
  (let [{:keys [confirmed errors]}
        (correlate-members! anchor identities rows pgid (constantly nil))]
    (throw-errors! errors)
    confirmed))

(defn- signal-original! [identity]
  (let [signalled? ((:destroy! identity))]
    (interleave! :after-signal identity)
    (when-not signalled?
      (fail! "Guidance retained process could not be signalled"
             {:role (:role identity) :pid (:pid identity)}))
    identity))

(defn signal!
  "Probe a retained birth, then signal its original handle under live authority."
  ([identity]
   (interleave! :before-signal identity)
   (when (live? identity)
     (signal-original! identity)))
  ([identity operation-authority]
   (interleave! :before-signal identity)
   (when (live? identity)
     (authority/run! operation-authority "cleanup-signal"
                     #(signal-original! identity)))))

(defn join!
  "Wait for the same retained identity without resolving its PID again."
  [identity deadline remaining-nanos]
  (interleave! :before-join identity)
  (while (live? identity)
    (when-not (pos? (remaining-nanos deadline))
      (fail! "Guidance retained process did not terminate"
             {:role (:role identity) :pid (:pid identity)}))
    (Thread/sleep 1))
  (interleave! :after-join identity)
  identity)
