(ns moyoshi.methods.ingest
  "moyoshi 催し — live kizuna 絆 ingest (ADR-2606272100 R2). Lifts a COMMITTED kizuna
  readout (its `beat`/`assess` output) into moyoshi's fragility input. Running kizuna is
  G7; JOINING its committed output is what moyoshi does — the kaname 要 join pattern
  (`kaname.methods.join`: run a mirror = G7, join a committed output = the actor's job).
  no-server-key (reads a committed edn / Datom, never a live key). Pure transform +
  a clj-only loader. Portable .cljc (bb)."
  (:require [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])))

(defn kizuna->fragility
  "Lift kizuna's readout {:isolated [...] :leverage-actor id :assessment {id {:reciprocity ..}}}
  into moyoshi's fragility {:isolated :leverage-actor :low-reciprocity}. isolated +
  leverage-actor pass through; low-reciprocity = actors whose kizuna reciprocity ratio is
  below `recip-floor` (default 0.5) and are NOT already isolated (isolated dominates —
  an isolated actor has no ties to reciprocate, so it belongs to :isolated, not here).
  Deterministic (sorted), pure."
  [kizuna-out & [{:keys [recip-floor] :or {recip-floor 0.5}}]]
  (let [isolated (vec (sort (:isolated kizuna-out)))
        iso?     (set isolated)
        assess   (:assessment kizuna-out)
        low-recip (->> assess
                       (filter (fn [[id m]]
                                 (and (not (iso? id))
                                      (< (double (get m :reciprocity 1.0)) recip-floor))))
                       (map key) sort vec)]
    {:isolated        isolated
     :leverage-actor  (:leverage-actor kizuna-out)
     :low-reciprocity low-recip}))

(defn reciprocal-ties
  "Extract kizuna's reciprocal-pair set as canonical moyoshi tie-vectors (sorted 2-vecs),
  for use as the settlement baseline / now-graph. kizuna stores reciprocal pairs as
  #{a b} sets under :reciprocal (or a vector of pairs); normalize either form."
  [kizuna-out]
  (->> (or (:reciprocal kizuna-out) (:reciprocal-pairs-set kizuna-out) [])
       (map (fn [p] (vec (sort (seq p)))))
       (sort) vec))

(defn observe-from-kizuna
  "R3 settlement now-graph: build a settlement observation {:surviving :distinct-dids
  :colluding} from a kizuna NOW-readout (its CURRENT reciprocal ties + actor set). The
  `surviving` ties = kizuna's present reciprocal pairs (the ties that still hold); the
  `distinct-dids` = the actor set kizuna sees; `colluding` defaults to none — the moyai
  proof-of-contribution anti-sybil membrane is the R3+ live leg (until wired, a tie must
  still be NEW vs baseline AND survived to mint, G4). Returns a fn gathering-id → obs so it
  drops straight into `settle/settle-due`. Pure."
  [kizuna-now & [{:keys [colluding] :or {colluding []}}]]
  (let [obs {:surviving     (reciprocal-ties kizuna-now)
             :distinct-dids (set (keys (:assessment kizuna-now)))
             :colluding     (vec colluding)}]
    (fn [_gathering-id] obs)))

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
   (defn load-kizuna
     "Read a committed kizuna readout edn (the file kizuna's heartbeat persists, or any
     beat output dumped as edn — either its original bare-map shape, or the Phase-4
     edn-datomize tx-data-wrapped shape). Returns the readout map. R3 live leg: point
     this at kizuna's OWN committed log/readout (read-only, no key — the kaname join
     pattern)."
     [path]
     (let [content (-> (slurp path) (edn/read-string))]
       (if (tx-data-vec? content) (reconstitute-entity content) content))))
