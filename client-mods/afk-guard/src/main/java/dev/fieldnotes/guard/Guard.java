package dev.fieldnotes.guard;

import java.util.Locale;

/** Safety and retry policy. All calls are on the client thread. */
public final class Guard {
    public static final float MIN_HEALTH = 16; // Eight hearts, plus first-damage detection.
    public static final int MIN_FOOD = 12;     // Six drumsticks; auto-eat should act well before this.
    public static final int MAX_ATTEMPTS = 120;
    public static final String LIMIT = "12-hour limit reached";
    private boolean armed;
    private String reason = "off";
    private float previousHealth, previousAbsorption;
    private long deadline, nextRetry;
    private int attempts;
    private boolean retryChain;

    public boolean armed() { return armed; }
    public String reason() { return reason; }
    public long deadline() { return deadline; }
    public long nextRetry() { return nextRetry; }
    public int attempts() { return attempts; }

    public void arm(float health, float maxHealth, float absorption, int food, long now, long duration) {
        if (!Float.isFinite(health) || health < maxHealth || health <= MIN_HEALTH || food < 18)
            throw new IllegalStateException("heal fully and eat to at least nine drumsticks first");
        armed = true;
        reason = "armed";
        previousHealth = health;
        previousAbsorption = absorption;
        deadline = now + duration;
        nextRetry = 0;
        attempts = 0;
        retryChain = false;
    }

    // Every stop lands here. Off means nothing reconnects automatically; connecting yourself is never blocked.
    public void off(String why) {
        armed = false;
        reason = why;
        nextRetry = 0;
        retryChain = false;
    }

    /** True when the connection must close. No grace period after joining. */
    public boolean sample(float health, float absorption, int food, boolean hurt, long now) {
        if (!armed) return false;
        String trip = !Float.isFinite(health) || health <= MIN_HEALTH ? "health at or below eight hearts"
                : hurt || health < previousHealth || absorption < previousAbsorption ? "took damage"
                : food <= MIN_FOOD ? "food at or below six drumsticks"
                : now >= deadline ? LIMIT : null;
        // The baseline survives reconnects, so damage taken during the gap still trips.
        previousHealth = health;
        previousAbsorption = absorption;
        if (trip != null) off(trip);
        return trip != null;
    }

    public void disconnected(String message, long now) {
        if (!armed) return;
        if (now >= deadline) off(LIMIT);
        else if (!retryable(message, retryChain)) off("not reconnecting after: " + message);
        else if (attempts >= MAX_ATTEMPTS) off("gave up after " + MAX_ATTEMPTS + " reconnects");
        else {
            retryChain = true;
            nextRetry = now + Math.min(300_000, 30_000L << Math.min(attempts, 3));
        }
    }

    public boolean takeRetry(long now) {
        if (!armed || nextRetry == 0 || now < nextRetry) return false;
        if (now >= deadline) { off(LIMIT); return false; }
        nextRetry = 0;
        attempts++;
        return true;
    }

    public void joined() {
        nextRetry = 0;
        retryChain = false;
    }

    static boolean retryable(String message, boolean followingApprovedKick) {
        String m = message.toLowerCase(Locale.ROOT).replaceAll("§.", "").strip()
                .replaceFirst("^[✘✖✕×⚠!\\s]+", "").replaceFirst("[.!]+$", "").strip();
        // Rejections override all positive matching, even when a message also mentions a restart.
        if (m.matches("(?s).*(banned|ban hammer|died|death|hardcore|whitelist|not whitelisted|authentication|invalid session|duplicate login|logged in from|kicked by|afk guard|safety|damage).*")) return false;
        if (m.equals("server closed") || m.equals("server is restarting") || m.equals("server restarting")
                || m.equals("proxy shutting down")
                || m.equals("server is shutting down") || m.equals("server shutting down")
                || m.matches("(?s)^(the )?server (is )?(restarting|under maintenance)[.! ]*$")
                || m.matches("(?s)^you have been kicked for (idling|being idle).*")) return true;
        // Network trouble alone does not start a reconnect chain.
        return followingApprovedKick && (m.equals("disconnected") || m.equals("timed out")
                || m.contains("connection refused") || m.contains("connection timed out")
                || m.contains("getsockopt") || m.equals("end of stream"));
    }
}
