package dev.kantobridge.client.puppet.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;

/**
 * How a character is drawn (docs/specs/VOXEL_BLOCKS_PLAN.md §5.11): one object per sprite, built once from its sheet and
 * cached; a frame only pushes vertices, without allocating. The pose stack stands at the character's feet, already
 * turned: to the camera for a billboard (its front, +Z, toward the camera), by the character's own facing for a
 * world-oriented style (its front, +Z, along the facing). Units: blocks; a 16-pixel frame is two blocks tall.
 */
public interface CharacterStyle {
	/** The style's name, as the settings and F7 name it. */
	String id();

	/** A real object turned by the character's facing (carved, rigged, a model), or a billboard turned to the camera. */
	boolean worldOriented();

	/** The texture the style draws with, given the sheet's. */
	default ResourceLocation texture(ResourceLocation sheet) {
		return sheet;
	}

	/**
	 * Emits the character.
	 *
	 * @param column   the sheet column the camera sees (billboards; {@code FacingFrames})
	 * @param walking  the walking frame shows
	 * @param mirrored the walking frame is mirrored (the other foot forward)
	 * @param phase    where in its walk cycle it is, 0..1, or -1 standing
	 */
	void emit(PoseStack poseStack, VertexConsumer out, int light, int column, boolean walking, boolean mirrored, float phase);
}
