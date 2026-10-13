package dev.kantobridge.client.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kantobridge.proto.Catalog;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link Links} against two real {@code tools/fake_gen1} processes over loopback sockets (MG4): one per hidden game, each
 * on its own port, the body following one at a time. Skipped without the project venv, like
 * {@link FakeGen1IntegrationTest}.
 */
class LinksFakeGen1IntegrationTest {
	private static final Catalog CATALOG = Catalog.bundled();

	private static Links.ClientFactory sockets() {
		return (config, listener) -> new BridgeClient(config.withHeartbeat(200, 5000).withReconnect(100, 500), CATALOG, () -> LinksTest.HELLO,
				Transport.socket(), BridgeClock.system(), listener);
	}

	/** Drains the links while waiting for {@code condition} (the handlers run on the calling thread, as in the game). */
	private static void awaitDrained(Links links, String what, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + 20_000_000_000L;
		while (true) {
			links.drain(256);
			if (condition.getAsBoolean()) {
				return;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("timed out waiting for " + what);
			}
			Thread.sleep(10);
		}
	}

	@Test
	void twoFakesAreTwoLinksAndTheBodyFollowsOneAtATime() throws Exception {
		try (FakeGen1Process blue = FakeGen1Process.start("--game", "blue")) {
			Assumptions.assumeTrue(blue != null, "tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 tests");
			try (FakeGen1Process red = FakeGen1Process.start("--game", "red")) {
				LinksTest.FakeRegions regions = new LinksTest.FakeRegions();
				regions.byGame.put("blue", "kanto");
				regions.byGame.put("red", "kanto_red");
				regions.legacy = "kanto";
				regions.regionWorld = true;
				LinksTest.FakeServer server = new LinksTest.FakeServer();
				Links links = new Links(CATALOG, List.of(new ClientConfig.Link("blue", blue.port()), new ClientConfig.Link("red", red.port())),
						sockets(), BridgeClock.system(), regions, server);
				List<String> bodyStates = new CopyOnWriteArrayList<>();
				links.body().onState((state, detail) -> bodyStates.add(state + (detail == null ? "" : ":" + detail)));
				links.start();
				try {
					awaitDrained(links, "both links up", () -> links.all().stream().allMatch(BridgeLink::connected));
					for (BridgeLink link : links.all()) {
						assertFalse(link.wrongGame(), link.toString());
						assertEquals(link.configuredGame(), link.game());
					}
					BridgeLink first = links.followed();
					assertEquals(1, links.parked().size());
					BridgeLink other = links.parked().get(0);
					assertEquals("CONNECTED:" + first.game(), bodyStates.get(bodyStates.size() - 1), "the body hears the followed link");
					links.tick();
					assertEquals(1, server.posts.size());
					assertTrue(server.posts.get(0).startsWith("region.state - "), server.posts.get(0));
					assertEquals(first.region(), links.presence().get("followed").getAsString());

					assertTrue(links.follow(other.game(), false));
					assertSame(other, links.followed());
					awaitDrained(links, "the body on the new link",
							() -> !bodyStates.isEmpty() && bodyStates.get(bodyStates.size() - 1).equals("CONNECTED:" + other.game()));
					assertTrue(bodyStates.contains("DISCONNECTED:swap"));
					links.tick();
					assertEquals(2, server.posts.size(), "the presence changed");
					assertEquals(other.region(), links.presence().get("followed").getAsString());
					assertEquals(first.region(), links.presence().getAsJsonArray("parked").get(0).getAsString());
				} finally {
					links.closeAll();
				}
			}
		}
	}

	@Test
	void aFakeRunningAnotherGameIsAWrongGameLink() throws Exception {
		try (FakeGen1Process red = FakeGen1Process.start("--game", "red")) {
			Assumptions.assumeTrue(red != null, "tools/.venv is missing: run tools/setup.ps1 (or tools/setup.sh) to enable the fake_gen1 tests");
			LinksTest.FakeRegions regions = new LinksTest.FakeRegions();
			regions.byGame.put("blue", "kanto");
			regions.byGame.put("red", "kanto_red");
			regions.regionWorld = true;
			Links links = new Links(CATALOG, List.of(new ClientConfig.Link("blue", red.port())), sockets(), BridgeClock.system(), regions,
					new LinksTest.FakeServer());
			links.start();
			try {
				BridgeLink link = links.all().get(0);
				awaitDrained(links, "the wrong game seen", link::wrongGame);
				assertEquals("red", link.wrongGameSeen());
				assertNull(link.region());
				assertFalse(links.follow("blue", false));
				assertFalse(links.presence().has("followed"));
				assertEquals("kantobridge.hud.link.wrong_game", link.hudLine().key());
			} finally {
				links.closeAll();
			}
		}
	}
}
