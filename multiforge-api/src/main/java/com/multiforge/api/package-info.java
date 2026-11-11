/**
 * Stable, Minecraft independent API of MultiForge.
 *
 * <p>Third party mods should depend on this module (or on the API classes
 * bundled in the MultiForge mod jar) and look up the live implementation with
 * {@code Services.get(MultiForgeApi.class)} from the Forge module, or through
 * {@link com.multiforge.api.MultiForgeApiProvider} when running outside the
 * game (tests, tooling).</p>
 */
package com.multiforge.api;
