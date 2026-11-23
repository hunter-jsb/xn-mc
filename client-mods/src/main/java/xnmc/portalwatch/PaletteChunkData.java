package xnmc.portalwatch;

import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Read both XaeroPlus caches directly; screen colors and inverse display settings do not change classification. */
final class PaletteChunkData {
    private final Object module;
    private final Method enabled, fresh, generated;
    PaletteChunkData() throws ReflectiveOperationException {
        Class<?> palette = Class.forName("xaeroplus.module.impl.PaletteNewChunks");
        module = Class.forName("xaeroplus.module.ModuleManager").getMethod("getModule", Class.class).invoke(null, palette);
        if (module == null) throw new IllegalStateException("XaeroPlus Palette NewChunks module isn't ready");
        enabled = palette.getMethod("isEnabled");
        fresh = palette.getMethod("isNewChunk", int.class, int.class, ResourceKey.class);
        generated = palette.getMethod("isInverseNewChunk", int.class, int.class, ResourceKey.class);
    }
    boolean enabled() throws ReflectiveOperationException { return (boolean) enabled.invoke(module); }
    Map<FrontierPlanner.Chunk, FrontierPlanner.Kind> snapshot(ResourceKey<Level> dimension, int x, int z, int radius) throws ReflectiveOperationException {
        Map<FrontierPlanner.Chunk, FrontierPlanner.Kind> map = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            boolean n = (boolean) fresh.invoke(module, x + dx, z + dz, dimension);
            boolean o = (boolean) generated.invoke(module, x + dx, z + dz, dimension);
            map.put(new FrontierPlanner.Chunk(x + dx, z + dz), n == o ? FrontierPlanner.Kind.UNKNOWN
                    : n ? FrontierPlanner.Kind.FRESH : FrontierPlanner.Kind.GENERATED);
        }
        return map;
    }
}
