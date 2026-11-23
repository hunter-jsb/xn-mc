package xnmc.portalwatch;

public enum PortalMode {
    ROOF("Roof portals only", "roof"), BELOW("Below-roof portals only", "below"), BOTH("All Nether portals", "both");

    public final String label, command;
    PortalMode(String label, String command) { this.label = label; this.command = command; }
    public PortalMode next() { return values()[(ordinal() + 1) % values().length]; }
    public boolean accepts(boolean nether, int portalY) {
        return !nether || this == BOTH || (this == ROOF ? portalY >= 128 : portalY < 128);
    }
}
