(ns ct.spools.harnesses.guidance-fixture
  "Explicit disposable fixtures for historical native interactive rows.")

(def interactive-selection
  "Define test-only providers and selection for historical interactive rows."
  '(do
     (require '[ct.spools.harnesses.providers.pi :as pi])
     (harnesses/register-harness! rt :pi (pi/harness rt))
     (harnesses/register-alias!
      rt :native-pi
      {:doc "Disposable interactive-rejection profile."
       :parent :pi
       :env {"PATH" (.getCanonicalPath fixture-dir)}
       :attributes {}})
     (defn with-native-interactive-fixture [f]
       (let [select! guidance/select!]
         (with-redefs
          [guidance/select!
           (fn [runtime request]
             (select! runtime
                      (if (and (= "native-v1" (:requested request))
                               (= :interactive (:mode request)))
                        (assoc request :mode :headless)
                        request)))]
           (f))))
     (defn create-native-interactive-fixture! [runtime request]
       (with-native-interactive-fixture
         #(harnesses/create! runtime request)))
     (defn begin-native-interactive-fixture! [runtime id]
       (with-native-interactive-fixture
         #(harnesses/begin-attempt! runtime id)))))
