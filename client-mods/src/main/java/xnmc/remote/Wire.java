package xnmc.remote;

import java.util.ArrayDeque;
import java.util.function.Predicate;

// What Remote decides without the game: which alerts go out, and what an order from the inbox may do.
public final class Wire {
	private Wire() {}

	// Only Discord's own webhook endpoints: a mistyped address must not send positions anywhere else.
	public static boolean webhookOk(String url) {
		return url != null && (url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/"))
				&& url.length() < 300 && url.chars().noneMatch(Character::isWhitespace);
	}

	// A run that stops says so once; one that fails in a loop must not flood the channel.
	public static final class Throttle {
		private static final long SAME_MS = 60_000, WINDOW_MS = 10_000;
		private static final int BURST = 5;
		private final ArrayDeque<Long> sent = new ArrayDeque<>();
		private String last = "";
		private long lastAt = Long.MIN_VALUE / 2;

		public boolean allow(String text, long now) {
			if (text.equals(last) && now - lastAt < SAME_MS) return false;
			while (!sent.isEmpty() && now - sent.peekFirst() >= WINDOW_MS) sent.pollFirst();
			if (sent.size() >= BURST) return false;
			sent.addLast(now);
			last = text;
			lastAt = now;
			return true;
		}
	}

	public enum Kind { DISCONNECT, SCREENSHOT, BARITONE, COMMAND, REFUSED }

	public record Order(Kind kind, String arg) {}

	// One line from the inbox. It may log out, take a screenshot, give Baritone a command or run a command this
	// client registered; it never becomes chat or a command sent to the server.
	public static Order parse(String line, Predicate<String> clientRoot) {
		if (line == null) return new Order(Kind.REFUSED, "empty");
		String text = line.strip();
		if (text.startsWith("/")) text = text.substring(1).strip();
		if (text.isEmpty()) return new Order(Kind.REFUSED, "empty");
		if (text.length() > 256 || text.chars().anyMatch(c -> c < 0x20)) return new Order(Kind.REFUSED, "one line of at most 256 characters");
		String root = text.split(" ", 2)[0];
		String rest = text.length() > root.length() ? text.substring(root.length()).strip() : "";
		if (root.equals("disconnect") && rest.isEmpty()) return new Order(Kind.DISCONNECT, "");
		if (root.equals("screenshot") && rest.isEmpty()) return new Order(Kind.SCREENSHOT, "");
		if (root.equals("baritone")) return rest.isEmpty() ? new Order(Kind.REFUSED, "baritone needs a command") : new Order(Kind.BARITONE, rest);
		if (clientRoot.test(root)) return new Order(Kind.COMMAND, text);
		return new Order(Kind.REFUSED, "'" + root + "' is not a command of this client");
	}

	// What an alert says in Discord: who said it, what, and where it was said.
	public static String alert(String feature, String text, String where) {
		String body = "**[" + feature + "]** " + text + (where.isEmpty() ? "" : "\n" + where);
		return body.length() <= 1900 ? body : body.substring(0, 1900);
	}
}
