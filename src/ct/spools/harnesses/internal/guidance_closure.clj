(ns ct.spools.harnesses.internal.guidance-closure
  "Local reviewed executable-closure evidence for native preflight."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [ct.spools.harnesses.internal.strict-json :as strict-json]
            [millstrand.api.spool.alpha :refer [fail!]])
  (:import [java.security MessageDigest]))

(def ^:private process-ownership-contract
  "Reviewed helpers whose children remain in the private POSIX session."
  "private-posix-session/inherited-process-group-v1")

(def ^:private executable-closure-schema
  "Harnesses-local finite executable-closure manifest version."
  "millstrand.local-guidance-executable-closure/v1")

(def ^:private resolver-policy-schema
  "millstrand.local-guidance-resolver-policy/v1")

(def ^:private sha-pattern #"[0-9a-f]{64}")
(def ^:private maximum-artifacts 64)
(def ^:private maximum-closure-bytes (* 256 1024 1024))
(def ^:private profile-keys
  #{:harness :preflight :capability :executable-closure :process-ownership})
(def ^:private preflight-keys #{:path :sha256})
(def ^:private ownership-keys
  #{:contract :reviewed-closure-sha256 :child-process-behavior})
(def ^:private closure-keys
  #{:schema :reviewed-complete :artifacts :resolver-policy
    :resolution-inputs})
(def ^:private artifact-keys #{:role :path :sha256 :size})
(def ^:private resolution-keys #{:cwd :environment})
(def ^:private resolver-policy-keys #{:schema :platform :environment})
(def ^:private supported-roles
  #{"entrypoint" "import" "helper" "interpreter" "selector"
    "subprocess" "ownership-scanner"})
(def ^:private executable-roles
  #{"helper" "interpreter" "subprocess" "ownership-scanner"})
(def ^:private resolver-environment-policy
  {"PATH" "bound"
   "NODE_OPTIONS" "absent"
   "NODE_PATH" "absent"
   "OPENSSL_CONF" "absent"
   "DYLD_INSERT_LIBRARIES" "absent"
   "DYLD_LIBRARY_PATH" "absent"
   "DYLD_FRAMEWORK_PATH" "absent"
   "DYLD_FALLBACK_LIBRARY_PATH" "absent"
   "DYLD_FALLBACK_FRAMEWORK_PATH" "absent"
   "LD_PRELOAD" "absent"
   "LD_LIBRARY_PATH" "absent"})

(def ^:private unsafe-environment-pattern
  #"^(?:DYLD_.+|LD_PRELOAD|LD_LIBRARY_PATH|OPENSSL_CONF)$")

(defn- closed-keys! [value required label]
  (when-not (and (map? value) (= required (set (keys value))))
    (fail! (str label " has invalid keys")
           {:required (sort required)
            :actual (when (map? value) (sort (keys value)))})))

(defn- sha! [value label]
  (when-not (and (string? value) (re-matches sha-pattern value))
    (fail! (str label " must be a lowercase SHA-256 digest")
           {:value value})))

(defn- check-budget! [budget!]
  (when budget! (budget!)))

(defn file-sha256
  "Return the SHA-256 digest of one regular file within an optional budget."
  ([path]
   (file-sha256 path nil))
  ([path budget!]
   (let [file (io/file path)]
     (when-not (.isFile file)
       (fail! "Guidance executable closure artifact is missing"
              {:path (str path)}))
     (let [digest (MessageDigest/getInstance "SHA-256")]
       (with-open [input (io/input-stream file)]
         (let [buffer (byte-array 8192)]
           (loop []
             (check-budget! budget!)
             (let [count (.read input buffer)]
               (when (pos? count)
                 (.update digest buffer 0 count)
                 (recur))))))
       (str/join (map #(format "%02x" (bit-and 0xff %))
                      (.digest digest)))))))

(defn- validate-artifact! [artifact]
  (closed-keys! artifact artifact-keys "Guidance closure artifact")
  (let [{:keys [role path sha256 size]} artifact]
    (when-not (contains? supported-roles role)
      (fail! "Guidance executable closure has an unsupported artifact role"
             {:role role}))
    (when-not (and (string? path) (not (str/blank? path)))
      (fail! "Guidance executable closure artifact path must be canonical"
             {:path path}))
    (let [file (io/file path)]
      (when-not (= path (.getCanonicalPath file))
        (fail! "Guidance executable closure artifact path must be canonical"
               {:path path}))
      (when-not (.isFile file)
        (fail! "Guidance executable closure artifact is missing"
               {:path path}))
      (sha! sha256 "Guidance executable closure artifact hash")
      (when-not (and (int? size) (<= 0 size maximum-closure-bytes))
        (fail! "Guidance executable closure artifact size is invalid"
               {:path path :size size}))
      (when (and (contains? executable-roles role)
                 (not (.canExecute file)))
        (fail! "Guidance executable closure artifact is not executable"
               {:path path :role role})))
    artifact))

(defn resolver-policy
  "Return the mandatory reviewed resolver policy for native preflight."
  []
  {:schema resolver-policy-schema
   :platform "darwin"
   :environment resolver-environment-policy})

(defn resolution-environment
  "Select every mandatory resolver input, retaining absent values as nil."
  [environment]
  (into {}
        (map (fn [key] [key (get environment key)]))
        (keys resolver-environment-policy)))

(defn- validate-resolver-policy! [policy]
  (closed-keys! policy resolver-policy-keys
                "Guidance closure resolver policy")
  (when-not (= (resolver-policy) policy)
    (fail! "Guidance closure resolver policy is unsupported"
           {:policy policy}))
  (when-not (= "Mac OS X" (System/getProperty "os.name"))
    (fail! "Native guidance resolver platform is unsupported"
           {:os-name (System/getProperty "os.name")}))
  policy)

(defn- selected-value-matches? [process-environment key expected mode]
  (case mode
    "absent" (and (nil? expected)
                  (not (contains? process-environment key)))
    "bound" (if (nil? expected)
              (not (contains? process-environment key))
              (and (contains? process-environment key)
                   (= expected (get process-environment key))))
    false))

(defn- validate-resolution! [profile resolution policy process-environment]
  (closed-keys! resolution resolution-keys
                "Guidance closure resolution inputs")
  (let [{:keys [cwd environment]} resolution
        expected-root (some-> profile :preflight :path io/file
                              .getParentFile .getParentFile .getCanonicalPath)]
    (when-not (and (string? cwd)
                   (= cwd (.getCanonicalPath (io/file cwd)))
                   (= cwd expected-root))
      (fail! "Guidance closure working directory does not match its entrypoint"
             {:cwd cwd :expected expected-root}))
    (when-not (and (map? environment)
                   (= (set (keys resolver-environment-policy))
                      (set (keys environment)))
                   (every? #(or (nil? %) (string? %)) (vals environment)))
      (fail! "Guidance closure resolution environment is incomplete" {}))
    (when-let [key (some (fn [[key mode]]
                           (when (and (= "absent" mode)
                                      (some? (get environment key)))
                             key))
                         (:environment policy))]
      (fail! "Guidance closure has unsupported dynamic resolution inputs"
             {:key key}))
    (when-not (and (map? process-environment)
                   (every? (fn [[key value]]
                             (and (string? key) (string? value)))
                           process-environment))
      (fail! "Guidance closure process environment is malformed" {}))
    (when-let [key (some #(when (re-matches unsafe-environment-pattern %) %)
                         (keys process-environment))]
      (fail! "Guidance closure has unsupported dynamic resolution inputs"
             {:key key}))
    (when-not (every? (fn [[key mode]]
                        (selected-value-matches?
                         process-environment key (get environment key) mode))
                      (:environment policy))
      (fail! "Guidance closure resolution inputs changed"
             {:keys (sort (keys environment))}))
    resolution))

(defn- validate-manifest! [profile process-environment]
  (let [manifest (:executable-closure profile)]
    (closed-keys! manifest closure-keys "Guidance executable closure")
    (when-not (= executable-closure-schema (:schema manifest))
      (fail! "Guidance executable closure schema is unsupported" {}))
    (when-not (true? (:reviewed-complete manifest))
      (fail! "Guidance executable closure is not reviewed as complete" {}))
    (let [policy (validate-resolver-policy! (:resolver-policy manifest))
          artifacts (:artifacts manifest)]
      (when-not (and (vector? artifacts)
                     (<= 3 (count artifacts) maximum-artifacts))
        (fail! "Guidance executable closure artifact set is incomplete"
               {:count (when (vector? artifacts) (count artifacts))}))
      (doseq [artifact artifacts] (validate-artifact! artifact))
      (when-not (= (count artifacts)
                   (count (distinct (map :path artifacts))))
        (fail! "Guidance executable closure repeats an artifact path" {}))
      (doseq [role ["entrypoint" "interpreter" "ownership-scanner"]]
        (when-not (= 1 (count (filter #(= role (:role %)) artifacts)))
          (fail! "Guidance executable closure is missing a required artifact"
                 {:role role})))
      (let [entrypoint (first (filter #(= "entrypoint" (:role %)) artifacts))]
        (when-not (= (select-keys entrypoint [:path :sha256])
                     (:preflight profile))
          (fail! "Guidance closure entrypoint does not match preflight evidence"
                 {})))
      (when (> (reduce + (map :size artifacts)) maximum-closure-bytes)
        (fail! "Guidance executable closure exceeds its byte limit"
               {:max-bytes maximum-closure-bytes}))
      (validate-resolution! profile (:resolution-inputs manifest) policy
                            process-environment))
    manifest))

(defn reviewed-sha256
  "Hash the exact local manifest, ownership policy, and wire capability."
  [profile]
  (strict-json/canonical-sha256
   {:harness (:harness profile)
    :capability (:capability profile)
    :executable-closure (:executable-closure profile)
    :process-ownership
    (dissoc (:process-ownership profile) :reviewed-closure-sha256)}))

(defn- validate-ownership! [profile]
  (let [ownership (:process-ownership profile)]
    (closed-keys! ownership ownership-keys "Guidance process ownership evidence")
    (when-not (= process-ownership-contract (:contract ownership))
      (fail! "Guidance capability process ownership is unsupported"
             {:contract (:contract ownership)}))
    (when-not (= "inherited-process-group-only"
                 (:child-process-behavior ownership))
      (fail! "Guidance capability permits children to escape private ownership"
             {:child-process-behavior (:child-process-behavior ownership)}))
    (sha! (:reviewed-closure-sha256 ownership)
          "Guidance process ownership closure hash")
    (when-not (= (reviewed-sha256 profile)
                 (:reviewed-closure-sha256 ownership))
      (fail! "Guidance process ownership does not match its reviewed closure"
             {}))
    ownership))

(defn verify!
  "Verify one complete local profile and every referenced byte.

  `process-environment` is the exact environment used for the helper. Optional
  `budget!` fails when the caller's monotonic execution budget is exhausted.
  Return the reviewed closure digest after all checks pass."
  ([profile process-environment]
   (verify! profile process-environment nil))
  ([profile process-environment budget!]
   (closed-keys! profile profile-keys "Guidance local capability profile")
   (closed-keys! (:preflight profile) preflight-keys
                 "Guidance preflight entrypoint")
   (validate-ownership! profile)
   (validate-manifest! profile process-environment)
   (doseq [{:keys [path sha256 size]} (get-in profile
                                              [:executable-closure :artifacts])]
     (check-budget! budget!)
     (let [file (io/file path)]
       (when-not (= size (.length file))
         (fail! "Guidance executable closure artifact size changed"
                {:path path :expected size :actual (.length file)}))
       (when-not (= sha256 (file-sha256 path budget!))
         (fail! "Guidance executable closure artifact bytes changed"
                {:path path}))))
   (reviewed-sha256 profile)))

(defn artifact
  "Return exact local manifest evidence for `file` and reviewed `role`."
  [role file]
  (let [file (io/file file)
        path (.getCanonicalPath file)]
    {:role role
     :path path
     :sha256 (file-sha256 path)
     :size (.length file)}))

(defn artifact-path
  "Return the unique verified artifact path for `role`."
  [profile role]
  (let [matches (filterv #(= role (:role %))
                         (get-in profile [:executable-closure :artifacts]))]
    (when-not (= 1 (count matches))
      (fail! "Guidance executable closure artifact role is not unique"
             {:role role :count (count matches)}))
    (:path (first matches))))
