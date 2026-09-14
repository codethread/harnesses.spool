(ns ct.spools.harnesses.internal.guidance-process-scan
  "Bounded concurrent process-group scanner for native preflight cleanup."
  (:require [clojure.string :as str]
            [ct.spools.harnesses.internal.guidance-closure :as closure]
            [ct.spools.harnesses.internal.guidance-process-identity :as identity]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset CharacterCodingException CodingErrorAction
            StandardCharsets]
           [java.util.concurrent Callable ExecutionException Executors Future
            ThreadFactory TimeUnit TimeoutException]))

(def ^:private scanner-capture-limit (* 256 1024))
(def ^:private scanner-millis 175)
(def ^:private scanner-join-millis 75)

(defn- timed-out! [phase]
  (fail! "Guidance preflight process ownership scan timed out"
         {:phase phase}))

(defn- await-future! [^Future future deadline remaining-nanos phase]
  (let [remaining (remaining-nanos deadline)]
    (when-not (pos? remaining)
      (timed-out! phase))
    (try
      (.get future remaining TimeUnit/NANOSECONDS)
      (catch TimeoutException _
        (timed-out! phase))
      (catch ExecutionException error
        (throw (.getCause error)))
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        (fail! "Guidance process ownership scan was interrupted"
               {:phase phase})))))

(defn- capture! [input stream-name]
  (let [output (ByteArrayOutputStream.)
        buffer (byte-array 4096)]
    (loop [total 0]
      (let [count (.read input buffer)]
        (if (neg? count)
          (.toByteArray output)
          (let [next-total (+ total count)]
            (when (> next-total scanner-capture-limit)
              (fail! "Guidance process ownership scan output exceeded its byte limit"
                     {:stream stream-name :max-bytes scanner-capture-limit}))
            (.write output buffer 0 count)
            (recur next-total)))))))

(defn- decode-utf8 [^bytes bytes stream-name]
  (try
    (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (java.nio.ByteBuffer/wrap bytes)))
    (catch CharacterCodingException _
      (fail! "Guidance process ownership scan output is not valid UTF-8"
             {:stream stream-name}))))

(defn- thread-factory []
  (reify ThreadFactory
    (newThread [_ runnable]
      (doto (Thread. runnable "guidance-preflight-scan-io")
        (.setDaemon true)))))

(defn- inspect-drains! [drains deadline remaining-nanos]
  (doseq [[stream-name ^Future future] drains
          :when (.isDone future)]
    (await-future! future deadline remaining-nanos stream-name)))

(defn- await-process! [process drains deadline remaining-nanos]
  (loop []
    (inspect-drains! drains deadline remaining-nanos)
    (let [remaining (remaining-nanos deadline)]
      (when-not (pos? remaining)
        (timed-out! "process"))
      (if (.waitFor process (min remaining (.toNanos TimeUnit/MILLISECONDS 5))
                    TimeUnit/NANOSECONDS)
        :exited
        (recur)))))

(defn- parse-row! [line]
  (let [tokens (str/split (str/trim line) #"\s+")]
    (when-not (= 2 (count tokens))
      (fail! "Guidance process ownership scan output is malformed"
             {:line line}))
    (let [[pid pgid] (mapv parse-long tokens)]
      (when-not (and (pos-int? pid) (pos-int? pgid))
        (fail! "Guidance process ownership scan output is malformed"
               {:line line}))
      {:pid pid :pgid pgid})))

(defn- parse-output! [stdout]
  (let [lines (remove str/blank? (str/split-lines stdout))]
    (when-not (seq lines)
      (fail! "Guidance process ownership scan output is empty" {}))
    (let [rows (mapv parse-row! lines)
          pids (mapv :pid rows)]
      (when-not (= (count pids) (count (distinct pids)))
        (fail! "Guidance process ownership scan contains duplicate PIDs" {}))
      rows)))

(defn- attempt-cleanup! [errors operation]
  (try
    (operation)
    (catch Throwable error
      (swap! errors conj error))))

(defn- cleanup-scanner!
  [process scanner-identity executor streams deadline remaining-nanos]
  (let [errors (atom [])]
    (attempt-cleanup!
     errors
     #(if-let [retained @scanner-identity]
        (when (identity/live? retained)
          (identity/signal! retained))
        (when (.isAlive process)
          (.destroyForcibly process))))
    (attempt-cleanup!
     errors
     #(let [remaining (remaining-nanos deadline)]
        (when-not (and (pos? remaining)
                       (.waitFor process remaining TimeUnit/NANOSECONDS))
          (fail! "Guidance process ownership scanner did not terminate"
                 {:pid (.pid process)}))))
    (doseq [stream streams]
      (try (.close stream) (catch Exception _ nil)))
    (.shutdownNow executor)
    (attempt-cleanup!
     errors
     #(let [remaining (remaining-nanos deadline)]
        (when-not (and (pos? remaining)
                       (.awaitTermination executor remaining
                                          TimeUnit/NANOSECONDS))
          (fail! "Guidance process ownership scanner workers did not terminate"
                 {}))))
    (when-let [error (first @errors)]
      (doseq [suppressed (rest @errors)]
        (.addSuppressed ^Throwable error ^Throwable suppressed))
      (throw error))))

(defn- scanner-deadline [deadline]
  (let [now (System/nanoTime)
        latest (- deadline (.toNanos TimeUnit/MILLISECONDS
                                     scanner-join-millis))
        bounded (+ now (.toNanos TimeUnit/MILLISECONDS scanner-millis))]
    (when-not (< now latest)
      (timed-out! "budget"))
    (min latest bounded)))

(defn scan!
  "Run one reviewed cleanup scanner with independent bounded drains."
  [profile process-environment root scanner deadline remaining-nanos]
  (let [scan-deadline (scanner-deadline deadline)
        budget! #(when-not (pos? (remaining-nanos scan-deadline))
                   (timed-out! "closure-verification"))]
    (closure/verify! profile process-environment budget!)
    (let [builder (doto (ProcessBuilder. ^java.util.List
                         [scanner "-axo" "pid=,pgid="])
                    (.directory root))
          _ (doto (.environment builder)
              (.clear)
              (.putAll process-environment))
          process (.start builder)
          scanner-identity (atom nil)
          executor (Executors/newFixedThreadPool 2 (thread-factory))
          stdout (.getInputStream process)
          stderr (.getErrorStream process)
          drains (atom [])]
      (try
        (reset! scanner-identity
                (identity/retain (.toHandle process) "ownership-scanner"))
        (reset! drains
                [["stdout"
                  (.submit executor ^Callable #(capture! stdout "stdout"))]
                 ["stderr"
                  (.submit executor ^Callable #(capture! stderr "stderr"))]])
        (await-process! process @drains scan-deadline remaining-nanos)
        (let [stdout-bytes
              (await-future! (second (first @drains)) scan-deadline
                             remaining-nanos "stdout")
              stderr-bytes
              (await-future! (second (second @drains)) scan-deadline
                             remaining-nanos "stderr")
              stderr-text (decode-utf8 stderr-bytes "stderr")]
          (when-not (zero? (.exitValue process))
            (fail! "Guidance preflight process ownership scan failed"
                   {:exit-code (.exitValue process)
                    :diagnostic stderr-text}))
          (parse-output! (decode-utf8 stdout-bytes "stdout")))
        (finally
          (cleanup-scanner! process scanner-identity executor [stdout stderr]
                            deadline remaining-nanos))))))
