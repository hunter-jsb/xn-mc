package xnmc.portalwatch;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import xnmc.portalwatch.FrontierPlanner.Chunk;

/** Chunks actually scanned by this client, separate from XaeroPlus generation guesses. */
final class ExploreHistory {
    final Set<Chunk> scanned = new HashSet<>();
    private final Path file;
    private boolean dirty;
    ExploreHistory(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) try (var lines = Files.lines(file)) {
            lines.forEach(line -> {
                String[] c = line.split(",");
                if (c.length == 2) try { scanned.add(new Chunk(Integer.parseInt(c[0]),Integer.parseInt(c[1]))); }
                catch (NumberFormatException ignored) {}
            });
        }
    }
    void record(int x, int z) { dirty |= scanned.add(new Chunk(x,z)); }
    void save() throws IOException {
        if (!dirty) return;
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(),"coverage-",".tmp");
        try {
            try (var out = Files.newBufferedWriter(temp)) {
                for (Chunk c : scanned) { out.write(c.x()+","+c.z()); out.newLine(); }
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } finally { Files.deleteIfExists(temp); }
    }
    void clear() throws IOException { scanned.clear(); dirty=true; save(); }
}
