package dev.kantobridge.client.puppet;

import dev.kantobridge.KantoBridge;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.Dispatcher;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.puppet.style.CharacterStyles;
import dev.kantobridge.client.shape.ClientShapes;
import dev.kantobridge.client.ui.Gen1Draw;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.entity.KbEntities;
import dev.kantobridge.entity.PuppetEntity;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.MapLayout;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.Scale;
import dev.kantobridge.world.WorldIndex;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft glue for the puppets (docs/PLAN.md §4.3 {@code puppet/}): feeds {@link PuppetManager} the bridge's NPC
 * messages and turns its puppets into {@link PuppetEntity}s that exist only in this client's level, under their negative
 * ids. A puppet stands at its cell's centre on its map's walking surface (from the WorldIndex); maps this world doesn't
 * have keep their puppets waiting. Each entity tick advances its puppet by 3 Gen 1 frames and moves the entity, so
 * Minecraft interpolates the walk between ticks. A new level (joining, respawning) gets every puppet added again. Logs
 * carry counts and symbols only.
 */
public final class PuppetRuntime implements PuppetManager.Listener {
	private static @Nullable PuppetRuntime instance;

	private final PuppetManager manager = new PuppetManager(this);
	private final PuppetTextures textures = new PuppetTextures();
	private final CharacterStyles styles = stylesFromConfig();
	private final BridgeRuntime bridge;
	/** The hidden player's map area for culling, made again when the map or the WorldIndex changes (M10). */
	private PuppetCulling.@Nullable Area area;
	private @Nullable String areaMap;
	private MapLayout.@Nullable Piece areaPiece;
	private @Nullable WorldIndex areaIndex;
	/** Puppets within drawing range at the last tick (F7). */
	private int inRange;
	/** The player's own emote sign and its width, kept while it shows. */
	private @Nullable String bubbleSign;
	private int bubbleWidth;
	/** Spawned entities by NPC id, all in {@link #level}. */
	private final Map<String, PuppetEntity> entities = new HashMap<>();
	private @Nullable ClientLevel level;
	private long spawned;

