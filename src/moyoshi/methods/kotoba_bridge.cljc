(ns moyoshi.methods.kotoba-bridge
  "moyoshi 催し — push the local convening commit-DAG into the LIVE kotoba engine
  (ADR-2606272100 R3; the kaname/ibuki bridge pattern, ADR-2606172100 §R3 / 2606101200).

  `moyoshi.methods.kotoba` persists each convening beat (proposal + settlement batch) to a
  LOCAL append-only kotoba commit-DAG. This namespace is the missing hop: each local tx
  becomes one `com.etzhayyim.apps.kotoba.datomic.transact` call against a running kotoba
  node (serving :8077), so the convening readout lands on the REAL distributed Datom graph
  (IPFS-backed, IPNS-headed) — not just a file.

  Discipline (same as kaname):
    - host allowlist (loopback + EVO-X2 LAN, ADR-2605215000) — any other endpoint throws BEFORE I/O;
    - a durable `:bridge/*` cursor ON the local log (keyed by the last pushed LOCAL CID) → exactly-once
      per local tx, crash/re-run safe;
    - every pushed tx carries `:moyoshi.tx/*` provenance (local tx-id / CID / prev) → the remote graph
      maps back to the local commit-DAG;
    - the previous push's remote commit_cid is sent as `expected_parent` (optimistic concurrency);
    - the loopback transact trust boundary needs NO auth; an unsigned public-DID operator bearer is
      attached ONLY when :operator-did is given (a PUBLIC identifier, never a secret) — no-server-key;
    - DRY-RUN by default (returns exact request bodies, no I/O); live = MOYOSHI_KOTOBA_LIVE=1 or :live true.
  HTTP is an injectable fn (*http-post* / :http-post), defaulting to babashka.http-client. Deterministic."
  (:require [clojure.string :as str]
            [kotoba.datom :as kd]))

(def allowed-kotoba-hosts
  #{"127.0.0.1:8077" "localhost:8077" "192.168.1.70:8077"})

(def default-endpoint "http://127.0.0.1:8077/xrpc/com.etzhayyim.apps.kotoba.datomic.transact")
(def default-graph "moyoshi")
(def live-env "MOYOSHI_KOTOBA_LIVE")
(def operator-did-env "MOYOSHI_KOTOBA_OPERATOR_DID")  ;; PUBLIC did:key (loopback node-persists-on-behalf)

(defn kotoba-boundary-violation
  ([msg] (kotoba-boundary-violation msg {}))
  ([msg data] (ex-info msg (assoc data :moyoshi/kotoba-boundary-violation true))))

(defn- url-parts [endpoint]
  (if-let [[_ scheme netloc] (re-find #"^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)" (str endpoint))]
    {:scheme (str/lower-case scheme) :netloc netloc}
    {:scheme nil :netloc nil}))

(defn assert-kotoba
  "Refuse any endpoint whose host:port is not in the kotoba fleet allowlist (http only). Throws
  before any I/O; returns nil on the fleet."
  [endpoint]
  (let [{:keys [scheme netloc]} (url-parts endpoint)]
    (when-not (and (= "http" scheme)
                   (contains? allowed-kotoba-hosts (some-> netloc str/lower-case)))
      (throw (kotoba-boundary-violation
              (str "kotoba endpoint " (pr-str endpoint) " is outside the fleet allowlist "
                   (vec (sort allowed-kotoba-hosts)))
              {:endpoint endpoint})))
    nil))

;; ── graph CID (KotobaCid::from_bytes parity) ──────────────────────────────────
(defn- b32-lower ^String [^bytes bs]
  (let [alphabet "abcdefghijklmnopqrstuvwxyz234567"
        sb (StringBuilder.)
        n (alength bs)]
    (loop [i 0 buf 0 bits 0]
      (cond
        (>= bits 5)
        (let [shift (- bits 5)]
          (.append sb ^char (nth alphabet (bit-and (bit-shift-right buf shift) 0x1f)))
          (recur i (bit-and buf (dec (bit-shift-left 1 shift))) shift))
        (< i n)
        (recur (inc i) (bit-or (bit-shift-left buf 8) (bit-and (long (aget bs i)) 0xff)) (+ bits 8))
        :else
        (do (when (pos? bits)
              (.append sb ^char (nth alphabet (bit-and (bit-shift-left buf (- 5 bits)) 0x1f))))
            (str sb))))))

#?(:clj
   (defn graph-cid
     "CIDv1 + dag-cbor(0x71) + sha2-256 over the raw graph-NAME bytes, multibase base32lower
     ('b' prefix). Mirrors kotoba-core cid.rs (a fresh CID transacts as genesis)."
     ^String [^String name]
     (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes name "UTF-8"))
           raw (byte-array (concat [0x01 0x71 0x12 0x20] (seq digest)))]
       (str "b" (b32-lower raw)))))

