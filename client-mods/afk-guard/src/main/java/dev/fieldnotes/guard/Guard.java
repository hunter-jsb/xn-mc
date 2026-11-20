package dev.fieldnotes.guard;

import java.io.IOException;
import java.util.Locale;

/** Single owner of both safety and retry policy. All calls are on the client thread. */
public final class Guard {
    public enum Mode { OFF, ARMED, LOCKED }
    public interface Store { void save(Mode mode, String reason) throws IOException; }
    private final Store store;
    private Mode mode;
    private String reason;
    private float previousHealth, previousAbsorption;
    private long deadline, nextRetry;
    private int attempts;
    private boolean retryChain;
    public static final float MIN_HEALTH = 16; // Eight hearts, plus first-damage detection.
    public static final int MIN_FOOD = 12;     // Six drumsticks; auto-eat should act well before this.
    public static final int MAX_ATTEMPTS = 120;

    public Guard(Store store, Mode saved, String savedReason) {
        this.store = store;
        mode = saved == Mode.OFF ? Mode.OFF : Mode.LOCKED;
        reason = saved == Mode.ARMED ? "Previous AFK session interrupted; inspect before returning" : savedReason;
    }
    public Mode mode() { return mode; }
    public String reason() { return reason; }
    public boolean locked() { return mode == Mode.LOCKED; }
    public boolean armed() { return mode == Mode.ARMED; }
    public long nextRetry() { return nextRetry; }
    public int attempts() { return attempts; }
    public boolean permitConnection(boolean approvedRetry) {
        if (armed() && !approvedRetry) lock("Connection requested outside the approved AFK retry path");
        return !locked();
    }

    public void arm(float health, float maxHealth, float absorption, int food, long now, long duration) throws IOException {
        if (locked()) throw new IllegalStateException("Acknowledge the safety lock first");
        if (!Float.isFinite(health) || health < maxHealth || health <= MIN_HEALTH || food < 18)
            throw new IllegalStateException("Heal fully and eat to at least nine drumsticks before arming");
        if (duration < 60_000 || duration > 24 * 60 * 60_000L) throw new IllegalArgumentException("Duration must be 1 minute to 24 hours");
        // An interrupted process with ARMED on disk also boots locked.
        try { store.save(Mode.ARMED, "AFK monitoring"); }
        catch (IOException e) { lock("Could not persist AFK state"); throw e; }
        mode = Mode.ARMED;
        reason = "AFK monitoring";
        previousHealth = health;
        previousAbsorption = absorption;
        deadline = now + duration;
        nextRetry = 0;
        attempts = 0;
        retryChain = false;
    }
    public void off() throws IOException {
        if (locked()) return; // Off must never clear a safety trip.
        nextRetry = 0;
        retryChain = false;
        try { store.save(Mode.OFF, "Disabled"); }
        catch (IOException e) { lock("Could not persist disabled state"); throw e; }
        mode = Mode.OFF;
        reason = "Disabled";
    }
    public void acknowledge() throws IOException {
        // Only explicit user action may clear the durable lock. Clearing does not arm or connect.
        store.save(Mode.OFF, "Safety lock acknowledged; monitoring disabled");
        mode = Mode.OFF;
        reason = "Safety lock acknowledged; monitoring disabled";
        nextRetry = 0;
        retryChain = false;
    }
    public void lock(String why) {
        mode = Mode.LOCKED;
        reason = why;
        nextRetry = 0;
        retryChain = false;
        try { store.save(Mode.LOCKED, why); }
        catch (IOException e) { reason += " (state write failed; prior ARMED state also blocks on restart)"; }
    }
    /** Returns true whenever the current connection must be closed. No grace period after joining. */
    public boolean sample(float health, float absorption, int food, boolean hurt, long now) {
        if (!armed()) return false;
        if (!Float.isFinite(health) || health <= MIN_HEALTH) lock("Health at or below eight hearts");
        else if (hurt || health < previousHealth || absorption < previousAbsorption) lock("Damage observed while AFK");
        else if (food <= MIN_FOOD) lock("Food at or below six drumsticks");
        else if (now >= deadline) lock("AFK time limit reached");
        previousHealth = health;
        previousAbsorption = absorption;
        return locked();
    }
    public boolean expire(long now) {
        if (armed() && now >= deadline) lock("AFK time limit reached");
        return locked();
    }
    public void disconnected(String message, long now) {
        if (!armed()) return;
        if (expire(now)) return;
        if (!retryable(message, retryChain)) { lock("Unrecognized or non-retryable disconnect: " + message); return; }
        if (attempts >= MAX_ATTEMPTS) { lock("Reconnect attempt limit reached"); return; }
        retryChain = true;
        nextRetry = now + Math.min(300_000, 30_000L << Math.min(attempts, 3));
    }
    public boolean takeRetry(long now) {
        if (!armed() || expire(now) || nextRetry == 0 || now < nextRetry) return false;
        nextRetry = 0;
        attempts++;
        return true;
    }
    public void joined() {
        nextRetry = 0;
        retryChain = false;
        // Keep the pre-kick health baseline: damage during the gap must lock, too.
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
        // Network trouble alone does not initiate a new reconnect session.
        return followingApprovedKick && (m.equals("disconnected") || m.equals("timed out")
                || m.contains("connection refused") || m.contains("connection timed out")
                || m.contains("getsockopt") || m.equals("end of stream"));
    }
}
