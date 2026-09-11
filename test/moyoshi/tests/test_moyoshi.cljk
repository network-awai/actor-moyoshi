(ns moyoshi.tests.test-moyoshi
  "moyoshi 催し — convening + validated-social-capital tests (ADR-2606272100). Verifies
  the loop's mathematical invariants (design → govern → settle/mint) AND its
  constitutional gates (G1..G6) on the synthetic society seed."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.java.io :as io])
            [moyoshi.methods.moyoshi :as m]))

#?(:clj (def actor-dir (-> (io/resource "moyoshi/tests/test_moyoshi.cljc") io/file
                           .getParentFile .getParentFile .getParentFile .getParentFile)))
#?(:clj (def seed (io/file actor-dir "data" "seed-society.kotoba.edn")))
#?(:clj (def fixture (m/load-seed seed)))
#?(:clj (def fragility (:fragility fixture)))
#?(:clj (def settlement (:settlement fixture)))

;; ── design ────────────────────────────────────────────────────────────────────
(deftest test-design-opens-access
  (testing "the designed gathering is an OPENING toward the fragile actors"
    (let [d (m/design-gathering fragility)]
      (is (= "kaname" (get d ":event/host")) "the 律速 bridge hosts the opening")
      (is (= ["kaname" "niyaku" "shionome"] (sort (get d ":event/audience")))
          "audience opens access to the isolated + low-reciprocity actors + host")
      (is (= ":public" (get d ":event/openness")))
      (is (true? (get d ":event/a11y")))
      (is (true? (get d ":event/coc"))))))

(deftest test-design-targets-are-sorted-ties
  (testing "target-ties are canonical sorted 2-vectors toward the host (a HOPE, not a quota)"
    (let [d (m/design-gathering fragility)]
      (is (= [["kaname" "niyaku"] ["kaname" "shionome"]] (get d ":event/target-ties"))))))

;; ── G2 / G5: forbidden fields are structurally absent ───────────────────────────
(deftest test-G2-no-turnout-field-representable
  (testing "the designer emits NO turnout/engagement field, and govern refuses one if injected"
    (let [d (m/design-gathering fragility)]
      (is (empty? (filter m/forbidden-turnout-keys (keys d))) "no turnout field in a clean design")
      (let [bad (m/govern (assoc d ":event/turnout-target" 200))]
        (is (false? (:ok? bad)))
        (is (= "G2" (get-in bad [:refusal :gate])))
        (is (= :turnout-shaped (get-in bad [:refusal :reason])))))))

(deftest test-G5-no-per-person-rank
  (testing "a per-person rank/engagement score is refused (person-protective)"
    (let [d   (m/design-gathering fragility)
          bad (m/govern (assoc d ":event/engagement-score" {"niyaku" 0.9}))]
      (is (false? (:ok? bad)))
      (is (= "G5" (get-in bad [:refusal :gate])))
      (is (= :per-person-rank (get-in bad [:refusal :reason]))))))

;; ── G3: opening-not-enclosure ───────────────────────────────────────────────────
(deftest test-G3-enclosure-refused
  (testing "paywalled / non-open / inaccessible gatherings are refused"
    (let [d (m/design-gathering fragility)]
      (is (= :enclosure (get-in (m/govern (assoc d ":event/paywall" 1000)) [:refusal :reason])))
      (is (= :not-an-opening (get-in (m/govern (assoc d ":event/openness" ":invite-only")) [:refusal :reason])))
      (is (= :inaccessible (get-in (m/govern (assoc d ":event/a11y" false)) [:refusal :reason]))))))

;; ── G1: propose-not-act ─────────────────────────────────────────────────────────
(deftest test-G1-propose-not-act
  (testing "a clean gathering passes as a dry-run routed to ossekai; no execute path"
    (let [b (m/beat fragility)]
      (is (= :proposed (:outcome b)))
      (is (= ":dry-run" (get-in b [:proposal ":status"])))
      (is (= ":ossekai" (get-in b [:proposal ":route"])))
      (is (not-any? #(re-find #"(?i)book|charge|invite|post|execute" (str %))
                    (keys (:proposal b)))
          "no book/charge/invite/post/execute key is representable on a proposal"))))

;; ── G4: mint only on validated + survived + anti-sybil ties ──────────────────────
(deftest test-G4-validated-ties-only
  (testing "only NEW + survived + anti-sybil ties count; pre-existing and sybil excluded"
    (let [vt (m/validated-ties settlement)]
      (is (= #{["kaname" "niyaku"] ["kaname" "shionome"]} vt))
      (is (not (contains? vt ["danjo" "kanae"])) "pre-existing tie does not mint")
      (is (not (contains? vt ["kaname" "sock"])) "colluding sybil tie is excluded"))))

(deftest test-G4-mint-amount
  (testing "mint = SCALE · w_convening · n_validated (headcount never enters)"
    (let [s (m/settle (:convener settlement) settlement)]
      (is (= 2 (get s ":mint/n-validated-ties")))
      (is (= "kaname" (get s ":mint/convener")))
      (is (= "social/mint/convening" (get s ":mint/predicate")))
      (is (= (long (* m/SCALE 1.5 2)) (get s ":mint/smic")) "1.5 pts/tie × 2 ties = 3.0 pts = 3_000_000 smic"))))

(deftest test-burn-is-asymmetric
  (testing "faking community burns more than an honest gathering of the same size earns"
    (is (> (m/burn-convening-smic 2) (m/mint-convening-smic 2)) "嘘で損 / 囲い込みで損")))

;; ── headcount independence (the defining inversion) ─────────────────────────────
(deftest test-mint-is-independent-of-headcount
  (testing "a packed gathering that forms NO surviving tie mints nothing"
    (let [packed {:convener "kaname"
                  :baseline []
                  :surviving []                 ; nobody bonded
                  :distinct-dids #{"kaname" "a" "b" "c" "d" "e"}}
          s (m/settle "kaname" packed)]
      (is (= 0 (get s ":mint/n-validated-ties")))
      (is (= 0 (get s ":mint/smic")) "turnout without bonds = zero social capital"))))
