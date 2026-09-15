(ns ct.spools.harnesses.internal.guidance-process-gate
  "Revocable publication for native guidance process gates."
  (:require [ct.spools.harnesses.internal.guidance-authority :as authority]
            [ct.spools.harnesses.internal.guidance-deadline :as deadline])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.util UUID]))

(defn ^:dynamic *prepare!*
  "Write one unpublished temporary gate file."
  [^Path temporary value]
  (Files/write temporary (.getBytes ^String value StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0)))

(defn publish!
  "Prepare a gate privately, then publish it under live operation authority."
  [budget ^Path path value phase]
  (deadline/owned!
   budget phase
   (fn [operation-authority]
     (let [temporary (.resolveSibling
                      path (str (.getFileName path) ".tmp-" (UUID/randomUUID)))]
       (try
         (*prepare!* temporary value)
         (authority/run!
          operation-authority phase
          #(Files/move temporary path
                       (into-array
                        java.nio.file.CopyOption
                        [StandardCopyOption/ATOMIC_MOVE
                         StandardCopyOption/REPLACE_EXISTING])))
         path
         (finally
           (Files/deleteIfExists temporary)))))))
