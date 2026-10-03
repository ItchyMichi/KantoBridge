package dev.kantobridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.McBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The puppet entity type: registered on both sides, never saved or summoned, and sized to fill its cell. */
class PuppetEntityTypeTest {
	@BeforeAll
	static void setUp() {
		McBootstrap.ensure();
		KbEntities.register();
	}

	@Test
	void registeredButNeverSavedOrSummoned() {
		assertSame(KbEntities.PUPPET, BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("kantobridge", "puppet")));
		assertFalse(KbEntities.PUPPET.canSerialize(), "noSave: never written with a chunk");
		assertFalse(KbEntities.PUPPET.canSummon(), "noSummon: /summon can't make a copy everyone sees");
		assertEquals(0, KbEntities.PUPPET.clientTrackingRange());
	}

	@Test
	void itAlmostFillsItsTwoBlockCell() {
		assertEquals(KbEntities.PUPPET_WIDTH, KbEntities.PUPPET.getWidth());
		assertEquals(KbEntities.PUPPET_HEIGHT, KbEntities.PUPPET.getHeight());
		// The local player (0.6 wide) can't get its centre into the cell, nor is a neighbouring cell blocked.
		float margin = (2.0F - KbEntities.PUPPET_WIDTH) / 2.0F;
		assertTrue(margin > 0.0F && margin < 0.3F);
	}
}
