package dev.fieldnotes.guard;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;
import static java.nio.file.StandardOpenOption.*;

/** Missing state is OFF; malformed/unreadable existing state is LOCKED. */
public final class StateFile implements Guard.Store {
    private final Path path;
    public StateFile(Path path) { this.path = path; }
    public Guard load() {
        if (Files.notExists(path)) return new Guard(this, Guard.Mode.OFF, "Disabled");
        try (Reader r = Files.newBufferedReader(path)) {
            Properties p = new Properties(); p.load(r);
            if (!"1".equals(p.getProperty("schema"))) throw new IOException("Unknown schema");
            return new Guard(this, Guard.Mode.valueOf(p.getProperty("mode")), p.getProperty("reason", "Inspect before reconnecting"));
        } catch (Exception e) { return new Guard(this, Guard.Mode.LOCKED, "Cannot read safety state; inspect before reconnecting"); }
    }
    public void save(Guard.Mode mode, String reason) throws IOException {
        Files.createDirectories(path.getParent());
        Properties p = new Properties();
        p.setProperty("schema", "1"); p.setProperty("mode", mode.name()); p.setProperty("reason", reason);
        Path tmp = Files.createTempFile(path.getParent(), "afk-state-", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, "Fieldnotes AFK Guard: do not clear a safety lock automatically"); }
            try (FileChannel channel = FileChannel.open(tmp, WRITE)) { channel.force(true); }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(path.getParent(), READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
