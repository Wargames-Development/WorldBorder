package com.wimbli.WorldBorder;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;


public class BorderCheckTask implements Runnable
{
	@Override
	public void run()
	{
		for (Player player : Bukkit.getServer().getOnlinePlayers())
		{
			BorderData border = Config.Border(player.getWorld().getName());
			BorderClientSync.sync(player, border);
			if (border != null)
				BorderVisuals.show(player, border);
			checkPlayer(player, null, false, true);
		}

		BorderProjectileSupport.tick();
	}

	// track players who are being handled (moved back inside the border) already; needed since Bukkit is sometimes sending teleport events with the old (now incorrect) location still indicated, which can lead to a loop when we then teleport them thinking they're outside the border, triggering event again, etc.
	private static Set<String> handlingPlayers = Collections.synchronizedSet(new LinkedHashSet<String>());

	// set targetLoc only if not current player location; set returnLocationOnly to true to have new Location returned if they need to be moved to one, instead of directly handling it
	public static Location checkPlayer(Player player, Location targetLoc, boolean returnLocationOnly, boolean notify)
	{
		if (player == null || !player.isOnline()) return null;

		Location playerLoc = (targetLoc == null) ? player.getLocation().clone() : targetLoc;
		if (playerLoc == null) return null;

		World world = playerLoc.getWorld();
		if (world == null) return null;
		BorderData border = Config.Border(world.getName());
		if (border == null) return null;

		// Preserve the plugin's legacy "knockback 0 disables a normal border" behavior,
		// but do not let that old switch accidentally disable an explicitly configured
		// traversable topology such as globe/torus/cylinder.
		if (Config.KnockBack() == 0.0D && border.getTopology() == BorderTopology.NORMAL)
			return null;

		ModVehicleSupport.VehicleContext modVehicle = ModVehicleSupport.inspect(player);
		ModVehicleSupport.VehicleSnapshot modSnapshot = null;

		// For supported mod vehicles, the vehicle root is the authoritative border position.
		// Secondary MCHeli seats can extend beyond a border while the aircraft itself remains inside.
		Location loc = playerLoc;
		if (targetLoc == null && modVehicle != null)
		{
			modSnapshot = modVehicle.snapshot(world);
			if (modSnapshot != null)
				loc = modSnapshot.getLocation();
		}

		// NTM landing targets are authoritative every tick. Rewrite the target first so the
		// rocket and border plugin do not repeatedly pull the rider to opposite coordinates.
		if (modVehicle != null)
			modVehicle.normalizeLandingTarget(border, Config.ShapeRound());

		// Active NTM launch/undocking trajectories may ignore only a green/passable seam.
		// A NORMAL/red edge is still authoritative, so a rocket cannot use its launch state
		// as a way to escape a genuinely closed world border.
		if (targetLoc == null && modVehicle != null && modVehicle.shouldBypassHorizontalBorder() &&
			!border.insideBorder(loc.getX(), loc.getZ(), Config.ShapeRound()) &&
			border.isPassableDestination(loc.getX(), loc.getZ(), Config.ShapeRound()))
			return null;

		if (border.insideBorder(loc.getX(), loc.getZ(), Config.ShapeRound()))
			return null;

		// if player is in bypass list (from bypass command), allow them beyond border; also ignore players currently being handled already
		if (Config.isPlayerBypassing(player.getUniqueId()) || handlingPlayers.contains(player.getName().toLowerCase()))
			return null;

		// tag this player as being handled so we can't get stuck in a loop due to Bukkit currently sometimes repeatedly providing incorrect location through teleport event
		handlingPlayers.add(player.getName().toLowerCase());

		BorderTransform transform = border.transformPosition(loc, Config.ShapeRound());
		if (Config.Debug())
		{
			Config.logWarn("Topology transform [" + border.getTopology().getConfigName() + "] raw X/Z " +
				Config.coord.format(loc.getX()) + ", " + Config.coord.format(loc.getZ()) + " -> " +
				Config.coord.format(transform.getX()) + ", " + Config.coord.format(transform.getZ()) +
				"; yaw " + Config.coord.format(loc.getYaw()) + " -> " +
				Config.coord.format(BorderMath.transformYaw(loc.getYaw(), transform.crossedPole())) +
				"; xSeam=" + transform.crossedXSeam() + ", pole=" + transform.crossedPole() + ".");
		}
		Location newLoc = newLocation(player, loc, border, notify);
		if (newLoc == null)
		{
			handlingPlayers.remove(player.getName().toLowerCase());
			return null;
		}

		// Supported mod vehicles expose a stable hook from their own source. Move the root entity
		// directly without ejecting any rider, preserving MCHeli seat graphs and NTM capsule mounts.
		if (!returnLocationOnly && modVehicle != null && modSnapshot != null)
		{
			Vector velocity = transformVelocity(modSnapshot.getVelocity(), border, loc, transform);
			float yawDelta = BorderMath.normalizeYaw(newLoc.getYaw() - modSnapshot.getLocation().getYaw());
			preloadDestination(world, newLoc);
			newLoc = ensureAirVehicleClearance(world, newLoc, modVehicle, transform, border, velocity);
			if (modVehicle.move(newLoc, velocity))
			{
				boolean refreshedChunks = modVehicle.refreshChunkRegistration();
				BorderClientSync.sendVehicleOrientationToRiders(modVehicle, newLoc.getYaw(), yawDelta);
				scheduleVehiclePostWrapStabilization(modVehicle, newLoc.getYaw(), Math.abs(yawDelta) > 0.001F);
				if (Config.Debug())
				{
					Config.logWarn("Wrapped supported mod vehicle \"" + modVehicle.describe() + "\" without dismounting its rider.");
					Config.logWarn("Supported vehicle chunk registration refresh: " +
						(refreshedChunks ? "applied" : "incomplete") + " (" + modVehicle.getLastChunkRefreshDetail() + ").");
				}
				Config.showWhooshEffect(playerLoc);
				handlingPlayers.remove(player.getName().toLowerCase());
				return null;
			}
			// If the compatibility hook fails unexpectedly, fall through to the old Bukkit vehicle path.
		}

		boolean handlingVehicle = false;
		Vector originalPlayerVelocity = copyVector(player.getVelocity());

		/*
		 * Bukkit 1.7 refuses normal entity teleports while passengers are attached. Vanilla/generic
		 * vehicles therefore retain the legacy eject -> move -> remount path. Mod vehicles with the
		 * WDG hook above never come through here.
		 */
		if (!returnLocationOnly && player.isInsideVehicle())
		{
			Entity ride = player.getVehicle();
			player.leaveVehicle();
			if (ride != null)
			{
				double vertOffset = (ride instanceof LivingEntity) ? 0 : ride.getLocation().getY() - playerLoc.getY();
				Location rideLoc = newLoc.clone();
				rideLoc.setY(newLoc.getY() + vertOffset);
				Vector rideVelocity = transformVelocity(copyVector(ride.getVelocity()), border, loc, transform);
				if (Config.Debug())
					Config.logWarn("Player was riding a \"" + ride.toString() + "\".");

				preloadDestination(world, rideLoc);
				if (ride instanceof Boat)
				{
					// old 1.7 clients commonly desync boats after direct teleport, retain the proven workaround
					ride.remove();
					ride = world.spawnEntity(rideLoc, EntityType.BOAT);
				}
				else
				{
					ride.teleport(rideLoc, TeleportCause.PLUGIN);
				}
				ride.setVelocity(rideVelocity);

				if (Config.RemountTicks() > 0)
				{
					setPassengerDelayed(ride, player, player.getName(), Config.RemountTicks());
					handlingVehicle = true;
				}
			}
		}

		// check if player has something (a pet, maybe?) riding them; only possible through odd plugins.
		// it can prevent all teleportation of the player completely, so it's very much not good and needs handling
		if (!returnLocationOnly && player.getPassenger() != null)
		{
			Entity rider = player.getPassenger();
			player.eject();
			rider.teleport(newLoc, TeleportCause.PLUGIN);
			player.sendMessage("Your passenger has been ejected.");
			if (Config.Debug())
				Config.logWarn("Player had a passenger riding on them: " + rider.getType());
		}

		Config.showWhooshEffect(playerLoc);

		if (!returnLocationOnly)
		{
			player.teleport(newLoc, TeleportCause.PLUGIN);
			if (!player.isInsideVehicle())
				player.setVelocity(transformVelocity(originalPlayerVelocity, border, loc, transform));
		}

		if (!handlingVehicle)
			handlingPlayers.remove(player.getName().toLowerCase());

		if (returnLocationOnly)
			return newLoc;

		return null;
	}

