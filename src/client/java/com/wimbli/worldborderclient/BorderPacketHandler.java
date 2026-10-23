package com.wimbli.worldborderclient;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.netty.buffer.ByteBuf;


public class BorderPacketHandler
{
    private static final int STATE_PROTOCOL_VERSION = 1;
    private static final int VEHICLE_TRANSFORM_PROTOCOL_VERSION = 3;

    @SubscribeEvent
    public void onPacket(FMLNetworkEvent.ClientCustomPacketEvent event)
    {
        if (event == null || event.packet == null)
            return;

        ByteBuf buf = event.packet.payload();
        if (buf == null || buf.readableBytes() < 1)
            return;

        try
        {
            int protocol = buf.readUnsignedByte();
            if (protocol == STATE_PROTOCOL_VERSION)
            {
                readBorderState(buf);
                return;
            }
            if (protocol == VEHICLE_TRANSFORM_PROTOCOL_VERSION)
                readVehicleTransform(buf);
        }
        catch (IndexOutOfBoundsException ignored)
        {
            // Ignore malformed/partial plugin messages; the last valid state remains authoritative.
        }
    }

    private static void readBorderState(ByteBuf buf)
    {
        if (buf.readableBytes() < 1)
            return;

        boolean active = buf.readBoolean();
        if (!active)
        {
            ClientBorderState.clear();
            return;
        }

        if (buf.readableBytes() < 38)
            return;

        double minX = buf.readDouble();
        double maxX = buf.readDouble();
        double minZ = buf.readDouble();
        double maxZ = buf.readDouble();
        boolean xPassable = buf.readBoolean();
        boolean zPassable = buf.readBoolean();
        int renderDistance = buf.readInt();
        ClientBorderState.set(minX, maxX, minZ, maxZ, xPassable, zPassable, renderDistance);
    }

    private static void readVehicleTransform(ByteBuf buf)
    {
        if (buf.readableBytes() < 8)
            return;

        float targetYaw = buf.readFloat();
        float yawDelta = buf.readFloat();
        ClientVehicleSeamTransform.queue(targetYaw, yawDelta);
    }
}
