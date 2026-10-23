package com.wimbli.WorldBorder;


public enum BorderTopology
{
	NORMAL("normal"),
	CYLINDER("cylinder"),
	TORUS("torus"),
	GLOBE("globe");

	private final String configName;

	private BorderTopology(String configName)
	{
		this.configName = configName;
	}

	public String getConfigName()
	{
		return configName;
	}

	public boolean wrapsX()
	{
		return this == CYLINDER || this == TORUS || this == GLOBE;
	}

	public boolean wrapsZ()
	{
		return this == TORUS;
	}

	/**
	 * Whether the east/west border is a traversable seam rather than a closed wall.
	 */
	public boolean isXPassable()
	{
		return wrapsX();
	}

	/**
	 * Whether the north/south border is a traversable seam rather than a closed wall.
	 * Globe topology reflects Z inward at the same pole and mirrors X across the
	 * rectangular map centre rather than directly wrapping north to south.
	 */
	public boolean isZPassable()
	{
		return this == TORUS || this == GLOBE;
	}

	public boolean usesPolarReflection()
	{
		return this == GLOBE;
	}

	public boolean isWrapping()
	{
		return this != NORMAL;
	}

	public static BorderTopology fromString(String value)
	{
		if (value == null)
			return NORMAL;

		String lower = value.trim().toLowerCase();
		if (lower.equals("normal") || lower.equals("none") || lower.equals("off") || lower.equals("clamp"))
			return NORMAL;
		if (lower.equals("cylinder") || lower.equals("cylindrical") || lower.equals("eastwest") || lower.equals("east-west") || lower.equals("ew"))
			return CYLINDER;
		if (lower.equals("torus") || lower.equals("toroid") || lower.equals("wrap") || lower.equals("wrapped"))
			return TORUS;
		if (lower.equals("globe") || lower.equals("sphere") || lower.equals("spherical") || lower.equals("earth"))
			return GLOBE;
		return null;
	}
}
