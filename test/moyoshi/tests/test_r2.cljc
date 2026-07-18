(ns moyoshi.tests.test-r2
  "moyoshi 催し — R2 tests (ADR-2606272100): live kizuna ingest + settlement decay-window
  job + commit-DAG persistence. Verifies the three R2 legs' invariants on the synthetic
  committed-kizuna seed."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.java.io :as io])
            [moyoshi.methods.moyoshi :as m]
            [moyoshi.methods.ingest  :as ingest]
            [moyoshi.methods.settle  :as settle]
            [moyoshi.methods.kotoba  :as kot]
            [moyoshi.autorun         :as auto]))

#?(:clj (def actor-dir (-> (io/resource "moyoshi/tests/test_r2.cljc") io/file
                           .getParentFile .getParentFile .getParentFile .getParentFile)))
#?(:clj (def kseed (io/file actor-dir "data" "seed-kizuna.kotoba.edn")))
#?(:clj (def kout  (ingest/load-kizuna kseed)))

;; ── leg 1: live kizuna ingest ───────────────────────────────────────────────────
(deftest test-ingest-kizuna-to-fragility
  (testing "a committed kizuna readout lifts into moyoshi fragility"
    (let [f (ingest/kizuna->fragility kout)]
      (is (= ["niyaku"] (:isolated f)) "isolated passes through")
      (is (= "kaname" (:leverage-actor f)) "律速 bridge passes through")
      (is (= ["shionome"] (:low-reciprocity f))
          "low-reciprocity = recip < floor AND not already isolated (niyaku excluded — it's isolated)"))))

(deftest test-ingest-baseline-ties
  (testing "kizuna reciprocal pairs become canonical sorted baseline ties"
    (is (= [["danjo" "kanae"] ["kaname" "tsumugi"]] (ingest/reciprocal-ties kout)))))

(deftest test-ingest-feeds-a-real-proposal
  (testing "the ingested fragility drives a governed proposal end-to-end"
    (let [b (m/beat (ingest/kizuna->fragility kout))]
      (is (= :proposed (:outcome b)))
      (is (= "kaname" (get-in b [:proposal ":event/host"]))))))

;; ── leg 2: settlement decay-window job ──────────────────────────────────────────
(deftest test-pending-window
  (testing "a gathering settles only after S epochs (not when hosted)"
    (let [g (settle/pending-gathering {:gathering-id "g-0" :convener "kaname"
                                       :baseline [["danjo" "kanae"]] :epoch 0})]
      (is (= 7 (:gathering/settle-at g)) "settle-at = epoch + S(=7)")
      (is (not (settle/due? g 3)) "not due mid-window")
      (is (settle/due? g 7) "due at the window's end")
      (is (settle/due? g 9) "due after"))))

(deftest test-settle-due-mints-only-survived-new-antisybil
  (testing "the decay-window job mints only survived + new + anti-sybil ties; pre-existing/sybil excluded"
    (let [pending [(settle/pending-gathering {:gathering-id "g-0" :convener "kaname"
                                              :baseline [["danjo" "kanae"]] :epoch 0})]
          observe (constantly
                   {:surviving [["kaname" "niyaku"]    ; NEW + survived → mints
                                ["kaname" "shionome"]  ; NEW + survived → mints
                                ["danjo" "kanae"]      ; pre-existing (baseline) → no mint
                                ["kaname" "sock"]]     ; sybil → excluded
                    :distinct-dids #{"kaname" "niyaku" "shionome" "danjo" "kanae"}
                    :colluding [["kaname" "sock"]]})
          {:keys [settled pending]} (settle/settle-due pending 7 observe)]
      (is (empty? pending) "the settled gathering leaves the ledger")
      (is (= 1 (count settled)))
      (is (= 2 (get (first settled) ":mint/n-validated-ties")))
      (is (= (long (* m/SCALE 1.5 2)) (get (first settled) ":mint/smic")))
      (is (= "g-0" (get (first settled) ":gathering/id"))))))

(deftest test-settle-leaves-immature-pending
  (testing "a gathering whose window has NOT elapsed stays pending (mints nothing yet)"
    (let [pending [(settle/pending-gathering {:gathering-id "g-5" :convener "kaname"
                                              :baseline [] :epoch 5})]
          {:keys [settled pending]} (settle/settle-due pending 7 (constantly {:surviving []}))]
      (is (empty? settled))
      (is (= 1 (count pending)) "still maturing (settle-at 12 > 7)"))))

;; ── leg 3: commit-DAG persistence (idempotent-by-content, verify-chain) ──────────
#?(:clj
   (deftest test-persist-idempotent-and-verifiable
     (testing "two identical beats append once; chain verifies; a changed beat appends"
       (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "moyoshi-r2-" (hash kout) ".kotoba.edn")))]
         (io/delete-file tmp true)
         (let [ds (kot/readout->datoms {:outcome :proposed
                                        :proposal (get (m/beat (ingest/kizuna->fragility kout)) :proposal)
                                        :settled [] :pending-count 1 :epoch 0})
               p1 (kot/persist! ds {:tx-id "t0" :as-of "a0" :log-path tmp})
               p2 (kot/persist! ds {:tx-id "t1" :as-of "a1" :log-path tmp})]
           (is (:appended p1) "first beat appends")
           (is (not (:appended p2)) "identical second beat is a no-op")
           (is (= :no-change (:reason p2)))
           (is (:ok (kot/verify tmp)) "chain verifies")
           ;; a different readout (an epoch's mint) DOES append
           (let [ds2 (kot/readout->datoms {:outcome :proposed :proposal (get ds 0)
                                           :settled [{":gathering/id" "g-0" ":mint/convener" "kaname"
                                                      ":mint/n-validated-ties" 2 ":mint/smic" 3000000}]
                                           :pending-count 0 :epoch 7})
                 p3 (kot/persist! ds2 {:tx-id "t2" :as-of "a2" :log-path tmp})]
             (is (:appended p3) "a settlement beat is new content → appends")
             (is (:ok (kot/verify tmp))))
           (io/delete-file tmp true))))))

;; ── autorun heartbeat: ingest → propose → record → settle → persist ─────────────
#?(:clj
   (deftest test-autorun-beat-end-to-end
     (testing "one heartbeat ingests kizuna, proposes, records pending, persists"
       (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "moyoshi-r2-auto-" (hash kout) ".kotoba.edn")))]
         (io/delete-file tmp true)
         (let [r (auto/beat {:kizuna-out kout :epoch 0 :log-path tmp :tx-id "m0" :as-of "a0"})]
           (is (= :proposed (get-in r [:beat :outcome])))
           (is (= 1 (count (:pending r))) "the proposed gathering is recorded, maturing")
           (is (empty? (:settled r)) "nothing settles at epoch 0 (window not elapsed)")
           (is (:appended (:persist r)))
           (is (:ok (kot/verify tmp))))
         (io/delete-file tmp true)))))
