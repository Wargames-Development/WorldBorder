package com.wimbli.worldborderclient;

import java.util.Map;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;


@Mod(modid = WorldBorderClientMod.MODID, name = "WorldBorder Client", version = "@WDG_VERSION@", acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public class WorldBorderClientMod
{
    public static final String MODID = "worldborderclient";
    public static final String CHANNEL = "WDBorder";

    @SidedProxy(clientSide = "com.wimbli.worldborderclient.ClientProxy", serverSide = "com.wimbli.worldborderclient.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
        proxy.init();
    }

    @NetworkCheckHandler
    public boolean acceptRemote(Map<String, String> remoteVersions, Side remoteSide)
    {
        // Rendering is optional and the authoritative border remains the Bukkit plugin.
        return true;
    }
}
