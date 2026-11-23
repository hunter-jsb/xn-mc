package xnmc.portalwatch;

import java.lang.reflect.Field;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/** Check the optional adapter against the installed Baritone API without starting a game or path. */
public final class PortalApiTest {
    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName("baritone.api." + name, false, PortalApiTest.class.getClassLoader());
    }
    public static void main(String[] args) throws Exception {
        type("BaritoneAPI").getMethod("getProvider");
        type("BaritoneAPI").getMethod("getSettings");
        type("IBaritoneProvider").getMethod("getPrimaryBaritone");
        for (String method : new String[]{"getPathingBehavior", "getCustomGoalProcess", "getBuilderProcess"}) type("IBaritone").getMethod(method);
        type("behavior.IPathingBehavior").getMethod("cancelEverything");
        type("pathing.goals.GoalBlock").getConstructor(BlockPos.class);
        type("process.ICustomGoalProcess").getMethod("setGoalAndPath", type("pathing.goals.Goal"));
        type("process.IBuilderProcess").getMethod("build", String.class, type("schematic.ISchematic"), Vec3i.class);
        for (String field : new String[]{"allowBreak", "allowPlace", "allowInventory", "allowDownward", "allowParkour", "buildRepeat", "buildInLayers", "acceptableThrowawayItems"}) {
            Field setting = type("Settings").getField(field);
            if (setting.getType() != type("Settings$Setting")) throw new AssertionError("Changed setting API: " + field);
        }
        type("Settings$Setting").getField("value");
        var expected = java.util.Set.of("inSchematic", "size", "desiredState", "reset", "widthX", "heightY", "lengthZ");
        for (var method : type("schematic.ISchematic").getMethods())
            if (!expected.contains(method.getName())) throw new AssertionError("Schematic adapter missing method: " + method);
        for (int baseY : new int[]{-32,64,128,200}) for (boolean alongX : new boolean[]{true, false}) {
            java.util.List<BlockPos> called = new java.util.ArrayList<>();
            Object schematic = PortalBuilderBridge.schematic(new PortalPlan(-30, 18, alongX,baseY), pos -> { called.add(pos); return null; });
            Class<?> api = type("schematic.ISchematic");
            if (!api.getMethod("widthX").invoke(schematic).equals(alongX ? 4 : 1)
                    || !api.getMethod("lengthZ").invoke(schematic).equals(alongX ? 1 : 4)
                    || !api.getMethod("heightY").invoke(schematic).equals(5)) throw new AssertionError("Wrong schematic dimensions");
            api.getMethod("inSchematic", int.class, int.class, int.class, net.minecraft.world.level.block.state.BlockState.class)
                    .invoke(schematic, 1, 2, 3, null);
            if (!called.getLast().equals(new BlockPos(-29, baseY+2, 21))) throw new AssertionError("Wrong relative-to-world coordinate translation");
            api.getMethod("desiredState", int.class, int.class, int.class, net.minecraft.world.level.block.state.BlockState.class, java.util.List.class)
                    .invoke(schematic, 0, 0, 0, null, java.util.List.of());
            if (!called.getLast().equals(new BlockPos(-30, baseY, 18))) throw new AssertionError("Wrong origin");
            api.getMethod("reset").invoke(schematic);
        }
        System.out.println("PASS: installed Baritone builder/goal/settings API matches the roof builder adapter");
    }
}
