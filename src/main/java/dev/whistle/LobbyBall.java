package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The football in a lobby: it is there whenever at least one player is in the lobby, to kick around while waiting, and
 * it is removed when the lobby is empty again. It is a normal football (the same kick as in a match). If it ends up far
 * from the lobby, or falls out of the world, it is put back.
 */
final class LobbyBall {

    private static final double MAX_DISTANCE = 30.0;
    private static final long KICK_COOLDOWN_MILLIS = 150;

    private final MatchManager matches;
    private final Supplier<Location> spot;
    private final IntSupplier players;
    private final BukkitTask task;
    private final Map<UUID, Long> lastKick = new HashMap<>();
    private SulfurCube ball;

    LobbyBall(JavaPlugin plugin, MatchManager matches, Supplier<Location> spot, IntSupplier players) {
        this.matches = matches;
        this.spot = spot;
        this.players = players;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 20L);
    }

    boolean owns(Entity entity) {
        return ball != null && ball.equals(entity);
    }

    /** Brings the ball in line with the lobby: there if someone is in it, gone if nobody is. Called every second. */
    void refresh() {
        Location lobby = spot.get();
        if (players.getAsInt() <= 0 || lobby == null || lobby.getWorld() == null) {
            remove();
            return;
        }
        if (ball == null || ball.isDead()) { // replaced only when it is really gone
            remove();
            ball = spawn(lobby);
            return;
        }
        Location at = ball.getLocation();
        if (!at.getWorld().equals(lobby.getWorld()) || at.distanceSquared(lobby) > MAX_DISTANCE * MAX_DISTANCE
                || at.getY() < lobby.getY() - 10) {
            ball.teleport(ballSpot(lobby));
            ball.setVelocity(new Vector(0, 0, 0));
        }
    }

    /** A plain football kick. */
    void onHit(Player kicker, Entity entity, Vector velocity) {
        if (!owns(entity)) return;
        long now = System.currentTimeMillis();
        if (now - lastKick.getOrDefault(kicker.getUniqueId(), 0L) < KICK_COOLDOWN_MILLIS) return; // one swing, one hit
        lastKick.put(kicker.getUniqueId(), now);
        entity.setVelocity(velocity);
    }

    void remove() {
        if (ball != null) ball.remove();
        ball = null;
    }

    void shutdown() {
        task.cancel();
        remove();
    }

    private SulfurCube spawn(Location lobby) {
        SulfurCube cube = matches.createBallEntity(ballSpot(lobby),
                Component.text("Football", NamedTextColor.YELLOW, TextDecoration.BOLD), true);
        cube.setMaximumNoDamageTicks(0);
        cube.setNoDamageTicks(0);
        return cube;
    }

    /** A couple of blocks in front of where the lobby is, so it doesn't appear on top of anyone. */
    private Location ballSpot(Location lobby) {
        Vector direction = lobby.getDirection().setY(0);
        if (direction.lengthSquared() < 1.0E-4) direction = new Vector(0, 0, 1);
        return lobby.clone().add(direction.normalize().multiply(2.0)).add(0, 1.0, 0);
    }
}
