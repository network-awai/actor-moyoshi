(ns moyoshi.methods.moyoshi
  "moyoshi 催し — convening actor: design/host gatherings whose telos is VALIDATED
  social capital, never turnout (ADR-2606272100). The realizing-act sibling of kizuna
  絆 (which only PROPOSES ties): moyoshi takes kizuna's fragility readout, designs a
  gathering that would OPEN access between the society's isolated/peripheral actors and
  a connecting host, runs it past an independent ConveningGovernor, and emits a dry-run
  `:event/proposed` for a human to host (ossekai + member CACAO). At settlement — S
  epochs later — it counts only the ties that FORMED and SURVIVED and passed the
  anti-sybil membrane, and that (never headcount) is what mints the convening sub-ledger
  of social capital.

  The loop (one beat): perceive(fragility) → design(EventDesigner) → govern(G2..G6)
  → propose(:dry-run → ossekai). Settlement (`settle`) runs later and is the only thing
  that mints. Pure + deterministic (sorted DID/tie order, ties as sorted 2-vectors; no
  wall clock, no randomness). The EventDesigner LLM is sealed + advisory at R1; the R0
  designer here is the deterministic stand-in. Portable .cljc (bb).

  Gates (in code + tests):
   G1 PROPOSE-not-act — every gathering is `:status :dry-run :route :ossekai`; there is
      NO book/charge/invite/post path. Actuation = a human host via ossekai + member
      CACAO leash (no-server-key, ADR-2606072802). moyoshi never hosts/pays/messages.
   G2 BONDS-not-turnout — the objective is tie-formation + reciprocity + connectivity
      repair, NEVER attendance/reach/RSVP/virality/retention (Charter §1.13 / §2(h)).
      A turnout/engagement field is UNREPRESENTABLE — the governor refuses any proposal
      that carries one.
   G3 OPENING-not-enclosure — every gathering must increase participation-openness
      (asobi OPENING route): public/open, accessible, no paywall. Enclosure is refused.
   G4 MINT-on-validated-ties-only — `settle` mints only from ties that are NEW
      vs the pre-event baseline AND survived the window AND passed anti-sybil (distinct,
      non-colluding DIDs — moyai proof-of-contribution). Headcount/RSVP mint nothing.
   G5 CONSENT-bound, person-protective — a gathering needs a code-of-conduct; the
      audience is a set of DIDs to OPEN access to, never individuals to RANK. A
      per-person engagement/rank field is UNREPRESENTABLE (refused).
   G6 no-server-key — moyoshi READS kizuna/asobi public signals + PROPOSES; holds no key."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])))

;; ── ledger constants (mirror kotoba/docs/SOCIAL-CAPITAL-LEDGER.md; params live in the
;;    Council-attested social/capital/params/active blob — defaults here are transparent
;;    tuned constants, not learned). ────────────────────────────────────────────────
(def ^:const SCALE 1000000) ; 1 social point = 1e6 smic (ledger convention)
(def default-params
  {:w-convening          1.5  ; points per validated+survived tie (disclosure 1.0 < this < wellbecoming 2.0)
   :burn-extractive-mult 1.5  ; faking community costs more than it earns (嘘で損 / 囲い込みで損)
   :survival-epochs      7})  ; S — a tie must persist this many epochs after the gathering to count

;; G2/G5 — fields that are STRUCTURALLY forbidden. Their mere presence on a proposal is
;; a governor refusal (the anti-engagement / person-protective membrane).
(def forbidden-turnout-keys
  #{":event/turnout-target" ":event/reach" ":event/rsvp-goal"
    ":event/retention" ":event/virality" ":event/headcount-goal" ":event/attendance"})
(def forbidden-rank-keys
  #{":event/rank" ":event/per-person-score" ":event/engagement-score" ":event/leaderboard"})

;; ── ties as canonical sorted 2-vectors (undirected, deterministic) ───────────────
(defn tie
  "Canonical undirected tie: a sorted 2-vector of DIDs (so #{a b} prints/compares
  deterministically)."
  [t] (vec (sort (seq t))))

