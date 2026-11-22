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
| Core parity implementation | 97 % | Everything except the MultiPaper-style proxy-side master plugin. Raids and the dragon fight are implemented (`FightSyncManager`, mixins, peer ids 37-38). |
| Automated verification | 90 % | 135 tests pass (protocol 72, master 56, api 4, example 3); full `:multiforge-forge:build` including reobf; a real Forge 47.4.29 dev server starts with all 35 mixins applied. Missing: a live cluster in CI, and no unit test can cover the Minecraft-facing managers. |
| Live cluster evidence | 95 % | 22 transcripts in `compat/evidence/`, including a fresh 18/18 two-server run and a 12/12 raid + dragon replication/handover run (`fight-sync-20261122T064859Z.md`). Missing: forced packet loss and client rendering evidence. |
| Required-mod compatibility | 57 % | 9 FUNCTIONAL, 22 PARTIAL, 4 LOADING of 35. The stated rule (FUNCTIONAL 1, PARTIAL 1/2, LOADING 0) gives (9 + 11) / 35 = 57 %. |
| Production hardening | 72 % | Backlog recovery, sequence/acks, fuzzing and bounded buffers are done; KNOWN_ISSUES still lists handover limits, polled fight state, config reload limits, Radium/Ksyxis interactions. |
| **Weighted overall** | **87 %** | 0.40·97 + 0.20·90 + 0.15·95 + 0.15·57 + 0.10·72 = 86.8 |

Two honest caveats:

* **Strict compatibility view.** The compatibility legend also has FULLY COMPATIBLE (every
  relevant behaviour verified); that count is still **0/35**. Scoring only that column would
  put compatibility at 0 % and the overall at 78 %. The 57 % figure credits a target whose
  main functionality was exercised and marked FUNCTIONAL, and half for a PARTIAL row; that is
  the rule written at the top of this file.
* **Correction.** The previous revision of this file reported compatibility 30 % and overall
  81 %. That was an arithmetic error: applying the stated rule to 9 FUNCTIONAL + 22 PARTIAL
  gives 57 %, not 30 %. The live evidence added in this session is separate from the fix.

For reference, the same rubric before the fight-state work (core 94, verification 88, live 84,
compatibility 57, hardening 70) scores **83 %**.

## What is actually left

1. **Client compatibility evidence (largest gap).** Turn the 11 PARTIAL client rows into
   FUNCTIONAL on a hardware GPU with player-driven visual checks. This needs the pinned client
   set and a real client, not a headless server. Raid/dragon handover no longer belongs here:
   both are live-verified.
2. **Live forced-loss run (+~0.5 point).** The acknowledgement/retransmit logic is already
   property-tested under random loss; a peer-level run with dropped frames would close it.
3. **Proxy-side master plugin (last parity item, optional).** MultiForge has a built-in proxy
   and documents external BungeeCord/Velocity deployments; MultiPaper's plugin that runs the
   master inside the proxy is not ported.
4. **Operational polish.** Radium/ServerCore profile decision, Ksyxis `/forceload`
   reproduction, config hot-reload leftovers (KNOWN_ISSUES 15, 16, 23).

Items 1-2 are validation work; the implementation is not the bottleneck. Anyone quoting a
single percentage should quote the weighted overall **and** the core-parity figure (97 %),
because they answer different questions.

## Change log

* **2026-11-22 (session 7, live run):** raid/dragon fight state implemented and **both**
  handovers verified live (18/18 cluster checks, 12/12 fight checks, raid epochs 7→8→9 and
  dragon 5→6→7); `/multiforge fights` and `tools/compat/fight_sync_check.py` added. Corrected
  the compatibility arithmetic; overall 83 % -> 87 %.
