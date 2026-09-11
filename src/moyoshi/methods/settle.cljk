(ns moyoshi.methods.settle
  "moyoshi 催し — settlement decay-window job (ADR-2606272100 R2, G4). A gathering does
  NOT mint when it is hosted; it mints S epochs later, and ONLY from the ties that
  actually formed and SURVIVED the window (validated + anti-sybil). This namespace is
  the pending-settlement ledger + the decay-window scheduler around the pure
  `moyoshi.methods.moyoshi/settle` mint core.

  Epoch is caller-supplied (no wall clock, no randomness — resume-safe): `epoch =
  floor(unix_seconds / 86_400)`, the same 1-day clock as the social capital ledger
  (`convening_survival_epochs` = S). Pure + deterministic; portable .cljc (bb)."
  (:require [moyoshi.methods.moyoshi :as m]))

(defn pending-gathering
  "Record a proposed/hosted gathering awaiting its settlement window. The baseline is the
  kizuna reciprocal-tie graph AS OF the gathering (so settlement counts only NEW ties).
  `settle-at = epoch + S`. Pure."
  [{:keys [gathering-id convener baseline epoch survival-epochs]
    :or   {survival-epochs (:survival-epochs m/default-params)}}]
  {:gathering/id            gathering-id
   :gathering/convener      convener
   :gathering/baseline      (vec (map m/tie baseline))
   :gathering/epoch-proposed epoch
   :gathering/settle-at     (+ epoch survival-epochs)})

(defn due?
  "Has gathering g's settlement window elapsed by epoch t? (t ≥ settle-at)"
  [g t]
  (>= t (:gathering/settle-at g)))

(defn due-settlements
  "The subset of `pending` whose decay window has elapsed by epoch t (sorted by id)."
  [pending t]
  (->> pending (filter #(due? % t)) (sort-by :gathering/id) vec))

(defn settle-due
  "Settle every gathering whose window has elapsed by epoch t. `observe` is a fn
  gathering-id → {:surviving [ties] :distinct-dids #{..} :colluding [ties]} — the kizuna
  NOW-graph + anti-sybil membrane at settlement time (the G7 live leg supplies it). Each
  due gathering runs the pure mint core (`m/settle`) over (its stored baseline vs the
  observed surviving ties). Returns {:settled [mint-result…] :pending [remaining…]} —
  settled gatherings drop out of the pending ledger (idempotent: re-running at the same t
  re-settles only what is still pending). Deterministic (due sorted by id)."
  [pending t observe]
  (let [grp   (group-by #(boolean (due? % t)) pending)
        due   (sort-by :gathering/id (get grp true []))
        still (vec (get grp false []))
        settled (mapv (fn [g]
                        (let [o       (observe (:gathering/id g))
                              outcome (assoc o :baseline (:gathering/baseline g))]
                          (-> (m/settle (:gathering/convener g) outcome)
                              (assoc ":gathering/id"     (:gathering/id g)
                                     ":settled-at-epoch" t))))
                      due)]
    {:settled settled :pending still}))

(defn minted-total-smic
  "Sum of convening smic minted across a settlement batch (G4 — headcount never enters)."
  [settled]
  (reduce + 0 (map #(get % ":mint/smic" 0) settled)))
