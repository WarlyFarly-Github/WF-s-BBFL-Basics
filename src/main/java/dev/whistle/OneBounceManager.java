package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One-Bounce: players stand in a circle and punch the ball to each other. After a punch the ball must land (its one
 * bounce) inside another player's area; that player must then punch it before it bounces a second time. A bad
 * punch puts the puncher out; a miss puts the receiver out. The last player left wins.
 */
final class OneBounceManager implements Listener {

    static final int MAX_PLAYERS = 10;

    private static final double ZONE_RADIUS = 2.0;       // the area around each player the ball must land in
    private static final double RING_SPACING = 7.0;      // blocks between neighbours in the circle
    private static final double MIN_RING_RADIUS = 4.0;
    private static final double MAX_STRAY = 25.0;        // further than this from the circle is out of play
    private static final int FIRST_COUNTDOWN_SECONDS = 5;
    private static final int SERVE_COUNTDOWN_SECONDS = 3;
    private static final int HIT_COOLDOWN_TICKS = 10;    // one swing, one hit
    private static final int SERVE_TIMEOUT_TICKS = 400;  // 20 seconds to serve
    private static final int FLIGHT_TIMEOUT_TICKS = 160; // 8 seconds for a punched ball to land
    private static final int RECEIVE_TIMEOUT_TICKS = 60; // 3 seconds to punch a ball that has bounced once
    private static final double HOP_KEEP = 0.85;         // share of its fall speed it hops back up with
    private static final double HOP_MIN = 0.40;          // so the one bounce is always a visible hop

    private enum Phase { COUNTDOWN, SERVE, FLIGHT, RECEIVE }

    private static final class Game {
        private final Set<UUID> alive = new LinkedHashSet<>();
        private final Set<UUID> everyone = new LinkedHashSet<>();
        private final Map<UUID, Location> origins = new HashMap<>();
        private final Map<UUID, GameMode> modes = new HashMap<>();
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        private final Location centre;
        private final double radius;
        private SulfurCube ball;
        private Phase phase = Phase.COUNTDOWN;
        private UUID hitter;    // who punched the ball last
        private UUID receiver;  // who has to punch it next (the server, before the first punch)
        private int countdownLeft;
        private long tick;
        private long phaseTicks;
        private long stateSince;
        private int airTicks;
        private Vector lastVelocity = new Vector(0, 0, 0);
        private BukkitTask task;

