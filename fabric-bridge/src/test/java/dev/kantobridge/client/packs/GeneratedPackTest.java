package dev.kantobridge.client.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spike S3: the generated-pack injection is applied, and its pack-source logic offers the right pack. */
class GeneratedPackTest {
	@TempDir
	Path gameDir;

	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
	}

	@Test
	void mixinIsAppliedToTheBuiltInPackSource() {
		// fabric-loader-junit runs Knot on the client side, so the client mixin config applies here. The mixin adds a
		// marker interface; a missing or unresolvable injection target would fail this class load instead.
		assertTrue(GeneratedPack.SourceMarker.class.isAssignableFrom(BuiltInPackSource.class),
				"kantobridge.client.mixins.json did not apply to BuiltInPackSource");
	}

	@Test
	void nothingIsOfferedWithoutAPack() throws IOException {
		assertFalse(GeneratedPack.present(this.gameDir));
		Files.createDirectories(GeneratedPack.directory(this.gameDir));
		assertFalse(GeneratedPack.present(this.gameDir), "an empty folder is not a pack");
		List<Pack> offered = new ArrayList<>();
		GeneratedPack.offer(this.gameDir, offered::add);
		assertTrue(offered.isEmpty());
		assertNull(GeneratedPack.create(this.gameDir));
	}

	@Test
	void offersARequiredBottomPackOnceWritten() throws IOException {
		Path dir = GeneratedPack.directory(this.gameDir);
		assertEquals(this.gameDir.resolve("kantobridge").resolve("resourcepack"), dir);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("pack.mcmeta"),
				"{\"pack\":{\"pack_format\":34,\"description\":\"Kanto Bridge test pack\"}}", StandardCharsets.UTF_8);
		assertTrue(GeneratedPack.present(this.gameDir));

		List<Pack> offered = new ArrayList<>();
		GeneratedPack.offer(this.gameDir, offered::add);
		assertEquals(1, offered.size());
		Pack pack = offered.get(0);
		assertNotNull(pack);
		assertEquals(GeneratedPack.ID, pack.getId());
		assertTrue(pack.isRequired(), "the generated pack must not be switchable off");
		assertEquals(Pack.Position.BOTTOM, pack.getDefaultPosition(), "below player packs (VD9): they override its textures");
		assertFalse(pack.isFixedPosition(), "a player may move it");
		assertTrue(pack.getCompatibility().isCompatible(), "pack_format 34 is 1.21.1's resource format");
	}

	@Test
	void invalidMetadataOffersNothing() throws IOException {
		Path dir = GeneratedPack.directory(this.gameDir);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("pack.mcmeta"), "not json", StandardCharsets.UTF_8);
		List<Pack> offered = new ArrayList<>();
		GeneratedPack.offer(this.gameDir, offered::add);
		assertTrue(offered.isEmpty());
	}
}
