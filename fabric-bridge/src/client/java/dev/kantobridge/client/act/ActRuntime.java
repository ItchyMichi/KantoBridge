package dev.kantobridge.client.act;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.block.TileBlock;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.story.ClientStoryView;
import dev.kantobridge.client.ui.Keybinds;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.entity.PuppetEntity;
import dev.kantobridge.proto.Envelope;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.MapPlacement;
import dev.kantobridge.world.WorldIndex;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player acting on the hidden game from the world (docs/PLAN.md §2.4 flow 2, §4.3 "Clicks", P20, §6 M8 item 4):
 *
 * <ul>
 * <li><b>A</b> ({@code Z}) talks to, reads or checks the cell in front of the player in the camera's quadrant:
 * {@code act.interact} (only while the Minecraft player stands on the hidden player's cell).</li>
 * <li><b>Right-click</b> on a puppet, or a plain right-click on a block ({@link ClickPolicy}: an empty hand or an item
 * that does nothing to blocks), sends {@code act.interact} for that cell (named in the hidden player's map) and returns
 * FAIL, so nothing reaches the server: a puppet's id is unknown there, and Kanto's blocks do nothing. Item use on a block
 * (a Poké Ball thrown at the ground, Cobblemon's medicine) passes through to Minecraft. Left-clicks on puppets are
 * cancelled before they start.</li>
 * <li><b>Field moves</b> (M8, {@link FieldClick}): A or a plain right-click on a Cut tree the player sees standing sends
 * {@code act.field{cut}}, on water while not surfing {@code act.field{surf}}, and a right-click on the ground of land
 * while surfing {@code act.field{surf}} (gen1-bridge's dismount). The crosshair meets closed art as it is drawn
 * ({@link ClosedArtPick}, after Minecraft's own pick), so a click on a tree's trunk reaches the tree. A refusal (no Pokémon
 * knows Cut, no badge, not next to it, …) shows a short line over the hotbar ({@link FieldFeedback}). Strength needs
 * nothing here: once it is on, walking into a boulder twice pushes it (the movement sync's pushes).</li>
 * <li><b>START</b> ({@code Enter}) opens the hidden start menu: {@code act.button{start, tap}}.</li>
 * </ul>
 *
 * Actions go out only while the hidden player stands idle and nothing of ours is open ({@link ActionGate}), and once per
 * press: a key held through a screen, or held down, never fires again until released. Outside Kanto worlds, and in
 * creative or spectator mode, clicks behave as in vanilla (except on puppets).
 */
public final class ActRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger("kantobridge");
	private static @Nullable ActRuntime instance;

	private final BridgeRuntime bridge;
	private final BridgeClock clock = BridgeClock.system();
	private final ActionGate gate = new ActionGate();
	private final FieldFeedback feedback = new FieldFeedback();
	private boolean aWasDown;
	private boolean startWasDown;
	private long results;
	private long failures;
	private @Nullable String lastReason;

	private ActRuntime(BridgeRuntime bridge) {
		this.bridge = bridge;
	}

	/** Call once from the client entrypoint, after the bridge, UI and puppet runtimes. */
	public static void init(BridgeRuntime bridge) {
		if (instance != null) {
			return;
		}
		ActRuntime runtime = new ActRuntime(bridge);
		instance = runtime;
		UseEntityCallback.EVENT.register(runtime::useEntity);
		UseBlockCallback.EVENT.register(runtime::useBlock);
		ClientPreAttackCallback.EVENT.register((minecraft, player, clicks) -> minecraft.crosshairPickEntity instanceof PuppetEntity);
		ClientTickEvents.END_CLIENT_TICK.register(runtime::tick);
		bridge.dispatcher().on(MsgTypes.ACT_RESULT, runtime::onResult);
		bridge.dispatcher().onState((state, detail) -> runtime.feedback.connectionChanged());
		bridge.addDebugLine(runtime::debugLine);
	}

	// ---- keys ----

	private void tick(Minecraft minecraft) {
		// B and SELECT do nothing in the world (Gen 1's overworld ignores them); they matter only inside our screens.
		boolean aClicked = drain(Keybinds.a());
		boolean startClicked = drain(Keybinds.start());
		// The physical state, read every tick even under a screen: a key held since before is no new press.
		boolean aDown = physicallyDown(minecraft, Keybinds.a());
		boolean startDown = physicallyDown(minecraft, Keybinds.start());
		boolean aPress = aClicked && !this.aWasDown;
		boolean startPress = startClicked && !this.startWasDown;
		this.aWasDown = aDown;
		this.startWasDown = startDown;
		if (minecraft.screen != null || minecraft.player == null) {
			return;
		}
		long now = this.clock.millis();
		if (aPress && this.idle() && this.gate.tryInteract(now)) {
			this.interactFacing(minecraft.player);
		}
		if (startPress && this.idle() && this.gate.tryButton(now)) {
			JsonObject data = new JsonObject();
			data.addProperty("button", Keybinds.Button.START.id());
			data.addProperty("how", "tap");
			this.bridge.client().send(MsgTypes.ACT_BUTTON, data);
		}
	}

	private void interactFacing(LocalPlayer player) {
		ClientBridgeState state = this.bridge.state();
		WorldIndex index = ClientWorldIndex.current();
		String map = state.map();
		if (map == null || index == null) {
			return;
		}
		CellPos target = InteractTarget.inFront(index, map, state.x(), state.y(), player.getX(), player.getY(), player.getZ(),
				Dir.fromYaw(player.getYRot()));
		if (target != null) {
			// A has no ground to aim at: it never means a dismount (walking ashore does that).
			this.act(index, target, false);
		}
	}

	// ---- clicks ----

	private InteractionResult useEntity(Player player, Level level, InteractionHand hand, Entity entity, @Nullable EntityHitResult hit) {
		if (!level.isClientSide() || !(entity instanceof PuppetEntity)) {
			return InteractionResult.PASS;
		}
		// FAIL whatever happens: the server must never hear of a puppet.
		PuppetRuntime puppets = PuppetRuntime.get();
		Puppet puppet = puppets == null ? null : puppets.manager().byEntityId(entity.getId());
		WorldIndex index = ClientWorldIndex.current();
		String map = this.bridge.state().map();
		if (hand == InteractionHand.MAIN_HAND && puppet != null && index != null && map != null && this.idle()
				&& this.gate.tryInteract(this.clock.millis())) {
			CellPos target = InteractTarget.cellOf(index, map, puppet.map(), puppet.x(), puppet.y());
			if (target != null) {
				this.sendIfReachable(target);
			}
		}
		return InteractionResult.FAIL;
	}

	private InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!level.isClientSide() || !MoveRuntime.following()) {
			return InteractionResult.PASS;
		}
		if (!ClickPolicy.plainClick(player.getItemInHand(hand))) {
			// Item use (a ball, a potion): Minecraft and Cobblemon handle it; the hidden game hears nothing.
			return InteractionResult.PASS;
		}
		WorldIndex index = ClientWorldIndex.current();
		String map = this.bridge.state().map();
		if (hand == InteractionHand.MAIN_HAND && index != null && map != null && this.idle() && this.gate.tryInteract(this.clock.millis())) {
			BlockPos pos = hit.getBlockPos();
			CellPos target = InteractTarget.clicked(index, map, pos.getX(), pos.getY(), pos.getZ());
			if (target != null) {
				this.act(index, target, ground(index, level, target, pos));
			}
		}
		// A plain click: Kanto's blocks do nothing in Minecraft, and nothing should reach the server.
		return InteractionResult.FAIL;
	}

	/**
	 * True when a click hit the ground surface of a land cell: its floor block (feet level - 1) with no column standing on
	 * it, where a surfer can step ashore.
	 */
	private static boolean ground(WorldIndex index, Level level, CellPos target, BlockPos pos) {
		CellPos own = InteractTarget.own(index, target);
		MapPlacement map = own == null ? null : index.map(own.map());
		return map != null && pos.getY() == map.y() - 1 && !(level.getBlockState(pos.above()).getBlock() instanceof TileBlock);
	}

	/** A press on {@code target}: a field move where one applies, else the ordinary A on it. */
	private void act(WorldIndex index, CellPos target, boolean ground) {
		ClientBridgeState state = this.bridge.state();
		String map = state.map();
		if (map == null) {
			return;
		}
		FieldClick.Decision decision = FieldClick.decide(index, map, state.x(), state.y(), target, ClientStoryView.surfing(), ClientStoryView::drawn,
				ground);
		long now = this.clock.millis();
		switch (decision.kind()) {
			case FIELD -> {
				if (this.feedback.busy(now)) {
					// The last one is still under way (a held right-click): the hidden game would only answer busy.
					return;
				}
				long seq = this.bridge.client().sendTracked(MsgTypes.ACT_FIELD, decision.fieldData());
				if (seq > 0) {
					this.feedback.sent(seq, decision.action(), now);
					LOGGER.debug("act: field {}", decision.action());
				}
			}
			case REFUSED -> this.show(this.feedback.refusal(decision.action(), decision.reason()));
			case INTERACT -> this.sendIfReachable(target);
		}
	}

	private void sendIfReachable(CellPos target) {
		ClientBridgeState state = this.bridge.state();
		if (state.map() != null && InteractTarget.reachable(state.map(), state.x(), state.y(), target)) {
			this.send(target);
		}
	}

	private void send(CellPos target) {
		JsonObject data = new JsonObject();
		data.addProperty("map", target.map());
		data.addProperty("x", target.x());
		data.addProperty("y", target.y());
		this.bridge.client().send(MsgTypes.ACT_INTERACT, data);
	}

	private void onResult(Envelope message) {
		JsonObject data = message.data();
		this.results++;
		boolean ok = JsonFields.bool(data, "ok", false);
		String reason = JsonFields.str(data, "reason");
		if (!ok) {
			this.failures++;
			this.lastReason = reason != null && reason.matches("^[a-z][a-z0-9_.]{0,63}$") ? reason : "?";
			LOGGER.debug("act: refused ({})", this.lastReason);
		}
		this.show(this.feedback.onResult(message.re(), ok, reason));
	}

	/** A field move's refusal, over the hotbar. */
	private void show(FieldFeedback.@Nullable Message message) {
		Minecraft minecraft = Minecraft.getInstance();
		if (message != null && minecraft.player != null) {
			minecraft.gui.setOverlayMessage(Component.translatable("kantobridge.field.refused", Component.translatable(message.actionKey()),
					Component.translatable(message.reasonKey())), false);
		}
	}

	// ---- the crosshair on closed art ----

	/**
	 * After Minecraft's pick (GameRendererMixin): if the look ray meets a closed column this player sees (a standing tree,
	 * a shut door) before whatever Minecraft found, the crosshair is on that column's cell. Only while following the
	 * hidden game with a plain hand: that click is then A on the cell; an item in hand keeps aiming at the real blocks.
	 */
	public static void pickClosedArt(float partialTick) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		WorldIndex index = ClientWorldIndex.current();
		if (instance == null || player == null || index == null || minecraft.getCameraEntity() != player || !MoveRuntime.following()
				|| !ClickPolicy.plainClick(player.getMainHandItem())) {
			return;
		}
		Set<String> drawn = ClientStoryView.drawnKeys();
		if (drawn.isEmpty()) {
			return;
		}
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		double reach = player.blockInteractionRange();
		HitResult current = minecraft.hitResult;
		double limit = current == null || current.getType() == HitResult.Type.MISS ? reach : Math.min(reach, current.getLocation().distanceTo(eye));
		List<ClosedArtPick.Box> boxes = ClosedArtPick.boxes(index, drawn, eye.x, eye.y, eye.z, reach);
		ClosedArtPick.Hit hit = ClosedArtPick.nearest(boxes, eye.x, eye.y, eye.z, look.x, look.y, look.z, limit);
		if (hit != null) {
			minecraft.hitResult = new BlockHitResult(new Vec3(hit.x(), hit.y(), hit.z()), direction(hit.face()),
					new BlockPos(hit.blockX(), hit.blockY(), hit.blockZ()), false);
			minecraft.crosshairPickEntity = null;
		}
	}

	private static Direction direction(ClosedArtPick.Face face) {
		return switch (face) {
			case DOWN -> Direction.DOWN;
			case UP -> Direction.UP;
			case NORTH -> Direction.NORTH;
			case SOUTH -> Direction.SOUTH;
			case WEST -> Direction.WEST;
			case EAST -> Direction.EAST;
		};
	}

	// ---- helpers ----

	private boolean idle() {
		ClientBridgeState state = this.bridge.state();
		UiRuntime ui = UiRuntime.get();
		return ActionGate.idle(MoveRuntime.following(), this.bridge.client().state() == ConnectionState.CONNECTED, state.ingame(), state.mode(),
				InputLock.locked(), ui != null && ui.open());
	}

	private static boolean drain(KeyMapping mapping) {
		boolean clicked = false;
		while (mapping.consumeClick()) {
			clicked = true;
		}
		return clicked;
	}

	private static boolean physicallyDown(Minecraft minecraft, KeyMapping mapping) {
		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
		if (key.getType() == InputConstants.Type.KEYSYM) {
			return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key.getValue());
		}
		return mapping.isDown();
	}

	private String debugLine() {
		return I18n.get("kantobridge.debug.act", this.gate.interacts(), this.feedback.sentCount(), this.gate.buttons(), this.results, this.failures,
				this.lastReason == null ? "-" : this.lastReason.toLowerCase(Locale.ROOT));
	}
}
