package dev.kantobridge.client.model;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates the dev-only mixins of {@code kantobridge.dev.mixins.json} (docs/specs/VOXEL_BLOCKS_PLAN.md §0.2): they apply
 * only with {@code -Dkantobridge.meshstats=true}. Reads a system property and nothing else, because it runs before
 * Minecraft's classes load.
 */
public final class MeshStatsMixinPlugin implements IMixinConfigPlugin {
	/** Whether the dev mixins apply under these system properties. */
	public static boolean applies(java.util.Properties properties) {
		return Boolean.parseBoolean(properties.getProperty(MeshStats.PROPERTY, "false"));
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return applies(System.getProperties());
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
