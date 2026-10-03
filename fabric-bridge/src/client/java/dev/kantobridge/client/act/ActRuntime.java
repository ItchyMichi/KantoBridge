package dev.kantobridge.client.act;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.kantobridge.client.bridge.BridgeClock;
import dev.kantobridge.client.bridge.BridgeRuntime;
import dev.kantobridge.client.bridge.ClientBridgeState;
import dev.kantobridge.client.bridge.ConnectionState;
import dev.kantobridge.client.bridge.JsonFields;
import dev.kantobridge.client.move.InputLock;
import dev.kantobridge.client.move.MoveRuntime;
import dev.kantobridge.client.puppet.Puppet;
import dev.kantobridge.client.puppet.PuppetRuntime;
import dev.kantobridge.client.ui.Keybinds;
import dev.kantobridge.client.ui.UiRuntime;
import dev.kantobridge.client.world.ClientWorldIndex;
import dev.kantobridge.entity.PuppetEntity;
import dev.kantobridge.proto.MsgTypes;
import dev.kantobridge.world.CellPos;
import dev.kantobridge.world.Dir;
import dev.kantobridge.world.WorldIndex;
import java.util.Locale;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player acting on the hidden game from the world (docs/PLAN.md §2.4 flow 2, §4.3 "Clicks", P20):
 *
 * <ul>
 * <li><b>A</b> ({@code Z}) talks to, reads or checks the cell in front of the player in the camera's quadrant:
 * {@code act.interact} (only while the Minecraft player stands on the hidden player's cell).</li>
 * <li><b>Right-click</b> on a puppet, or a plain right-click on a block ({@link ClickPolicy}: an empty hand or an item
 * that does nothing to blocks), sends {@code act.interact} for that cell (named in the hidden player's map) and returns
 * FAIL, so nothing reaches the server: a puppet's id is unknown there, and Kanto's blocks do nothing. Item use on a block
 * (a Poké Ball thrown at the ground, Cobblemon's medicine) passes through to Minecraft. Left-clicks on puppets are
 * cancelled before they start.</li>
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
		bridge.dispatcher().on(MsgTypes.ACT_RESULT, message -> runtime.onResult(message.data()));
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
			this.send(target);
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
				this.sendIfReachable(target);
			}
		}
		// A plain click: Kanto's blocks do nothing in Minecraft, and nothing should reach the server.
		return InteractionResult.FAIL;
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

	private void onResult(JsonObject data) {
		this.results++;
		if (!JsonFields.bool(data, "ok", false)) {
			this.failures++;
			String reason = JsonFields.str(data, "reason");
			this.lastReason = reason != null && reason.matches("^[a-z][a-z0-9_.]{0,63}$") ? reason : "?";
			LOGGER.debug("act: refused ({})", this.lastReason);
		}
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
		return I18n.get("kantobridge.debug.act", this.gate.interacts(), this.gate.buttons(), this.results, this.failures,
				this.lastReason == null ? "-" : this.lastReason.toLowerCase(Locale.ROOT));
	}
}