	public static Location checkPlayer(Player player, Location targetLoc, boolean returnLocationOnly)
	{
		return checkPlayer(player, targetLoc, returnLocationOnly, true);
	}

	private static Location newLocation(Player player, Location loc, BorderData border, boolean notify)
	{
		if (Config.Debug())
		{
			Config.logWarn((notify ? "Border crossing" : "Check was run") + " in \"" + loc.getWorld().getName() + "\". Border " + border.toString());
			Config.logWarn("Player/vehicle position X: " + Config.coord.format(loc.getX()) + " Y: " + Config.coord.format(loc.getY()) + " Z: " + Config.coord.format(loc.getZ()));
		}

		boolean preserveVehicleY = player.isInsideVehicle();
		boolean surfaceTravel = isGroundedSurfaceTraveller(player);
		Location newLoc = border.correctedPosition(loc, Config.ShapeRound(), player.isFlying() || preserveVehicleY, preserveVehicleY, surfaceTravel);

		// it's remotely possible (such as in the Nether) a suitable location isn't available, in which case...
		if (newLoc == null)
		{
			if (Config.Debug())
				Config.logWarn("Target new location unviable, using spawn or killing player.");
			if (Config.getIfPlayerKill())
			{
				player.setHealth(0.0D);
				return null;
			}
			newLoc = player.getWorld().getSpawnLocation();
		}

		if (Config.Debug())
			Config.logWarn("New position in world \"" + newLoc.getWorld().getName() + "\" at X: " + Config.coord.format(newLoc.getX()) + " Y: " + Config.coord.format(newLoc.getY()) + " Z: " + Config.coord.format(newLoc.getZ()));

		if (notify)
			player.sendMessage(Config.Message());

		return newLoc;
	}

