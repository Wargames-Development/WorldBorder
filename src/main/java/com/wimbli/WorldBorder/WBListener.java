package com.wimbli.WorldBorder;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;



public class WBListener implements Listener
{
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerMove(PlayerMoveEvent event)
	{
		// PlayerTeleportEvent extends PlayerMoveEvent; the dedicated teleport handler below
		// owns pearls/portals/plugin teleports and must not be processed twice.
		if (event instanceof PlayerTeleportEvent)
			return;

		Location from = event.getFrom();
		Location to = event.getTo();
		if (from == null || to == null || (from.getX() == to.getX() && from.getZ() == to.getZ()))
			return;

		BorderPlayerProtection.MoveResolution resolution = BorderPlayerProtection.resolveMove(event.getPlayer(), from, to);
		if (resolution != null && resolution.getDestination() != null)
			event.setTo(resolution.getDestination());
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event)
	{
		if (BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), event.getBlock()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockDamage(BlockDamageEvent event)
	{
		if (BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), event.getBlock()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event)
	{
		Block clicked = event.getClickedBlock();
		if (clicked != null && BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), clicked))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockPlace(BlockPlaceEvent event)
	{
		if (BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), event.getBlockPlaced()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBucketEmpty(PlayerBucketEmptyEvent event)
	{
		Block target = event.getBlockClicked();
		if (target != null && event.getBlockFace() != null)
			target = target.getRelative(event.getBlockFace());
		if (target != null && BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), target))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBucketFill(PlayerBucketFillEvent event)
	{
		if (BorderPlayerProtection.blocksBlockInteraction(event.getPlayer(), event.getBlockClicked()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{
		if (BorderPlayerProtection.blocksEntityInteraction(event.getPlayer(), event.getRightClicked()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onEntityDamage(EntityDamageByEntityEvent event)
	{
		if (event.getDamager() instanceof Player &&
			BorderPlayerProtection.blocksEntityInteraction((Player)event.getDamager(), event.getEntity()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onPlayerTeleport(PlayerTeleportEvent event)
	{
		if (Config.Debug())
			Config.log("Teleport cause: " + event.getCause().toString());

		Location target = event.getTo();
		BorderData border = (target == null || target.getWorld() == null) ? null : Config.Border(target.getWorld().getName());
		boolean passableTarget = border != null && target != null &&
			border.isPassableDestination(target.getX(), target.getZ(), Config.ShapeRound());

		Location newLoc = BorderCheckTask.checkPlayer(event.getPlayer(), target, true, true);
		if (newLoc != null)
		{
			// Green seams are intentional routes through the world. A red edge is a hard
			// gameplay boundary, so an ender pearl may never bypass it regardless of the
			// legacy deny-enderpearl option. The option is retained for config compatibility.
			if(event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !passableTarget)
			{
				event.setCancelled(true);
				return;
			}

			event.setTo(newLoc);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void onPlayerPortal(PlayerPortalEvent event)
	{
		// Portal redirection can be disabled independently. BorderCheckTask itself
		// handles the legacy knockback=0 semantics for normal borders.
		if (!Config.portalRedirection())
			return;

		Location newLoc = BorderCheckTask.checkPlayer(event.getPlayer(), event.getTo(), true, false);
		if (newLoc != null)
			event.setTo(newLoc);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event)
	{
		BorderClientSync.forget(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onProjectileLaunch(ProjectileLaunchEvent event)
	{
		BorderProjectileSupport.onLaunch(event);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onProjectileHit(ProjectileHitEvent event)
	{
		BorderProjectileSupport.onHit(event);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkLoad(ChunkLoadEvent event)
	{
/*		// tested, found to spam pretty rapidly as client repeatedly requests the same chunks since they're not being sent
		// definitely too spammy at only 16 blocks outside border
		// potentially useful at standard 208 block padding as it was triggering only occasionally while trying to get out all along edge of round border, though sometimes up to 3 triggers within a second corresponding to 3 adjacent chunks
		// would of course need to be further worked on to have it only affect chunks outside a border, along with an option somewhere to disable it or even set specified distance outside border for it to take effect; maybe  send client chunk composed entirely of air to shut it up

		// method to prevent new chunks from being generated, core method courtesy of code from NoNewChunk plugin (http://dev.bukkit.org/bukkit-plugins/nonewchunk/)
		if(event.isNewChunk())
		{
			Chunk chunk = event.getChunk();
			chunk.unload(false, false);
			Config.logWarn("New chunk generation has been prevented at X " + chunk.getX() + ", Z " + chunk.getZ());
		}
*/
		// make sure our border monitoring task is still running like it should
		if (Config.isBorderTimerRunning()) return;

		Config.logWarn("Border-checking task was not running! Something on your server apparently killed it. It will now be restarted.");
		Config.StartBorderTimer();
	}
}
