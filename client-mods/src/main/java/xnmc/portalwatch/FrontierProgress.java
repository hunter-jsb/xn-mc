package xnmc.portalwatch;

/** Monotonic progress toward one goal; spinning or shuffling must not reset the timeout. */
final class FrontierProgress {
    static final long TIMEOUT_MS = 30_000;
    private double bestDistance;
    private long lastProgress;

    void reset(double distance, long now) {
        bestDistance = distance;
        lastProgress = now;
    }

    boolean stalled(double distance, long now) {
        if (distance <= bestDistance - .5) {
            bestDistance = distance;
            lastProgress = now;
        }
        return now - lastProgress >= TIMEOUT_MS;
    }
}
