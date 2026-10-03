package dev.kantobridge.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The new world's folder: "Kanto", else the first free "Kanto (n)"; an existing world is never reused. */
class KantoWorldNamesTest {
	@Test
	void takesTheFirstFreeName() {
		assertEquals("Kanto", KantoWorldNames.free("Kanto", name -> false));
		assertEquals("Kanto (1)", KantoWorldNames.free("Kanto", Set.of("Kanto")::contains));
		assertEquals("Kanto (3)", KantoWorldNames.free("Kanto", Set.of("Kanto", "Kanto (1)", "Kanto (2)")::contains));
		// A gap is reused: only the name matters, not the order worlds were made in.
		assertEquals("Kanto (1)", KantoWorldNames.free("Kanto", Set.of("Kanto", "Kanto (2)")::contains));
		assertThrows(IllegalStateException.class, () -> KantoWorldNames.free("Kanto", name -> true));
	}

	@Test
	void anythingInTheSavesFolderCountsAsTaken(@TempDir Path saves) throws Exception {
		assertEquals("Kanto", KantoWorldNames.free(saves));
		Files.createDirectories(saves.resolve("Kanto"));
		assertEquals("Kanto (1)", KantoWorldNames.free(saves));
		Files.writeString(saves.resolve("Kanto (1)"), "a file, not a world");
		assertEquals("Kanto (2)", KantoWorldNames.free(saves));
	}
}
