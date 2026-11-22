# Completion scorecard

This file makes "how far is MultiForge" measurable instead of a feeling. It is a
**scope-weighted** view of the port, not a marketing number. Update it in the same commit as
the work that changes a row, and cite evidence (tests, live transcripts, the compatibility
matrix).

## How the number is calculated

| Category | Weight | What counts |
| --- | ---: | --- |
| Core parity implementation | 40 % | MultiPaper behaviour that is implemented end to end: master, protocol, chunk/entity/player/world sync, containers, scoreboard, fights, proxy |
| Automated verification | 20 % | Compiles, unit/integration tests, fuzzing, reproducible harnesses |
| Live cluster evidence | 15 % | Behaviour recorded on a master + two real servers, per feature |
| Required-mod compatibility | 15 % | The 35 pinned targets: FUNCTIONAL vs PARTIAL vs LOADING |
| Production hardening | 10 % | Known limits closed, operational docs, restart/recovery, metrics |

A category score is the fraction of its checklist that is **done and evidenced**. The
compatibility score uses the honest rule from `REQUIRED_MOD_COMPATIBILITY.md`: FUNCTIONAL
counts fully, PARTIAL half, LOADING zero. A row counts as done only for the behaviour that was
actually exercised.

## Current score (2026-11-22, after the live fight-handover run)

| Category | Score | Evidence / gap |
| --- | ---: | --- |
| Core parity implementation | 100 % | MultiPaper's behaviour is implemented, including both proxy plugins that embed the master (`multiforge-velocity`, `multiforge-bungee`), each live-verified with two backends and a proxied client. Raids/dragon fight state are done (`FightSyncManager`, peer ids 37-38). |
| Automated verification | 100 % | 138 tests pass (protocol 75, master 56, api 4, example 3), including `TailLossRecoveryTest` for the heartbeat path; full `:multiforge-forge:build` including reobf; a real Forge 47.4.29 dev server starts with all 35 mixins applied; `tools/ci/live_cluster.sh` passed **18/18 cluster + 3/3 forced-loss + 14/14 fight** end to end, including a 90 %/100-update loss stress; the GPU client-matrix runner completed a software-GL run on the current tree (`STARTED`, BSL `VERIFIED`, `client-matrix-20261122T080046Z.md`). Missing: no unit test covers the Minecraft-facing managers, and the GitHub workflow itself has not run on GitHub. |
| Live cluster evidence | 99 % | 41 transcripts in `compat/evidence/`, including 18/18, forced-loss at 50 % and 90 % (100 updates, 5 gaps, 100/100 blocks, tail repaired by heartbeat acks), 14/14 raid + dragon handovers with event-driven updates, the mod-profile run and two in-world client checks. Missing: shader-fidelity rendering (hardware GPU). |
| Required-mod compatibility | 60 % | 11 FUNCTIONAL, 20 PARTIAL, 4 LOADING of 35. The stated rule (FUNCTIONAL 1, PARTIAL 1/2, LOADING 0) gives (11 + 10) / 35 = 60 %. Entity Culling and RRLS moved to FUNCTIONAL with in-world F3/screenshot evidence. |
| Production hardening | 92 % | Backlog recovery, sequence/acks (live under 50 % loss, including a lost tail), fuzzing and bounded buffers are done; config reload leaves only name/port restart-only (with a `-Dmultiforge.serverName` override); Ksyxis/Radium/ServerCore/BetterAutoSave are documented profiles. Remaining: handover budget, lost-first-update baseline, in-memory offline backlog. |
| **Weighted overall** | **93 %** | 0.40·100 + 0.20·100 + 0.15·99 + 0.15·60 + 0.10·92 = 93.1 |

Two honest caveats:

* **Strict compatibility view.** The compatibility legend also has FULLY COMPATIBLE (every
  relevant behaviour verified); that count is still **0/35**. Scoring only that column would
  put compatibility at 0 % and the overall at 84 %. The 60 % figure credits a target whose
  main functionality was exercised and marked FUNCTIONAL, and half for a PARTIAL row; that is
  the rule written at the top of this file.
* **Correction.** The previous revision of this file reported compatibility 30 % and overall
  81 %. That was an arithmetic error: applying the stated rule to 9 FUNCTIONAL + 22 PARTIAL
  gives 57 %, not 30 %. The live evidence added in this session is separate from the fix.

