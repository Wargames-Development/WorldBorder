package com.wimbli.WorldBorder;


public final class BorderMath
{
	private BorderMath() {}

	public static BorderTransform transformRectangle(double xLoc, double zLoc, double minX, double maxX, double minZ, double maxZ, double knockBack, BorderTopology topology)
	{
		double originalX = xLoc;
		double originalZ = zLoc;
		boolean crossedX = false;
		boolean crossedPole = false;

		if (topology == null)
			topology = BorderTopology.NORMAL;

		if (topology == BorderTopology.NORMAL)
		{
			if (xLoc <= minX)
				xLoc = minX + knockBack;
			else if (xLoc >= maxX)
				xLoc = maxX - knockBack;

			if (zLoc <= minZ)
				zLoc = minZ + knockBack;
			else if (zLoc >= maxZ)
				zLoc = maxZ - knockBack;
		}
		else if (topology == BorderTopology.CYLINDER)
		{
			if (xLoc < minX || xLoc > maxX)
			{
				xLoc = wrap(xLoc, minX, maxX);
				crossedX = true;
			}

			if (zLoc <= minZ)
				zLoc = minZ + knockBack;
			else if (zLoc >= maxZ)
				zLoc = maxZ - knockBack;
		}
		else if (topology == BorderTopology.TORUS)
		{
			if (xLoc < minX || xLoc > maxX)
			{
				xLoc = wrap(xLoc, minX, maxX);
				crossedX = true;
			}
			if (zLoc < minZ || zLoc > maxZ)
				zLoc = wrap(zLoc, minZ, maxZ);
		}
		else if (topology == BorderTopology.GLOBE)
		{
			double width = maxX - minX;
			double height = maxZ - minZ;
			double centerX = (minX + maxX) * 0.5D;

			// East/west keeps the normal rectangular-map seam. Normalizing X first
			// makes simultaneous corner/high-speed crossings deterministic.
			if (xLoc < minX || xLoc > maxX)
			{
				xLoc = wrap(xLoc, minX, maxX);
				crossedX = true;
			}

			if ((zLoc < minZ || zLoc > maxZ) && height > 0.0)
			{
				// North/south uses the WDG rectangular Earth-map pairing: stay at the
				// same pole, reflect the Z overshoot inward, and mirror X about the
				// horizontal map centre. For a border centred at X=0 this is +X <-> -X.
				double t = zLoc - minZ;
				long segment = (long)Math.floor(t / height);
				double remainder = t - ((double)segment * height);
				boolean odd = (Math.abs(segment) % 2L) == 1L;

				if (odd)
				{
					zLoc = maxZ - remainder;
					xLoc = (2.0D * centerX) - xLoc;
					crossedPole = true;
				}
				else
				{
					zLoc = minZ + remainder;
				}

				if (width > 0.0D)
					xLoc = wrap(xLoc, minX, maxX);
			}
		}

		boolean changed = different(originalX, xLoc) || different(originalZ, zLoc);
		return new BorderTransform(xLoc, zLoc, changed, crossedX, crossedPole);
	}

	public static double wrap(double value, double min, double max)
	{
		double size = max - min;
		if (size <= 0.0)
			return min;

		double offset = (value - min) % size;
		if (offset < 0.0)
			offset += size;
		return min + offset;
	}

	public static float transformYaw(float yaw, boolean crossedPole)
	{
		if (!crossedPole)
			return yaw;
		// Pole pairing mirrors both horizontal map axes from the traveller's
		// perspective, so emerge facing exactly 180 degrees from the entry heading.
		return normalizeYaw(yaw + 180.0F);
	}

	public static float normalizeYaw(float yaw)
	{
		while (yaw <= -180.0F)
			yaw += 360.0F;
		while (yaw > 180.0F)
			yaw -= 360.0F;
		return yaw;
	}

	private static boolean different(double a, double b)
	{
		return Math.abs(a - b) > 0.0000001D;
	}
}
