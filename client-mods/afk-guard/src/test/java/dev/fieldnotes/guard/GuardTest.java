package dev.fieldnotes.guard;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class GuardTest {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }
    static Guard armed() throws Exception {
        Guard g = new Guard((m,r) -> {}, Guard.Mode.OFF, "Disabled");
        g.arm(20, 20, 0, 20, 1_000, 43_200_000);
        return g;
    }
    public static void main(String[] args) throws Exception {
        Guard gate = armed();
        check(gate.permitConnection(true), "Guard's approved reconnect can enter Minecraft connection screen");
        check(!gate.permitConnection(false) && gate.locked(), "Another reconnect path during AFK locks instead of racing classification");
        check(!gate.permitConnection(true), "Even an approved retry cannot bypass a safety lock");
        gate.acknowledge(); check(gate.permitConnection(false), "Manual connection works after explicit acknowledgement");
        Guard g = armed();
        check(!g.sample(20, 0, 18, false, 2_000), "Normal AFK stays armed");
        check(g.sample(19, 0, 18, false, 2_001), "First half-heart lost trips immediately");
        g.disconnected("Server closed", 3_000);
        check(!g.takeRetry(999_999) && g.nextRetry() == 0, "Safety beats restart reason and all future retries");
        g.off(); check(g.locked(), "Turning off cannot acknowledge a trip");
        g.acknowledge(); check(g.mode() == Guard.Mode.OFF && !g.takeRetry(999_999), "Acknowledgement stays offline and disarmed");

        g = armed(); g.disconnected("Server closed", 3_000);
        check(!g.takeRetry(32_999) && g.takeRetry(33_000), "Benign kick waits at least thirty seconds");
        check(!g.takeRetry(33_000), "No duplicate retry");
        g.disconnected("Connection refused: getsockopt", 40_000);
        check(!g.takeRetry(99_999) && g.takeRetry(100_000), "Approved retry chain backs off during server downtime");
        g.joined(); check(g.armed() && g.nextRetry() == 0, "Successful reconnect preserves monitoring");
        check(g.sample(18, 0, 20, false, 101_000), "Damage across disconnect is not hidden by a new baseline");

        g = armed(); g.disconnected("Server is restarting", 3_000); g.lock("Damage");
        check(!g.takeRetry(500_000), "Safety cancels an already queued reconnect");
        g = armed(); g.disconnected("Server closed", 3_000); g.off();
        check(!g.takeRetry(500_000), "Manual cancellation removes queued retry");
        for (String reason : List.of("Disconnected", "Timed out", "You died", "Banned: server restarting", "Kicked by an operator", "AFK Guard safety lock", "Unknown plugin reason", "Server closed because of damage")) {
            g = armed(); g.disconnected(reason, 3_000);
            check(g.locked() && !g.takeRetry(500_000), "Fail closed on initial reason: " + reason);
        }
        for (String reason : List.of("Server closed", "Server is restarting", "Server restarting", "The server is restarting!", "Server under maintenance", "✘ Proxy shutting down.", "You have been kicked for idling more than 10 minutes")) {
            g = armed(); g.disconnected(reason, 3_000);
            check(g.armed() && g.takeRetry(33_000), "Allow recognized kick: " + reason);
        }
        g = armed(); check(g.sample(20,0,12,false,3_000), "Low food trips before starvation");
        g = armed(); check(g.sample(16,0,20,false,3_000), "Eight-heart cutoff trips");
        g = armed(); check(g.sample(20,0,20,true,3_000), "Hurt animation also trips without observed HP loss");
        g = new Guard((m,r)->{}, Guard.Mode.OFF, "Disabled");
        g.arm(20,20,4,20,1_000,43_200_000);
        check(g.sample(20,3,20,false,3_000), "Absorption loss trips");
        g = armed(); check(g.expire(43_201_000), "Deadline locks rather than silently disabling");
        g = armed(); g.disconnected("Server closed", 43_200_999);
        check(!g.takeRetry(43_230_999) && g.locked(), "Deadline cancels retries while disconnected");
        g = new Guard((m,r)->{}, Guard.Mode.OFF, "Disabled");
        try { g.arm(18,20,0,20,0,60_000); throw new AssertionError("Armed while injured"); }
        catch (IllegalStateException expected) { checks++; }

        Path dir = Files.createTempDirectory("fieldnotes-guard-test-");
        try {
            StateFile file = new StateFile(dir.resolve("state.properties"));
            check(file.load().mode() == Guard.Mode.OFF, "Fresh installation starts disabled");
            g = file.load(); g.arm(20,20,0,20,1_000,43_200_000);
            check(file.load().locked(), "Process restart while armed boots locked");
            g.lock("Damage observed");
            Guard restarted = file.load();
            restarted.disconnected("Server closed", 3_000);
            check(restarted.locked() && !restarted.takeRetry(500_000), "Safety lock survives process restart");
            restarted.acknowledge(); check(file.load().mode() == Guard.Mode.OFF, "Explicit acknowledgement persists OFF");
            Files.writeString(dir.resolve("state.properties"), "garbage");
            check(file.load().locked(), "Malformed state fails closed");
            g = new Guard((m,r)->{throw new IOException("Disk full");}, Guard.Mode.OFF, "Disabled");
            try { g.arm(20,20,0,20,0,60_000); throw new AssertionError("Armed without durable write"); }
            catch (IOException expected) { check(g.locked(), "Cannot arm if persistence fails"); }
            Guard broken = new Guard((m,r)->{throw new IOException("Disk full");}, Guard.Mode.ARMED, "Interrupted");
            try { broken.acknowledge(); throw new AssertionError("Cleared without write"); }
            catch (IOException expected) { check(broken.locked(), "Failed acknowledgement retains lock"); }
        } finally {
            try (var files = Files.walk(dir)) { for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(f); }
        }
        System.out.println("PASS: " + checks + " safety/reconnect checks");
    }
}
