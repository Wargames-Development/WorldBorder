package com.wimbli.worldborderclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;


/**
 * Lightweight 1.7.10 recreation of the later Minecraft world-border presentation.
 * It draws at most four textured quads per frame and never places/collides with blocks.
 */
public class ModernBorderRenderer
{
    private static final ResourceLocation BORDER_TEXTURE =
        new ResourceLocation(WorldBorderClientMod.MODID, "textures/misc/border.png");

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event)
    {
        ClientBorderState.Snapshot state = ClientBorderState.get();
        if (!state.enabled)
            return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null)
            return;

        Entity camera = mc.renderViewEntity != null ? mc.renderViewEntity : mc.thePlayer;
        if (camera == null)
            return;

        float partial = event.partialTicks;
        double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partial;
        double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partial;
        double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partial;

        int clientRange = Math.max(16, mc.gameSettings.renderDistanceChunks * 16);
        double range = Math.max(16.0D, Math.min((double)state.renderDistance, (double)clientRange));

        boolean nearMinX = Math.abs(camX - state.minX) <= range;
        boolean nearMaxX = Math.abs(camX - state.maxX) <= range;
        boolean nearMinZ = Math.abs(camZ - state.minZ) <= range;
        boolean nearMaxZ = Math.abs(camZ - state.maxZ) <= range;
        if (!nearMinX && !nearMaxX && !nearMinZ && !nearMaxZ)
            return;

        double yBottom = Math.min(0.0D, Math.floor(camY - 128.0D));
        double yTop = Math.max((double)mc.theWorld.getHeight(), Math.ceil(camY + 128.0D));
        double scroll = (double)(Minecraft.getSystemTime() % 3000L) / 3000.0D;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT |
            GL11.GL_POLYGON_BIT | GL11.GL_TEXTURE_BIT);
        mc.getTextureManager().bindTexture(BORDER_TEXTURE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glPushMatrix();
        GL11.glTranslated(-camX, -camY, -camZ);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glPolygonOffset(-3.0F, -3.0F);

        if (nearMinX)
            drawXWall(state.minX, camZ, state.minZ, state.maxZ, yBottom, yTop, range,
                state.xPassable, alphaForDistance(Math.abs(camX - state.minX), range), scroll);
        if (nearMaxX)
            drawXWall(state.maxX, camZ, state.minZ, state.maxZ, yBottom, yTop, range,
                state.xPassable, alphaForDistance(Math.abs(camX - state.maxX), range), scroll);
        if (nearMinZ)
            drawZWall(state.minZ, camX, state.minX, state.maxX, yBottom, yTop, range,
                state.zPassable, alphaForDistance(Math.abs(camZ - state.minZ), range), scroll);
        if (nearMaxZ)
            drawZWall(state.maxZ, camX, state.minX, state.maxX, yBottom, yTop, range,
                state.zPassable, alphaForDistance(Math.abs(camZ - state.maxZ), range), scroll);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    private static void drawXWall(double x, double centreZ, double minZ, double maxZ,
                                  double yBottom, double yTop, double range,
                                  boolean passable, float alpha, double scroll)
    {
        if (alpha <= 0.0F)
            return;
        double start = Math.max(minZ, centreZ - range);
        double end = Math.min(maxZ, centreZ + range);
        if (end <= start)
            return;

        setTint(passable, alpha);
        double u0 = wrappedTextureAnchor(start) + scroll;
        double u1 = u0 + (end - start) * 0.5D;
        double v0 = wrappedTextureAnchor(yBottom) + scroll;
        double v1 = v0 + (yTop - yBottom) * 0.5D;

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, yTop, start, u0, v0);
        tess.addVertexWithUV(x, yTop, end, u1, v0);
        tess.addVertexWithUV(x, yBottom, end, u1, v1);
        tess.addVertexWithUV(x, yBottom, start, u0, v1);
        tess.draw();
    }

    private static void drawZWall(double z, double centreX, double minX, double maxX,
                                  double yBottom, double yTop, double range,
                                  boolean passable, float alpha, double scroll)
    {
        if (alpha <= 0.0F)
            return;
        double start = Math.max(minX, centreX - range);
        double end = Math.min(maxX, centreX + range);
        if (end <= start)
            return;

        setTint(passable, alpha);
        double u0 = wrappedTextureAnchor(start) + scroll;
        double u1 = u0 + (end - start) * 0.5D;
        double v0 = wrappedTextureAnchor(yBottom) + scroll;
        double v1 = v0 + (yTop - yBottom) * 0.5D;

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(start, yTop, z, u0, v0);
        tess.addVertexWithUV(end, yTop, z, u1, v0);
        tess.addVertexWithUV(end, yBottom, z, u1, v1);
        tess.addVertexWithUV(start, yBottom, z, u0, v1);
        tess.draw();
    }

    private static double wrappedTextureAnchor(double coordinate)
    {
        // Keep UVs small even on multi-million-block Earth maps. Very large texture
        // coordinates lose float precision in the 1.7 tessellator and can visibly shimmer.
        double wrapped = coordinate % 32.0D;
        if (wrapped < 0.0D)
            wrapped += 32.0D;
        return wrapped * 0.5D;
    }

    private static void setTint(boolean passable, float alpha)
    {
        if (passable)
            GL11.glColor4f(0.18F, 1.0F, 0.34F, alpha);
        else
            GL11.glColor4f(1.0F, 0.16F, 0.12F, alpha);
    }

    private static float alphaForDistance(double distance, double range)
    {
        double proximity = 1.0D - Math.min(1.0D, Math.max(0.0D, distance / range));
        // More legible from fast aircraft than vanilla's fourth-power fade while still
        // fading completely out at the configured render distance.
        return (float)(0.82D * proximity * proximity);
    }
}
