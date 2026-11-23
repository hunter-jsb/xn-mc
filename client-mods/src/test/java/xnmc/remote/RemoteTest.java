package xnmc.remote;

import java.util.Set;

// What Remote may send and run, checked without the game. A failure fails the build.
public final class RemoteTest {
	private static int checks;

	private static void check(boolean ok, String what) {
		checks++;
		if (!ok) throw new AssertionError(what);
	}

	public static void main(String[] args) {
		Set<String> client = Set.of("portalwatch", "afkguard", "build", "stasis");

		// An order is a logout, a screenshot, a word to Baritone or one of this client's own commands.
		check(Wire.parse("/portalwatch off", client::contains).equals(new Wire.Order(Wire.Kind.COMMAND, "portalwatch off")), "a client command runs, without its slash");
		check(Wire.parse("  disconnect ", client::contains).kind() == Wire.Kind.DISCONNECT, "disconnect");
		check(Wire.parse("screenshot", client::contains).kind() == Wire.Kind.SCREENSHOT, "screenshot");
		check(Wire.parse("baritone stop", client::contains).equals(new Wire.Order(Wire.Kind.BARITONE, "stop")), "a word to Baritone");
		// Anything else would go to the server as a command or as chat: it is refused instead.
		check(Wire.parse("/tpa someone", client::contains).kind() == Wire.Kind.REFUSED, "a server command is refused");
		check(Wire.parse("hello everyone", client::contains).kind() == Wire.Kind.REFUSED, "chat is refused");
		check(Wire.parse("disconnect now", client::contains).kind() == Wire.Kind.REFUSED, "disconnect takes nothing after it");
		check(Wire.parse("portalwatch off\n/kill", client::contains).kind() == Wire.Kind.REFUSED, "a second line is refused");
		check(Wire.parse("", client::contains).kind() == Wire.Kind.REFUSED && Wire.parse(null, client::contains).kind() == Wire.Kind.REFUSED, "nothing is refused");

		// Only Discord's webhook endpoints are sent to.
		check(Wire.webhookOk("https://discord.com/api/webhooks/1/abc"), "a Discord webhook");
		check(!Wire.webhookOk("https://example.com/api/webhooks/1/abc") && !Wire.webhookOk("http://discord.com/api/webhooks/1/abc")
				&& !Wire.webhookOk("https://discord.com/api/webhooks/1/a b") && !Wire.webhookOk(null), "anything else is not");

		// The same line again within a minute is not sent twice, and a burst is cut at five in ten seconds.
		Wire.Throttle throttle = new Wire.Throttle();
		check(throttle.allow("stopped", 0) && !throttle.allow("stopped", 59_000) && throttle.allow("stopped", 61_000), "a repeat waits a minute");
		Wire.Throttle burst = new Wire.Throttle();
		int sent = 0;
		for (int i = 0; i < 9; i++) if (burst.allow("find " + i, 100_000 + i)) sent++;
		check(sent == 5 && burst.allow("later", 111_000), "five in ten seconds, then again once they have passed");

		check(Wire.alert("Portal Watch", "portal at 1 2 3", "the_nether 8 120 8").equals("**[Portal Watch]** portal at 1 2 3\nthe_nether 8 120 8"), "an alert names who, what and where");
		System.out.println(checks + " remote checks passed");
	}
}
