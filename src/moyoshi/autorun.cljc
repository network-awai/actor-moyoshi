(ns moyoshi.autorun
  "moyoshi 催し — autonomous convening heartbeat (ADR-2606272100 R2; kaname/ibuki pattern).

  One beat = ingest a COMMITTED kizuna 絆 readout (live ingest, G7-join) → fragility →
  design + govern (ConveningGovernor) → (if proposed) record a pending gathering with its
  settlement window → settle any gathering whose decay window has elapsed (the settlement
  job, G4) → persist the readout to the kotoba Datom-log commit-DAG. Idempotent-by-content
  (no append when unchanged), resume-safe (cycle = log length), no wall clock / randomness
  (epoch supplied), no-server-key. The pure core (`beat`) runs over supplied inputs; the
  clj `-main` wires the committed kizuna log + the epoch + the settlement now-graph
  (`observe`), and the kotoba live-engine bridge is the further G7 leg. Portable .cljc (bb)."
  (:require [moyoshi.methods.moyoshi :as m]
            [moyoshi.methods.ingest  :as ingest]
            [moyoshi.methods.settle  :as settle]
            [moyoshi.methods.kotoba  :as kot]
            #?(:clj [moyoshi.methods.kotoba-bridge :as bridge])
            #?(:clj [clojure.java.io :as io])
            #?(:clj [kotoba.datom :as kd])))

(defn beat
  "Run one ingest→design→govern→record→settle→persist cycle. input keys:
  {:kizuna-out :epoch :pending :observe :gathering-id :baseline :log-path :tx-id :as-of}.
  `observe` is gathering-id → {:surviving :distinct-dids :colluding} (the kizuna now-graph
  at settlement; defaults to an empty observation = nothing survived = nothing mints).
  Returns {:beat … :settled … :pending … :persist …}. Pure (given inputs)."
  [{:keys [kizuna-out epoch pending observe gathering-id baseline log-path tx-id as-of bridge?]
    :or   {pending [] epoch 0
           observe (constantly {:surviving [] :distinct-dids #{} :colluding []})}}]
  (let [fragility   (ingest/kizuna->fragility kizuna-out)
        b           (m/beat fragility)
        proposed?   (= :proposed (:outcome b))
        ;; baseline = kizuna reciprocal ties AS OF this beat (settlement counts only NEW ties)
        base        (or baseline (ingest/reciprocal-ties kizuna-out))
        new-pending (if proposed?
                      [(settle/pending-gathering
                         {:gathering-id (or gathering-id (str "g-" epoch))
                          :convener     (get-in b [:proposal ":event/host"])
                          :baseline     base
                          :epoch        epoch})]
                      [])
        ledger      (into (vec pending) new-pending)
        {:keys [settled] still :pending} (settle/settle-due ledger epoch observe)
        readout     {:outcome (:outcome b) :proposal (:proposal b) :refusal (:refusal b)
                     :settled settled :pending-count (count still) :epoch epoch}
        persist     (kot/persist! (kot/readout->datoms readout)
                                  {:tx-id tx-id :as-of as-of :log-path log-path})
        ;; R3 bridge: push the local commit-DAG to the LIVE kotoba engine. FAIL-OPEN —
        ;; engine down / operator DID absent → the beat still completes locally.
        br          #?(:clj (when bridge?
                              (try (select-keys (bridge/push log-path {:live true})
                                                [:mode :pushed :remote-tx-cids :parent-commit :datoms-confirmed])
                                   (catch Exception e {:error (let [m (.getMessage e)]
                                                               (if (and m (seq m)) m (str (class e))))})))
                       :default nil)]
    (cond-> {:beat b :settled settled :pending still :persist persist}
      bridge? (assoc :bridge br))))

#?(:clj
   (defn- epoch-now
     "R3 epoch-from-clock: floor(unix_seconds / 86_400) — the social capital ledger's 1-day
     clock (the ONLY wall-clock read, isolated in the clj -main so `beat` stays deterministic)."
     []
     (quot (quot (System/currentTimeMillis) 1000) 86400)))

#?(:clj
   (defn -main
     "Resume-safe heartbeat. Args: [base-dir] [epoch] [--bridge] [--kizuna <path>].
     Loads a COMMITTED kizuna readout (default data/seed-kizuna.kotoba.edn; R3 live leg =
     --kizuna pointing at kizuna's OWN committed log), runs one beat at the given epoch
     (default = today's epoch-from-clock), settles due gatherings against the kizuna NOW-graph
     (`ingest/observe-from-kizuna`), persists, optionally pushes to the LIVE engine (--bridge,
     fail-open), verifies the chain."
     [& argv]
     (let [pos     (vec (remove #(clojure.string/starts-with? (str %) "--") argv))
           argset  (set argv)
           bridge? (contains? argset "--bridge")
           base    (or (first pos) ".")
           kflag   (->> argv (drop-while #(not= % "--kizuna")) second)
           kpath   (or kflag (str (io/file base "data" "seed-kizuna.kotoba.edn")))
           log     (str (io/file base kot/default-log))
           n       (count (kd/read-log log))
           epoch   (if (second pos) (Long/parseLong (second pos)) (epoch-now))
           kout    (ingest/load-kizuna kpath)
           r       (beat {:kizuna-out kout :epoch epoch
                          :observe (ingest/observe-from-kizuna kout)
                          :log-path log :tx-id (str "moyoshi-" n) :as-of (str "as-of:" n)
                          :bridge? bridge?})
           p       (:persist r)]
       (println (str "moyoshi 催し beat #" n " (epoch " epoch "): "
                     (name (get-in r [:beat :outcome]))
                     " host=" (get-in r [:beat :proposal ":event/host"])
                     " settled=" (count (:settled r))
                     " pending=" (count (:pending r))
                     " appended=" (:appended p) (when (:reason p) (str " (" (name (:reason p)) ")"))
                     " head=" (:head p)))
       (when bridge?
         (let [bb (:bridge r)]
           (println (str "  bridge: " (if (:error bb) (str "fail-open (" (:error bb) ")")
                                          (str (:mode bb) " pushed=" (:pushed bb)
                                               " datoms=" (:datoms-confirmed bb)))))))
       (println "  verify-chain:" (kot/verify log)))))

#?(:clj
   (when (= *file* (System/getProperty "babashka.file"))
     (apply -main *command-line-args*)))
