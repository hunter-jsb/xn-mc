package xnmc.replays;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// ReplayMod records every session and never deletes one. At game start, recordings older than a week go,
// but only ones still carrying ReplayMod's automatic date name: renaming one in the Replay Viewer keeps it.
public final class ReplayPrune {
	private static final Logger LOG = LoggerFactory.getLogger("xnmc/replays");
	private static final Pattern AUTO_NAME = Pattern.compile("\\d{4}(_\\d{2}){5}\\.mcpr");
	private static final Duration KEEP = Duration.ofDays(7);

	public static void init() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("replay_recordings");
		Instant cutoff = Instant.now().minus(KEEP);
		long freed = 0;
		int count = 0;
		// raw/ holds the unprocessed copy ReplayMod keeps beside each post-processed recording.
		for (Path d : List.of(dir, dir.resolve("raw"))) {
			if (!Files.isDirectory(d)) continue;
			try (var files = Files.list(d)) {
				for (Path f : (Iterable<Path>) files::iterator) {
					if (!AUTO_NAME.matcher(f.getFileName().toString()).matches() || !Files.isRegularFile(f)) continue;
					if (!Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) continue;
					long size = Files.size(f);
					Files.delete(f);
					freed += size;
					count++;
				}
			} catch (IOException e) {
				LOG.warn("couldn't prune {}", d, e);
			}
		}
		if (count > 0) LOG.info("deleted {} recordings older than {} days, {} MB", count, KEEP.toDays(), freed >> 20);
	}
}
