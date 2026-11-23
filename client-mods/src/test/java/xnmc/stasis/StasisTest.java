package xnmc.stasis;

import com.google.gson.Gson;
import java.util.List;

public final class StasisTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable run) {
        checks++;
        try { run.run(); throw new AssertionError("Expected invalid operation to fail"); }
        catch (IllegalArgumentException | IllegalStateException expected) {}
    }
    public static void main(String[] args) {
        String server = "example.test:25565";
        var home = new Destination(server, "Home", 1500, 3500);
        check(home.name().equals("home") && home.targetMs() == 2500, "Normalized name and midpoint");
        check(!new Destination(server, "draft", 0, 0).configured(), "Draft cannot recall");
        rejects(() -> new Destination(server, "bad name", 0, 0));
        rejects(() -> new Destination(server, "bad", 0, 1000));
        rejects(() -> new Destination(server, "bad", 2000, 1500));
        rejects(() -> new Destination(server, "bad", 1000, 1200));
        rejects(() -> new Destination(server, "bad", 500, Long.MAX_VALUE));
        rejects(() -> new Destination(server, "mine", 3000, 5000).checkOverlap(List.of(home)));
        rejects(() -> new Destination(server, "mine", 4500, 6000).checkOverlap(List.of(home)));
        new Destination(server, "mine", 4501, 6000).checkOverlap(List.of(home));
        new Destination("other.test:25565", "mine", 1500, 3500).checkOverlap(List.of(home));
        new Destination(server, "home", 1600, 3600).checkOverlap(List.of(home));
        var gson = new Gson();
        check(home.equals(gson.fromJson(gson.toJson(home), Destination.class)), "Window survives config round trip");

        var timer = new RecallTimer();
        long baseline = 100_000;
        timer.start(2000, 3000, baseline);
        rejects(() -> timer.start(2000, 3000, baseline));
        check(!timer.takeAttempt(baseline + 2000), "No attempt before confirmed local disconnect");
        timer.disconnected(baseline + 100);
        check(!timer.takeAttempt(baseline + 2099), "Wait measured from disconnect event");
        check(timer.remaining(baseline + 1100) == 1000, "Countdown");
        check(timer.takeAttempt(baseline + 2100), "One attempt at target");
        check(timer.attemptAt() - timer.disconnectedAt() == 2000, "Actual wait recorded");
        check(!timer.takeAttempt(baseline + 2200), "Cannot duplicate attempt");
        timer.disconnected(baseline + 2300);
        check(!timer.active() && timer.message().contains("no automatic retry"), "Failure stops the cycle");
        check(!timer.takeAttempt(baseline + 5000), "Never retry a failed login");

        timer.start(2000, 3000, baseline);
        timer.disconnected(baseline);
        check(!timer.takeAttempt(baseline + 3001) && !timer.active(), "Stalled client misses window and stays offline");
        timer.start(2000, 3000, baseline);
        timer.disconnected(baseline);
        check(timer.takeAttempt(baseline + 3000), "Inclusive latest time");
        timer.joined();
        check(timer.state() == RecallTimer.State.DONE && !timer.message().contains("success"), "Joining does not claim a teleport");
        timer.start(2000, 3000, baseline);
        timer.disconnected(baseline);
        timer.cancel("User cancelled");
        check(!timer.takeAttempt(baseline + 2000), "Cancel prevents reconnect");
        timer.start(2000, 3000, baseline);
        timer.disconnected(baseline);
        timer.joined();
        check(timer.message().contains("manually") && !timer.active(), "Manual join ends timer, never blocks player");
        timer.start(2000, 3000, baseline);
        timer.disconnected(baseline + 5001);
        check(!timer.active(), "Slow disconnect fails closed");
        timer.start(2000, 3000, baseline);
        timer.expire(baseline + 5001);
        check(!timer.active(), "Disconnect timeout");
        var freshProcess = new RecallTimer();
        check(!freshProcess.active(), "No recall restored after crash or restart");
        rejects(() -> freshProcess.start(0, 1000, 0));
        rejects(() -> freshProcess.start(1000, 500, 0));
        rejects(() -> freshProcess.start(600000, 600001, 0));
        // Rendering can tick early/late many times; all valid schedules still connect at most once.
        for (int jitter = 0; jitter <= 1200; jitter++) {
            var t = new RecallTimer();
            t.start(2000, 3000, baseline);
            t.disconnected(baseline);
            int attempts = 0;
            for (int tick = 0; tick < 5; tick++) if (t.takeAttempt(baseline + 2000 + jitter + tick)) attempts++;
            check(attempts == (jitter <= 1000 ? 1 : 0), "Jitter must not duplicate or send late login");
        }
        System.out.println(checks + " stasis policy checks passed");
    }
}