;; ── per-tx body ───────────────────────────────────────────────────────────────
(defn- edn-val ^String [v]
  (cond
    (true? v) "true"
    (false? v) "false"
    (number? v) (str v)
    (string? v) (if (str/starts-with? v ":") v (pr-str v))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (pr-str (str v))))

(defn tx->edn-vec
  "Local tx → the `tx_edn` string: its [:db/add e a v] forms + :moyoshi.tx/* provenance meta."
  ^String [tx]
  (let [meta-e (str "moyoshi-tx-" (:tx/id tx))
        forms (concat (:tx/datoms tx)
                      [[":db/add" meta-e ":moyoshi.tx/id" (str (:tx/id tx))]
                       [":db/add" meta-e ":moyoshi.tx/local-cid" (:tx/cid tx)]
                       [":db/add" meta-e ":moyoshi.tx/local-prev" (:tx/prev tx)]
                       [":db/add" meta-e ":moyoshi.tx/as-of" (str (:tx/as-of tx))]])]
    (str "[" (str/join " " (map (fn [[op e a v]]
                                  (str "[" op " " (pr-str e) " " a " " (edn-val v) "]")) forms)) "]")))

;; ── injectable HTTP edge ──────────────────────────────────────────────────────
#?(:clj
   (defn default-http-post [url body-map headers timeout-s]
     (let [post (requiring-resolve 'babashka.http-client/post)
           generate (requiring-resolve 'cheshire.core/generate-string)
           parse (requiring-resolve 'cheshire.core/parse-string)
           resp (post (str url) {:headers headers :body (generate body-map)
                                 :timeout (long (* 1000 (double timeout-s))) :throw false})
           status (:status resp)]
       (if (<= 200 status 299)
         (parse (:body resp) true)
         (throw (ex-info (str "kotoba transact HTTP " status ": "
                              (let [b (str (:body resp))] (subs b 0 (min 200 (count b)))))
                         {:moyoshi/kotoba-transact-http-error true :status status}))))))

(def ^:dynamic *http-post* #?(:clj default-http-post :default nil))

#?(:clj
   (defn default-transport
     "POST a transact. The loopback trust boundary needs no auth; attach an unsigned operator
     bearer ONLY when :operator-did is supplied (a public identifier, never a secret)."
     ([url body] (default-transport url body {}))
     ([url body {:keys [timeout-s http-post operator-did] :or {timeout-s 60.0}}]
      (assert-kotoba url)
      (let [headers (cond-> {"Content-Type" "application/json"}
                      (and operator-did (not (str/blank? operator-did)))
                      (assoc "Authorization"
                             (str "Bearer "
                                  (let [b64 (fn [s] (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder))
                                                                     (.getBytes ^String s "UTF-8")))]
                                    (str (b64 (kd/canonical-json {"alg" "none"})) "."
                                         (b64 (kd/canonical-json {"sub" operator-did})) ".unsigned-loopback")))))]
        ((or http-post *http-post*) url body headers timeout-s)))))

;; ── durable push cursor (keyed by local CID — robust to string tx-ids) ────────
(defn- bridge-tx? [tx]
  (boolean (some (fn [[_ _ a _]] (str/starts-with? (str a) ":bridge/")) (:tx/datoms tx))))

(defn data-txs [txs] (vec (remove bridge-tx? txs)))

