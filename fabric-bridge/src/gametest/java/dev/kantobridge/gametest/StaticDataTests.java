package dev.kantobridge.gametest;

import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.spawning.CobblemonSpawnRules;
import com.cobblemon.mod.common.api.spawning.rules.SpawnRule;
import dev.kantobridge.KantoBridge;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** The static data shipped in the jar loads as intended (docs/PLAN.md §4.2 "Static data in the jar"). */
public class StaticDataTests implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void worldgenEntries(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
		for (String name : new String[] {"kanto_outdoor", "kanto_interior", "kanto_cave"}) {
			helper.assertTrue(biomes.containsKey(id(name)), "missing biome kantobridge:" + name);
		}
		Registry<WorldPreset> presets = server.registryAccess().registryOrThrow(Registries.WORLD_PRESET);
		helper.assertTrue(presets.containsKey(id("kanto")), "missing world preset kantobridge:kanto");
		helper.assertTrue(presets.getHolderOrThrow(ResourceKey.create(Registries.WORLD_PRESET, id("kanto")))
				.is(net.minecraft.tags.WorldPresetTags.NORMAL), "kantobridge:kanto is not in #minecraft:normal");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void overworldDepth(GameTestHelper helper) {
		// P4: interiors go in layers below Y 64, so the overworld reaches down to Y -256 (same key, still "stable").
		DimensionType overworld = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
				.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
		helper.assertTrue(overworld.minY() == -256, "overworld min_y is " + overworld.minY());
		helper.assertTrue(overworld.height() == 576, "overworld height is " + overworld.height());
		helper.assertTrue(helper.getLevel().getMinBuildHeight() == -256, "the test level doesn't use the deep overworld");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void cobblemonRecipesDisabled(GameTestHelper helper) {
		var recipes = helper.getLevel().getServer().getRecipeManager();
		for (String name : new String[] {"pc", "healing_machine", "pasture"}) {
			ResourceLocation recipe = ResourceLocation.fromNamespaceAndPath("cobblemon", name);
			helper.assertTrue(recipes.byKey(recipe).isEmpty(), "recipe " + recipe + " is still loaded");
		}
		// The override must not have taken anything else with it.
		helper.assertTrue(recipes.byKey(ResourceLocation.fromNamespaceAndPath("cobblemon", "heal_powder")).isPresent(),
				"an unrelated Cobblemon recipe went missing");
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void battleProxyClasses(GameTestHelper helper) {
		String[] names = {"battle_proxy", "battle_proxy_gym", "battle_proxy_rival", "battle_proxy_elite"};
		int[] skills = {1, 3, 3, 5};
		for (int i = 0; i < names.length; i++) {
			NPCClass npcClass = NPCClasses.getByIdentifier(id(names[i]));
			helper.assertTrue(npcClass != null, "missing NPC class kantobridge:" + names[i]);
			helper.assertTrue(npcClass.getSkill() == skills[i], names[i] + " has skill " + npcClass.getSkill());
			helper.assertTrue(!npcClass.getCanDespawn() && npcClass.getHideNameTag(), names[i] + " can despawn or shows a name tag");
		}
		helper.succeed();
	}

	@GameTest(template = EMPTY_STRUCTURE)
	public void interiorSpawnRule(GameTestHelper helper) {
		SpawnRule rule = CobblemonSpawnRules.INSTANCE.getRules().get(id("no_interior_spawns"));
		helper.assertTrue(rule != null, "spawn rule kantobridge:no_interior_spawns not loaded");
		helper.assertTrue(rule.getEnabled() && rule.getComponents().size() == 1, "spawn rule is disabled or malformed");
		helper.succeed();
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, path);
	}
}
