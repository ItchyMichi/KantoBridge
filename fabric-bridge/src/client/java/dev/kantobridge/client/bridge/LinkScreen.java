package dev.kantobridge.client.bridge;

/**
 * A screen that shows the hidden game through the link (our text boxes, menus, the mirror): without the link it means
 * nothing, so the pause overlay replaces it ({@link LinkPauseScreen}; gen1-bridge sends its open UIs again after the
 * reconnect). Other screens (the pause menu, the inventory, Cobblemon's) are left alone and the overlay waits.
 */
public interface LinkScreen {
}
