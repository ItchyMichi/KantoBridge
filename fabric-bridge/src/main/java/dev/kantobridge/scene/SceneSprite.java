package dev.kantobridge.scene;

/**
 * One overworld sprite sheet (docs/specs/VOXEL_BLOCKS_PLAN.md §5.3, §5.11): its frame size and how many frames it has.
 * Gen 1: 16×16 frames in a vertical strip, 0–2 standing down/up/left, 3–5 walking; right is left mirrored.
 *
 * @param image  the image the frames come from (the export's sprite resource)
 * @param frames frames in the strip: 1 (an item ball), 3 (standing only) or 6
 */
public record SceneSprite(String id, String image, int frameW, int frameH, int frames, boolean mirrorRight) {
}
