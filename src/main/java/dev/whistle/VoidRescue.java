package dev.whistle;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stops infinite falling: a player who drops below y = -64 is teleported back up to the closest safe block on the
 * surface (same x/z if possible, otherwise the nearest safe spot in a widening square around it).
 */
final class VoidRescue implements Listener {

    /** Falling below this height triggers a rescue. */
    private static final int FLOOR_Y = -64;
    /** How far (in blocks) to search sideways for a safe block before falling back to the world spawn. */
    private static final int MAX_SEARCH_RADIUS = 16;
    /** How many blocks below the top of a column to look for a safe floor (the Nether has a roof to get past). */
    private static final int SCAN_DEPTH_OVERWORLD = 4;
    private static final int SCAN_DEPTH_ROOFED = 90;

    private final JavaPlugin plugin;
    /** Players with a rescue already queued, so repeated move events don't queue it twice. */
    private final Set<UUID> pending = new HashSet<>();

    VoidRescue(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getY() >= FLOOR_Y) return;

        Player player = event.getPlayer();
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!pending.add(player.getUniqueId())) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(player.getUniqueId());
            if (!player.isOnline() || player.isDead()) return;
            if (player.getLocation().getY() >= FLOOR_Y) return;
            rescue(player);
        });
    }

    private void rescue(Player player) {
        Location from = player.getLocation();
        Location target = findSafeSpot(from);
        if (target == null) target = from.getWorld().getSpawnLocation().add(0.5, 0, 0.5);

        target.setYaw(from.getYaw());
        target.setPitch(from.getPitch());
        player.setFallDistance(0f);
        player.setVelocity(new Vector(0, 0, 0));
        player.teleport(target);
        player.setFallDistance(0f);
    }

    // ------------------------------------------------------------------ search

    /** The closest safe standing spot to the player's x/z, or null if nothing is safe within the search radius. */
    private Location findSafeSpot(Location from) {
        World world = from.getWorld();
        int cx = from.getBlockX();
        int cz = from.getBlockZ();

        for (int radius = 0; radius <= MAX_SEARCH_RADIUS; radius++) {
            Location best = null;
            double bestDistance = Double.MAX_VALUE;
            // Walk the square ring at this radius and keep the closest safe column; rings are tried nearest first.
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int floorY = safeFloorY(world, x, z);
                    if (floorY == Integer.MIN_VALUE) continue;
                    double distance = dx * dx + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new Location(world, x + 0.5, floorY + 1, z + 0.5);
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Y of the highest safe floor block in this column, or Integer.MIN_VALUE if there isn't one. */
    private int safeFloorY(World world, int x, int z) {
        boolean roofed = world.getEnvironment() == World.Environment.NETHER;
        int top;
        int depth;
        if (roofed) {
            top = world.getLogicalHeight() - 2; // skip the bedrock roof
            depth = SCAN_DEPTH_ROOFED;
        } else {
            top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            depth = SCAN_DEPTH_OVERWORLD;
        }
        int bottom = Math.max(world.getMinHeight(), top - depth);
        for (int y = top; y >= bottom; y--) {
            if (isSafeFloor(world.getBlockAt(x, y, z))) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** A solid, harmless block with two free, harmless blocks above it for the player to stand in. */
    private boolean isSafeFloor(Block floor) {
        if (!floor.getType().isSolid() || isDangerous(floor.getType())) return false;
        if (floor.getY() + 2 >= floor.getWorld().getMaxHeight()) return false;
        Block feet = floor.getRelative(0, 1, 0);
        Block head = floor.getRelative(0, 2, 0);
        return isFree(feet) && isFree(head);
    }

    private boolean isFree(Block block) {
        return block.isPassable() && !block.isLiquid() && !isDangerous(block.getType());
    }

    private boolean isDangerous(Material material) {
        return switch (material) {
            case LAVA, WATER, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, CAMPFIRE, SOUL_CAMPFIRE, SWEET_BERRY_BUSH,
                 WITHER_ROSE, POWDER_SNOW, COBWEB, BEDROCK -> true;
            default -> false;
        };
    }
}
