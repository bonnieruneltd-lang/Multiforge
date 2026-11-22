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

## Current score (2026-11-22, after the fight-state port)

| Category | Score | Evidence / gap |
| --- | ---: | --- |
| Core parity implementation | 97 % | Everything except the MultiPaper-style proxy-side master plugin. Raids and the dragon fight were the last in-game gap and are now implemented (`FightSyncManager`, mixins, peer ids 37-38). |
| Automated verification | 90 % | 135 tests pass (protocol 72, master 56, api 4, example 3); full `:multiforge-forge:build` including reobf; a real Forge 47.4.29 dev server starts with all 35 mixins applied (`Mixin RaidMixin`, `RaidsAccessor`, `EndDragonFightMixin`, `RaidAccessor`). Missing: a live cluster in CI, and no unit test can cover the Minecraft-facing managers. |
| Live cluster evidence | 85 % | 15 transcripts in `compat/evidence/` (handover, mirroring, containers, weather, backlog, boss bars, ...). Missing: raid and dragon handover, forced live packet loss. |
| Required-mod compatibility | 30 % | Server 5 FUNCTIONAL / 11 PARTIAL / 4 LOADING; client 4 FUNCTIONAL / 11 PARTIAL. No row is FULLY COMPATIBLE. Halving PARTIAL gives 30 %. |
| Production hardening | 72 % | Backlog recovery, sequence/acks, fuzzing and bounded buffers are done; KNOWN_ISSUES still lists handover limits, polled fight state, config reload limits, Radium/Ksyxis interactions. |
| **Weighted overall** | **81 %** | 0.40·97 + 0.20·90 + 0.15·85 + 0.15·30 + 0.10·72 = 81.3 |

For reference, the same rubric before the fight-state port scored about **79 %** (core 94,
verification 88, live 84, compatibility 30, hardening 70).

## What is actually left

1. **Compatibility evidence (largest gap, +3 to +4 points).** Turn PARTIAL rows into
   FUNCTIONAL on a hardware GPU and with player-driven gameplay checks. This needs the pinned
   client/mod set and a real client, not a headless server.
2. **Live fight-handover transcript (+1 point).** Start a raid and a dragon fight on one
   server, walk a bot across the ownership boundary, and record that waves/phases continue
   exactly once. The code path exists and the mixins apply; only the transcript is missing.
3. **Live forced-loss run (+0.5 point).** The acknowledgement/retransmit logic is already
   property-tested under random loss; a peer-level run with dropped frames would close it.
4. **Proxy-side master plugin (last parity item, optional).** MultiForge has a built-in proxy
   and documents external BungeeCord/Velocity deployments; MultiPaper's plugin that runs the
   master inside the proxy is not ported.
5. **Operational polish.** Radium/ServerCore profile decision, Ksyxis `/forceload`
   reproduction, config hot-reload leftovers (KNOWN_ISSUES 15, 16, 23).

Items 1-3 are validation work; the implementation is not the bottleneck. Anyone quoting a
single percentage should quote the weighted overall **and** the core-parity figure (97 %),
because they answer different questions.
