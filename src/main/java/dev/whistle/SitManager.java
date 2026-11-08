package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /sit: sit down anywhere, shift to get up. The player rides an invisible armor stand placed at their feet, which
 * puts them in the seated pose with their bottom on the spot they were standing.
 */
final class SitManager implements Listener, CommandExecutor {

    private static final long CHECK_INTERVAL_TICKS = 10L;
    private static final double SNAP_BACK_RANGE_SQUARED = 3.0 * 3.0;

    private record Seat(ArmorStand stand, Location origin) { }

    private final MatchManager matches;
    private final Map<UUID, Seat> seats = new HashMap<>();

    SitManager(JavaPlugin plugin, MatchManager matches) {
        this.matches = matches;
        // Catches every way of leaving the seat other than shift: dying, being teleported, the stand disappearing.
        Bukkit.getScheduler().runTaskTimer(plugin, this::validate, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can sit.", NamedTextColor.RED));
            return true;
        }
        sit(player);
        return true;
    }

    private void sit(Player player) {
        if (seats.containsKey(player.getUniqueId())) {
            error(player, "You are already sitting. Press shift to get up.");
            return;
        }
        if (matches.isInMatch(player.getUniqueId())) {
            error(player, "You can't sit during a match or minigame.");
            return;
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            error(player, "You can't sit right now.");
            return;
        }
        if (player.isInsideVehicle() || player.isGliding() || player.isSleeping()) {
            error(player, "You can't sit right now.");
            return;
        }

        Location origin = player.getLocation().clone();
        ArmorStand stand = origin.getWorld().spawn(origin, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);       // no hitbox: can't be hit, clicked or pushed
            s.setSmall(true);
            s.setGravity(false);
            s.setInvulnerable(true);
            s.setSilent(true);
            s.setPersistent(false);  // never saved to disk, so a crash can't leave seats behind
        });
        if (!stand.addPassenger(player)) {
            stand.remove();
            error(player, "You can't sit here.");
            return;
        }
        seats.put(player.getUniqueId(), new Seat(stand, origin));
        player.sendMessage(Component.text("You sit down. Press shift to get up.", NamedTextColor.GREEN));
    }

    /**
     * Gets the player up and removes their seat. snapBack puts them back where they were standing, which is only
     * right when they are still next to the seat (not when they died or were teleported away).
     */
    private void standUp(UUID id, boolean snapBack) {
        Seat seat = seats.remove(id);
        if (seat == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.getVehicle() == seat.stand()) player.leaveVehicle();
        if (snapBack && player != null && player.isOnline() && !player.isDead()) {
            Location here = player.getLocation();
            if (here.getWorld() == seat.origin().getWorld() && here.distanceSquared(seat.stand().getLocation()) <= SNAP_BACK_RANGE_SQUARED) {
                Location target = seat.origin().clone();
                target.setYaw(here.getYaw());
                target.setPitch(here.getPitch());
                player.teleport(target);
            }
        }
        seat.stand().remove();
    }

    private void validate() {
        for (UUID id : new ArrayList<>(seats.keySet())) {
            Seat seat = seats.get(id);
            Player player = Bukkit.getPlayer(id);
            boolean seated = player != null && player.isOnline() && !player.isDead()
                    && seat.stand().isValid() && player.getVehicle() == seat.stand();
            if (!seated) standUp(id, true);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) standUp(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        standUp(event.getPlayer().getUniqueId(), true); // so they don't log back in inside the block they sat on
    }

    /** Plugin disabling: everyone gets up and every seat is removed. */
    void shutdown() {
        for (UUID id : new ArrayList<>(seats.keySet())) standUp(id, true);
    }

    private void error(Player player, String text) {
        player.sendMessage(Component.text(text, NamedTextColor.RED));
    }
}
