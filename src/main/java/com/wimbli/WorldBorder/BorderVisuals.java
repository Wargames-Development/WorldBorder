package com.wimbli.WorldBorder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.entity.Player;


public final class BorderVisuals
{
	private static ParticleBridge particleBridge = null;
	private static boolean particleBridgeChecked = false;

	private BorderVisuals() {}

	public static void show(Player player, BorderData border)
	{
		if (player == null || border == null || !Config.BorderVisualsEnabled())
			return;
		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		if (round)
			return;

		Location loc = player.getLocation();
		if (loc == null || loc.getWorld() == null)
			return;

		double minX = border.getX() - border.getRadiusX();
		double maxX = border.getX() + border.getRadiusX();
		double minZ = border.getZ() - border.getRadiusZ();
		double maxZ = border.getZ() + border.getRadiusZ();
		int range = Config.BorderVisualRange();
		int spacing = Config.BorderVisualSpacing();

		if (Config.BorderVisualEastWest())
		{
			boolean strong = !border.getTopology().isXPassable();
			if (Math.abs(loc.getX() - minX) <= range)
				renderXEdge(player, minX, loc, minZ, maxZ, spacing, strong);
			if (Math.abs(loc.getX() - maxX) <= range)
				renderXEdge(player, maxX, loc, minZ, maxZ, spacing, strong);
		}

		if (Config.BorderVisualNorthSouth())
		{
			boolean strong = !border.getTopology().isZPassable();
			if (Math.abs(loc.getZ() - minZ) <= range)
				renderZEdge(player, minZ, loc, minX, maxX, spacing, strong);
			if (Math.abs(loc.getZ() - maxZ) <= range)
				renderZEdge(player, maxZ, loc, minX, maxX, spacing, strong);
		}
	}

	private static void renderXEdge(Player player, double edgeX, Location playerLoc, double minZ, double maxZ, int spacing, boolean strong)
	{
		double centre = clamp(playerLoc.getZ(), minZ, maxZ);
		double halfSpan = Math.max(8.0D, Config.BorderVisualRange() * 0.5D);
		double start = Math.max(minZ, centre - halfSpan);
		double end = Math.min(maxZ, centre + halfSpan);
		double y = playerLoc.getY() + 1.0D;

		for (double z = start; z <= end; z += spacing)
			particle(player, edgeX, y, z);

		if (strong)
			renderVertical(player, edgeX, centre, playerLoc.getY(), spacing);
	}

	private static void renderZEdge(Player player, double edgeZ, Location playerLoc, double minX, double maxX, int spacing, boolean strong)
	{
		double centre = clamp(playerLoc.getX(), minX, maxX);
		double halfSpan = Math.max(8.0D, Config.BorderVisualRange() * 0.5D);
		double start = Math.max(minX, centre - halfSpan);
		double end = Math.min(maxX, centre + halfSpan);
		double y = playerLoc.getY() + 1.0D;

		for (double x = start; x <= end; x += spacing)
			particle(player, x, y, edgeZ);

		if (strong)
			renderVertical(player, centre, edgeZ, playerLoc.getY(), spacing);
	}

	private static void renderVertical(Player player, double x, double z, double playerY, int spacing)
	{
		int verticalSpacing = Math.max(3, spacing);
		for (double y = playerY - 6.0D; y <= playerY + 12.0D; y += verticalSpacing)
			particle(player, x, y, z);
	}

	private static void particle(Player player, double x, double y, double z)
	{
		if (sendBlueDust(player, x, y, z))
			return;

		Location loc = new Location(player.getWorld(), x, y, z);
		player.getWorld().playEffect(loc, Effect.SMOKE, 4);
	}

	private static boolean sendBlueDust(Player player, double x, double y, double z)
	{
		try
		{
			if (!particleBridgeChecked)
			{
				particleBridgeChecked = true;
				particleBridge = ParticleBridge.create(player);
			}
			if (particleBridge == null)
				return false;
			particleBridge.send(player, x, y, z);
			return true;
		}
		catch (Throwable ignored)
		{
			particleBridge = null;
			return false;
		}
	}

	private static double clamp(double value, double min, double max)
	{
		if (value < min) return min;
		if (value > max) return max;
		return value;
	}

	private static class ParticleBridge
	{
		private final Method getHandle;
		private final Field playerConnection;
		private final Constructor<?> packetConstructor;
		private final Method sendPacket;

		private ParticleBridge(Method getHandle, Field playerConnection, Constructor<?> packetConstructor, Method sendPacket)
		{
			this.getHandle = getHandle;
			this.playerConnection = playerConnection;
			this.packetConstructor = packetConstructor;
			this.sendPacket = sendPacket;
		}

		public static ParticleBridge create(Player player) throws Exception
		{
			Method getHandle = player.getClass().getMethod("getHandle", new Class<?>[0]);
			Object handle = getHandle.invoke(player, new Object[0]);
			String nmsPackage = handle.getClass().getPackage().getName();
			Class<?> packetClass = Class.forName(nmsPackage + ".PacketPlayOutWorldParticles");
			Constructor<?> constructor = packetClass.getConstructor(new Class<?>[] {
				String.class, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Float.TYPE, Integer.TYPE
			});
			Field connection = findField(handle.getClass(), "playerConnection");
			Object connectionObject = connection.get(handle);
			Method sendPacket = findSendPacket(connectionObject.getClass(), packetClass);
			return new ParticleBridge(getHandle, connection, constructor, sendPacket);
		}

		public void send(Player player, double x, double y, double z) throws Exception
		{
			Object handle = getHandle.invoke(player, new Object[0]);
			Object connection = playerConnection.get(handle);
			Object packet = packetConstructor.newInstance(new Object[] {
				"reddust", Float.valueOf((float)x), Float.valueOf((float)y), Float.valueOf((float)z),
				Float.valueOf(0.08F), Float.valueOf(0.45F), Float.valueOf(1.0F), Float.valueOf(1.0F), Integer.valueOf(0)
			});
			sendPacket.invoke(connection, new Object[] {packet});
		}

		private static Field findField(Class<?> type, String name) throws Exception
		{
			Class<?> current = type;
			while (current != null)
			{
				try
				{
					Field field = current.getDeclaredField(name);
					field.setAccessible(true);
					return field;
				}
				catch (NoSuchFieldException ignored)
				{
					current = current.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		}

		private static Method findSendPacket(Class<?> type, Class<?> packetClass) throws Exception
		{
			Method[] methods = type.getMethods();
			for (int i = 0; i < methods.length; i++)
			{
				Method method = methods[i];
				if (!method.getName().equals("sendPacket") || method.getParameterTypes().length != 1)
					continue;
				if (method.getParameterTypes()[0].isAssignableFrom(packetClass))
					return method;
			}
			throw new NoSuchMethodException("sendPacket");
		}
	}
}