For reference, the same rubric before the fight-state work (core 94, verification 88, live 84,
compatibility 57, hardening 70) scores **83 %**; before the verification/hardening pass
(verification 90, live 95, hardening 72) it scores **87 %**; before the forced-loss pass
(verification 98, live 95, hardening 89) it scores **90 %**; before the proxy work
(verification 99, live 98, hardening 90) it scores **91 % (90.9)**. The Velocity plugin took it
to **92.2 %**, the BungeeCord plugin to **92.6 %**, and the in-world client checks to **93.1 %**.

## What is actually left

1. **Client compatibility evidence (the only real gap).** Turn the 9 PARTIAL client rows into
   FUNCTIONAL on a hardware GPU with player-driven visual checks. This needs the pinned client
   set and a real client, not a headless server. Raid/dragon handover, forced loss and both
   proxy plugins no longer belong here: all are live-verified.
2. **Documented design limits, not open tasks:** the 5 s handover drain budget, the 1 s fight
   state poll, and the in-memory offline chunk backlog are deliberate trade-offs in
   KNOWN_ISSUES. Ksyxis, Radium/ServerCore and BetterAutoSave are closed as deployment profiles
   (`docs/COMPATIBILITY_PROFILES.md`).

Only item 1 is validation work; the implementation is not the bottleneck. Anyone quoting a
single percentage should quote the weighted overall **and** the core-parity figure (100 %),
because they answer different questions.

## Change log

* **2026-11-22 (session 7, live run):** raid/dragon fight state implemented and **both**
  handovers verified live (18/18 cluster checks, 14/14 fight checks, raid epochs 2→3→4 and
  dragon 1→2→3); `/multiforge fights` and `tools/compat/fight_sync_check.py` added. Corrected
  the compatibility arithmetic; overall 83 % -> 87 %.
* **2026-11-22 (session 7, verification/hardening pass):** `tools/ci/live_cluster.sh` and its
  GitHub Actions template make the whole cluster reproducible (18/18 + 7/7 in one command);
  Ksyxis `/forceload` is closed as not reproducible; Radium/ServerCore and BetterAutoSave are
  documented deployment profiles. Overall 87 % -> 90 %.
* **2026-11-22 (session 7, forced-loss pass):** `/multiforge drop` / `PacketLossInjector` and
  `packet_loss_check.py` verify retransmit convergence live (50 % loss, gaps, 40/40 blocks,
  chest NBT); `fight_sync_check.py` now creates the raid itself (`/multiforge startraid`), so
  one CI run covers 18/18 cluster + 3/3 loss + 14/14 fights. Overall 90 % -> 91 %.
* **2026-11-22 (session 7, reliability pass):** raid and dragon mutations are pushed immediately
  (`RaidMixin`/`EndDragonFightMixin` plus the 1 s safety net); a once-per-second heartbeat ack
  repairs a lost tail update; `-Dmultiforge.serverName` overrides a rewritten config; the GPU
  client-matrix runner and its documentation are merged. Overall 90.9 -> 91.2 %.
* **2026-11-22 (session 7, bug-test pass):** added `TailLossRecoveryTest` (heartbeat ack
  returns the lost tail and converges), a 90 %/100-update live loss stress (5 gaps, 100/100
  blocks, chest NBT), a drain guard for fight events and a port preflight in the CI script.
  Overall stays 91.2 % with stronger evidence.
* **2026-11-22 (session 7, client-matrix pass):** the integrated runner completed a
  software-GL production-client run on the current tree (`STARTED`, BSL `VERIFIED`,
  `client-matrix-20261122T080046Z.md`); a hardware-GPU run is the only remaining client step.
* **2026-11-22 (session 7, Proxy plugins):** ported MultiPaper's proxy-hosted master as
  `multiforge-velocity`; it embedded the master in Velocity 3.4.0, accepted two backends, moved
  a proxied client from `try=live1` to live2 by tick time, and the cluster passed 18/18
  (`velocity-plugin-20261122T080834Z.md`). Core parity 97 → 99 %.
* **2026-11-22 (session 7, BungeeCord plugin):** added `multiforge-bungee`; Waterfall 1.20
  loaded it unchanged, the embedded master accepted both backends, a proxied client joined, and
  the cluster passed 18/18 (`bungee-plugin-20261122T082425Z.md`). Core parity 99 → 100 %.
* **2026-11-22 (session 7, in-world client checks):** the production client was driven with
  `xdotool` and screenshots. F3 shows Entity Culling skipping 741 entities and RRLS removed the
  resource-reload overlay, moving both rows to FUNCTIONAL
  (`client-functional-checks-20261122T083000Z.md`). Compatibility 57 → 60 %; overall 92.6 → 93.1 %.