(defn bridge-cursor
  "Replay the durable cursor: {:pushed-cid <last pushed local CID> :parent-commit <remote>}."
  [txs]
  (reduce (fn [st tx]
            (reduce (fn [st [_ _ a v]]
                      (case a
                        ":bridge/pushed-cid" (assoc st :pushed-cid v)
                        ":bridge/parent-commit" (assoc st :parent-commit v)
                        st))
                    st (:tx/datoms tx)))
          {:pushed-cid "" :parent-commit ""} txs))

(defn pending-txs
  "Data txs not yet pushed (those AFTER the cursor's pushed-cid, by position)."
  [txs]
  (let [data (data-txs txs)
        {:keys [pushed-cid]} (bridge-cursor txs)]
    (if (str/blank? pushed-cid)
      data
      (let [idx (first (keep-indexed (fn [i tx] (when (= (:tx/cid tx) pushed-cid) i)) data))]
        (if idx (vec (drop (inc idx) data)) data)))))

#?(:clj (defn- env-live? [] (= "1" (System/getenv live-env))))

#?(:clj
   (defn push
     "Push every not-yet-sent local data tx (oldest first), one transact per tx. Live requires
     MOYOSHI_KOTOBA_LIVE=1 or :live true; otherwise DRY-RUN (returns the exact bodies). After a live
     push, ONE :bridge/* checkpoint tx is appended (exactly-once cursor). Options: :graph :endpoint
     :transport :live :http-post :operator-did :as-of-base."
     ([log-path] (push log-path {}))
     ([log-path {:keys [graph endpoint transport live http-post operator-did as-of-base]
                 :or {graph default-graph endpoint default-endpoint as-of-base 2606270000}}]
      (assert-kotoba endpoint)
      (let [operator-did (or operator-did (System/getenv operator-did-env))
            graph-id (if (and (str/starts-with? graph "b") (> (count graph) 40)) graph (graph-cid graph))
            txs (kd/read-log log-path)
            state (bridge-cursor txs)
            pending (pending-txs txs)
            bodies (mapv (fn [tx] {:graph graph-id :tx_edn (tx->edn-vec tx)}) pending)
            is-live (if (some? live) (boolean live) (env-live?))]
        (if-not is-live
          {:mode "dry-run" :pending (count bodies) :graph-cid graph-id :bodies bodies
           :pushed-cid (:pushed-cid state)}
          (loop [pairs (map vector pending bodies)
                 remote-cids [] last-commit (:parent-commit state) datoms-confirmed 0]
            (if-let [[tx body] (first pairs)]
              (let [body (if (seq last-commit) (assoc body :expected_parent last-commit) body)
                    out (if transport (transport endpoint body)
                            (default-transport endpoint body {:http-post http-post :operator-did operator-did}))]
                (when-not (contains? #{"ok" "committed" "success"} (str (:status out)))
                  (throw (ex-info (str "kotoba transact refused tx " (:tx/id tx) ": " (pr-str out))
                                  {:moyoshi/kotoba-transact-refused true :tx/id (:tx/id tx) :out out})))
                (recur (rest pairs) (conj remote-cids (or (:tx_cid out) ""))
                       (or (:commit_cid out) "") (+ datoms-confirmed (or (:datom_count out) 0))))
              (do
                (when (seq pending)
                  (let [beat (inc (count txs))
                        e (str "bridge-" beat)
                        ds [(kd/add e ":bridge/pushed-cid" (:tx/cid (peek pending)))
                            (kd/add e ":bridge/parent-commit" last-commit)
                            (kd/add e ":bridge/graph" graph)
                            (kd/add e ":bridge/remote-tx-cids" remote-cids)
                            (kd/add e ":bridge/beat" beat)
                            (kd/add e ":bridge/as-of" (+ as-of-base beat))]
                        ck (kd/make-tx ds {:tx-id (str "bridge-" beat) :as-of (+ as-of-base beat)
                                           :prev-cid (kd/head-cid log-path)})]
                    (kd/append-tx! ck log-path)))
                {:mode "live" :pushed (count pending) :graph-cid graph-id
                 :remote-tx-cids remote-cids :parent-commit last-commit
                 :datoms-confirmed datoms-confirmed
                 :pushed-cid (if (seq pending) (:tx/cid (peek pending)) (:pushed-cid state))}))))))))
