package com.wimbli.WorldBorder;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;


/**
 * Tracks Bukkit-visible projectiles only while they are alive. This makes vanilla
 * pearls/arrows/snowballs obey the same red/green edge policy as players without
 * scanning every entity in every world each border tick.
 */
public final class BorderProjectileSupport
{
	private static final Set<Projectile> tracked = Collections.synchronizedSet(new LinkedHashSet<Projectile>());

	private BorderProjectileSupport() {}

	public static void onLaunch(ProjectileLaunchEvent event)
	{
		if (!Config.TrackProjectilesAtBorder() || event == null || event.isCancelled())
			return;
		if (event.getEntity() != null)
			tracked.add(event.getEntity());
	}

	public static void onHit(ProjectileHitEvent event)
	{
		if (event != null && event.getEntity() != null)
			tracked.remove(event.getEntity());
	}

	public static void clear()
	{
		tracked.clear();
	}

	public static void tick()
	{
		if (!Config.TrackProjectilesAtBorder() || tracked.isEmpty())
			return;

		synchronized (tracked)
		{
			Iterator<Projectile> iterator = tracked.iterator();
			while (iterator.hasNext())
			{
				Projectile projectile = iterator.next();
				if (projectile == null || projectile.isDead() || !projectile.isValid())
				{
					iterator.remove();
					continue;
				}

				Location loc = projectile.getLocation();
				if (loc == null || loc.getWorld() == null)
					continue;
				BorderData border = Config.Border(loc.getWorld().getName());
				if (border == null || (Config.KnockBack() == 0.0D && border.getTopology() == BorderTopology.NORMAL) ||
					border.insideBorder(loc.getX(), loc.getZ(), Config.ShapeRound()))
					continue;

				boolean passable = border.isPassableDestination(loc.getX(), loc.getZ(), Config.ShapeRound());
				if (!passable)
				{
					// A closed/red edge is authoritative. Removing the projectile prevents arrows,
					// pearls and other Bukkit-visible projectiles from interacting through it.
					projectile.remove();
					iterator.remove();
					continue;
				}

				BorderTransform transform = border.transformPosition(loc, Config.ShapeRound());
				Location destination = new Location(loc.getWorld(), transform.getX(), loc.getY(), transform.getZ(),
					BorderMath.transformYaw(loc.getYaw(), transform.crossedPole()), loc.getPitch());
				Vector velocity = projectile.getVelocity();
				if (velocity == null)
					velocity = new Vector(0, 0, 0);
				else
					velocity = new Vector(velocity.getX(), velocity.getY(), velocity.getZ());
				if (transform.crossedPole())
				{
					velocity.setX(-velocity.getX());
					velocity.setZ(-velocity.getZ());
				}

				int chunkX = CoordXZ.blockToChunk(Location.locToBlock(destination.getX()));
				int chunkZ = CoordXZ.blockToChunk(Location.locToBlock(destination.getZ()));
				Chunk chunk = loc.getWorld().getChunkAt(chunkX, chunkZ);
				if (!chunk.isLoaded())
					chunk.load();

				projectile.teleport(destination);
				projectile.setVelocity(velocity);
			}
		}
	}
}
