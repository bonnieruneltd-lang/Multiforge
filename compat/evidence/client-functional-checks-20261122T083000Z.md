# Client functional checks 2026-11-22

Production Forge 47.4.29 client (all pinned client mods + MultiForge) in-world on a MultiForge
dev server, Xvfb `:99`, Mesa llvmpipe, BSL/Oculus active. Software GL cannot judge shader
fidelity, but the two behaviours below do not depend on GPU quality.

## Entity Culling 1.11.3 — FUNCTIONAL

The in-game F3 overlay exposes the mod's own counters while the client is in a world with
entities (including mirrored ones):

```
[Culling] Last pass: 0ms/0ms
[Culling] Rendered Block Entities: 0 Skipped: 0
[Culling] Rendered Entities: 36 Skipped: 741
[Culling] Ticked Entities: 19 Skipped: 741
```

The mod is actively skipping 741 entities per pass, which is its main function. Screenshot:
`compat/evidence/client-f3-entity-culling-20261122T083000Z.png`.

## Remove Reloading Screen 4.0.6.1 — FUNCTIONAL (resource reload)

`F3+T` was sent to the client while in-world and the screen was captured every 150 ms. The
resource reload completed with **no "Reloading Resource Packs" overlay**; the world stayed
visible and the debug line `[Debug]: Reloaded resource packs` appeared. Screenshot:
`compat/evidence/client-f3-rrls-20261122T083000Z.png`.

The server-switch loading overlay was not exercised; that is the remaining edge in the matrix
row.

## Remaining client rows

Shader fidelity, leaf culling, Dynamic FPS throttling, Kerria/OBE/BadOptimizations performance
and shader-pack compilation still need a hardware GPU. The matrix entries and their notes were
updated; only the two behaviours above moved from PARTIAL to FUNCTIONAL.
