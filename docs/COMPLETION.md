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
| Automated verification | 99 % | 135 tests pass (protocol 72, master 56, api 4, example 3); full `:multiforge-forge:build` including reobf; a real Forge 47.4.29 dev server starts with all 35 mixins applied; `tools/ci/live_cluster.sh` booted master + two servers + bots and passed **18/18 cluster + 3/3 forced-loss + 14/14 fight** end to end, with a GitHub Actions template. Missing: no unit test covers the Minecraft-facing managers, and the workflow has not run on GitHub yet. |
| Live cluster evidence | 98 % | 29 transcripts in `compat/evidence/`, including the 18/18 two-server run, 14/14 raid + dragon handovers with an auto-created raid, 3/3 forced-loss convergence (`packet-loss-20261122T072758Z.md`) and the mod-profile run. Missing: client rendering evidence (hardware GPU). |
| Required-mod compatibility | 57 % | 9 FUNCTIONAL, 22 PARTIAL, 4 LOADING of 35. The stated rule (FUNCTIONAL 1, PARTIAL 1/2, LOADING 0) gives (9 + 11) / 35 = 57 %. |
| Production hardening | 90 % | Backlog recovery, sequence/acks (live under 50 % loss), fuzzing and bounded buffers are done; config reload leaves only name/port restart-only; Ksyxis/Radium/ServerCore/BetterAutoSave are closed as documented profiles. Remaining: handover and polled-fight limits, in-memory offline backlog. |
| **Weighted overall** | **91 %** | 0.40·97 + 0.20·99 + 0.15·98 + 0.15·57 + 0.10·90 = 90.9 |

Two honest caveats:

* **Strict compatibility view.** The compatibility legend also has FULLY COMPATIBLE (every
  relevant behaviour verified); that count is still **0/35**. Scoring only that column would
  put compatibility at 0 % and the overall at 82 %. The 57 % figure credits a target whose
  main functionality was exercised and marked FUNCTIONAL, and half for a PARTIAL row; that is
  the rule written at the top of this file.
* **Correction.** The previous revision of this file reported compatibility 30 % and overall
  81 %. That was an arithmetic error: applying the stated rule to 9 FUNCTIONAL + 22 PARTIAL
  gives 57 %, not 30 %. The live evidence added in this session is separate from the fix.

For reference, the same rubric before the fight-state work (core 94, verification 88, live 84,
compatibility 57, hardening 70) scores **83 %**; before the verification/hardening pass
(verification 90, live 95, hardening 72) it scores **87 %**; before this final pass
(verification 98, live 95, hardening 89) it scores **90 %**.

## What is actually left

1. **Client compatibility evidence (largest gap).** Turn the 11 PARTIAL client rows into
   FUNCTIONAL on a hardware GPU with player-driven visual checks. This needs the pinned client
   set and a real client, not a headless server. Raid/dragon handover and forced loss no longer
   belong here: both are live-verified.
2. **Proxy-side master plugin (last parity item, optional).** MultiForge has a built-in proxy
   and documents external BungeeCord/Velocity deployments; MultiPaper's plugin that runs the
   master inside the proxy is not ported.
3. **Documented design limits, not open tasks:** the 5 s handover drain budget, the 1 s fight
   state poll, and the in-memory offline chunk backlog are deliberate trade-offs in
   KNOWN_ISSUES. Ksyxis, Radium/ServerCore and BetterAutoSave are closed as deployment profiles
   (`docs/COMPATIBILITY_PROFILES.md`).

Only item 1 is validation work; the implementation is not the bottleneck. Anyone quoting a
single percentage should quote the weighted overall **and** the core-parity figure (97 %),
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
  `packet_loss_check.py` verify retransmit convergence live (50 % loss, 23 gaps, 40/40 blocks,
  chest NBT); `fight_sync_check.py` now creates the raid itself (`/multiforge startraid`), so
  one CI run covers 18/18 cluster + 3/3 loss + 14/14 fights. Overall 90 % -> 91 %.
