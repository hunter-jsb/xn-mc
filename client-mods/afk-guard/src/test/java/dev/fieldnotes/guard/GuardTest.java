package dev.fieldnotes.guard;

import java.util.List;

public final class GuardTest {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    static Guard armed() {
        Guard g = new Guard();
        g.arm(20, 20, 0, 20, 1_000, 43_200_000);
        return g;
    }
    public static void main(String[] args) {
        Guard g = armed();
        check(!g.sample(20, 0, 18, false, 2_000), "Normal AFK stays armed");
        check(g.sample(19, 0, 18, false, 2_001) && !g.armed(), "First half-heart lost trips");
        g.disconnected("Server closed", 3_000);
        check(!g.takeRetry(999_999), "Nothing reconnects after a trip");

        g = armed(); g.disconnected("Server closed", 3_000);
        check(!g.takeRetry(32_999) && g.takeRetry(33_000), "Benign kick waits thirty seconds");
        check(!g.takeRetry(33_000), "No duplicate retry");
        g.disconnected("Connection refused: getsockopt", 40_000);
        check(!g.takeRetry(99_999) && g.takeRetry(100_000), "Retry chain backs off through downtime");
        g.joined(); check(g.armed() && g.nextRetry() == 0, "Rejoining keeps monitoring");
        check(g.sample(18, 0, 20, false, 101_000), "Damage during the gap still trips");

        g = armed(); g.disconnected("Server closed", 3_000); g.off("off");
        check(!g.takeRetry(500_000), "Turning off cancels a queued retry");
        for (String reason : List.of("Disconnected", "Timed out", "You died", "Banned: server restarting", "Kicked by an operator", "AFK Guard: took damage", "Unknown plugin reason")) {
            g = armed(); g.disconnected(reason, 3_000);
            check(!g.armed() && !g.takeRetry(500_000), "No reconnect after: " + reason);
        }
        for (String reason : List.of("Server closed", "Server is restarting", "The server is restarting!", "Server under maintenance", "✘ Proxy shutting down.", "You have been kicked for idling more than 10 minutes")) {
            g = armed(); g.disconnected(reason, 3_000);
            check(g.takeRetry(33_000), "Reconnect after: " + reason);
        }
        check(armed().sample(20, 0, 12, false, 3_000), "Low food trips");
        check(armed().sample(16, 0, 20, false, 3_000), "Eight-heart cutoff trips");
        check(armed().sample(20, 0, 20, true, 3_000), "Hurt animation trips without HP loss");
        g = new Guard(); g.arm(20, 20, 4, 20, 1_000, 43_200_000);
        check(g.sample(20, 3, 20, false, 3_000), "Absorption loss trips");
        check(armed().sample(20, 0, 20, false, 43_201_000), "Time limit logs out");
        g = armed(); g.disconnected("Server closed", 43_200_999);
        check(!g.takeRetry(43_230_999) && !g.armed(), "Time limit cancels a pending retry");
        try { new Guard().arm(18, 20, 0, 20, 0, 60_000); throw new AssertionError("Armed while injured"); }
        catch (IllegalStateException expected) { checks++; }
        System.out.println("PASS: " + checks + " checks");
    }
}