	private static boolean isGroundedSurfaceTraveller(Player player)
	{
		if (player == null || player.isFlying() || player.isInsideVehicle())
			return false;

		try
		{
			return player.isOnGround();
		}
		catch (Throwable ignored)
		{
			return false;
		}
	}

	private static Vector copyVector(Vector velocity)
	{
		if (velocity == null)
			return new Vector(0, 0, 0);
		return new Vector(velocity.getX(), velocity.getY(), velocity.getZ());
	}

	private static Vector transformVelocity(Vector velocity, BorderData border, Location source, BorderTransform transform)
	{
		Vector transformed = copyVector(velocity);
		if (transform.crossedPole())
		{
			// Mirrored pole pairing reverses the complete horizontal travel vector
			// while preserving climb/descent velocity.
			transformed.setX(-transformed.getX());
			transformed.setZ(-transformed.getZ());
		}

		if (border == null || source == null)
			return transformed;

		boolean round = (border.getShape() == null) ? Config.ShapeRound() : border.getShape().booleanValue();
		if (round)
		{
			// A closed circular/elliptic border has no single axis-aligned surface normal.
			// Stop horizontal outward motion after correction rather than allowing a vehicle
			// to repeatedly push through the red wall. TORUS remains passable.
			if (border.getTopology() != BorderTopology.TORUS)
			{
				transformed.setX(0.0D);
				transformed.setZ(0.0D);
			}
			return transformed;
		}

		// Closed/red rectangle edges act like a barrier: remove only the component
		// still trying to travel farther outside. Tangential and vertical movement survive.
		if (!border.getTopology().isXPassable())
		{
			if (source.getX() < border.getMinX() && transformed.getX() < 0.0D)
				transformed.setX(0.0D);
			else if (source.getX() > border.getMaxX() && transformed.getX() > 0.0D)
				transformed.setX(0.0D);
		}

		if (!border.getTopology().isZPassable())
		{
			if (source.getZ() < border.getMinZ() && transformed.getZ() < 0.0D)
				transformed.setZ(0.0D);
			else if (source.getZ() > border.getMaxZ() && transformed.getZ() > 0.0D)
				transformed.setZ(0.0D);
		}

		return transformed;
	}

