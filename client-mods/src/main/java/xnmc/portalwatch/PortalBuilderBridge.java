package xnmc.portalwatch;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import xnmc.Baritone;

/** Optional Baritone API integration; no packaged Baritone dependency or server chat commands. */
final class PortalBuilderBridge implements AutoCloseable {
    private final Object baritone;
    private final Map<Object, Object> settings = new LinkedHashMap<>();
    private final Field value;
    private boolean closed;
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName("baritone.api." + name); }
    private static Object invoke(String iface, Object obj, String name) throws ReflectiveOperationException {
        return type(iface).getMethod(name).invoke(obj);
    }
    static void cancelCurrent() throws ReflectiveOperationException {
        Baritone.clearQueue();
        Object provider = type("BaritoneAPI").getMethod("getProvider").invoke(null);
        Object b = invoke("IBaritoneProvider", provider, "getPrimaryBaritone");
        invoke("behavior.IPathingBehavior", invoke("IBaritone", b, "getPathingBehavior"), "cancelEverything");
    }
    PortalBuilderBridge(List<Item> supports) throws ReflectiveOperationException {
        Object provider = type("BaritoneAPI").getMethod("getProvider").invoke(null);
        baritone = invoke("IBaritoneProvider", provider, "getPrimaryBaritone");
        value = type("Settings$Setting").getField("value");
        Object config = type("BaritoneAPI").getMethod("getSettings").invoke(null);
        try {
            set(config, "allowBreak", false);
            set(config, "allowPlace", true);
            set(config, "allowInventory", true);
            set(config, "allowDownward", false);
            set(config, "allowParkour", false);
            set(config, "buildRepeat", Vec3i.ZERO);
            set(config, "buildInLayers", false);
            set(config, "acceptableThrowawayItems", new ArrayList<>(supports));
        } catch (ReflectiveOperationException e) { close(); throw e; }
    }
    private void set(Object config, String name, Object next) throws ReflectiveOperationException {
        Object setting = type("Settings").getField(name).get(config);
        settings.put(setting, value.get(setting));
        value.set(setting, next);
    }
    void go(BlockPos pos) throws ReflectiveOperationException {
        Object goal = type("pathing.goals.GoalBlock").getConstructor(BlockPos.class).newInstance(pos);
        Object process = invoke("IBaritone", baritone, "getCustomGoalProcess");
        type("process.ICustomGoalProcess").getMethod("setGoalAndPath", type("pathing.goals.Goal")).invoke(process, goal);
    }
    void build(PortalPlan plan, Function<BlockPos, BlockState> desired) throws ReflectiveOperationException {
        Object builder = invoke("IBaritone", baritone, "getBuilderProcess");
        type("process.IBuilderProcess").getMethod("build", String.class, type("schematic.ISchematic"), Vec3i.class)
                .invoke(builder, "XNMC portal", schematic(plan, desired), new BlockPos(plan.x(), plan.y(), plan.z()));
    }
    static Object schematic(PortalPlan plan, Function<BlockPos, BlockState> desired) throws ReflectiveOperationException {
        Class<?> schematicType = type("schematic.ISchematic");
        return Proxy.newProxyInstance(schematicType.getClassLoader(), new Class<?>[]{schematicType}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "widthX" -> plan.alongX() ? 4 : 1;
                case "heightY" -> 5;
                case "lengthZ" -> plan.alongX() ? 1 : 4;
                case "desiredState" -> {
                    BlockState state = desired.apply(new BlockPos(plan.x() + (int) args[0], plan.y() + (int) args[1], plan.z() + (int) args[2]));
                    yield state == null ? args[3] : state;
                }
                case "inSchematic" -> desired.apply(new BlockPos(plan.x() + (int) args[0], plan.y() + (int) args[1], plan.z() + (int) args[2])) != null;
                case "size" -> switch ((net.minecraft.core.Direction.Axis) args[0]) {
                    case X -> plan.alongX() ? 4 : 1;
                    case Y -> 5;
                    case Z -> plan.alongX() ? 1 : 4;
                };
                case "reset" -> null;
                case "toString" -> "XNMC portal";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            };
        });
    }
    void cancel() throws ReflectiveOperationException {
        invoke("behavior.IPathingBehavior", invoke("IBaritone", baritone, "getPathingBehavior"), "cancelEverything");
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try { cancel(); } catch (ReflectiveOperationException ignored) {}
        for (var entry : settings.entrySet()) try { value.set(entry.getKey(), entry.getValue()); }
        catch (IllegalAccessException e) { System.getLogger("xnmc/portalwatch").log(System.Logger.Level.ERROR, "Could not restore Baritone setting", e); }
        settings.clear();
    }
}
