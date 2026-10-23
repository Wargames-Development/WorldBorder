package com.wimbli.WorldBorder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;


/**
 * Sends compact authoritative border state to the optional WorldBorder Client
 * companion. Clients without the companion simply ignore this custom payload.
 */
public final class BorderClientSync
{
	public static final String CHANNEL = "WDBorder";
	private static final int STATE_PROTOCOL_VERSION = 1;
	private static final int VEHICLE_TRANSFORM_PROTOCOL_VERSION = 3;
	private static final Map<UUID, String> lastState = Collections.synchronizedMap(new HashMap<UUID, String>());
	private static final Map<UUID, Long> lastSentAt = Collections.synchronizedMap(new HashMap<UUID, Long>());
	private static final long RESEND_INTERVAL_MS = 5000L;

	private BorderClientSync() {}

	public static void register(WorldBorder plugin)
	{
		plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
	}

	public static void unregister(WorldBorder plugin)
	{
		try
		{
			plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
		}
		catch (Throwable ignored) {}
		lastState.clear();
		lastSentAt.clear();
	}

	public static void forget(Player player)
	{
		if (player != null)
		{
			lastState.remove(player.getUniqueId());
			lastSentAt.remove(player.getUniqueId());
		}
	}

	public static void sync(Player player, BorderData border)
	{
		if (player == null || !player.isOnline())
			return;

		boolean round = false;
		if (border != null)
			round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();

		boolean active = border != null && Config.ClientBorderRendererEnabled() && !round;
		String signature;
		if (!active)
		{
			signature = "off|" + player.getWorld().getName();
		}
		else
		{
			signature = player.getWorld().getName() + "|" + border.getMinX() + "|" + border.getMaxX() + "|" +
				border.getMinZ() + "|" + border.getMaxZ() + "|" + border.getTopology().getConfigName() + "|" +
				Config.ClientBorderRenderDistance();
		}

		UUID id = player.getUniqueId();
		String previous = lastState.get(id);
		Long sentAt = lastSentAt.get(id);
		long now = System.currentTimeMillis();
		if (signature.equals(previous) && sentAt != null && now - sentAt.longValue() < RESEND_INTERVAL_MS)
			return;

		try
		{
			ByteArrayOutputStream raw = new ByteArrayOutputStream(64);
			DataOutputStream out = new DataOutputStream(raw);
			out.writeByte(STATE_PROTOCOL_VERSION);
			out.writeBoolean(active);
			if (active)
			{
				out.writeDouble(border.getMinX());
				out.writeDouble(border.getMaxX());
				out.writeDouble(border.getMinZ());
				out.writeDouble(border.getMaxZ());
				out.writeBoolean(border.getTopology().isXPassable());
				out.writeBoolean(border.getTopology().isZPassable());
				out.writeInt(Config.ClientBorderRenderDistance());
			}
			out.flush();
			player.sendPluginMessage(WorldBorder.plugin, CHANNEL, raw.toByteArray());
			lastState.put(id, signature);
			lastSentAt.put(id, Long.valueOf(now));
		}
		catch (Throwable ex)
		{
			if (Config.Debug())
				Config.logWarn("Unable to sync WorldBorder Client state to " + player.getName() + ": " + ex.getClass().getSimpleName());
		}
	}
	public static void sendVehicleOrientationToRiders(ModVehicleSupport.VehicleContext vehicle, float targetYaw, float yawDelta)
	{
		if (vehicle == null || !vehicle.isMCHeliVehicle())
			return;

		yawDelta = BorderMath.normalizeYaw(yawDelta);
		if (Math.abs(yawDelta) < 0.001F)
			return;

		for (Player candidate : Bukkit.getServer().getOnlinePlayers())
		{
			ModVehicleSupport.VehicleContext context = ModVehicleSupport.inspect(candidate);
			if (context != null && vehicle.sameRoot(context))
				sendVehicleOrientation(candidate, targetYaw, yawDelta);
		}
	}

	private static void sendVehicleOrientation(Player player, float targetYaw, float yawDelta)
	{
		if (player == null || !player.isOnline())
			return;
		try
		{
			ByteArrayOutputStream raw = new ByteArrayOutputStream(16);
			DataOutputStream out = new DataOutputStream(raw);
			out.writeByte(VEHICLE_TRANSFORM_PROTOCOL_VERSION);
			out.writeFloat(targetYaw);
			out.writeFloat(yawDelta);
			out.flush();
			player.sendPluginMessage(WorldBorder.plugin, CHANNEL, raw.toByteArray());
		}
		catch (Throwable ex)
		{
			if (Config.Debug())
				Config.logWarn("Unable to sync supported vehicle seam orientation to " + player.getName() + ": " + ex.getClass().getSimpleName());
		}
	}

}
