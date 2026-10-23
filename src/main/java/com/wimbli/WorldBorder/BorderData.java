package com.wimbli.WorldBorder;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;


public class BorderData
{
	// the main data interacted with
	private double x = 0;
	private double z = 0;
	private int radiusX = 0;
	private int radiusZ = 0;
	private Boolean shapeRound = null;
	private BorderTopology topology = BorderTopology.NORMAL;

	// some extra data kept handy for faster border checks
	private double maxX;
	private double minX;
	private double maxZ;
	private double minZ;
	private double radiusXSquared;
	private double radiusZSquared;
	private double DefiniteRectangleX;
	private double DefiniteRectangleZ;
	private double radiusSquaredQuotient;

	public BorderData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound, boolean wrap)
	{
		setData(x, z, radiusX, radiusZ, shapeRound, wrap ? BorderTopology.TORUS : BorderTopology.NORMAL);
	}
	public BorderData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound, BorderTopology topology)
	{
		setData(x, z, radiusX, radiusZ, shapeRound, topology);
	}
	public BorderData(double x, double z, int radiusX, int radiusZ)
	{
		setData(x, z, radiusX, radiusZ, null);
	}
	public BorderData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound)
	{
		setData(x, z, radiusX, radiusZ, shapeRound);
	}
	public BorderData(double x, double z, int radius)
	{
		setData(x, z, radius, null);
	}
	public BorderData(double x, double z, int radius, Boolean shapeRound)
	{
		setData(x, z, radius, shapeRound);
	}

	public final void setData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound, boolean wrap)
	{
		setData(x, z, radiusX, radiusZ, shapeRound, wrap ? BorderTopology.TORUS : BorderTopology.NORMAL);
	}
	public final void setData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound, BorderTopology topology)
	{
		this.x = x;
		this.z = z;
		this.shapeRound = shapeRound;
		this.topology = (topology == null) ? BorderTopology.NORMAL : topology;
		this.setRadiusX(radiusX);
		this.setRadiusZ(radiusZ);
	}
	public final void setData(double x, double z, int radiusX, int radiusZ, Boolean shapeRound)
	{
		setData(x, z, radiusX, radiusZ, shapeRound, false);
	}
	public final void setData(double x, double z, int radius, Boolean shapeRound)
	{
		setData(x, z, radius, radius, shapeRound, false);
	}

	public BorderData copy()
	{
		return new BorderData(x, z, radiusX, radiusZ, shapeRound, topology);
	}

	public double getX()
	{
		return x;
	}
	public void setX(double x)
	{
		this.x = x;
		this.maxX = x + radiusX;
		this.minX = x - radiusX;
	}
	public double getZ()
	{
		return z;
	}
	public void setZ(double z)
	{
		this.z = z;
		this.maxZ = z + radiusZ;
		this.minZ = z - radiusZ;
	}

	public double getMinX()
	{
		return minX;
	}
	public double getMaxX()
	{
		return maxX;
	}
	public double getMinZ()
	{
		return minZ;
	}
	public double getMaxZ()
	{
		return maxZ;
	}

	public int getRadiusX()
	{
		return radiusX;
	}
	public int getRadiusZ()
	{
		return radiusZ;
	}
	public void setRadiusX(int radiusX)
	{
		this.radiusX = radiusX;
		this.maxX = x + radiusX;
		this.minX = x - radiusX;
		this.radiusXSquared = (double)radiusX * (double)radiusX;
		this.radiusSquaredQuotient = this.radiusXSquared / this.radiusZSquared;
		this.DefiniteRectangleX = Math.sqrt(.5 * this.radiusXSquared);
	}
	public void setRadiusZ(int radiusZ)
	{
		this.radiusZ = radiusZ;
		this.maxZ = z + radiusZ;
		this.minZ = z - radiusZ;
		this.radiusZSquared = (double)radiusZ * (double)radiusZ;
		this.radiusSquaredQuotient = this.radiusXSquared / this.radiusZSquared;
		this.DefiniteRectangleZ = Math.sqrt(.5 * this.radiusZSquared);
	}


	// backwards-compatible methods from before elliptical/rectangular shapes were supported
	/**
	 * @deprecated  Replaced by {@link #getRadiusX()} and {@link #getRadiusZ()};
	 * this method now returns an average of those two values and is thus imprecise
	 */
	public int getRadius()
	{
		return (radiusX + radiusZ) / 2;  // average radius; not great, but probably best for backwards compatibility
	}
	public void setRadius(int radius)
	{
		setRadiusX(radius);
		setRadiusZ(radius);
	}


	public Boolean getShape()
	{
		return shapeRound;
	}
	public void setShape(Boolean shapeRound)
	{
		this.shapeRound = shapeRound;
	}


	public boolean getWrapping()
	{
		return topology.isWrapping();
	}
	public void setWrapping(boolean wrap)
	{
		this.topology = wrap ? BorderTopology.TORUS : BorderTopology.NORMAL;
	}
	public BorderTopology getTopology()
	{
		return topology;
	}
	public void setTopology(BorderTopology topology)
	{
		this.topology = (topology == null) ? BorderTopology.NORMAL : topology;
	}

	/**
	 * Returns true when an out-of-bounds destination crosses only edges that are
	 * traversable for this topology. Used for pearls, portals, projectiles and
	 * special launch trajectories so green seams behave as actual seams while
	 * red edges remain closed.
	 */
	public boolean isPassableDestination(double xLoc, double zLoc, boolean round)
	{
		if (shapeRound != null)
			round = shapeRound.booleanValue();

		if (insideBorder(xLoc, zLoc, round))
			return true;

		// Cylinder/globe are intentionally rectangular-only. If a config is manually
		// corrupted underneath the command validation, fail closed rather than allowing
		// a crossing whose visual/coordinate semantics are undefined.
		if (round)
			return topology == BorderTopology.TORUS;

		boolean outsideX = xLoc < minX || xLoc > maxX;
		boolean outsideZ = zLoc < minZ || zLoc > maxZ;

		if (outsideX && !topology.isXPassable())
			return false;
		if (outsideZ && !topology.isZPassable())
			return false;
		return outsideX || outsideZ;
	}


	@Override
	public String toString()
	{
		return "radius " + ((radiusX == radiusZ) ? radiusX : radiusX + "x" + radiusZ) + " at X: " + Config.coord.format(x) + " Z: " + Config.coord.format(z) + (shapeRound != null ? (" (shape override: " + Config.ShapeName(shapeRound.booleanValue()) + ")") : "") + (topology != BorderTopology.NORMAL ? (" (topology: " + topology.getConfigName() + ")") : "");
	}

	// This algorithm of course needs to be fast, since it will be run very frequently
	public boolean insideBorder(double xLoc, double zLoc, boolean round)
	{
		// if this border has a shape override set, use it
		if (shapeRound != null)
			round = shapeRound.booleanValue();

		// square border
		if (!round)
			return !(xLoc < minX || xLoc > maxX || zLoc < minZ || zLoc > maxZ);

		// round border
		else
		{
			// elegant round border checking algorithm is from rBorder by Reil with almost no changes, all credit to him for it
			double X = Math.abs(x - xLoc);
			double Z = Math.abs(z - zLoc);

			if (X < DefiniteRectangleX && Z < DefiniteRectangleZ)
				return true;	// Definitely inside
			else if (X >= radiusX || Z >= radiusZ)
				return false;	// Definitely outside
			else if (X * X + Z * Z * radiusSquaredQuotient < radiusXSquared)
				return true;	// After further calculation, inside
			else
				return false;	// Apparently outside, then
		}
	}
	public boolean insideBorder(double xLoc, double zLoc)
	{
		return insideBorder(xLoc, zLoc, Config.ShapeRound());
	}
	public boolean insideBorder(Location loc)
	{
		return insideBorder(loc.getX(), loc.getZ(), Config.ShapeRound());
	}
	public boolean insideBorder(CoordXZ coord, boolean round)
	{
		return insideBorder(coord.x, coord.z, round);
	}
	public boolean insideBorder(CoordXZ coord)
	{
		return insideBorder(coord.x, coord.z, Config.ShapeRound());
	}

	public BorderTransform transformPosition(Location loc, boolean round)
	{
		return transformCoordinates(loc.getX(), loc.getZ(), round);
	}

	public BorderTransform transformCoordinates(double xLoc, double zLoc, boolean round)
	{
		// if this border has a shape override set, use it
		if (shapeRound != null)
			round = shapeRound.booleanValue();

		if (!round)
			return BorderMath.transformRectangle(xLoc, zLoc, minX, maxX, minZ, maxZ, Config.KnockBack(), topology);

		// Cylinder/globe topology only makes mathematical sense for a rectangular projection.
		// If a configuration is changed underneath us, degrade safely to the old antipodal round wrap.
		BorderTopology effectiveTopology = topology;
		if (effectiveTopology == BorderTopology.CYLINDER || effectiveTopology == BorderTopology.GLOBE)
			effectiveTopology = BorderTopology.TORUS;

		double dX = xLoc - x;
		double dZ = zLoc - z;
		double dU = Math.sqrt(dX * dX + dZ * dZ);
		double dT = Math.sqrt(dX * dX / radiusXSquared + dZ * dZ / radiusZSquared);
		if (dU == 0.0D || dT == 0.0D)
			return new BorderTransform(xLoc, zLoc, false, false, false);

		double f = (1 / dT - Config.KnockBack() / dU);
		double newX;
		double newZ;
		if (effectiveTopology == BorderTopology.TORUS)
		{
			newX = x - dX * f;
			newZ = z - dZ * f;
		}
		else
		{
			newX = x + dX * f;
			newZ = z + dZ * f;
		}
		return new BorderTransform(newX, newZ, true, effectiveTopology == BorderTopology.TORUS, false);
	}

	public Location correctedPosition(Location loc, boolean round, boolean flying, boolean preserveY, boolean surfaceTravel)
	{
		BorderTransform transform = transformPosition(loc, round);
		double xLoc = transform.getX();
		double zLoc = transform.getZ();
		double yLoc = loc.getY();
		float yaw = BorderMath.transformYaw(loc.getYaw(), transform.crossedPole());

		int ixLoc = Location.locToBlock(xLoc);
		int izLoc = Location.locToBlock(zLoc);

		// Make sure the chunk we're checking in is actually loaded before any safety scan.
		Chunk tChunk = loc.getWorld().getChunkAt(CoordXZ.blockToChunk(ixLoc), CoordXZ.blockToChunk(izLoc));
		if (!tChunk.isLoaded())
			tChunk.load();

		// A player who crossed while standing on terrain must arrive on the surface at
		// the transformed X/Z. Keeping the source Y can bury them in a hill/building or
		// leave them inside a block when the two seam columns have different terrain.
		// Flying players and vehicles deliberately skip this and retain exact altitude.
		if (surfaceTravel && !flying && !preserveY)
		{
			double surfaceY = getSurfaceY(loc.getWorld(), ixLoc, izLoc);
			if (surfaceY == -1.0D)
				surfaceY = getSafeY(loc.getWorld(), ixLoc, Location.locToBlock(yLoc), izLoc, false);
			if (surfaceY == -1.0D)
				return null;
			return new Location(loc.getWorld(), xLoc, surfaceY, zLoc, yaw, loc.getPitch());
		}

		// Wrapped/projected topologies are intended to be continuous seams. Vehicles
		// preserve exact altitude. Players preserve altitude when it is clear, but if the
		// same Y at the destination would put their feet/head inside terrain, move only
		// far enough vertically to the nearest two-block-high safe opening.
		if (topology != BorderTopology.NORMAL)
		{
			if (!preserveY)
			{
				int checkY = Location.locToBlock(yLoc);
				int limTop = (loc.getWorld().getEnvironment() == World.Environment.NETHER) ? 125 : loc.getWorld().getMaxHeight() - 2;
				if (checkY > limBot && checkY < limTop && !isSafeSpot(loc.getWorld(), ixLoc, checkY, izLoc, true))
				{
					double safeY = getSafeY(loc.getWorld(), ixLoc, checkY, izLoc, true);
					if (safeY == -1.0D)
						return null;
					yLoc = safeY;
				}
			}
			return new Location(loc.getWorld(), xLoc, yLoc, zLoc, yaw, loc.getPitch());
		}

		if (preserveY)
		{
			// Vehicle/flying callers intentionally keep their exact altitude, including modded
			// altitudes above the vanilla build height.
			if (flying)
				return new Location(loc.getWorld(), xLoc, yLoc, zLoc, yaw, loc.getPitch());

			double keptY = clampY(loc.getWorld(), yLoc);
			int keptBlockY = Location.locToBlock(keptY);
			if (isSafeSpot(loc.getWorld(), ixLoc, keptBlockY, izLoc, true))
				return new Location(loc.getWorld(), Math.floor(xLoc) + 0.5, keptY, Math.floor(zLoc) + 0.5, yaw, loc.getPitch());
		}

		yLoc = getSafeY(loc.getWorld(), ixLoc, Location.locToBlock(yLoc), izLoc, flying);
		if (yLoc == -1)
			return null;

		return new Location(loc.getWorld(), Math.floor(xLoc) + 0.5, yLoc, Math.floor(zLoc) + 0.5, yaw, loc.getPitch());
	}

	public Location correctedPosition(Location loc, boolean round, boolean flying, boolean preserveY)
	{
		return correctedPosition(loc, round, flying, preserveY, false);
	}
	public Location correctedPosition(Location loc, boolean round, boolean flying)
	{
		return correctedPosition(loc, round, flying, false);
	}
	public Location correctedPosition(Location loc, boolean round)
	{
		return correctedPosition(loc, round, false, false);
	}
	public Location correctedPosition(Location loc)
	{
		return correctedPosition(loc, Config.ShapeRound(), false, false);
	}

	//these material IDs are acceptable for places to teleport player; breathable blocks and water
	public static final LinkedHashSet<Integer> safeOpenBlocks = new LinkedHashSet<Integer>(Arrays.asList(
		 new Integer[] {0, 6, 8, 9, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 55, 59, 63, 64, 65, 66, 68, 69, 70, 71, 72, 75, 76, 77, 78, 83, 90, 93, 94, 96, 104, 105, 106, 115, 131, 132, 141, 142, 149, 150, 157, 171}
	));

	//these material IDs are ones we don't want to drop the player onto, like cactus or lava or fire or activated Ender portal
	public static final LinkedHashSet<Integer> painfulBlocks = new LinkedHashSet<Integer>(Arrays.asList(
		 new Integer[] {10, 11, 51, 81, 119}
	));

	// check if a particular spot consists of 2 breathable blocks over something relatively solid
	private boolean isSafeSpot(World world, int X, int Y, int Z, boolean flying)
	{
		boolean safe = safeOpenBlocks.contains((Integer)world.getBlockTypeIdAt(X, Y, Z))		// target block open and safe
					&& safeOpenBlocks.contains((Integer)world.getBlockTypeIdAt(X, Y + 1, Z));	// above target block open and safe
		if (!safe || flying)
			return safe;

		Integer below = (Integer)world.getBlockTypeIdAt(X, Y - 1, Z);
		return (safe
			 && (!safeOpenBlocks.contains(below) || below == 8 || below == 9)	// below target block not open/breathable (so presumably solid), or is water
			 && !painfulBlocks.contains(below)									// below target block not painful
			);
	}

	private static final int limBot = 1;

	private double clampY(World world, double Y)
	{
		final int limTop = (world.getEnvironment() == World.Environment.NETHER) ? 125 : world.getMaxHeight() - 2;

		if (Y > limTop)
			return (double) limTop;
		if (Y < limBot)
			return (double) limBot;

		return Y;
	}

	// Find the highest safe standing position in this column. This is intentionally
	// used only for grounded seam crossings, so a surface walker stays on terrain
	// rather than preserving a source Y that may be inside destination terrain.
	private double getSurfaceY(World world, int X, int Z)
	{
		final int limTop = (world.getEnvironment() == World.Environment.NETHER) ? 125 : world.getMaxHeight() - 2;
		for (int y = limTop; y > limBot; y--)
		{
			if (isSafeSpot(world, X, y, Z, false))
				return (double)y;
		}
		return -1.0D;
	}

	// find closest safe Y position from the starting position
	private double getSafeY(World world, int X, int Y, int Z, boolean flying)
	{
		// artificial height limit of 127 added for Nether worlds since CraftBukkit still incorrectly returns 255 for their max height, leading to players sent to the "roof" of the Nether
		final int limTop = (world.getEnvironment() == World.Environment.NETHER) ? 125 : world.getMaxHeight() - 2;
		// Expanding Y search method adapted from Acru's code in the Nether plugin

		for(int y1 = Y, y2 = Y; (y1 > limBot) || (y2 < limTop); y1--, y2++){
			// Look below.
			if(y1 > limBot)
			{
				if (isSafeSpot(world, X, y1, Z, flying))
					return (double)y1;
			}

			// Look above.
			if(y2 < limTop && y2 != y1)
			{
				if (isSafeSpot(world, X, y2, Z, flying))
					return (double)y2;
			}
		}

		return -1.0;	// no safe Y location?!?!? Must be a rare spot in a Nether world or something
	}


	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
			return true;
		else if (obj == null || obj.getClass() != this.getClass())
			return false;

		BorderData test = (BorderData)obj;
		return test.x == this.x && test.z == this.z && test.radiusX == this.radiusX && test.radiusZ == this.radiusZ;
	}

	@Override
	public int hashCode()
	{
		return (((int)(this.x * 10) << 4) + (int)this.z + (this.radiusX << 2) + (this.radiusZ << 3));
	}
}
