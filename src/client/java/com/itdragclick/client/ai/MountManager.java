package com.itdragclick.client.ai;

import com.itdragclick.AmAI;
import com.itdragclick.client.ui.AIDashboardFrame;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import java.util.Locale;

/**
 * Mount/dismount state machine on {@code END_CLIENT_TICK} (main thread).
 *
 * mount &lt;target&gt;: finds the nearest rideable entity (horses/donkeys/mules/
 * camels via AbstractHorse, boats, minecarts, saddled pigs/striders — or an
 * exact name match), walks over with Baritone, and right-clicks it. Untamed
 * horses may buck the bot off; the interact is retried a few times and then
 * the failure announced.
 *
 * dismount: holds the sneak key for a few ticks — the vanilla input path —
 * and verifies the bot actually left the vehicle.
 */
public final class MountManager {

	private enum Phase {IDLE, WALK_TO_MOUNT, DISMOUNTING}

	private static final double SEARCH_RADIUS = 24.0;
	private static final double MOUNT_REACH = 3.0;
	private static final int REPATH_INTERVAL_TICKS = 40;
	private static final int WALK_PATIENCE_TICKS = 600; // 30s to reach the ride
	private static final int INTERACT_RETRY_LIMIT = 3;
	private static final int SNEAK_HOLD_TICKS = 5;

	private static Phase phase = Phase.IDLE;
	private static Entity targetMount = null;
	private static int phaseTicks = 0;
	private static int repathCooldown = 0;
	private static int interactAttempts = 0;
	private static int sneakTicks = 0;

