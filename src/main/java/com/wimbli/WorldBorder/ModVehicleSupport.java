package com.wimbli.WorldBorder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;


public final class ModVehicleSupport
{
	private static final String SNAPSHOT_METHOD = "wdgWorldBorderSnapshot";
	private static final String MOVE_METHOD = "wdgWorldBorderMove";
	private static final String BYPASS_METHOD = "wdgWorldBorderBypassHorizontalBorder";
	private static final String GET_LANDING_METHOD = "wdgWorldBorderGetLandingTarget";
	private static final String SET_LANDING_METHOD = "wdgWorldBorderSetLandingTarget";

	private ModVehicleSupport() {}

	public static VehicleContext inspect(Player player)
	{
		if (player == null || !player.isInsideVehicle())
			return null;

		Entity bukkitVehicle = player.getVehicle();
		if (bukkitVehicle == null)
			return null;

		try
		{
			Object handle = unwrapBukkitEntity(bukkitVehicle);
			if (handle == null)
				return null;

			Object root = resolveRoot(handle);
			if (root == null || findMethod(root.getClass(), SNAPSHOT_METHOD, 0) == null || findMethod(root.getClass(), MOVE_METHOD, 7) == null)
				return null;

			return new VehicleContext(root);
		}
		catch (Throwable ignored)
		{
			return null;
		}
	}

	private static Object unwrapBukkitEntity(Entity entity) throws Exception
	{
		Method getHandle = findMethod(entity.getClass(), "getHandle", 0);
		if (getHandle == null)
			return null;
		return getHandle.invoke(entity, new Object[0]);
	}

