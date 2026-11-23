package xnmc.portalwatch;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

final class PortalGroups {
    static List<BlockPos> group(Set<BlockPos> input) {
        Set<BlockPos> blocks = new HashSet<>(input);
        List<BlockPos> portals = new ArrayList<>();
        while (!blocks.isEmpty()) {
            BlockPos first = blocks.stream().min(Comparator.<BlockPos>comparingInt(p -> p.getY())
                    .thenComparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ())).orElseThrow();
            portals.add(first);
            Queue<BlockPos> flood = new ArrayDeque<>(); flood.add(first); blocks.remove(first);
            while (!flood.isEmpty()) {
                BlockPos p = flood.remove();
                for (Direction direction : Direction.values()) {
                    BlockPos next = p.relative(direction);
                    if (blocks.remove(next)) flood.add(next);
                }
            }
        }
        return portals;
    }
}