        private Game(Location centre, double radius) {
            this.centre = centre;
            this.radius = radius;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final Celebration celebration;
    private final Random random = new Random();
    private final Map<UUID, Game> byPlayer = new HashMap<>();
    private final List<Game> games = new ArrayList<>();

    OneBounceManager(JavaPlugin plugin, MatchManager matches, Celebration celebration) {
        this.plugin = plugin;
        this.matches = matches;
        this.celebration = celebration;
    }

    boolean isPlaying(UUID id) {
        return byPlayer.containsKey(id);
    }

    boolean ownsBall(Entity entity) {
        for (Game game : games) {
            if (game.ball != null && game.ball.equals(entity)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ starting

    /**
     * Starts a game for these players in a circle around the first one (the host), on the host's level, so it should be
     * started on flat open ground. Returns a problem or null.
     */
    String start(List<Player> players) {
        List<Player> ready = players.stream()
                .filter(p -> p.isOnline() && !matches.isInMatch(p.getUniqueId()) && !matches.isWatching(p.getUniqueId()))
                .toList();
        if (ready.size() < 2) return "One-Bounce needs at least 2 free players.";
        if (ready.size() > MAX_PLAYERS) return "One-Bounce can have at most " + MAX_PLAYERS + " players.";

        Location centre = ready.get(0).getLocation().clone();
        centre.setPitch(0);
        double radius = Math.max(MIN_RING_RADIUS, ready.size() * RING_SPACING / (2 * Math.PI));
        Game game = new Game(centre, radius);
        games.add(game);

        for (int i = 0; i < ready.size(); i++) {
            Player p = ready.get(i);
            UUID id = p.getUniqueId();
            game.alive.add(id);
            game.everyone.add(id);
            game.origins.put(id, p.getLocation().clone());
            game.modes.put(id, p.getGameMode());
            byPlayer.put(id, game);

            double angle = 2 * Math.PI * i / ready.size();
            Location spot = centre.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            spot.setDirection(centre.toVector().subtract(spot.toVector()).setY(0)); // face the middle
            p.teleport(spot);
            p.setGameMode(GameMode.ADVENTURE);
            p.showBossBar(game.bar);
            p.sendMessage(Component.text("One-Bounce has started! Punch the ball so it lands in another player's area "
                    + "(the ring around them), then punch it back when it lands in yours. Only one bounce allowed.",
                    NamedTextColor.GREEN));
        }

        game.ball = matches.createBallEntity(centre.clone().add(0, 1, 0), ballName(), false);
        updateBar(game);
        serve(game, FIRST_COUNTDOWN_SECONDS);
        game.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(game), 1L, 1L);
        return null;
    }

    private Component ballName() {
        return Component.text("One-Bounce Ball", NamedTextColor.GREEN, TextDecoration.BOLD);
    }

    /** Picks a random player to serve and puts the ball just in front of them, frozen until the countdown ends. */
    private void serve(Game game, int seconds) {
        List<UUID> candidates = new ArrayList<>(game.alive);
        UUID serverId = candidates.get(random.nextInt(candidates.size()));
        Player server = Bukkit.getPlayer(serverId);

        game.phase = Phase.COUNTDOWN;
        game.countdownLeft = seconds;
        game.phaseTicks = 0;
        game.receiver = serverId;
        game.hitter = null;
        game.airTicks = 0;
        game.lastVelocity = new Vector(0, 0, 0);

        if (game.ball != null && server != null) {
            Location base = server.getLocation();
            Vector toMiddle = game.centre.toVector().subtract(base.toVector()).setY(0);
            if (toMiddle.lengthSquared() < 1.0E-4) toMiddle = new Vector(0, 0, 1);
            Location ballSpot = base.clone().add(toMiddle.normalize().multiply(1.5)).add(0, 1, 0);
            game.ball.teleport(ballSpot);
            game.ball.setGravity(false);
            game.ball.setVelocity(new Vector(0, 0, 0));
        }
        announceCountdown(game, server);
    }

    private void announceCountdown(Game game, Player server) {
        String serverName = server == null ? "someone" : server.getName();
        game.bar.name(Component.text("One-Bounce - " + serverName + " serves in " + game.countdownLeft,
                NamedTextColor.GREEN, TextDecoration.BOLD));
        Title title = Title.title(Component.text(String.valueOf(game.countdownLeft), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(serverName + " serves", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        for (Player p : players(game)) {
            p.showTitle(title);
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 1.0f);
        }
    }

    private void endCountdown(Game game) {
        game.phase = Phase.SERVE;
        game.stateSince = game.tick;
        game.ball.setGravity(true);
        updateBar(game);
        for (Player p : players(game)) p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 2.0f);
        Player server = Bukkit.getPlayer(game.receiver);
        if (server != null) {
            server.sendActionBar(Component.text("Your serve! Punch the ball to another player.", NamedTextColor.YELLOW,
                    TextDecoration.BOLD));
        }
    }

    // ------------------------------------------------------------------ the game loop

    private void tick(Game game) {
        game.tick++;
        if (game.ball == null || !game.ball.isValid()) {
            game.ball = matches.createBallEntity(game.centre.clone().add(0, 1, 0), ballName(), false);
            serve(game, SERVE_COUNTDOWN_SECONDS);
        }
        if (game.tick % 10 == 0) showZones(game);

        if (game.phase == Phase.COUNTDOWN) {
            game.ball.setGravity(false);
            game.ball.setVelocity(new Vector(0, 0, 0));
            game.phaseTicks++;
            if (game.phaseTicks % 20 == 0) {
                game.countdownLeft--;
                if (game.countdownLeft <= 0) endCountdown(game);
                else announceCountdown(game, Bukkit.getPlayer(game.receiver));
            }
            return;
        }

        // Did the ball just land? (It was in the air for a few ticks, falling, and now it's on the ground.)
        Vector previousVelocity = game.lastVelocity;
        boolean onGround = game.ball.isOnGround();
        boolean landed = false;
        if (!onGround) {
            game.airTicks++;
        } else {
            landed = game.airTicks >= 3 && previousVelocity.getY() < -0.1;
            game.airTicks = 0;
        }
        game.lastVelocity = game.ball.getVelocity().clone();

        Location centre = game.ball.getBoundingBox().getCenter().toLocation(game.ball.getWorld());
        if (centre.getWorld().equals(game.centre.getWorld())
                && Math.hypot(centre.getX() - game.centre.getX(), centre.getZ() - game.centre.getZ()) > game.radius + MAX_STRAY) {
            stray(game);
            return;
        }

        switch (game.phase) {
            case SERVE -> {
                if (game.tick - game.stateSince > SERVE_TIMEOUT_TICKS) {
                    Player server = Bukkit.getPlayer(game.receiver);
                    if (server != null) eliminate(game, server, "didn't serve in time");
                }
            }
            case FLIGHT -> {
                if (landed) {
                    landedAfterPunch(game, centre, previousVelocity);
                } else if (game.tick - game.stateSince > FLIGHT_TIMEOUT_TICKS) {
                    Player hitter = Bukkit.getPlayer(game.hitter);
                    if (hitter != null) eliminate(game, hitter, "the ball never landed in anyone's area");
                }
            }
            case RECEIVE -> {
                Player receiver = Bukkit.getPlayer(game.receiver);
                if (receiver == null) return;
                if (landed) {
                    eliminate(game, receiver, "the ball bounced twice");
                } else if (game.tick - game.stateSince > RECEIVE_TIMEOUT_TICKS) {
                    eliminate(game, receiver, "missed the ball");
                }
            }
            default -> { }
        }
    }

    /** The ball landed after a punch: that is its one bounce, and it has to be in another player's area. */
    private void landedAfterPunch(Game game, Location landing, Vector previousVelocity) {
        Player hitter = Bukkit.getPlayer(game.hitter);

        Player target = null;
        double best = ZONE_RADIUS;
        for (UUID id : game.alive) {
            if (id.equals(game.hitter)) continue; // landing in your own area doesn't count
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.getWorld().equals(landing.getWorld())) continue;
            double d = Math.hypot(p.getLocation().getX() - landing.getX(), p.getLocation().getZ() - landing.getZ());
            if (d <= best) {
                best = d;
                target = p;
            }
        }

        if (target == null) {
            if (hitter != null) eliminate(game, hitter, "a bad punch: the ball didn't land in anyone's area");
            return;
        }

        // Its one bounce: hop back up so the receiver has a moment to punch it.
        Vector v = game.ball.getVelocity();
        v.setY(Math.max(-previousVelocity.getY() * HOP_KEEP, HOP_MIN));
        game.ball.setVelocity(v);

        game.receiver = target.getUniqueId();
        game.phase = Phase.RECEIVE;
        game.stateSince = game.tick;
        target.sendActionBar(Component.text("It's yours! Punch it before it bounces again!", NamedTextColor.YELLOW,
                TextDecoration.BOLD));
        target.playSound(target.getLocation(), "block.note_block.pling", 1.0f, 1.5f);
    }

    /** Called by the match code when a player hits a ball. */
    void onHit(Player player, Vector velocity) {
        UUID id = player.getUniqueId();
        Game game = byPlayer.get(id);
        if (game == null || !game.alive.contains(id)) return;
        if (game.phase != Phase.SERVE && game.phase != Phase.RECEIVE) return; // nobody can hit it in flight
        if (!id.equals(game.receiver)) return; // only the player it's meant for
        if (game.tick - game.lastHit.getOrDefault(id, -100L) < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        game.lastHit.put(id, game.tick);

        game.ball.setGravity(true);
        game.ball.setVelocity(velocity);
        game.hitter = id;
        game.receiver = null;
        game.phase = Phase.FLIGHT;
        game.stateSince = game.tick;
        game.airTicks = 0;
        game.lastVelocity = velocity.clone();
    }

    /** The ball is far outside the circle: whoever was responsible for it is out. */
    private void stray(Game game) {
        UUID responsible = game.phase == Phase.FLIGHT ? game.hitter : game.receiver;
        Player p = responsible == null ? null : Bukkit.getPlayer(responsible);
        if (p != null) eliminate(game, p, "the ball went out of play");
        else serve(game, SERVE_COUNTDOWN_SECONDS);
    }

    // ------------------------------------------------------------------ out and finished

    private void eliminate(Game game, Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!game.alive.remove(id)) return;
        byPlayer.remove(id);
        release(game, player);

        player.sendMessage(Component.text("You are out: " + reason + ".", NamedTextColor.RED, TextDecoration.BOLD));
        broadcast(game, Component.text(player.getName() + " is out: " + reason + ".", NamedTextColor.RED, TextDecoration.BOLD));

        if (game.alive.size() <= 1) {
            finish(game);
        } else {
            updateBar(game);
            serve(game, SERVE_COUNTDOWN_SECONDS);
        }
    }

    private void finish(Game game) {
        if (game.task != null) game.task.cancel();
        if (game.ball != null) game.ball.remove();

        Player winner = null;
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) winner = p;
        }
        Component message = winner != null
                ? Component.text(winner.getName() + " wins One-Bounce!", NamedTextColor.GOLD, TextDecoration.BOLD)
                : Component.text("One-Bounce ended with no winner.", NamedTextColor.YELLOW);
        List<Player> everyone = new ArrayList<>();
        for (UUID id : game.everyone) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            everyone.add(p);
            p.sendMessage(message);
            if (game.alive.contains(id)) release(game, p);
        }
        for (UUID id : game.alive) byPlayer.remove(id);
        game.alive.clear();
        games.remove(game);

        celebration.finish(everyone, winner);
    }