	private static Object resolveRoot(Object handle) throws Exception
	{
		if (findMethod(handle.getClass(), SNAPSHOT_METHOD, 0) != null)
			return handle;

		// MCHeli secondary seats expose their aircraft through a stable mod-owned getParent().
		Method getParent = findMethod(handle.getClass(), "getParent", 0);
		if (getParent != null)
		{
			Object parent = getParent.invoke(handle, new Object[0]);
			if (parent != null && findMethod(parent.getClass(), SNAPSHOT_METHOD, 0) != null)
				return parent;
		}

		return null;
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

	public static class VehicleContext
	{
		private final Object root;
		private final Method snapshotMethod;
		private final Method moveMethod;
		private final Method bypassMethod;
		private final Method getLandingMethod;
		private final Method setLandingMethod;
		private String lastChunkRefreshDetail = "not attempted";

		private VehicleContext(Object root)
		{
			this.root = root;
			this.snapshotMethod = findMethod(root.getClass(), SNAPSHOT_METHOD, 0);
			this.moveMethod = findMethod(root.getClass(), MOVE_METHOD, 7);
			this.bypassMethod = findMethod(root.getClass(), BYPASS_METHOD, 0);
			this.getLandingMethod = findMethod(root.getClass(), GET_LANDING_METHOD, 0);
			this.setLandingMethod = findMethod(root.getClass(), SET_LANDING_METHOD, 2);
		}

		public String describe()
		{
			return root.getClass().getName();
		}

		public boolean shouldBypassHorizontalBorder()
		{
			if (bypassMethod == null)
				return false;
			try
			{
				Object result = bypassMethod.invoke(root, new Object[0]);
				return result instanceof Boolean && ((Boolean)result).booleanValue();
			}
			catch (Throwable ignored)
			{
				return false;
			}
		}

		public boolean normalizeLandingTarget(BorderData border, boolean round)
		{
			if (getLandingMethod == null || setLandingMethod == null || border == null)
				return false;
			try
			{
				Object raw = getLandingMethod.invoke(root, new Object[0]);
				if (!(raw instanceof int[]))
					return false;
				int[] target = (int[])raw;
				if (target.length < 2 || border.insideBorder(target[0], target[1], round))
					return false;

				BorderTransform transform = border.transformCoordinates(target[0], target[1], round);
				int newX = (int)Math.floor(transform.getX());
				int newZ = (int)Math.floor(transform.getZ());
				setLandingMethod.invoke(root, new Object[] {Integer.valueOf(newX), Integer.valueOf(newZ)});
				if (Config.Debug())
					Config.logWarn("Normalized mod rocket landing target from " + target[0] + ", " + target[1] + " to " + newX + ", " + newZ + ".");
				return true;
			}
			catch (Throwable ex)
			{
				if (Config.Debug())
					Config.logWarn("Unable to normalize mod rocket landing target for " + describe() + ": " + ex.getClass().getSimpleName());
				return false;
			}
		}

		public boolean sameRoot(VehicleContext other)
		{
			return other != null && this.root == other.root;
		}

		public boolean isMCHeliVehicle()
		{
			return root.getClass().getName().startsWith("mcheli.");
		}

		public boolean isAirVehicle()
		{
			String name = root.getClass().getName();
			return name.startsWith("mcheli.helicopter.") || name.startsWith("mcheli.plane.");
		}

		/**
		 * MCHeli moves its child seat/hitbox entities with raw setPosition calls. On
		 * Cauldron a large seam jump can therefore leave those entities registered in
		 * their old chunk until the next normal entity update, where Cauldron may remove
		 * them as a crash-safety measure.
		 *
		 * Refresh chunk membership without ticking the entities.  Passing false to
		 * World.updateEntityWithOptionalForce performs the vanilla chunk-registration
		 * check but does not call the entity's onUpdate method.  The lookup deliberately
		 * uses exact method names/signatures rather than enumerating every Cauldron
		 * WorldServer method; enumerating those methods can resolve unrelated optional
		 * classes and was the source of the NoClassDefFoundError seen in 1.0.3.
		 */
		public boolean refreshChunkRegistration()
		{
			lastChunkRefreshDetail = "not attempted";
			if (!isMCHeliVehicle())
			{
				lastChunkRefreshDetail = "not an MCHeli vehicle";
				return false;
			}

			try
			{
				Set<Object> entities = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
				entities.add(root);

				// Use stable mod-owned fields rather than reflective method enumeration.
				Field seats = findField(root.getClass(), "seats");
				if (seats != null)
					addArrayMembers(entities, seats.get(root));

				Field partEntities = findField(root.getClass(), "partEntities");
				if (partEntities != null)
					addArrayMembers(entities, partEntities.get(root));

				Field pilotSeat = findField(root.getClass(), "pilotSeat");
				if (pilotSeat != null)
				{
					Object part = pilotSeat.get(root);
					if (part != null)
						entities.add(part);
				}

				int refreshed = 0;
				String firstFailure = null;
				for (Object entity : entities)
				{
					String failure = refreshEntityChunk(entity);
					if (failure == null)
						refreshed++;
					else if (firstFailure == null)
						firstFailure = failure;
				}

				lastChunkRefreshDetail = "refreshed " + refreshed + "/" + entities.size() + " root/seat/hitbox chunk registrations";
				if (firstFailure != null)
					lastChunkRefreshDetail += "; first failure: " + firstFailure;
				return refreshed > 0 && firstFailure == null;
			}
			catch (Throwable ex)
			{
				lastChunkRefreshDetail = "setup failure: " + describeThrowable(ex);
				return false;
			}
		}

		public String getLastChunkRefreshDetail()
		{
			return lastChunkRefreshDetail;
		}

		private static void addArrayMembers(Set<Object> entities, Object rawArray)
		{
			if (rawArray == null || !rawArray.getClass().isArray())
				return;
			int length = java.lang.reflect.Array.getLength(rawArray);
			for (int i = 0; i < length; i++)
			{
				Object value = java.lang.reflect.Array.get(rawArray, i);
				if (value != null)
					entities.add(value);
			}
		}

		private static String refreshEntityChunk(Object entity)
		{
			if (entity == null)
				return "null entity";
			try
			{
				Field worldField = findField(entity.getClass(), "worldObj");
				if (worldField == null)
					worldField = findField(entity.getClass(), "field_70170_p");
				if (worldField == null)
					return entity.getClass().getName() + ": world field unavailable";

				Object world = worldField.get(entity);
				if (world == null)
					return entity.getClass().getName() + ": world is null";

				Method update = findWorldEntityUpdate(world.getClass(), worldField.getDeclaringClass());
				if (update == null)
					return entity.getClass().getName() + ": World.updateEntityWithOptionalForce unavailable";

				// false is intentional: run chunk membership correction without a second onUpdate.
				update.invoke(world, new Object[] {entity, Boolean.FALSE});
				return null;
			}
			catch (Throwable ex)
			{
				return entity.getClass().getName() + ": " + describeThrowable(ex);
			}
		}

		private static Method findWorldEntityUpdate(Class<?> worldClass, Class<?> vanillaEntityClass)
		{
			if (worldClass == null || vanillaEntityClass == null)
				return null;

			String[] names = new String[] {"updateEntityWithOptionalForce", "func_72866_a"};
			Class<?> current = worldClass;
			while (current != null)
			{
				for (int i = 0; i < names.length; i++)
				{
					try
					{
						Method method = current.getDeclaredMethod(names[i], new Class<?>[] {vanillaEntityClass, Boolean.TYPE});
						method.setAccessible(true);
						return method;
					}
					catch (NoSuchMethodException ignored) {}
				}
				current = current.getSuperclass();
			}
			return null;
		}

		private static String describeThrowable(Throwable ex)
		{
			if (ex == null)
				return "unknown error";
			Throwable current = ex;
			if (current instanceof java.lang.reflect.InvocationTargetException &&
				((java.lang.reflect.InvocationTargetException)current).getTargetException() != null)
				current = ((java.lang.reflect.InvocationTargetException)current).getTargetException();

			String detail = current.getClass().getSimpleName();
			String message = current.getMessage();
			if (message != null && message.length() > 0)
				detail += "(" + message + ")";
			Throwable cause = current.getCause();
			if (cause != null && cause != current)
			{
				detail += " <- " + cause.getClass().getSimpleName();
				if (cause.getMessage() != null && cause.getMessage().length() > 0)
					detail += "(" + cause.getMessage() + ")";
			}
			return detail;
		}

		public boolean setYawOnly(float yaw)
		{
			if (!isMCHeliVehicle())
				return false;
			try
			{
				Method setYaw = findMethod(root.getClass(), "setRotYaw", 1);
				if (setYaw == null)
					return false;
				setYaw.invoke(root, new Object[] {Float.valueOf(yaw)});
				return true;
			}
			catch (Throwable ex)
			{
				if (Config.Debug())
					Config.logWarn("Unable to reassert supported mod vehicle yaw for " + describe() + ": " + describeThrowable(ex));
				return false;
			}
		}

		public VehicleSnapshot snapshot(World world)
		{
			try
			{
				Object raw = snapshotMethod.invoke(root, new Object[0]);
				if (!(raw instanceof double[]))
					return null;
				double[] values = (double[])raw;
				if (values.length < 8)
					return null;
				return new VehicleSnapshot(new Location(world, values[0], values[1], values[2], (float)values[3], (float)values[4]), new Vector(values[5], values[6], values[7]));
			}
			catch (Throwable ignored)
			{
				return null;
			}
		}

		public boolean move(Location destination, Vector velocity)
		{
			try
			{
				moveMethod.invoke(root, new Object[] {
					Double.valueOf(destination.getX()), Double.valueOf(destination.getY()), Double.valueOf(destination.getZ()),
					Float.valueOf(destination.getYaw()),
					Double.valueOf(velocity.getX()), Double.valueOf(velocity.getY()), Double.valueOf(velocity.getZ())
				});
				return true;
			}
			catch (Throwable ex)
			{
				if (Config.Debug())
					Config.logWarn("Unable to move supported mod vehicle " + describe() + ": " + ex.getClass().getSimpleName());
				return false;
			}
		}
	}

	public static class VehicleSnapshot
	{
		private final Location location;
		private final Vector velocity;

		public VehicleSnapshot(Location location, Vector velocity)
		{
			this.location = location;
			this.velocity = velocity;
		}

		public Location getLocation()
		{
			return location;
		}

		public Vector getVelocity()
		{
			return velocity;
		}
	}
}
