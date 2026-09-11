(ns moyoshi.cell
  "moyoshi 催し cell entry — kotodama-cell-runner contract (ADR-2605192415 §7.1).

  Registered in 50-infra/cluster/murakumo/cell-runner/cells.edn as MoyoshiHeartbeatCell
  (node reuben, cron 39 * * * *, healthz 13092). `fire` runs ONE deterministic convening
  heartbeat (ADR-2606272100 R3 / pattern 2606091000):

      ingest a committed kizuna 絆 readout → design a gathering → govern (G1..G6) →
      record a pending gathering → settle any whose decay window elapsed (against kizuna's
      now-graph) → ONE content-addressed tx appended to the actor-local kotoba commit-DAG →
      chain verified.

  NO external I/O in the cell — the LIVE-engine bridge (kotoba_bridge, MOYOSHI_KOTOBA_LIVE +
  operator DID) stays operator-gated and runs only from an explicit `--bridge` heartbeat.
  The returned summary is aggregate-only (G2): outcome + counts, never a turnout/per-person score."
  (:require [moyoshi.autorun :as autorun]
            [moyoshi.methods.ingest :as ingest]
            [moyoshi.methods.kotoba :as kot]
            [kotoba.datom :as kd]
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defn- actor-dir
     "Standalone repository root, resolved from this namespace's classpath location."
     []
     (-> (io/resource "moyoshi/cell.cljc") io/file .getParentFile .getParentFile .getParentFile)))

#?(:clj
   (defn fire
     "One convening heartbeat. Idempotent per log state (cycle derives from log length).
     Epoch derives from the log length too (deterministic in-cell — the wall-clock epoch is
     the explicit `--bridge` heartbeat concern, not the cell's)."
     ([] (fire nil))
     ([log-path]
      (let [dir    (actor-dir)
            target (str (or log-path (io/file dir "data" "persisted" "moyoshi.convening.kotoba.edn")))
            kpath  (str (io/file dir "data" "seed-kizuna.kotoba.edn"))
            n      (count (kd/read-log target))
            kout   (ingest/load-kizuna kpath)
            r (autorun/beat {:kizuna-out kout :epoch n
                             :observe (ingest/observe-from-kizuna kout)
                             :log-path target
                             :tx-id (str "moyoshi-" n) :as-of (str "as-of:" n)})
            p (:persist r)]
        (println (str "MoyoshiHeartbeatCell cycle " n ": " (name (get-in r [:beat :outcome]))
                      " host=" (get-in r [:beat :proposal ":event/host"])
                      " settled=" (count (:settled r)) " pending=" (count (:pending r))
                      " appended=" (:appended p) (when (:reason p) (str " (" (name (:reason p)) ")"))
                      " head=" (some-> (:head p) (subs 0 (min 16 (count (:head p)))))))
        r))))
