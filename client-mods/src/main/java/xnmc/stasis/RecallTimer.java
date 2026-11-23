package xnmc.stasis;

/** One-shot, monotonic timer. It never schedules retries or survives a process restart. */
public final class RecallTimer {
    public enum State { IDLE, DISCONNECTING, WAITING, CONNECTING, DONE }
    private State state = State.IDLE;
    private long started, disconnected, target, latest, attempt;
    private String message = "No recall scheduled";

    public State state() { return state; }
    public String message() { return message; }
    public long disconnectedAt() { return disconnected; }
    public long attemptAt() { return attempt; }
    public boolean active() { return state == State.DISCONNECTING || state == State.WAITING || state == State.CONNECTING; }
    public long remaining(long now) { return state == State.WAITING ? Math.max(0, disconnected + target - now) : 0; }

    public void start(long targetMs, long latestMs, long now) {
        if (active()) throw new IllegalStateException("Cancel the current recall first.");
        if (targetMs < 250 || latestMs < targetMs || latestMs > 600_000)
            throw new IllegalArgumentException("Invalid reconnect timing.");
        target = targetMs;
        latest = latestMs;
        started = now;
        disconnected = attempt = 0;
        state = State.DISCONNECTING;
        message = "Disconnecting";
    }

    public void disconnected(long now) {
        if (state == State.DISCONNECTING) {
            if (now - started > 5000) { cancel("Disconnect took too long; reconnect cancelled"); return; }
            disconnected = now;
            state = State.WAITING;
            message = "Waiting to reconnect";
        } else if (state == State.CONNECTING) cancel("Connection failed; no automatic retry");
    }

    public boolean takeAttempt(long now) {
        expire(now);
        if (state != State.WAITING) return false;
        long elapsed = now - disconnected;
        if (elapsed < target) return false;
        attempt = now;
        state = State.CONNECTING;
        message = "Connecting once";
        return true;
    }

    public void expire(long now) {
        if (state == State.DISCONNECTING && now - started > 5000) cancel("Disconnect timed out");
        if (state == State.WAITING && now - disconnected > latest) cancel("Timing window missed; reconnect cancelled");
    }

    public void joined() {
        if (!active()) return;
        boolean planned = state == State.CONNECTING;
        state = State.DONE;
        message = planned ? "Rejoined; check the destination and re-arm the pearl" : "Joined manually; timer cancelled";
    }

    public void cancel(String why) { state = State.DONE; message = why; }
}
