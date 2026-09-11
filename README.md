# moyoshi 催し — convening actor that mints *validated* social capital

Canonical repository: `network-awai/actor-moyoshi`. This governed, resident
convening actor belongs to the AWAI human-network plane. It proposes gatherings
and records consent-bound social-capital evidence; Tamaki separately owns
artificial-organism evolution.

**DID**: `did:web:moyoshi.etzhayyim.com`
**Namespace**: `com.etzhayyim.moyoshi.*`
**ADR**: ADR-2606272100
**Status**: R3 — convening core, kizuna ingest, settlement decay window,
content-addressed commit DAG, and an explicitly operator-gated live-engine bridge.
**TIGHT PAIR**: kizuna 絆 (fragility-in / settlement-baseline), ossekai 御節介 (actuator).

> **催し (moyoshi)** — a thing one *holds*; 催す = to host/convene. The name is the
> **means**; the telos is **絆 (validated ties)**, denominated in social capital.

## Why an actor layer at all?

An event-designer LLM is great at proposing a gathering — but it has **no notion of
turnout-vs-bond, openness, consent, accessibility, or sybil-resistance**. Left
unsealed it optimizes attendance and reach, i.e. it rebuilds the
engagement-industrial complex the Charter forbids. moyoshi seals the designer into a
single node and wraps it with an independent **ConveningGovernor**, a **human host**
in the actuation loop, and a mint rule that pays out **only on ties that actually
formed and survived** — never on headcount.

## The core contract

```
kizuna fragility signal  +  injected openness/consent/accessibility context
        │
        ▼
  ┌──────────────────┐  event design   ┌────────────────────┐
  │ EventDesigner-LLM │ ──────────────▶ │ ConveningGovernor  │  (independent)
  │ (sealed node)     │                 │ openness · consent │
  └──────────────────┘                 │ a11y · anti-engmt  │
                                        └─────────┬──────────┘
                                   propose ◀──────┴──────▶ refuse
                                      │
                            :event/proposed (:dry-run) → ossekai + member CACAO
                                      │
                              human hosts (member-signed go)
                                      ▼
                        settle: ties that FORMED and SURVIVED a decay window
                        → mint social/mint/convening/<epoch>  (anti-sybil)
```

**moyoshi never books, charges, invites, or posts on its own, and never mints from
attendance.**

## The loop (one heartbeat — `moyoshi.autorun/beat`, R2)

`ingest` a committed kizuna 絆 readout (`src/moyoshi/methods/ingest`) → `design` (sealed LLM stand-in)
→ `govern` (ConveningGovernor, G2..G6) → `propose` (`:event/proposed` :dry-run → ossekai)
→ `record` a pending gathering (settle-at = epoch + S) → `settle` any gathering whose
window has elapsed (`src/moyoshi/methods/settle`, survived + new + anti-sybil ties → mint signal) →
`persist` (content-addressed kotoba commit-DAG, `src/moyoshi/methods/kotoba`, idempotent-by-content,
verify-chain tamper-evident). The on-kse face is `src/moyoshi/methods/mesh.cljk`.

## Constitutional gates (enforced in code + tests at R1)

- **G1 PROPOSE-not-act** — `:event/proposed` (:dry-run / :route :ossekai); no
  book/charge/invite/post; human host via ossekai + member CACAO; no-server-key.
- **G2 BONDS-not-turnout** — reciprocal-tie + connectivity-repair + wellbecoming-Δ;
  never attendance/reach/RSVP/virality/retention. No turnout field representable.
- **G3 OPENING-not-enclosure** — every gathering must increase participation-openness;
  pay-to-enter / exclusionary / attention-locked convening is refused.
- **G4 MINT-on-validated-ties-only** — mints only from ties that formed AND
  survived ≥ S epochs AND passed the anti-sybil membrane; headcount/RSVP mint nothing.
- **G5 CONSENT-bound, person-protective** — no invite/profile/match without consent;
  no per-person engagement score representable.
- **G6 no-server-key** — reads public signals + proposes; holds no key.

## Mint — the convening sub-ledger

Reuses **moyai 舫い** (`proof_of_contribution` / `ledger`) verbatim; adds exactly one
mint predicate `social/mint/convening/<epoch>`, minted to the **convener** DID from
**survived, anti-sybil-validated** ties (Δ vs the pre-event kizuna baseline). Decay /
conservation / non-transfer / burn machinery is inherited unchanged. Engagement-farmed
or coerced gatherings **burn** (`burn_extractive_mult > 1` — 囲い込みで損).

The canonical actor-owned decision is in
`docs/adr/2606272100-moyoshi-convening-social-capital.edn`.

## Standalone verification

Run `kbb -M:test`. EDN is canonical for metadata, data, and contracts. No external
wire representation is currently actor-owned. Go, TinyGo, macOS LaunchAgent,
plist, and shell deployment artifacts are deprecated.