	private static Location ensureAirVehicleClearance(World world, Location destination, ModVehicleSupport.VehicleContext vehicle,
		BorderTransform transform, BorderData border, Vector velocity)
	{
		if (world == null || destination == null || vehicle == null || transform == null || !vehicle.isAirVehicle())
			return destination;

		// Only passable seam relocation needs this. A closed/red wall should not lift
		// an aircraft merely because it touched the boundary.
		if (!transform.crossedXSeam() && !transform.crossedPole())
			return destination;

		int radius = Config.VehicleAirWrapScanRadiusBlocks();
		int clearance = Config.VehicleAirWrapClearanceBlocks();
		double lookaheadSeconds = Config.VehicleAirWrapLookaheadSeconds();
		int lookaheadMaxBlocks = Config.VehicleAirWrapLookaheadMaxBlocks();
		Vector motion = copyVector(velocity);
		double horizontalSpeed = Math.sqrt(motion.getX() * motion.getX() + motion.getZ() * motion.getZ());

		// Predict the aircraft's constant-velocity corridor after the seam.  This is
		// deliberately based on the already-transformed velocity, so a mirrored pole
		// scans the direction the aircraft will actually leave the pole in.
		double lookaheadTicks = Math.max(0.0D, lookaheadSeconds * 20.0D);
		if (horizontalSpeed > 0.0001D)
		{
			if (lookaheadMaxBlocks > 0)
				lookaheadTicks = Math.min(lookaheadTicks, (double)lookaheadMaxBlocks / horizontalSpeed);
			double nextBorderTicks = ticksUntilNextBorder(destination, border, motion);
			if (!Double.isInfinite(nextBorderTicks))
				lookaheadTicks = Math.min(lookaheadTicks, Math.max(0.0D, nextBorderTicks));
		}
		else
		{
			lookaheadTicks = 0.0D;
		}

		double lookaheadDistance = horizontalSpeed * lookaheadTicks;
		// Samples overlap by at least half the configured aircraft footprint.  The
		// height cache means overlapping discs do not repeatedly query the same column.
		double sampleStep = (radius <= 0) ? 1.0D : Math.max(2.0D, Math.min(8.0D, (double)radius * 0.5D));
		int sampleCount = (lookaheadDistance <= 0.0001D) ? 1 : Math.max(2, (int)Math.ceil(lookaheadDistance / sampleStep) + 1);
		Map<Long, Integer> heightCache = new HashMap<Long, Integer>();

		double requiredStartY = destination.getY();
		int worstSurface = Integer.MIN_VALUE;
		double worstTicks = 0.0D;
		double worstX = destination.getX();
		double worstZ = destination.getZ();

		for (int sample = 0; sample < sampleCount; sample++)
		{
			double distance = (sampleCount == 1) ? 0.0D :
				Math.min(lookaheadDistance, lookaheadDistance * (double)sample / (double)(sampleCount - 1));
			double ticks = (horizontalSpeed > 0.0001D) ? distance / horizontalSpeed : 0.0D;
			double centreX = destination.getX() + motion.getX() * ticks;
			double centreZ = destination.getZ() + motion.getZ() * ticks;
			double predictedY = destination.getY() + motion.getY() * ticks;
			int baseX = Location.locToBlock(centreX);
			int baseZ = Location.locToBlock(centreZ);
			int sampleHighest = Integer.MIN_VALUE;

			for (int dx = -radius; dx <= radius; dx++)
			{
				for (int dz = -radius; dz <= radius; dz++)
				{
					// Circular footprint covers rotors/wings without scanning the unused
					// corners of a square around every centreline sample.
					if (radius > 0 && dx * dx + dz * dz > radius * radius)
						continue;

					int x = baseX + dx;
					int z = baseZ + dz;
					long key = (((long)x) << 32) ^ ((long)z & 0xffffffffL);
					Integer cached = heightCache.get(Long.valueOf(key));
					int surface;
					if (cached == null)
					{
						surface = world.getHighestBlockYAt(x, z);
						heightCache.put(Long.valueOf(key), Integer.valueOf(surface));
					}
					else
					{
						surface = cached.intValue();
					}

					if (surface > sampleHighest)
						sampleHighest = surface;
				}
			}

			if (sampleHighest == Integer.MIN_VALUE)
				continue;

			// Raise the starting altitude just enough that the aircraft's predicted
			// vertical motion still clears this future terrain sample.  A descending
			// aircraft therefore receives more lift than a climbing one.
			double requiredHere = (double)sampleHighest + (double)clearance - (predictedY - destination.getY());
			if (requiredHere > requiredStartY)
			{
				requiredStartY = requiredHere;
				worstSurface = sampleHighest;
				worstTicks = ticks;
				worstX = centreX;
				worstZ = centreZ;
			}
		}

		if (Config.Debug())
		{
			Config.logWarn("Wrapped air-vehicle corridor scan: speed=" + Config.coord.format(horizontalSpeed) +
				" blocks/tick, lookahead=" + Config.coord.format(lookaheadTicks / 20.0D) + "s/" +
				Config.coord.format(lookaheadDistance) + " blocks, samples=" + sampleCount + ", columns=" +
				heightCache.size() + ", radius=" + radius + ", clearance=" + clearance + ".");
		}

		if (requiredStartY <= destination.getY())
			return destination;

		Location raised = destination.clone();
		raised.setY(requiredStartY);
		if (Config.Debug())
		{
			String reason = (worstSurface == Integer.MIN_VALUE) ? "destination terrain" :
				("predicted terrain height " + worstSurface + " near X/Z " + Config.coord.format(worstX) + ", " +
				 Config.coord.format(worstZ) + " at +" + Config.coord.format(worstTicks / 20.0D) + "s");
			Config.logWarn("Raised wrapped air vehicle from Y " + Config.coord.format(destination.getY()) +
				" to " + Config.coord.format(requiredStartY) + " to clear " + reason + ".");
		}
		return raised;
	}

