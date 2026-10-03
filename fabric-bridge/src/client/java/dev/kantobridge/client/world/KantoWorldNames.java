package dev.kantobridge.client.world;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Picks the folder for a new Kanto world: {@code Kanto}, else {@code Kanto (1)}, {@code Kanto (2)}, ... (Minecraft's
 * own pattern for a taken name). An existing world is never reused or overwritten. Pure; the saves folder is only asked
 * whether a name is taken.
 */
public final class KantoWorldNames {
	public static final String BASE = "Kanto";
	/** Far more Kanto worlds than anyone keeps; past it something is wrong with the saves folder. */
	static final int MAX_COPIES = 9999;

	private KantoWorldNames() {
	}

	/** The first free name, given which names are taken. */
	public static String free(String base, Predicate<String> taken) {
		if (!taken.test(base)) {
			return base;
		}
		for (int i = 1; i <= MAX_COPIES; i++) {
			String name = base + " (" + i + ")";
			if (!taken.test(name)) {
				return name;
			}
		}
		throw new IllegalStateException("no free world folder name");
	}

	/** The first free folder in {@code savesDir}; anything at a path (folder, file or link) makes a name taken. */
	public static String free(Path savesDir) {
		return free(BASE, name -> {
			try {
				// Files.exists is case-insensitive where the file system is (Windows), so "kanto" counts as "Kanto".
				return Files.exists(savesDir.resolve(name), LinkOption.NOFOLLOW_LINKS);
			} catch (InvalidPathException e) {
				return true;
			}
		});
	}
}