;; ── design: EventDesigner step (R1 = sealed advisory LLM; R0 = deterministic stand-in)
(defn design-gathering
  "Given kizuna's fragility readout {:isolated [...] :leverage-actor id :low-reciprocity
  [...]}, design ONE gathering that opens access between the isolated/peripheral actors
  and a connecting host, aiming to FORM ties. Emits no turnout/rank field (G2/G5) — the
  audience is a set of DIDs to open access to, and `:event/target-ties` are the ties we
  HOPE form (never a guarantee, never a quota)."
  [{:keys [isolated leverage-actor low-reciprocity] :or {isolated [] low-reciprocity []}}]
  (let [guests (vec (sort (distinct (concat isolated low-reciprocity))))
        host   (or leverage-actor (first guests))
        audience (vec (sort (distinct (cons host guests))))
        targets  (->> guests
                      (remove #(= % host))
                      (map #(tie [% host]))
                      distinct sort vec)]
    {":event/purpose"     ":connectivity-repair"
     ":event/host"        host
     ":event/audience"    audience                  ; DIDs to OPEN access to (G5: not ranked)
     ":event/format"      ":open-roundtable"
     ":event/openness"    ":public"                 ; G3
     ":event/coc"         true                      ; G5 — code of conduct
     ":event/a11y"        true                      ; G3 — accessibility
     ":event/target-ties" targets                   ; ties we HOPE form (not a quota)
     ":event/objective"   ":tie-formation+reciprocity" ; G2 — never :turnout
     ":status"            ":dry-run"                 ; G1
     ":route"             ":ossekai"}))              ; G1

;; ── govern: the independent ConveningGovernor ────────────────────────────────────
(defn govern
  "ConveningGovernor (independent system). Refuses any gathering that is turnout/
  engagement-shaped (G2), ranks persons (G5), is an enclosure (G3), lacks consent-safety
  scaffolding (G3/G5), or is not a dry-run routed to ossekai (G1). Returns
  {:ok? true :proposal p} or {:ok? false :refusal {:gate .. :reason ..}}."
  [proposal]
  (let [ks       (set (keys proposal))
        turnout? (seq (filter forbidden-turnout-keys ks))
        rank?    (seq (filter forbidden-rank-keys ks))]
    (cond
      turnout? {:ok? false :refusal {:gate "G2" :reason :turnout-shaped :keys (vec (sort turnout?))}}
      rank?    {:ok? false :refusal {:gate "G5" :reason :per-person-rank :keys (vec (sort rank?))}}
      (get proposal ":event/paywall")
      {:ok? false :refusal {:gate "G3" :reason :enclosure}}
      (not (#{":public" ":open"} (get proposal ":event/openness")))
      {:ok? false :refusal {:gate "G3" :reason :not-an-opening}}
      (not (true? (get proposal ":event/a11y")))
      {:ok? false :refusal {:gate "G3" :reason :inaccessible}}
      (not (true? (get proposal ":event/coc")))
      {:ok? false :refusal {:gate "G5" :reason :no-code-of-conduct}}
      (not= ":dry-run" (get proposal ":status"))
      {:ok? false :refusal {:gate "G1" :reason :not-dry-run}}
      (not= ":ossekai" (get proposal ":route"))
      {:ok? false :refusal {:gate "G1" :reason :not-routed-to-ossekai}}
      :else {:ok? true :proposal proposal})))

;; ── beat: perceive → design → govern → propose (no mint here — settlement mints) ─────
(defn beat
  "One convening beat over a fragility readout. Pure: designs a gathering and runs it
  past the governor. Emits a dry-run proposal (→ ossekai) or a refusal. Minting happens
  later, only via `settle`."
  [fragility]
  (let [design  (design-gathering fragility)
        verdict (govern design)]
    (if (:ok? verdict)
      {:outcome  :proposed
       :proposal (:proposal verdict)
       :targets  (count (get design ":event/target-ties"))}
      {:outcome :refused :refusal (:refusal verdict) :design design})))

;; ── settle: the ONLY thing that mints (G4) ───────────────────────────────────────
(defn validated-ties
  "G4: a tie MINTS only if it (a) is NEW (not in :baseline), (b) SURVIVED the window
  (present in :surviving), and (c) passes anti-sybil — both endpoints are distinct
  verified DIDs (:distinct-dids) and the pair is not a known colluding pair (:colluding,
  from moyai proof-of-contribution). Returns the set of validated tie-vectors."
  [{:keys [baseline surviving distinct-dids colluding]
    :or   {baseline [] surviving [] distinct-dids #{} colluding []}}]
  (let [base (set (map tie baseline))
        coll (set (map tie colluding))]
    (->> surviving
         (map tie)
         (filter (fn [[a b :as t]]
                   (and (not (base t))
                        (contains? distinct-dids a)
                        (contains? distinct-dids b)
                        (not (coll t)))))
         set)))

(defn mint-convening-smic
  "Convening points minted to the convener this epoch = SCALE · w_convening ·
  n_validated_ties. (Headcount/RSVP contribute nothing — they never reach this fn.)"
  [n-validated & [{:keys [w-convening] :or {w-convening (:w-convening default-params)}}]]
  (long (* SCALE w-convening n-validated)))

(defn burn-convening-smic
  "Asymmetric downside (§4): a gathering Council-attested as engagement-farmed/coerced/
  exclusionary burns SCALE · burn_extractive_mult · w_convening · n_manipulative —
  strictly more than an honest one of the same size earns (囲い込みで損)."
  [n-manipulative & [{:keys [w-convening burn-extractive-mult]
                      :or   {w-convening (:w-convening default-params)
                             burn-extractive-mult (:burn-extractive-mult default-params)}}]]
  (long (* SCALE burn-extractive-mult w-convening n-manipulative)))

(defn settle
  "Settlement at epoch e+S: from the pre-event baseline and the ties that survived,
  compute the validated ties and the convening capital minted to the convener. G4:
  headcount/RSVP are absent by construction — only survived, anti-sybil ties count."
  [convener outcome & [params]]
  (let [vt (validated-ties outcome)
        n  (count vt)]
    {":mint/convener"        convener
     ":mint/predicate"       "social/mint/convening"
     ":mint/n-validated-ties" n
     ":mint/validated-ties"  (vec (sort vt))
     ":mint/smic"            (mint-convening-smic n params)}))

;; ── seed I/O (clj only) ──────────────────────────────────────────────────────────
(defn- unblob
  "A Phase-4 edn-datomize blob attr pr-str's a non-scalar value into a string; undo
  that (parse it back to the coll) so downstream un-namespaced key lookups keep
  working unchanged. Non-string / non-parseable values pass through untouched."
  [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch #?(:clj Exception :cljs :default) _ v))
    v))

(defn- reconstitute-entity
  "Undo the Phase-4 edn-datomize wrap: a 1-entity tx-data vector `[{:db/id -1 ns/k v ...}]`
  back into the original bare-keyed map `{:k v ...}` (namespace stripped, blobs unblobbed)."
  [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn- tx-data-vec?
  [content]
  (and (vector? content) (seq content) (map? (first content)) (contains? (first content) :db/id)))

#?(:clj
   (defn load-seed
     "Read the seed edn (either its original bare-map shape, or the Phase-4
     edn-datomize tx-data-wrapped shape) and return the {:fragility :settlement} map."
     [path]
     (let [content (-> (slurp path) (edn/read-string))]
       (if (tx-data-vec? content) (reconstitute-entity content) content))))

#?(:clj
   (defn -main [& args]
     (let [path (or (first args)
                    (-> *file* io/file .getParentFile .getParentFile .getParentFile .getParentFile
                        (io/file "data" "seed-society.kotoba.edn") str))
           {:keys [fragility settlement]} (load-seed path)
           b (beat fragility)
           s (settle (:convener settlement) settlement)]
       (println "moyoshi 催し beat:")
       (println "  outcome:" (:outcome b) " target-ties:" (:targets b))
       (when (= :proposed (:outcome b))
         (println "  proposal: host" (get-in b [:proposal ":event/host"])
                  "→ open access to" (get-in b [:proposal ":event/audience"])
                  "(:dry-run → ossekai)"))
       (println "  settlement:")
       (println "    validated+survived+anti-sybil ties:" (get s ":mint/n-validated-ties")
                (get s ":mint/validated-ties"))
       (println "    minted to" (get s ":mint/convener") ":" (get s ":mint/smic") "smic"
                "→" (get s ":mint/predicate")))))