	private MountManager() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(MountManager::onTick);
	}

	public static boolean isMounted() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.isPassenger();
	}

	public static boolean isBusy() {
		return phase != Phase.IDLE;
	}

	/** Stop override: abort a walk-to-mount, but never yank the bot off a ride. */
	public static void cancel() {
		if (phase == Phase.WALK_TO_MOUNT) {
			BaritoneBridge.stopQuietly();
		}
		if (phase == Phase.DISMOUNTING) {
			Minecraft.getInstance().options.keyShift.setDown(false);
		}
		reset();
	}

	/** Movement tasks can't run while riding — get off first, quietly. */
	public static void dismountIfRiding() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.isPassenger()) {
			startDismount(null);
		}
	}

	// -------------------------------------------------------------- orders

	/** targetName may be an entity/display name, or null/"nearby" for the closest ride. */
	public static void startMount(String targetName) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		if (player.isPassenger()) {
			announce(mc, "Already riding something!");
			return;
		}
		Entity ride = findRideable(mc, player, targetName);
		if (ride == null) {
			announce(mc, "No rideable " + (targetName != null && !targetName.isBlank() ? targetName : "thing")
					+ " nearby!");
			return;
		}
		targetMount = ride;
		phase = Phase.WALK_TO_MOUNT;
		phaseTicks = 0;
		repathCooldown = 0;
		interactAttempts = 0;
		BlockPos pos = ride.blockPosition();
		BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
		AIDashboardFrame.appendSystemLog("[MOUNT] Walking to " + ride.getName().getString()
				+ " at " + pos.toShortString() + ".");
	}

	public static void startDismount(Minecraft mcOrNull) {
		Minecraft mc = mcOrNull != null ? mcOrNull : Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (!player.isPassenger()) {
			return;
		}
		phase = Phase.DISMOUNTING;
		sneakTicks = 0;
		phaseTicks = 0;
		mc.options.keyShift.setDown(true);
		AIDashboardFrame.appendSystemLog("[MOUNT] Dismounting.");
	}

	// ---------------------------------------------------------------- tick

	private static void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (phase == Phase.IDLE || player == null || mc.level == null) {
			return;
		}
		phaseTicks++;

		if (phase == Phase.WALK_TO_MOUNT) {
			tickWalkToMount(mc, player);
		} else if (phase == Phase.DISMOUNTING) {
			tickDismount(mc, player);
		}
	}

	private static void tickWalkToMount(Minecraft mc, LocalPlayer player) {
		if (player.isPassenger()) {
			announce(mc, "Mounted up! Let's ride.");
			AIDashboardFrame.appendSystemLog("[MOUNT] Mounted " + (targetMount != null
					? targetMount.getName().getString() : "the ride") + ".");
			reset();
			return;
		}
		if (targetMount == null || targetMount.isRemoved() || !targetMount.isAlive() && targetMount instanceof LivingEntity) {
			announce(mc, "My ride disappeared!");
			reset();
			return;
		}
		if (SurvivalMonitor.isInCombat()) {
			return; // fight first, ride later — Baritone goal is cached/restored
		}
		if (phaseTicks > WALK_PATIENCE_TICKS) {
			announce(mc, "Can't reach the " + targetMount.getName().getString() + " — giving up.");
			BaritoneBridge.stopQuietly();
			reset();
			return;
		}

		double distance = player.distanceTo(targetMount);
		if (distance > MOUNT_REACH) {
			if (--repathCooldown <= 0) {
				BlockPos pos = targetMount.blockPosition();
				BaritoneBridge.goTo(pos.getX(), pos.getY(), pos.getZ());
				repathCooldown = REPATH_INTERVAL_TICKS;
			}
			return;
		}

		// In reach: face it and right-click. Retry a few times (untamed
		// horses buck), then give up loudly.
		BaritoneBridge.stopQuietly();
		if (phaseTicks % 20 != 0) {
			return; // one interact per second, gives the mount time to accept
		}
		if (interactAttempts >= INTERACT_RETRY_LIMIT) {
			announce(mc, "It won't let me ride it!");
			reset();
			return;
		}
		interactAttempts++;
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
				targetMount.position().add(0, targetMount.getBbHeight() * 0.5, 0));
		try {
			mc.gameMode.interact(player, targetMount,
					new net.minecraft.world.phys.EntityHitResult(targetMount), InteractionHand.MAIN_HAND);
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Mount interact failed", e);
		}
	}

	private static void tickDismount(Minecraft mc, LocalPlayer player) {
		sneakTicks++;
		if (sneakTicks <= SNEAK_HOLD_TICKS) {
			mc.options.keyShift.setDown(true);
			return;
		}
		mc.options.keyShift.setDown(false);
		if (!player.isPassenger()) {
			AIDashboardFrame.appendSystemLog("[MOUNT] Dismounted.");
			reset();
			return;
		}
		if (sneakTicks > SNEAK_HOLD_TICKS + 10) {
			// Sneak didn't take (server plugin quirks) — force it.
			AIDashboardFrame.appendSystemLog("[MOUNT] Sneak dismount ignored by server — using stopRiding fallback.");
			player.stopRiding();
			reset();
		}
	}

	// ------------------------------------------------------------- helpers

	private static Entity findRideable(Minecraft mc, LocalPlayer player, String targetName) {
		String wanted = targetName == null ? "" : targetName.toLowerCase(Locale.ROOT)
				.replace('_', ' ').strip();
		boolean any = wanted.isEmpty() || wanted.equals("nearby") || wanted.equals("nearest")
				|| wanted.equals("horse"); // "mount horse" should take any equine
		Entity nearest = null;
		double nearestDistance = SEARCH_RADIUS;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == player || entity.isRemoved() || !isRideable(entity)) {
				continue;
			}
			if (!any) {
				String display = entity.getName().getString().toLowerCase(Locale.ROOT).replace('_', ' ');
				if (!display.equals(wanted)) {
					continue;
				}
			}
			double distance = player.distanceTo(entity);
			if (distance <= nearestDistance) {
				nearest = entity;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	private static boolean isRideable(Entity entity) {
		if (entity instanceof AbstractHorse || entity instanceof AbstractBoat
				|| entity instanceof AbstractMinecart) {
			return true;
		}
		// Pigs/striders only steer with a saddle on.
		return (entity instanceof Pig pig && pig.isSaddled())
				|| (entity instanceof Strider strider && strider.isSaddled());
	}

	private static void reset() {
		phase = Phase.IDLE;
		targetMount = null;
		phaseTicks = 0;
		repathCooldown = 0;
		interactAttempts = 0;
		sneakTicks = 0;
	}

	private static void announce(Minecraft mc, String message) {
		try {
			if (mc.getConnection() != null) {
				mc.getConnection().sendChat(message.length() > 100 ? message.substring(0, 100) : message);
			}
		} catch (Exception e) {
			AmAI.LOGGER.error("[am-ai] Mount announcement failed", e);
		}
	}
}
