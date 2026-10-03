package dev.kantobridge.client.bridge;

/**
 * The facts gen1-bridge gives in {@code hello.ok}. All four are versions or ids, never game content.
 *
 * @param session random per gen1-bridge start: a new value means gen1-bridge restarted
 * @param game    {@code red} or {@code blue}
 * @param engine  the Gen1Recomp engine version
 * @param bridge  gen1-bridge's version
 */
public record HelloOk(String session, String game, String engine, String bridge) {
}