    private void release(Game game, Player player) {
        player.hideBossBar(game.bar);
        GameMode mode = game.modes.get(player.getUniqueId());
        if (mode != null) player.setGameMode(mode);
        Location origin = game.origins.get(player.getUniqueId());
        if (origin != null) player.teleport(origin);
    }

    /** /match leave while playing: you're out. Returns false if the player wasn't playing. */
    boolean leave(Player player) {
        Game game = byPlayer.get(player.getUniqueId());
        if (game == null) return false;
        eliminate(game, player, "left the game");
        return true;
    }

    void shutdown() {
        for (Game game : new ArrayList<>(games)) {
            for (UUID id : new ArrayList<>(game.alive)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) release(game, p);
                byPlayer.remove(id);
            }
            game.alive.clear();
            if (game.task != null) game.task.cancel();
            if (game.ball != null) game.ball.remove();
        }
        games.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer());
    }

    /** Everyone is frozen in place (but can look around) during the countdowns. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Game game = byPlayer.get(event.getPlayer().getUniqueId());
        if (game == null || game.phase != Phase.COUNTDOWN) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A ring of particles on the ground around every player, showing the area the ball has to land in. */
    private void showZones(Game game) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.WHITE, 1.0f);
        List<Player> viewers = players(game);
        for (Player target : viewers) {
            Location base = target.getLocation();
            for (int i = 0; i < 16; i++) {
                double angle = 2 * Math.PI * i / 16;
                double x = base.getX() + Math.cos(angle) * ZONE_RADIUS;
                double z = base.getZ() + Math.sin(angle) * ZONE_RADIUS;
                for (Player viewer : viewers) {
                    viewer.spawnParticle(Particle.DUST, x, base.getY() + 0.1, z, 1, 0, 0, 0, 0, dust);
                }
            }
        }
    }

    private void updateBar(Game game) {
        int left = game.alive.size();
        game.bar.name(Component.text("One-Bounce - " + left + " player" + (left == 1 ? "" : "s") + " left",
                NamedTextColor.GREEN, TextDecoration.BOLD));
        game.bar.progress(Math.max(0.0f, Math.min(1.0f, left / (float) Math.max(1, game.everyone.size()))));
    }

    private List<Player> players(Game game) {
        List<Player> list = new ArrayList<>();
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) list.add(p);
        }
        return list;
    }

    private void broadcast(Game game, Component message) {
        for (UUID id : game.everyone) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(message);
        }
    }
}
