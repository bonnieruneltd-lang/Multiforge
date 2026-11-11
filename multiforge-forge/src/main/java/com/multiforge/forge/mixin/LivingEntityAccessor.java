package com.multiforge.forge.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code LivingEntity.DATA_HEALTH_ID} so mirrored metadata can update
 * the server-side health field as well as the synched data.
 *
 * <p>Vanilla stores health twice: the {@code health} field used by game logic and
 * the synched data value sent to clients. Assigning a metadata packet only
 * updates the latter. Without the accessor a mirrored mob would show the right
 * hearts but keep stale health for damage and death checks.</p>
 *
 * <p>Mixin accessor interfaces are implemented by the target class, so callers
 * cast a {@link LivingEntity} to this interface. The accessor only reads a
 * constant, so it is safe from any thread, although the manager calls it on the
 * server thread.</p>
 */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {

    /**
     * @return the health data accessor (a static field in {@code LivingEntity})
     */
    @Accessor("DATA_HEALTH_ID")
    static EntityDataAccessor<Float> multiforge$dataHealthId() {
        throw new AssertionError("Mixin accessor was not applied");
    }
}
