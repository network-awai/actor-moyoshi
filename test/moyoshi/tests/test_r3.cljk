(ns moyoshi.tests.test-r3
  "moyoshi 催し — R3 tests (ADR-2606272100): the LIVE-engine bridge (host allowlist,
  dry-run bodies, exactly-once cursor, provenance) + the settlement now-graph from kizuna
  (observe-from-kizuna) wired through the autorun heartbeat. The live push itself is
  operator-gated (MOYOSHI_KOTOBA_LIVE); these verify the deterministic, no-I/O surface."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            #?(:clj [clojure.java.io :as io])
            [moyoshi.methods.moyoshi :as m]
            [moyoshi.methods.ingest  :as ingest]
            [moyoshi.methods.kotoba  :as kot]
            [moyoshi.methods.kotoba-bridge :as bridge]
            [moyoshi.autorun         :as auto]))

#?(:clj (def actor-dir (-> (io/resource "moyoshi/tests/test_r3.cljc") io/file
                           .getParentFile .getParentFile .getParentFile .getParentFile)))
#?(:clj (def kseed (io/file actor-dir "data" "seed-kizuna.kotoba.edn")))
#?(:clj (def kout  (ingest/load-kizuna kseed)))

;; ── settlement now-graph from kizuna (R3 live ingest leg) ────────────────────────
(deftest test-observe-from-kizuna
  (testing "the kizuna now-readout yields a settlement observation usable by settle-due"
    (let [obs ((ingest/observe-from-kizuna kout) "g-0")]
      (is (= [["danjo" "kanae"] ["kaname" "tsumugi"]] (:surviving obs))
          "surviving = kizuna's current reciprocal ties")
      (is (contains? (:distinct-dids obs) "niyaku") "actor set carried through")
      (is (= [] (:colluding obs)) "anti-sybil colluding set empty until the moyai membrane is wired"))))

;; ── bridge: host allowlist (G6, throws BEFORE any I/O) ───────────────────────────
(deftest test-bridge-allowlist
  (testing "only the kotoba fleet hosts are reachable; anything else throws pre-I/O"
    (is (nil? (bridge/assert-kotoba "http://127.0.0.1:8077/x")) "loopback ok")
    (is (nil? (bridge/assert-kotoba "http://192.168.1.70:8077/x")) "EVO-X2 LAN ok")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :default :default)
                 (bridge/assert-kotoba "http://evil.example.com/x")) "off-fleet refused")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :default :default)
                 (bridge/assert-kotoba "https://127.0.0.1:8077/x")) "https refused (http-only loopback)")))

;; ── bridge: dry-run bodies + provenance (no I/O) ─────────────────────────────────
#?(:clj
   (deftest test-bridge-dry-run-bodies
     (testing "a persisted beat → dry-run transact bodies with moyoshi.tx provenance, no I/O"
       (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "moyoshi-r3-bridge-" (hash kout) ".kotoba.edn")))]
         (io/delete-file tmp true)
         (let [ds (kot/readout->datoms {:outcome :proposed
                                        :proposal (get (m/beat (ingest/kizuna->fragility kout)) :proposal)
                                        :settled [] :pending-count 1 :epoch 0})]
           (kot/persist! ds {:tx-id "t0" :as-of "a0" :log-path tmp})
           (let [out (bridge/push tmp {})]  ; dry-run (no :live)
             (is (= "dry-run" (:mode out)))
             (is (= 1 (:pending out)) "one data tx pending")
             (is (str/starts-with? (:graph-cid out) "b") "graph CID is multibase base32 (b…)")
             (let [edn (get-in out [:bodies 0 :tx_edn])]
               (is (str/includes? edn ":moyoshi.tx/local-cid") "provenance meta is attached")
               (is (str/includes? edn ":moyoshi/proposed-host") "the readout datoms are carried"))))
         (io/delete-file tmp true)))))

;; ── bridge: exactly-once cursor (pending shrinks after a checkpoint) ──────────────
(deftest test-bridge-cursor-pending
  (testing "the durable :bridge cursor excludes already-pushed data txs"
    (let [txs [{:tx/cid "c1" :tx/datoms [[":db/add" "moyoshi:convening" ":moyoshi/epoch" 0]]}
               {:tx/cid "b1" :tx/datoms [[":db/add" "bridge-2" ":bridge/pushed-cid" "c1"]]}
               {:tx/cid "c2" :tx/datoms [[":db/add" "moyoshi:convening" ":moyoshi/epoch" 7]]}]]
      (is (= ["c2"] (map :tx/cid (bridge/pending-txs txs)))
          "c1 already pushed (cursor=c1); only c2 is pending")
      (is (= "c1" (:pushed-cid (bridge/bridge-cursor txs)))))))

