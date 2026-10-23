package com.wimbli.worldborderclient;


public final class ClientBorderState
{
    private static volatile Snapshot snapshot = Snapshot.disabled();

    private ClientBorderState() {}

    public static Snapshot get()
    {
        return snapshot;
    }

    public static void set(double minX, double maxX, double minZ, double maxZ,
                           boolean xPassable, boolean zPassable, int renderDistance)
    {
        snapshot = new Snapshot(true, minX, maxX, minZ, maxZ, xPassable, zPassable, renderDistance);
    }

    public static void clear()
    {
        snapshot = Snapshot.disabled();
    }

    public static final class Snapshot
    {
        public final boolean enabled;
        public final double minX;
        public final double maxX;
        public final double minZ;
        public final double maxZ;
        public final boolean xPassable;
        public final boolean zPassable;
        public final int renderDistance;

        private Snapshot(boolean enabled, double minX, double maxX, double minZ, double maxZ,
                         boolean xPassable, boolean zPassable, int renderDistance)
        {
            this.enabled = enabled;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.xPassable = xPassable;
            this.zPassable = zPassable;
            this.renderDistance = renderDistance;
        }

        private static Snapshot disabled()
        {
            return new Snapshot(false, 0, 0, 0, 0, false, false, 0);
        }
    }
}
