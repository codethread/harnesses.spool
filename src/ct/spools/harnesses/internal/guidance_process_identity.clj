(ns ct.spools.harnesses.internal.guidance-process-identity
  "Retained birth-fenced identities for native preflight processes."
  (:require [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.lang ProcessHandle]))

(defn ^:dynamic ^:private interleave!
  "Test seam invoked at deterministic process-identity boundaries."
  [_phase _identity]
  nil)

(defn- start-instant [^ProcessHandle handle]
  (.orElse (.startInstant (.info handle)) nil))

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
     :destroy! #(.destroyForcibly handle)}))

(defn retain-pid
  "Resolve and retain `pid` once; never reacquire it for later authority."
  [pid role]
  (when-not (and (integer? pid) (pos? pid))
    (fail! "Guidance process identity has an invalid PID"
           {:role role :pid pid}))
  (retain (.orElse (ProcessHandle/of (long pid)) nil) role))

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

(defn retain-members!
  "Retain identities from one scan while the original anchor owns its PGID."
  [anchor rows pgid]
  (require-live! anchor "Guidance ownership anchor disappeared during scan")
  (let [members (filterv #(= pgid (:pgid %)) rows)
        row-pids (set (map :pid members))]
    (when-not (contains? row-pids (:pid anchor))
      (fail! "Guidance ownership scan omitted the original anchor"
             {:anchor-pid (:pid anchor) :pgid pgid}))
    (let [identities (mapv #(retain-pid (:pid %) "owned-group-member")
                           (remove #(= (:pid anchor) (:pid %)) members))]
      (require-live! anchor "Guidance ownership anchor disappeared during scan")
      identities)))

(defn correlate!
  "Correlate a confirming scan with retained identities and the anchor."
  [anchor identities rows pgid]
  (require-live! anchor "Guidance ownership anchor disappeared during scan")
  (let [by-pid (into {(:pid anchor) anchor}
                     (map (fn [retained]
                            [(:pid retained) retained]))
                     identities)
        members (filterv #(= pgid (:pgid %)) rows)
        row-pids (set (map :pid members))]
    (when-not (contains? row-pids (:pid anchor))
      (fail! "Guidance ownership scan omitted the original anchor"
             {:anchor-pid (:pid anchor) :pgid pgid}))
    (doseq [pid row-pids]
      (when-not (contains? by-pid pid)
        (fail! "Guidance ownership scan has ambiguous process identity"
               {:pid pid :pgid pgid})))
    (->> members
         (remove #(= (:pid anchor) (:pid %)))
         (mapv #(require-live!
                 (get by-pid (:pid %))
                 "Guidance owned process identity disappeared")))))

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
