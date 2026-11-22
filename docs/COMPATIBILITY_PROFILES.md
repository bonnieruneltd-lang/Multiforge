# Compatibility profiles

This document records the deployment choice for the performance mods whose mixins overlap.
They are **deployment profiles**, not MultiForge config flags: the mods' mixin priorities are
static, so MultiForge cannot switch an optimisation back on at runtime. The server is correct
in every profile; only the set of active optimisations differs.

Tested on Forge 47.4.29 / Java 17.0.21 with the pinned artifacts from
`compat/required-mods.json`. Evidence: `compat/evidence/mod-profile-20261122T070103Z.md`,
`docs/REQUIRED_MOD_COMPATIBILITY.md`, and the production runs in `compat/evidence/`.

## Profile "radium"

**Install:** Radium 0.12.4, no ServerCore.

* All Radium/Lithium optimisations are active.
* BetterAutoSave disables its synchronous chunk-load detector (Radium replaces
  `ServerChunkCache.getChunk`); BetterAutoSave's own diagnostic says so. The save pipeline and
  `save-all flush` still work.
* Best choice when Radium is already the server's performance standard.

## Profile "servercore"

**Install:** ServerCore 1.5.2+1.20.1, no Radium.

* All ServerCore optimisations are active, including the chunk-cache redirect.
* BetterAutoSave keeps its synchronous chunk-load detector.
* Best choice when ServerCore is the standard and the world relies on that detector.

## Profile "both" (supported, not recommended)

Installing both is **correct** but not additive: ServerCore's
`ServerChunkCacheMixin->@Redirect::servercore$noList` is skipped because Radium's lithium
redirect has priority 1000. The server logs the conflict once and stays at 20 TPS. There is no
config value that restores the skipped optimisation; choose one of the two profiles above if
you need every optimisation active.

## BetterAutoSave

Supported on a production Forge installation: pipeline installed, chunk workers started,
`save-all flush` completed, graceful shutdown. The `level.dat ... MISSING` line during startup
is cosmetic — MultiForge downloads `level.dat` from the master in `loadLevel`, after
BetterAutoSave's first probe (KNOWN_ISSUES 17).

In a **ForgeGradle dev run** BetterAutoSave 0.20.2 fails to apply its mixin
(`@Shadow m_129903_` not located) because its production refmap uses SRG names against the
official-mapped dev jar. Test it in a production install, as the compatibility matrix does.

## Ksyxis

Keep it. The session-3 finding that `/forceload` tickets vanished did **not** reproduce with
Ksyxis 1.4.6 on Forge 47.4.29: `forceload add 0 0` survived `forceload query` after three
seconds and the chunk stayed loaded (`mod-profile-20261122T070103Z.md`). If it recurs, remove
Ksyxis from the servers used by `tools/compat/cluster_check.py`, as the old workaround did; the
harness only needs the mods for the required-target rows, not for the chunk checks.

## Why Radium/ServerCore cannot be exercised in a dev run

`lithium.mixins.json:math.sine_lut.MixinMathHelper` targets the SRG name `m_14031_`, which the
official-mapped dev jar does not contain, so `runServer` aborts at mixin application. This is a
dev-run refmap limitation, not a MultiForge bug. Use a production Forge installation for any
compatibility conclusion about these mods.
