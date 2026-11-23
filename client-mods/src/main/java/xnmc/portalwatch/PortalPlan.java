package xnmc.portalwatch;

import java.util.ArrayList;
import java.util.List;

/** A cornerless ten-obsidian frame, with four ordinary-block corners. No game state. */
public record PortalPlan(int x, int z, boolean alongX, int y) {
    public static final int Y = 128;
    public PortalPlan(int x, int z, boolean alongX) { this(x,z,alongX,Y); }
    public record Cell(int x, int y, int z, boolean obsidian) {}
    public List<Cell> frame() {
        List<Cell> cells = new ArrayList<>();
        for (int dy = 0; dy < 5; dy++) for (int u = 0; u < 4; u++) {
            if (dy != 0 && dy != 4 && u != 0 && u != 3) continue;
            boolean corner = (dy == 0 || dy == 4) && (u == 0 || u == 3);
            cells.add(new Cell(x + (alongX ? u : 0), y + dy, z + (alongX ? 0 : u), !corner));
        }
        return cells;
    }
    /** Try the closest clear site first; frame and standing space all remain in the found chunk. */
    public static List<PortalPlan> sites(int portalX, int portalZ) {
        return sites(portalX,portalZ,Y);
    }
    public static List<PortalPlan> sites(int portalX, int portalZ, int y) {
        int minX = Math.floorDiv(portalX, 16) * 16, minZ = Math.floorDiv(portalZ, 16) * 16;
        List<PortalPlan> sites = new ArrayList<>();
        for (int u = 2; u <= 11; u++) for (int v = 2; v <= 13; v++) {
            sites.add(new PortalPlan(minX + u, minZ + v, true,y));
            sites.add(new PortalPlan(minX + v, minZ + u, false,y));
        }
        sites.sort(java.util.Comparator.comparingInt(p -> Math.abs(p.x - portalX) + Math.abs(p.z - portalZ)));
        return sites;
    }
    public int standX() { return x + (alongX ? 1 : -2); }
    public int standZ() { return z + (alongX ? -2 : 1); }
    public int interiorX() { return x + (alongX ? 1 : 0); }
    public int interiorZ() { return z + (alongX ? 0 : 1); }
}
