package xnmc.portalwatch;

import java.util.*;
import net.minecraft.core.BlockPos;

public final class PortalTest {
    private static int checks;
    private static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        var commands=PortalWatch.commands().build();
        for(String removed:new String[]{"resume","scan","buildabove","mode"})
            check(commands.getChild(removed)==null,"Removed primary command: "+removed);
        check(commands.getChild("build")!=null,"One manual portal build command remains");
        var modeCommands=commands.getChild("settings").getChild("mode");
        check(modeCommands!=null && modeCommands.getChild("roof")!=null && modeCommands.getChild("below")!=null && modeCommands.getChild("both")!=null,
                "Portal modes live under settings");
        check(PortalWatch.announcement(true,PortalMode.BOTH.label,PortalWatch.trailStrategy(ExploreSettings.Bias.INWARD))
                .equals("On · All Nether portals · Trail toward spawn"),"Startup says on/off, scope, and actual strategy");
        check(PortalWatch.announcement(false,PortalMode.ROOF.label,"Spiral sweep").equals("Off · Roof portals only · Spiral sweep"),
                "Off message remains compact and describes portal scope");
        check(PortalMode.ROOF.accepts(true, 128), "Roof includes the Y128 boundary");
        check(!PortalMode.ROOF.accepts(true, 127), "Roof excludes below-bedrock portals");
        check(PortalMode.BELOW.accepts(true, 127), "Below includes Y127");
        check(!PortalMode.BELOW.accepts(true, 128), "Below excludes the roof boundary");
        for (PortalMode mode : PortalMode.values()) {
            check(mode.accepts(false, 20) && mode.accepts(false, 180), "Nether filtering does not hide Overworld portals");
            check(mode.next().next().next() == mode, "Selector cycles back through all modes");
        }
        for (int x : new int[]{-30_000_000, -33, -17, -16, -15, -1, 0, 1, 15, 16, 17, 30_000_000})
            for (int z : new int[]{-17, -16, -1, 0, 15, 16, 17}) {
                List<PortalPlan> plans = PortalPlan.sites(x, z);
                check(plans.size() == 240, "Both orientations searched throughout the same chunk");
                for (PortalPlan p : plans) {
                    var frame = p.frame();
                    check(frame.size() == 14 && frame.stream().filter(PortalPlan.Cell::obsidian).count() == 10,
                            "Exactly ten obsidian and four support corners");
                    check(new HashSet<>(frame).size() == 14, "No overlapping frame cells");
                    check(frame.stream().allMatch(c -> c.y() >= 128 && c.y() <= 132), "All construction stays above bedrock");
                    check(frame.stream().allMatch(c -> Math.floorDiv(c.x(), 16) == Math.floorDiv(x, 16)
                            && Math.floorDiv(c.z(), 16) == Math.floorDiv(z, 16)), "Frame remains in the source chunk, including negative coordinates");
                    check(Math.floorDiv(p.standX(), 16) == Math.floorDiv(x, 16)
                            && Math.floorDiv(p.standZ(), 16) == Math.floorDiv(z, 16), "Standing position stays in the source chunk");
                    check(frame.stream().noneMatch(c -> c.x() == p.standX() && c.z() == p.standZ()), "Standing position is outside the portal");
                    check(frame.stream().noneMatch(c -> c.x() == p.interiorX() && c.z() == p.interiorZ() && c.y() == 129), "Ignition interior stays empty");
                    check(frame.stream().anyMatch(c -> c.obsidian() && c.x() == p.interiorX() && c.z() == p.interiorZ() && c.y() == 128), "Ignition clicks the obsidian floor");
                }
            }
        Set<BlockPos> portalBlocks = new HashSet<>();
        for(int baseY:new int[]{-32,64,128,200})for(var plan:PortalPlan.sites(-31,18,baseY)) {
            check(plan.frame().stream().allMatch(c->c.y()>=baseY && c.y()<=baseY+4),"Manual frame uses current build height");
            check(plan.frame().stream().filter(PortalPlan.Cell::obsidian).count()==10,"Manual frame still needs only ten obsidian");
        }
        for (int y : new int[]{20, 60, 129}) for (int h = 0; h < 3; h++) for (int u = 0; u < 2; u++) portalBlocks.add(new BlockPos(5 + u, y + h, 5));
        var groups = PortalGroups.group(portalBlocks);
        check(groups.size() == 3, "All three portal heights in one chunk remain discoverable");
        check(groups.stream().filter(p -> PortalMode.ROOF.accepts(true, p.getY())).count() == 1, "Roof search sees only the upper portal in a mixed chunk");
        check(groups.stream().filter(p -> PortalMode.BELOW.accepts(true, p.getY())).count() == 2, "Below search sees both lower portals in a mixed chunk");
        check(groups.stream().filter(p -> PortalMode.BOTH.accepts(true, p.getY())).count() == 3, "Both search preserves all heights");
        check(groups.stream().map(BlockPos::getY).toList().equals(List.of(20,60,129)), "Each connected group has a stable lowest representative");
        check(portalBlocks.size() == 18, "Grouping does not mutate input");
        check(PortalGroups.group(Set.of()).isEmpty(), "Empty chunks return no finds");
        check(PortalGroups.group(Set.of(new BlockPos(0,20,0),new BlockPos(1,21,0))).size() == 2,
                "Diagonal contact does not merge separate portals");
        check(PortalGroups.group(Set.of(new BlockPos(0,20,0),new BlockPos(0,21,0),new BlockPos(0,22,0))).size() == 1,
                "Vertically connected portal blocks are one portal");
        System.out.println("PASS: " + checks + " portal plan/grouping checks");
    }
}
