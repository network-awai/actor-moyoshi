(ns moyoshi.methods.kotoba
  "moyoshi 催し — kotoba Datom-log persistence (ADR-2606272100 R2; the kaname/busshi/ugachi
  pattern via the standalone kotoba.datom dependency). Each convening
  beat (the dry-run proposal + the epoch's settlement batch) is ONE tx of EAVT
  `[:db/add e a v]` datoms chained by CID; a later edit breaks every downstream CID
  (tamper-evident, 永久記憶). Idempotent-by-content: if the readout is unchanged from the
  last beat, NO new tx is appended. Values are strings / longs (no floats) so the CID is
  deterministic; no wall clock, no randomness (caller supplies tx-id/as-of). no-server-key
  (local file append only). Portable .cljc (bb)."
  (:require [kotoba.lang.text :as str]
            [moyoshi.methods.settle :as settle]
            [kotoba.datom :as kd]
            #?(:clj [clojure.java.io :as io])))

(def default-log "data/persisted/moyoshi.convening.kotoba.edn")

(defn- nm
  "Strip a leading ':' so a datom VALUE never starts with a colon (kotoba.datom would emit
  it as a bare keyword token and split on commas)."
  [x]
  (let [s (if (keyword? x) (name x) (str x))]
    (if (str/starts-with? s ":") (subs s 1) s)))

(defn readout->datoms
  "Project one convening beat into deterministic EAVT datoms: a summary entity
  `moyoshi:convening` (the proposal shape + this epoch's mint total + pending count), plus
  one record per settled gathering (convener / validated-tie count / minted smic). Sorted
  → stable CID. A refusal beat records the refusing gate instead of a proposal."
  [{:keys [outcome proposal refusal settled pending-count epoch]}]
  (let [settled  (vec (sort-by #(str (get % ":gathering/id")) (or settled [])))
        minted   (settle/minted-total-smic settled)
        summary  [(kd/add "moyoshi:convening" ":moyoshi/epoch" (long (or epoch 0)))
                  (kd/add "moyoshi:convening" ":moyoshi/outcome" (nm (or outcome :none)))
                  (kd/add "moyoshi:convening" ":moyoshi/proposed-host"
                          (nm (get proposal ":event/host" "")))
                  (kd/add "moyoshi:convening" ":moyoshi/audience-count"
                          (long (count (get proposal ":event/audience" []))))
                  (kd/add "moyoshi:convening" ":moyoshi/target-tie-count"
                          (long (count (get proposal ":event/target-ties" []))))
                  (kd/add "moyoshi:convening" ":moyoshi/refused-gate"
                          (nm (get refusal :gate "")))
                  (kd/add "moyoshi:convening" ":moyoshi/settled-count" (long (count settled)))
                  (kd/add "moyoshi:convening" ":moyoshi/minted-total-smic" (long minted))
                  (kd/add "moyoshi:convening" ":moyoshi/pending-count" (long (or pending-count 0)))]
        rows (mapcat (fn [s]
                       (let [gid (nm (get s ":gathering/id"))]
                         [(kd/add gid ":moyoshi.settle/convener" (nm (get s ":mint/convener" "")))
                          (kd/add gid ":moyoshi.settle/validated-ties"
                                  (long (get s ":mint/n-validated-ties" 0)))
                          (kd/add gid ":moyoshi.settle/smic" (long (get s ":mint/smic" 0)))]))
                     settled)]
    (vec (concat summary rows))))

(defn persist!
  "Append a convening-beat tx to the commit-DAG, idempotent-by-content. opts: {:tx-id
  :as-of :log-path}. Returns {:head cid :appended bool :reason (:no-change|nil) :count n}."
  [datoms {:keys [tx-id as-of log-path]}]
  (let [log-path (or log-path default-log)
        prev     (kd/head-cid log-path)
        last-ds  (some-> (kd/read-log log-path) peek :tx/datoms)
        base     {:count (count datoms) :head prev}]
    (if (= datoms last-ds)
      (assoc base :appended false :reason :no-change)
      (let [tx (kd/make-tx datoms {:tx-id tx-id :as-of as-of :prev-cid prev})]
        #?(:clj (io/make-parents log-path))
        (assoc base :appended true :reason nil :head (kd/append-tx! tx log-path))))))

(defn head   [log-path] (kd/head-cid    (or log-path default-log)))
(defn verify [log-path] (kd/verify-chain (or log-path default-log)))
