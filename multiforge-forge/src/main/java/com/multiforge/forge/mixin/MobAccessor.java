package com.multiforge.forge.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code Mob.restoreLeashFromSave} to entity mirroring.
 *
 * <p>Entity NBT stores a leash as a UUID that is resolved when the entity is
 * loaded. Vanilla calls {@code restoreLeashFromSave} from its own load path; a
 * copy created from an entity packet goes through
 * {@code EntityType.loadEntityRecursive} instead, which leaves the leash tag
 * unresolved. The mirror calls this invoker after adding the copy so a leashed
 * mob appears leashed on every server, not only after the leash changes
 * again.</p>
 *
 * <p>Mixin invoker interfaces are implemented by {@link Mob}, so callers cast
 * the mob to this interface. Server thread only (it touches world state).</p>
 */
@Mixin(Mob.class)
public interface MobAccessor {

    /**
     * Resolves the leash stored in the entity's NBT.
     */
    @Invoker("restoreLeashFromSave")
    void multiforge$restoreLeashFromSave();
}
