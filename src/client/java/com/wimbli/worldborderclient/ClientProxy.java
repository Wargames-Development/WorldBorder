package com.wimbli.worldborderclient;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraftforge.common.MinecraftForge;


public class ClientProxy extends CommonProxy
{
    private FMLEventChannel channel;

    @Override
    public void init()
    {
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(WorldBorderClientMod.CHANNEL);
        channel.register(new BorderPacketHandler());
        MinecraftForge.EVENT_BUS.register(new ModernBorderRenderer());
        FMLCommonHandler.instance().bus().register(this);
        FMLCommonHandler.instance().bus().register(new ClientVehicleSeamTransform());
    }

    @SubscribeEvent
    public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event)
    {
        ClientBorderState.clear();
        ClientVehicleSeamTransform.clear();
    }

    @SubscribeEvent
    public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event)
    {
        ClientBorderState.clear();
        ClientVehicleSeamTransform.clear();
    }
}
