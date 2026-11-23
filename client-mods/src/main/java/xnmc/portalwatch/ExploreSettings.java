package xnmc.portalwatch;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ExploreSettings {
    enum Pattern { TRAIL("Chunk trail"), SPIRAL("Spiral sweep");
        final String label; Pattern(String label) { this.label = label; }
        Pattern next() { return values()[(ordinal()+1)%values().length]; }
    }
    enum Bias { OUTWARD("Away from spawn"), INWARD("Toward spawn"), NEUTRAL("No spawn bias");
        final String label; Bias(String label) { this.label = label; }
        Bias next() { return values()[(ordinal()+1)%values().length]; }
    }
    Pattern pattern = Pattern.TRAIL;
    Bias bias = Bias.OUTWARD;
    // -1 follows just inside the visible edge: keep two chunks of visibility beyond the trail edge.
    int inset = -1;
    int insetChunks(int viewDistance) { return inset < 0 ? Math.max(1, Math.min(12, viewDistance-2)) : Math.min(inset, Math.max(0,viewDistance-1)); }
    String insetLabel() { return inset < 0 ? "Automatic (view distance - 2)" : inset + " chunks"; }
    void cycleInset() { inset = switch(inset) { case -1 -> 0; case 0 -> 2; case 2 -> 4; default -> -1; }; }
    void load(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path)) { p.load(r); }
        Pattern nextPattern = Pattern.valueOf(p.getProperty("pattern","TRAIL"));
        Bias nextBias = Bias.valueOf(p.getProperty("bias","OUTWARD"));
        int nextInset = Math.max(-1, Math.min(12,Integer.parseInt(p.getProperty("inset","-1"))));
        pattern=nextPattern; bias=nextBias; inset=nextInset;
    }
    void save(Path path) throws IOException {
        Properties p = new Properties();
        p.setProperty("pattern",pattern.name()); p.setProperty("bias",bias.name()); p.setProperty("inset",Integer.toString(inset));
        Files.createDirectories(path.getParent());
        try (Writer w = Files.newBufferedWriter(path)) { p.store(w,"Portal Watch exploration"); }
    }
}