;; ── autorun --bridge wiring is fail-open (engine down → beat still completes) ─────
#?(:clj
   (deftest test-autorun-bridge-fail-open
     (testing "a --bridge beat with no live engine completes locally; bridge reports dry-run/fail-open"
       (let [tmp (str (io/file (System/getProperty "java.io.tmpdir")
                               (str "moyoshi-r3-auto-" (hash kout) ".kotoba.edn")))]
         (io/delete-file tmp true)
         ;; bridge? true but MOYOSHI_KOTOBA_LIVE unset → push is dry-run (no network), beat completes
         (let [r (auto/beat {:kizuna-out kout :epoch 0 :log-path tmp :tx-id "m0" :as-of "a0"
                             :observe (ingest/observe-from-kizuna kout) :bridge? true})]
           (is (= :proposed (get-in r [:beat :outcome])))
           (is (:appended (:persist r)) "local persist still happened")
           (is (contains? r :bridge) "bridge result is reported")
           (is (:ok (kot/verify tmp))))
         (io/delete-file tmp true)))))


;; ── internal-trust header: present when configured, ABSENT (loudly) when not ──
;; ADR-2608124000, "clients first". kotoba-server's require_internal_trust gate
;; returns success while KOTOBA_INTERNAL_SECRET is unset, and it is unset across
;; the fleet — so sending this header today changes nothing on the wire. These
;; tests pin the shape NOW so that arming the server later is a one-variable
;; decision instead of a fleet-wide outage. The values below are obviously
;; synthetic; no real secret is read, written, or generated by this suite.
(def ^:private synthetic-trust "synthetic-internal-trust-not-a-real-secret")

(deftest internal-trust-header-present-when-configured
  (let [seen (atom nil)
        capture (fn [_u _b headers _t] (reset! seen headers) {:status "ok"})]
    (with-redefs [bridge/internal-trust (constantly synthetic-trust)]
      (bridge/default-transport "http://127.0.0.1:8077/xrpc/x" {} {:http-post capture :operator-did "did:key:zSyntheticTestOperator"})
      (is (= synthetic-trust (get @seen "x-internal-trust"))
          "the configured value is sent verbatim")
      ;; positive control — the pre-existing headers are untouched
      (is (= "application/json" (get @seen "Content-Type")))
      (is (str/starts-with? (get @seen "Authorization") "Bearer "))
      (is (str/ends-with? (get @seen "Authorization") ".unsigned-loopback")))))

(deftest internal-trust-header-absent-when-unconfigured
  (let [seen (atom nil)
        capture (fn [_u _b headers _t] (reset! seen headers) {:status "ok"})]
    (with-redefs [bridge/internal-trust (constantly nil)]
      (bridge/default-transport "http://127.0.0.1:8077/xrpc/x" {} {:http-post capture :operator-did "did:key:zSyntheticTestOperator"})
      (is (not (contains? @seen "x-internal-trust"))
          "omitted entirely — never sent as an empty string")
      ;; positive control — omitting trust must not disturb anything else
      (is (= "application/json" (get @seen "Content-Type")))
      (is (str/starts-with? (get @seen "Authorization") "Bearer "))
      (is (str/ends-with? (get @seen "Authorization") ".unsigned-loopback")))))

(deftest internal-trust-absence-is-reported-not-silent
  (with-redefs [bridge/internal-trust (constantly nil)]
    (is (= :unconfigured (bridge/internal-trust-status))))
  (with-redefs [bridge/internal-trust (constantly synthetic-trust)]
    (is (= :configured (bridge/internal-trust-status)))))

;; positive control — the host allowlist still refuses off-fleet endpoints, with
;; or without a configured secret. A bearer token is not a substitute for it.
(deftest allowlist-still-enforced-alongside-internal-trust
  (with-redefs [bridge/internal-trust (constantly synthetic-trust)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (bridge/default-transport "http://evil.example.com/x" {}
                                       {:http-post (fn [& _] {:status "ok"})})))))
