(ns ct.spools.harnesses.internal.guidance-authority
  "Revocable operation-local authority for admission side effects."
  (:refer-clojure :exclude [run!])
  (:require [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.util.concurrent.locks ReentrantLock]))

(defn create
  "Create authority that expires at `deadline` on the monotonic clock."
  [deadline]
  {:deadline deadline
   :revoked? (atom false)
   :lock (ReentrantLock.)})

(defn run!
  "Run `operation` only while its authority remains live.

  Revocation uses the same lock, so a caller cannot return from cancellation
  while an already-authorized side effect is still running."
  [{:keys [deadline revoked? ^ReentrantLock lock]} phase operation]
  (.lock lock)
  (try
    (when (or @revoked? (not (pos? (- deadline (System/nanoTime)))))
      (fail! "Guidance operation authority is no longer live" {:phase phase}))
    (operation)
    (finally
      (.unlock lock))))

(defn revoke!
  "Revoke authority and wait for any authorized operation to leave its gate."
  [{:keys [revoked? ^ReentrantLock lock]}]
  (.lock lock)
  (try
    (reset! revoked? true)
    (finally
      (.unlock lock))))