	private PuppetRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
	}

	/** Call once from the client entrypoint, after {@link BridgeRuntime#init}. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		PuppetRuntime runtime = new PuppetRuntime(bridge);
		instance = runtime;
		EntityRendererRegistry.register(KbEntities.PUPPET, PuppetRenderer::new);
		Dispatcher dispatcher = bridge.dispatcher();
		PuppetManager manager = runtime.manager;
		dispatcher.on(MsgTypes.NPC_SET, message -> manager.onSet(message.data()));
		dispatcher.on(MsgTypes.NPC_ADD, message -> manager.onAdd(message.data()));
		dispatcher.on(MsgTypes.NPC_REMOVE, message -> manager.onRemove(message.data()));
		dispatcher.on(MsgTypes.NPC_MOVE, message -> manager.onMove(message.data()));
		dispatcher.on(MsgTypes.NPC_FACE, message -> manager.onFace(message.data()));
		dispatcher.on(MsgTypes.EMOTE, message -> manager.onEmote(message.data()));
		dispatcher.on(MsgTypes.SNAPSHOT, message -> manager.onSet(JsonFields.obj(message.data(), "npcs")));
		dispatcher.onState((state, detail) -> {
			if (state != ConnectionState.CONNECTED) {
				// The snapshot after the reconnect brings the current ones back.
				manager.clear();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runtime.forgetLevel());
		HudRenderCallback.EVENT.register((graphics, delta) -> runtime.renderPlayerBubble(graphics));
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "puppet_textures");
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				runtime.textures.clear();
				runtime.styles.clear();
			}
		});
		bridge.addDebugLine(runtime::debugLine);
		bridge.addDebugLine(runtime.styles::debugLine);
	}

	/** The running glue, or null before client init. */
	public static @Nullable PuppetRuntime get() {
		return instance;
	}

	/** A texture pack changed (V6): sheets are looked up again, and styles decided again (V8). */
	public void packsChanged() {
		this.textures.clear();
		this.styles.clear();
	}

	/** How characters are drawn (V8). */
	public CharacterStyles styles() {
		return this.styles;
	}

	private static CharacterStyles stylesFromConfig() {
		BridgeRuntime bridge = BridgeRuntime.get();
		return bridge == null ? new CharacterStyles(null, true) : new CharacterStyles(bridge.config().characterStyle(), bridge.config().characterPacks());
	}

	public PuppetManager manager() {
		return this.manager;
	}

	public PuppetTextures textures() {
		return this.textures;
	}

	/** The hidden player's current map area (for {@link PuppetCulling}), or null when it isn't known. */
	public PuppetCulling.@Nullable Area area() {
		return this.area;
	}

	// ---- manager events ----

	@Override
	public void added(Puppet puppet) {
		this.spawn(puppet);
	}

	@Override
	public void removed(Puppet puppet) {
		PuppetEntity entity = this.entities.remove(puppet.id());
		if (entity != null && this.level != null) {
			this.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
		}
	}

	// ---- ticks ----

	private void tick(Minecraft minecraft) {
		if (minecraft.level != this.level) {
			// A new level lost the old one's entities: add every puppet again.
			this.entities.clear();
			this.level = minecraft.level;
		}
		if (this.level == null) {
			return;
		}
		for (Puppet puppet : this.manager.all()) {
			PuppetEntity entity = this.entities.get(puppet.id());
			if (entity == null || entity.isRemoved()) {
				this.entities.remove(puppet.id());
				this.spawn(puppet);
			}
		}
		if (!minecraft.isPaused()) {
			this.manager.advancePlayer(Puppet.FRAMES_PER_TICK);
		}
		this.updateArea();
		this.countInRange(minecraft);
	}

	/**
	 * The culling area follows the hidden player's map, or its piece in a map with a layout (only made again when the
	 * map, the piece or the WorldIndex changes).
	 */
	private void updateArea() {
		WorldIndex index = ClientWorldIndex.current();
		String map = this.bridge.state().map();
		MapLayout layout = index == null || map == null ? null : index.layout(map);
		MapLayout.Piece piece = layout == null ? null : layout.pieceAt(this.bridge.state().x(), this.bridge.state().y());
		if (index == this.areaIndex && java.util.Objects.equals(map, this.areaMap) && piece == this.areaPiece) {
			return;
		}
		this.areaIndex = index;
		this.areaMap = map;
		this.areaPiece = piece;
		MapPlacement placement = index == null || map == null ? null : index.map(map);
		WorldIndex.Zone zone = null;
		if (placement != null && placement.kind() == MapPlacement.Kind.ROUTE) {
			for (WorldIndex.Zone z : index.zones()) {
				if (z.routeMap().equals(map)) {
					zone = z;
				}
			}
		}
		// Wild Routes: on a route map the hidden player stands for the whole zone, whose puppets are all on its grid
		this.area = zone != null ? PuppetCulling.Area.of(zone)
				: placement == null ? null : PuppetCulling.Area.of(index, placement, this.bridge.state().x(), this.bridge.state().y());
	}

	private void countInRange(Minecraft minecraft) {
		Entity camera = minecraft.getCameraEntity();
		int count = 0;
		if (camera != null) {
			for (PuppetEntity entity : this.entities.values()) {
				if (PuppetCulling.visible(entity.getX(), entity.getY(), entity.getZ(), camera.getX(), camera.getEyeY(), camera.getZ(), this.area)) {
					count++;
				}
			}
		}
		this.inRange = count;
	}

	private void forgetLevel() {
		this.entities.clear();
		this.level = null;
	}

	/** Adds a puppet's entity to the level, if its map is in this world. */
	private void spawn(Puppet puppet) {
		ClientLevel current = this.level;
		WorldIndex index = ClientWorldIndex.current();
		if (current == null || index == null || this.entities.containsKey(puppet.id())) {
			return;
		}
		Vec3 position = position(index, puppet, 0.0F);
		if (position == null) {
			return;
		}
		// Appearing mid-step: stand on the step's target.
		puppet.settle();
		position = position(index, puppet, 0.0F);
		PuppetEntity entity = new PuppetEntity(KbEntities.PUPPET, current);
		entity.setId(puppet.entityId());
		entity.setNpcId(puppet.id());
		entity.moveTo(position.x, position.y, position.z, 0.0F, 0.0F);
		entity.setDriver(this::drive);
		current.addEntity(entity);
		this.entities.put(puppet.id(), entity);
		this.spawned++;
	}

	/** One entity tick: the puppet's step goes on by a tick's frames, and the entity follows. */
	private void drive(PuppetEntity entity) {
		Puppet puppet = this.manager.byEntityId(entity.getId());
		WorldIndex index = ClientWorldIndex.current();
		if (puppet == null || index == null) {
			entity.discard();
			return;
		}
		puppet.advance(Puppet.FRAMES_PER_TICK);
		Vec3 position = position(index, puppet, 0.0F);
		if (position == null) {
			entity.discard();
			return;
		}
		entity.setPos(position);
	}

	/**
	 * Where a puppet stands: its (sliding) cell's centre on its map's walking surface, or on top of a solid cell's column
	 * (an item on a table, the voxel plan's §5.7); null for a map not in the world.
	 */
	static @Nullable Vec3 position(WorldIndex index, Puppet puppet, float partialFrames) {
		double cx = puppet.cellX(partialFrames);
		double cy = puppet.cellY(partialFrames);
		int rx = (int) Math.round(cx);
		int ry = (int) Math.round(cy);
		// The nearest cell's origin carries its piece's offset and its level (docs/specs/CAVES_3D_PLAN.md §3.1)
		BlockPos origin = index.cellOrigin(puppet.map(), rx, ry);
		if (origin == null) {
			return null;
		}
		double half = Scale.CELL_BLOCKS / 2.0;
		// Along a ramp it climbs as it walks (CAVES_3D_PLAN §3.4); elsewhere it stands on the cell's column, if any.
		double top = index.ramp(puppet.map(), rx, ry) ? index.rampLift(puppet.map(), rx, ry, cx, cy)
				: ClientShapes.topHeight(index, puppet.map(), rx, ry);
		return new Vec3(origin.getX() + (cx - rx) * Scale.CELL_BLOCKS + half, origin.getY() + top,
				origin.getZ() + (cy - ry) * Scale.CELL_BLOCKS + half);
	}

	// ---- HUD and debug ----

	/** The player's own emote, over the crosshair (the player is the camera, so a bubble above them would be unseen). */
	private void renderPlayerBubble(GuiGraphics graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		String bubble = this.manager.playerBubble();
		if (bubble == null || minecraft.options.hideGui || minecraft.level == null) {
			return;
		}
		String sign = bubble.equals("happy") ? "♥" : bubble;
		if (!sign.equals(this.bubbleSign)) {
			// Measured once per sign: the HUD draws it every frame (M10: no allocation per frame).
			this.bubbleSign = sign;
			this.bubbleWidth = minecraft.font.width(sign) + 8;
		}
		int w = this.bubbleWidth;
		int x = graphics.guiWidth() / 2 - w / 2;
		int y = graphics.guiHeight() / 2 - 34;
		graphics.fill(x, y, x + w, y + 16, Gen1Draw.BLACK);
		graphics.fill(x + 1, y + 1, x + w - 1, y + 15, Gen1Draw.WHITE);
		graphics.drawString(minecraft.font, sign, x + 4, y + 4, Gen1Draw.BLACK, false);
	}

	private String debugLine() {
		PuppetManager.Stats stats = this.manager.stats();
		String map = this.manager.map();
		return I18n.get("kantobridge.debug.npcs", this.manager.size(), this.entities.size(), this.inRange, map == null ? "-" : map, stats.moves,
				stats.faces, stats.emotes, stats.stale + stats.refused, this.textures.missing());
	}
}