	private static double ticksUntilNextBorder(Location destination, BorderData border, Vector velocity)
	{
		if (destination == null || border == null || velocity == null)
			return Double.POSITIVE_INFINITY;

		double ticks = Double.POSITIVE_INFINITY;
		if (velocity.getX() > 0.0001D)
			ticks = Math.min(ticks, (border.getMaxX() - destination.getX()) / velocity.getX());
		else if (velocity.getX() < -0.0001D)
			ticks = Math.min(ticks, (border.getMinX() - destination.getX()) / velocity.getX());

		if (velocity.getZ() > 0.0001D)
			ticks = Math.min(ticks, (border.getMaxZ() - destination.getZ()) / velocity.getZ());
		else if (velocity.getZ() < -0.0001D)
			ticks = Math.min(ticks, (border.getMinZ() - destination.getZ()) / velocity.getZ());

		if (ticks < 0.0D)
			return 0.0D;
		return ticks;
	}

	private static void preloadDestination(World world, Location destination)
	{
		int chunkX = CoordXZ.blockToChunk(Location.locToBlock(destination.getX()));
		int chunkZ = CoordXZ.blockToChunk(Location.locToBlock(destination.getZ()));
		int radius = Config.VehiclePreloadRadiusChunks();
		for (int x = chunkX - radius; x <= chunkX + radius; x++)
		{
			for (int z = chunkZ - radius; z <= chunkZ + radius; z++)
			{
				Chunk chunk = world.getChunkAt(x, z);
				if (!chunk.isLoaded())
					chunk.load();
			}
		}
	}

	private static void scheduleVehiclePostWrapStabilization(final ModVehicleSupport.VehicleContext vehicle, final float targetYaw, final boolean reassertYaw)
	{
		if (vehicle == null || !vehicle.isMCHeliVehicle())
			return;

		// One tick later MCHeli has completed its normal seat update. Refresh chunk membership
		// again without ticking the entities, and briefly reassert a polar yaw so a stale
		// pre-wrap client rotation packet cannot immediately undo the seam transform.
		Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(WorldBorder.plugin, new Runnable()
		{
			@Override
			public void run()
			{
				boolean refreshed = vehicle.refreshChunkRegistration();
				if (reassertYaw)
					vehicle.setYawOnly(targetYaw);
				if (Config.Debug())
					Config.logWarn("Delayed supported vehicle chunk refresh: " +
						(refreshed ? "applied" : "incomplete") + " (" + vehicle.getLastChunkRefreshDetail() + ").");
			}
		}, 1L);

		if (reassertYaw)
		{
			for (long delay = 2L; delay <= 3L; delay++)
			{
				Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(WorldBorder.plugin, new Runnable()
				{
					@Override
					public void run()
					{
						vehicle.setYawOnly(targetYaw);
					}
				}, delay);
			}
		}
	}

	private static void setPassengerDelayed(final Entity vehicle, final Player player, final String playerName, long delay)
	{
		Bukkit.getServer().getScheduler().scheduleSyncDelayedTask(WorldBorder.plugin, new Runnable()
		{
			@Override
			public void run()
			{
				handlingPlayers.remove(playerName.toLowerCase());
				if (vehicle == null || player == null)
					return;

				vehicle.setPassenger(player);
			}
		}, delay);
	}
}
