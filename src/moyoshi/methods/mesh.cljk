;; mesh.clj — moyoshi 催し KOTOBA Mesh entry component (Clojure / kotoba-clj, ADR-2606272100 R2).
;;
;; The mesh-hosting face of actor:moyoshi (convening for validated social capital).
;; Observatory on-kse pattern (ADR-2606230001 §4): observes the OPENING edges a proposed
;; gathering would create (host ⇄ each fragile actor it opens access to) as Datom
;; assertions, and derives the access-opening map via Datalog. The full design / govern /
;; settle / mint logic stays in methods (moyoshi/ingest/settle/kotoba); this is the thin
;; on-kse trigger the kotoba.app.edn component points at.
;;
;; Posture (G2/G3): an ACCESS-OPENING map across actors — who a gathering would connect to
;; whom — NEVER a turnout/engagement ranking and never a target-list. The bearer is the
;; opening 縁; minting happens only at settlement, never here.
;; Host hooks resolve to kotoba:kais/kqe (needs cap/kqe).
(ns moyoshi.methods.mesh)

(def ^:dynamic *kqe-assert!*
  (fn [& _]
    (throw (ex-info "moyoshi mesh requires :kqe-assert! host capability" {}))))

(def ^:dynamic *kqe-query*
  (fn [& _]
    (throw (ex-info "moyoshi mesh requires :kqe-query host capability" {}))))

(defn observe []
  ;; observe — the opening edges a connectivity-repair gathering proposes (host ⇄ fragile).
  (*kqe-assert!* "moyoshi" "host" "opens-access-to" "isolated-actor")
  (*kqe-assert!* "moyoshi" "host" "opens-access-to" "low-reciprocity-actor")
  ;; derive — which actors a gathering would open access to (Datalog), aggregate.
  (*kqe-query* "opening(?b) :- opens-access-to(?b)."))

(defn run [ctx]
  (binding [*kqe-assert!* (or (:kqe-assert! ctx) *kqe-assert!*)
            *kqe-query* (or (:kqe-query ctx) *kqe-query*)]
    (observe)))
(defn on-kse [topic payload] (observe))
