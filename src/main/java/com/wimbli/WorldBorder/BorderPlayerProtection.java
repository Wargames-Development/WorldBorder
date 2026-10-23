package com.wimbli.WorldBorder;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;


/**
 * Immediate player-facing border enforcement. The periodic border task remains the
 * safety net for vehicles and unusual movement, while this class makes a closed
 * rectangular border feel like a solid wall and prevents interacting through either
 * a closed (red) edge or a traversable (green) seam.
 */
public final class BorderPlayerProtection
{
	private static final double PLAYER_HALF_WIDTH = 0.31D;

	private BorderPlayerProtection() {}

	public static MoveResolution resolveMove(Player player, Location from, Location to)
	{
		if (player == null || from == null || to == null || to.getWorld() == null ||
			from.getWorld() == null || from.getWorld() != to.getWorld())
			return null;
		if (Config.isPlayerBypassing(player.getUniqueId()) || player.isInsideVehicle())
			return null;

		BorderData border = Config.Border(to.getWorld().getName());
		if (border == null)
			return null;

		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		BorderTopology topology = border.getTopology();

		if (round)
		{
			if (border.insideBorder(to.getX(), to.getZ(), true))
				return null;

			if (topology == BorderTopology.TORUS)
			{
				Location wrapped = BorderCheckTask.checkPlayer(player, to, true, false);
				return wrapped == null ? null : new MoveResolution(wrapped, false);
			}

			// A closed elliptic border cannot be represented as an axis-aligned block wall.
			// Keep the player's last legal position instead of allowing penetration and
			// then knocking/damaging them back.
			return new MoveResolution(from.clone(), true);
		}

		if (topology == BorderTopology.GLOBE || topology == BorderTopology.TORUS)
		{
			if (border.insideBorder(to.getX(), to.getZ(), false))
				return null;

			BorderTransform transform = border.transformPosition(to, false);
			Location wrapped = BorderCheckTask.checkPlayer(player, to, true, false);
			if (wrapped != null && transform.crossedPole())
			{
				Vector velocity = player.getVelocity();
				if (velocity != null)
					player.setVelocity(new Vector(-velocity.getX(), velocity.getY(), -velocity.getZ()));
			}
			return wrapped == null ? null : new MoveResolution(wrapped, false);
		}

		if (topology == BorderTopology.CYLINDER)
		{
			double x = to.getX();
			double z = to.getZ();
			boolean wrappedX = x < border.getMinX() || x > border.getMaxX();
			boolean hitWall = z < border.getMinZ() + PLAYER_HALF_WIDTH || z > border.getMaxZ() - PLAYER_HALF_WIDTH;

			if (!wrappedX && !hitWall)
				return null;

			if (wrappedX)
				x = BorderMath.wrap(x, border.getMinX(), border.getMaxX());
			z = clamp(z, border.getMinZ() + PLAYER_HALF_WIDTH, border.getMaxZ() - PLAYER_HALF_WIDTH);

			Location result = new Location(to.getWorld(), x, to.getY(), z, to.getYaw(), to.getPitch());
			if (wrappedX && player.isOnGround() && !player.isFlying())
				result = border.correctedPosition(result, false, false, false, true);

			if (hitWall)
				stopOutwardVelocity(player, border, to, false, true);
			return new MoveResolution(result, hitWall);
		}

		// NORMAL rectangular topology: stop the player's collision box at the plane.
		double minX = border.getMinX() + PLAYER_HALF_WIDTH;
		double maxX = border.getMaxX() - PLAYER_HALF_WIDTH;
		double minZ = border.getMinZ() + PLAYER_HALF_WIDTH;
		double maxZ = border.getMaxZ() - PLAYER_HALF_WIDTH;
		double x = clamp(to.getX(), minX, maxX);
		double z = clamp(to.getZ(), minZ, maxZ);

		if (same(x, to.getX()) && same(z, to.getZ()))
			return null;

		Location blocked = new Location(to.getWorld(), x, to.getY(), z, to.getYaw(), to.getPitch());
		stopOutwardVelocity(player, border, to, true, true);
		return new MoveResolution(blocked, true);
	}

	public static boolean blocksBlockInteraction(Player player, Block block)
	{
		if (player == null || block == null || block.getWorld() == null ||
			Config.isPlayerBypassing(player.getUniqueId()))
			return false;

		BorderData border = Config.Border(block.getWorld().getName());
		if (border == null)
			return false;

		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		// Use the block centre rather than its integer corner. A block centred beyond the
		// seam is on the other side and cannot be mined/used through the visual wall.
		double x = block.getX() + 0.5D;
		double z = block.getZ() + 0.5D;
		return !border.insideBorder(x, z, round);
	}

	public static boolean blocksEntityInteraction(Player player, Entity target)
	{
		if (player == null || target == null || target.getWorld() == null ||
			Config.isPlayerBypassing(player.getUniqueId()))
			return false;

		BorderData border = Config.Border(target.getWorld().getName());
		if (border == null)
			return false;

		Location loc = target.getLocation();
		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		return !border.insideBorder(loc.getX(), loc.getZ(), round);
	}

	private static void stopOutwardVelocity(Player player, BorderData border, Location attempted, boolean blockX, boolean blockZ)
	{
		Vector velocity = player.getVelocity();
		if (velocity == null)
			return;

		double vx = velocity.getX();
		double vz = velocity.getZ();
		boolean changed = false;

		if (blockX)
		{
			if (attempted.getX() <= border.getMinX() + PLAYER_HALF_WIDTH && vx < 0.0D)
			{
				vx = 0.0D;
				changed = true;
			}
			else if (attempted.getX() >= border.getMaxX() - PLAYER_HALF_WIDTH && vx > 0.0D)
			{
				vx = 0.0D;
				changed = true;
			}
		}

		if (blockZ)
		{
			if (attempted.getZ() <= border.getMinZ() + PLAYER_HALF_WIDTH && vz < 0.0D)
			{
				vz = 0.0D;
				changed = true;
			}
			else if (attempted.getZ() >= border.getMaxZ() - PLAYER_HALF_WIDTH && vz > 0.0D)
			{
				vz = 0.0D;
				changed = true;
			}
		}

		if (changed)
			player.setVelocity(new Vector(vx, velocity.getY(), vz));
	}

	private static double clamp(double value, double min, double max)
	{
		if (min > max)
			return (min + max) * 0.5D;
		if (value < min)
			return min;
		if (value > max)
			return max;
		return value;
	}

	private static boolean same(double a, double b)
	{
		return Math.abs(a - b) < 0.0000001D;
	}

	public static final class MoveResolution
	{
		private final Location destination;
		private final boolean hardWall;

		public MoveResolution(Location destination, boolean hardWall)
		{
			this.destination = destination;
			this.hardWall = hardWall;
		}

		public Location getDestination()
		{
			return destination;
		}

		public boolean isHardWall()
		{
			return hardWall;
		}
	}
}
