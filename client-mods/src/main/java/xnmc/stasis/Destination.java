package xnmc.stasis;

import java.util.Collection;
import java.util.Locale;

/** A window measured as client disconnect -> reconnect attempt, not server ticks. Zero means draft. */
public record Destination(String server, String name, long minWaitMs, long maxWaitMs) {
    public Destination {
        if (server == null || server.isBlank()) throw new IllegalArgumentException("Missing server.");
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Use a name of 1–32 letters, digits, hyphens or underscores.");
        name = name.toLowerCase(Locale.ROOT);
        if (!(minWaitMs == 0 && maxWaitMs == 0)
                && (minWaitMs < 250 || maxWaitMs > 600_000 || maxWaitMs - minWaitMs < 500))
            throw new IllegalArgumentException("Window must be within 0.25–600 seconds, at least 0.5 seconds wide.");
    }

    public boolean configured() { return minWaitMs != 0; }
    public long targetMs() { return minWaitMs + (maxWaitMs - minWaitMs) / 2; }

    public void checkOverlap(Collection<Destination> others) {
        if (!configured()) return;
        for (var other : others) {
            if (!other.configured() || !server.equals(other.server) || name.equals(other.name)) continue;
            // Leave at least one second between measured windows for different destinations.
            if (minWaitMs <= other.maxWaitMs + 1000 && maxWaitMs + 1000 >= other.minWaitMs)
                throw new IllegalArgumentException("Window overlaps or is within 1 second of " + other.name + ".");
        }
    }
}
