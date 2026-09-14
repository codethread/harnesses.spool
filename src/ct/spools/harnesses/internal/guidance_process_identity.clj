(ns ct.spools.harnesses.internal.guidance-process-identity
  "Retained birth-fenced identities for native preflight processes."
  (:require [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.lang ProcessHandle]))

(defn ^:dynamic ^:private interleave!
  "Test seam invoked at deterministic process-identity boundaries."
  [_phase _identity]
  nil)

(declare retain live? require-live!)

(defn- start-instant [^ProcessHandle handle]
  (.orElse (.startInstant (.info handle)) nil))

(defn- birth [^ProcessHandle handle]
  {:pid (.pid handle)
   :started-at (start-instant handle)})

(defn- children [^ProcessHandle handle]
  (with-open [children (.children handle)]
    (mapv #(retain % "observed-child")
          (iterator-seq (.iterator children)))))

(defn- parent-birth [^ProcessHandle handle]
  (some-> (.orElse (.parent handle) nil) birth))

;; The supported JDK's ProcessHandle implementation retains the native process
;; start time and supplies it to the native destroy operation. Keeping that same
;; handle therefore makes signaling birth-fenced; `started-at` is the separately
;; observed correlation identity.
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
     :children #(children handle)
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

(defn retain-children
  "Retain only live direct children proven against the same spawning parent."
  [parent role]
  (require-live! parent "Guidance spawning parent is not live")
  (let [retained
        (->> ((:children parent))
             (filter #(and (:started-at %)
                           (live? %)
                           (same-birth? parent ((:parent-birth %)))))
             (mapv #(assoc % :role role)))]
    (require-live! parent
                   "Guidance spawning parent changed during child discovery")
    retained))

(defn- unique-child! [parent pid role]
  (let [matches (filterv #(= pid (:pid %)) ((:children parent)))]
    (when-not (= 1 (count matches))
      (fail! "Guidance child process provenance is unavailable or ambiguous"
             {:role role :pid pid :matches (count matches)
              :parent-pid (:pid parent)}))
    (assoc (first matches) :role role)))

(defn retain-child
  "Retain `pid` only when it remains the original child of live `parent`."
  [parent pid role]
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
    candidate))

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
        (let [child (retain-child parent pid role)]
          (swap! ownership assoc key child)
          child)
        (catch Throwable error
          (try
            (when-let [children (seq (retain-children
                                      parent "proven-child-for-cleanup"))]
              (swap! ownership update :proven-children
                     (fnil into []) children))
            (catch Throwable cleanup-error
              (.addSuppressed error cleanup-error)))
          (throw error))))))

(defn live?
  "Return whether the retained birth-fenced identity is still live."
  [identity]
  (and identity
       ((:alive? identity))
       (= (:started-at identity) ((:current-start identity)))))

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

(defn retain-members
  "Retain each group discovery independently while the original anchor owns it.

  Returned identities are unconfirmed and have no signaling authority."
  [anchor rows pgid]
  (let [errors (atom [])
        retained (atom [])
        members (filterv #(= pgid (:pgid %)) rows)
        row-pids (set (map :pid members))]
    (when (record-error!
           errors
           #(do
              (require-live!
               anchor "Guidance ownership anchor disappeared during scan")
              (when-not (contains? row-pids (:pid anchor))
                (fail! "Guidance ownership scan omitted the original anchor"
                       {:anchor-pid (:pid anchor) :pgid pgid}))
              true))
      (doseq [row (remove #(= (:pid anchor) (:pid %)) members)]
        (record-error!
         errors
         #(swap! retained conj
                 (retain-pid (:pid row) "owned-group-member")))))
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
        rows-by-pid (group-by :pid members)]
    (record-error!
     errors
     #(do
        (require-live! anchor
                       "Guidance ownership anchor disappeared during scan")
        (when-not (= 1 (count (get rows-by-pid (:pid anchor))))
          (fail! "Guidance ownership scan omitted the original anchor"
                 {:anchor-pid (:pid anchor) :pgid pgid}))))
    (doseq [retained identities]
      (record-error!
       errors
       #(do
          (interleave! :before-member-correlation retained)
          (when-not (= 1 (count (get rows-by-pid (:pid retained))))
            (fail! "Guidance owned process identity disappeared"
                   {:pid (:pid retained) :pgid pgid}))
          (require-live! retained
                         "Guidance owned process identity disappeared")
          (confirmed! retained)
          (swap! confirmed conj retained))))
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

(defn signal!
  "Signal the same retained identity when its birth fence remains current."
  [identity]
  (interleave! :before-signal identity)
  (when (live? identity)
    (let [signalled? ((:destroy! identity))]
      (interleave! :after-signal identity)
      (when-not signalled?
        (fail! "Guidance retained process could not be signalled"
               {:role (:role identity) :pid (:pid identity)}))
      identity)))

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
