package com.wimbli.worldborderclient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;


/**
 * Applies only the orientation part of an authoritative MCHeli seam transform.
 *
 * The server remains the sole authority for aircraft/seat/rider positions.  Older
 * WorldBorder Client builds called MCHeli's full wdgWorldBorderMove hook on the
 * client as well as the server, which could race MCHeli's normal entity tracking
 * and leave the local mount graph visually desynchronised.  This class therefore
 * never changes X/Y/Z, seat positions, motion, or mount relationships.
 *
 * MCHeli pilot yaw is client-driven, so a mirrored north/south seam still needs a
 * tiny client correction.  The server sends the explicit yaw delta instead of the
 * client trying to reconstruct it from whatever aircraft yaw happened to arrive
 * first.  For a few ticks we pin only the aircraft yaw, giving MCHeli time to send
 * that corrected yaw back to the server before normal controls resume.
 */
public class ClientVehicleSeamTransform
{
    private static Pending pending;

    public static void queue(float targetYaw, float yawDelta)
    {
        yawDelta = normalizeYaw(yawDelta);
        if (Math.abs(yawDelta) < 0.001F)
            return;
        pending = new Pending(normalizeYaw(targetYaw), yawDelta, 10, 3);
    }

    public static void clear()
    {
        pending = null;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || pending == null)
            return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc == null ? null : mc.thePlayer;
        if (player == null || player.ridingEntity == null)
        {
            if (--pending.retries <= 0)
                pending = null;
            return;
        }

        Object root = resolveRoot(player.ridingEntity);
        if (root == null)
        {
            if (--pending.retries <= 0)
                pending = null;
            return;
        }

        try
        {
            if (!pending.playerRotated)
            {
                rotateLocalPlayer(player, pending.yawDelta);
                pending.playerRotated = true;
            }

            applyAircraftYaw(root, pending.targetYaw);

            if (--pending.holdTicks <= 0)
                pending = null;
        }
        catch (Throwable ignored)
        {
            if (--pending.retries <= 0)
                pending = null;
        }
    }

    private static Object resolveRoot(Object riding)
    {
        if (riding == null)
            return null;

        if (isMCHeliAircraft(riding))
            return riding;

        try
        {
            Method parent = findMethod(riding.getClass(), "getParent", 0);
            if (parent == null)
                return null;
            Object root = parent.invoke(riding, new Object[0]);
            return isMCHeliAircraft(root) ? root : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static boolean isMCHeliAircraft(Object value)
    {
        return value != null && value.getClass().getName().startsWith("mcheli.") &&
            findMethod(value.getClass(), "setRotYaw", 1) != null;
    }

    private static void applyAircraftYaw(Object root, float targetYaw) throws Exception
    {
        Method setYaw = findMethod(root.getClass(), "setRotYaw", 1);
        if (setYaw == null)
            return;

        setYaw.invoke(root, new Object[] {Float.valueOf(targetYaw)});

        // MCHeli sends MCH_PacketIndRotation when this flag is set.  Reasserting it
        // during the short latch makes the corrected client yaw authoritative again
        // without moving the aircraft/seat graph locally.
        Field changed = findField(root.getClass(), "aircraftRotChanged");
        if (changed != null)
            changed.setBoolean(root, true);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount)
    {
        Class<?> current = type;
        while (current != null)
        {
            Method[] methods = current.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++)
            {
                Method method = methods[i];
                if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount)
                {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> type, String name)
    {
        Class<?> current = type;
        while (current != null)
        {
            try
            {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            }
            catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static void rotateLocalPlayer(EntityPlayer player, float delta)
    {
        player.rotationYaw = normalizeYaw(player.rotationYaw + delta);
        player.prevRotationYaw = normalizeYaw(player.prevRotationYaw + delta);
        player.rotationYawHead = normalizeYaw(player.rotationYawHead + delta);
        player.prevRotationYawHead = normalizeYaw(player.prevRotationYawHead + delta);
        player.renderYawOffset = normalizeYaw(player.renderYawOffset + delta);
        player.prevRenderYawOffset = normalizeYaw(player.prevRenderYawOffset + delta);
    }

    private static float normalizeYaw(float yaw)
    {
        while (yaw <= -180.0F)
            yaw += 360.0F;
        while (yaw > 180.0F)
            yaw -= 360.0F;
        return yaw;
    }

    private static final class Pending
    {
        final float targetYaw;
        final float yawDelta;
        int retries;
        int holdTicks;
        boolean playerRotated;

        Pending(float targetYaw, float yawDelta, int retries, int holdTicks)
        {
            this.targetYaw = targetYaw;
            this.yawDelta = yawDelta;
            this.retries = retries;
            this.holdTicks = holdTicks;
        }
    }
}
