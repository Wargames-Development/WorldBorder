package com.wimbli.WorldBorder;


public class BorderTransform
{
	private final double x;
	private final double z;
	private final boolean changed;
	private final boolean crossedXSeam;
	private final boolean crossedPole;

	public BorderTransform(double x, double z, boolean changed, boolean crossedXSeam, boolean crossedPole)
	{
		this.x = x;
		this.z = z;
		this.changed = changed;
		this.crossedXSeam = crossedXSeam;
		this.crossedPole = crossedPole;
	}

	public double getX()
	{
		return x;
	}

	public double getZ()
	{
		return z;
	}

	public boolean isChanged()
	{
		return changed;
	}

	public boolean crossedXSeam()
	{
		return crossedXSeam;
	}

	public boolean crossedPole()
	{
		return crossedPole;
	}
}
