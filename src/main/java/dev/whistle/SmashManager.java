package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import java.util.Set;
import java.util.UUID;

/**
 * Smash: players take turns hitting the ball against the target wall of a smash stadium. After a hit the ball has to
 * reach the wall; nobody may hit it twice in a row; stealing the ball from someone right next to it is a warning, and
 * two warnings put you out. The last player left wins.
 */
final class SmashManager implements Listener {

    static final int MAX_PLAYERS = 10;

    private static final int FIRST_COUNTDOWN_SECONDS = 5;
    private static final int SERVE_COUNTDOWN_SECONDS = 3;
    private static final double STEAL_RADIUS = 2.5;     // someone this close (and closer than you) is being stolen from
    private static final int HIT_COOLDOWN_TICKS = 10;   // one swing can't count as several hits
    private static final int WALL_TIMEOUT_TICKS = 160;  // 8 seconds for a hit ball to reach the wall
    private static final int IDLE_RESET_TICKS = 400;    // a ball nobody touches for 20 seconds goes back to the middle
    private static final int MAX_WARNINGS = 2;


    // Hits and the wall are livelier in Smash than in football.
    private static final double HIT_LIFT = 0.65;               // a hit sends the ball up this much (football is 0.35)
    private static final double WALL_RECOIL_MIN = 0.45;        // share of the impact speed kept off the wall, even slow
    private static final double WALL_RECOIL_PER_SPEED = 0.35;  // extra share kept per block/tick of impact speed
    private static final double WALL_RECOIL_MAX = 0.95;        // the most that is ever kept
    private static final double WALL_RECOIL_MIN_SPEED = 0.30;  // the wall always pushes the ball back out at least this fast
    private static final double WALL_SLIDE_KEEP = 0.95;        // speed kept along the wall

    private enum Phase { COUNTDOWN, PLAY }

    private static final class Game {
        private final SmashStadium stadium;
        private final Set<UUID> alive = new LinkedHashSet<>();
        private final Set<UUID> everyone = new LinkedHashSet<>();
        private final Map<UUID, Location> origins = new HashMap<>();
        private final Map<UUID, GameMode> modes = new HashMap<>();
        private final Map<UUID, Integer> warnings = new HashMap<>();
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        private final Location spawn;
        private SulfurCube ball;
        private Location lastCentre; // where the ball was last tick, so a fast ball can't skip over the trigger
        private UUID lastHitter;   // who hit the ball last (they can't hit it again straight away)
        private UUID pending;      // who hit it and hasn't had it reach the wall yet
        private long tick;       // never reset, so hit cooldowns keep working across countdowns
        private long phaseTicks;  // ticks since this countdown began
        private long pendingSince;
        private long lastActivity;
        private Phase phase = Phase.COUNTDOWN;
        private int countdownLeft;
        private BukkitTask task;

        private Game(SmashStadium stadium) {
            this.stadium = stadium;
            this.spawn = stadium.centre();
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final SmashStadiums stadiums;
    private final Celebration celebration;
    private final Map<UUID, Game> byPlayer = new HashMap<>();
    private final List<Game> games = new ArrayList<>();

    SmashManager(JavaPlugin plugin, MatchManager matches, SmashStadiums stadiums, Celebration celebration) {
        this.plugin = plugin;
        this.celebration = celebration;
        this.matches = matches;
        this.stadiums = stadiums;
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

    /** Starts a game for these players on a random finished smash stadium nobody is using. Returns a problem or null. */
    String start(List<Player> players) {
        List<Player> ready = players.stream()
                .filter(p -> p.isOnline() && !matches.isInMatch(p.getUniqueId()) && !matches.isWatching(p.getUniqueId()))
                .toList();
        if (ready.size() < 2) return "Smash needs at least 2 free players.";
        if (ready.size() > MAX_PLAYERS) return "Smash can have at most " + MAX_PLAYERS + " players.";

        List<SmashStadium> free = new ArrayList<>();
        for (SmashStadium stadium : stadiums.all()) {
            if (stadium.isComplete() && games.stream().noneMatch(g -> g.stadium == stadium)) free.add(stadium);
        }
        if (free.isEmpty()) return "No finished smash stadium is free right now, so Smash can't start.";
        SmashStadium stadium = free.get(new java.util.Random().nextInt(free.size()));

        Game game = new Game(stadium);
        games.add(game);

        Location facing = stadium.wallMiddle();
        double radius = 2.5;
        for (int i = 0; i < ready.size(); i++) {
            Player p = ready.get(i);
            UUID id = p.getUniqueId();
            game.alive.add(id);
            game.everyone.add(id);
            game.origins.put(id, p.getLocation().clone());
            game.modes.put(id, p.getGameMode());
            byPlayer.put(id, game);

            double angle = 2 * Math.PI * i / ready.size();
            Location spot = game.spawn.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            spot.setDirection(facing.toVector().subtract(spot.toVector()).setY(0));
            p.teleport(spot);
            p.setGameMode(GameMode.ADVENTURE);
            p.showBossBar(game.bar);
            p.sendMessage(Component.text("Smash has started on " + stadium.name + "! Hit the ball against the wall, "
                    + "but never twice in a row. Last player standing wins.", NamedTextColor.GREEN));
        }

        game.ball = matches.createBallEntity(game.spawn.clone().add(0, 1, 0),
                Component.text("Smash Ball", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD), false);
        updateBar(game);
        beginCountdown(game, FIRST_COUNTDOWN_SECONDS);
        game.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(game), 1L, 1L);
        return null;
    }

    // ------------------------------------------------------------------ the countdown

    private void beginCountdown(Game game, int seconds) {
        game.phase = Phase.COUNTDOWN;
        game.countdownLeft = seconds;
        game.phaseTicks = 0;
        game.lastCentre = null;
        resetBall(game);
        announceCountdown(game);
    }

    private void announceCountdown(Game game) {
        game.bar.name(Component.text("Smash starts in " + game.countdownLeft, NamedTextColor.GREEN, TextDecoration.BOLD));
        Title title = Title.title(Component.text(String.valueOf(game.countdownLeft), NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Get ready...", NamedTextColor.GRAY),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        for (Player p : players(game)) {
            p.showTitle(title);
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 1.0f); // the same ding as the football freezes
        }
    }

    private void endCountdown(Game game) {
        game.phase = Phase.PLAY;
        game.lastActivity = game.tick;
        game.ball.setGravity(true);
        updateBar(game);
        for (Player p : players(game)) p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 2.0f); // an octave up
    }

    // ------------------------------------------------------------------ the game loop

    private void tick(Game game) {
        game.tick++;
        if (game.ball == null || !game.ball.isValid()) {
            game.ball = matches.createBallEntity(game.spawn.clone().add(0, 1, 0),
                    Component.text("Smash Ball", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD), false);
            if (game.phase == Phase.COUNTDOWN) game.ball.setGravity(false);
        }

        if (game.phase == Phase.COUNTDOWN) {
            game.ball.setGravity(false);
            game.ball.setVelocity(new Vector(0, 0, 0));
            game.phaseTicks++;
            if (game.phaseTicks % 20 == 0) {
                game.countdownLeft--;
                if (game.countdownLeft <= 0) endCountdown(game);
                else announceCountdown(game);
            }
            return;
        }

        Location centre = ballCentre(game);
        if (!game.stadium.boundsContain(centre)) {
            outOfBounds(game);
            return;
        }

        // Before the serve (and after a reset) the ball only moves when punched: undo any nudge.
        if (game.lastHitter == null && game.pending == null) {
            Vector still = game.ball.getVelocity();
            if (still.getX() != 0 || still.getZ() != 0) game.ball.setVelocity(new Vector(0, still.getY(), 0));
        }

        boolean hitWall = bounceOffWall(game, centre);

        if (game.pending != null) {
            if (hitWall) {
                game.pending = null; // that hit reached the wall, so it counts
                game.lastActivity = game.tick;
                for (Player p : players(game)) p.sendActionBar(Component.text("Wall hit!", NamedTextColor.GREEN, TextDecoration.BOLD));
            } else if (game.tick - game.pendingSince > WALL_TIMEOUT_TICKS) {
                Player hitter = Bukkit.getPlayer(game.pending);
                if (hitter != null) eliminate(game, hitter, "the ball never reached the wall");
                else resetAndServe(game);
            }
        } else if (game.tick - game.lastActivity > IDLE_RESET_TICKS) {
            game.lastHitter = null;
            broadcast(game, Component.text("Nobody hit the ball, so it goes back to the middle.", NamedTextColor.YELLOW));
            resetAndServe(game);
        }
    }

    /**
     * The wall trigger: when the ball reaches the line just in front of the wall it recoils strongly off it, and the
     * hit counts as reaching the wall. Returns true if it did.
     */
    private boolean bounceOffWall(Game game, Location centre) {
        SmashStadium stadium = game.stadium;
        Location previous = game.lastCentre;
        game.lastCentre = centre.clone();

        // The trigger sits just in front of the blocks: the ball's real half width plus the block face.
        double ballHalfWidth = Math.max(game.ball.getBoundingBox().getWidthX(), game.ball.getBoundingBox().getWidthZ()) / 2.0;
        double trigger = SmashStadium.triggerDistance(ballHalfWidth);
        double front = stadium.frontDistance(centre);

        // Normally the ball is in the trigger zone. A hard punch can move it right across the zone in one tick,
        // so also check whether it crossed the trigger line since the last tick.
        boolean inZone = stadium.inTriggerZone(centre, trigger);
        Location crossing = null;
        if (!inZone && previous != null && previous.getWorld().equals(centre.getWorld())) {
            double before = stadium.frontDistance(previous);
            if (before > trigger && front <= trigger) {
                double t = (before - trigger) / (before - front);
                Location point = previous.clone().add(centre.clone().subtract(previous).toVector().multiply(t));
                if (stadium.alongWall(point)) crossing = point;
            }
        }
        if (!inZone && crossing == null) return false;

        double[] normal = stadium.frontNormal(); // pointing away from the wall, into the arena
        double nx = normal[0];
        double nz = normal[1];

        // The ball's velocity, or how far it actually moved this tick if the server reports it as (nearly) still.
        Vector v = game.ball.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) < 0.01 && previous != null && previous.getWorld().equals(centre.getWorld())) {
            v = centre.clone().subtract(previous).toVector();
        }
        double intoPitch = v.getX() * nx + v.getZ() * nz;
        if (intoPitch >= 0 && front >= 0 && crossing == null) return false; // moving away: not a new hit

        // A strong recoil: the ball keeps most of a hard hit and is always pushed clearly back out.
        double impact = Math.max(0, -intoPitch);
        double restitution = Math.min(WALL_RECOIL_MAX, WALL_RECOIL_MIN + WALL_RECOIL_PER_SPEED * impact);
        double bounce = Math.max(impact * restitution, WALL_RECOIL_MIN_SPEED);
        double slideX = v.getX() - intoPitch * nx;
        double slideZ = v.getZ() - intoPitch * nz;
        Vector bounced = new Vector(
                slideX * WALL_SLIDE_KEEP + nx * bounce,
                v.getY(),
                slideZ * WALL_SLIDE_KEEP + nz * bounce);

        // Put it back just in front of the trigger so it doesn't trigger again straight away: at the point where it
        // crossed the line if it skipped over, otherwise pushed out from where it is.
        Location target = game.ball.getLocation();
        if (crossing != null) {
            target.add(crossing.getX() - centre.getX() + nx * MatchManager.WALL_INSET, crossing.getY() - centre.getY(),
                    crossing.getZ() - centre.getZ() + nz * MatchManager.WALL_INSET);
        } else {
            double push = trigger + MatchManager.WALL_INSET - front;
            target.add(nx * push, 0, nz * push);
        }
        game.ball.teleport(target);
        game.ball.setVelocity(bounced);
        game.lastCentre = null; // it moved, so don't compare the next tick with the old spot
        return true;
    }

    private void outOfBounds(Game game) {
        for (Player p : players(game)) {
            p.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        if (game.pending != null) {
            // The hit never reached the wall before leaving the stadium.
            Player hitter = Bukkit.getPlayer(game.pending);
            if (hitter != null) {
                eliminate(game, hitter, "the ball left the stadium before reaching the wall");
                return;
            }
        }
        resetAndServe(game);
    }

    /** Called by the match code when a player hits a ball; returns after applying (or refusing) the hit. */
    void onHit(Player player, Vector velocity) {
        UUID id = player.getUniqueId();
        Game game = byPlayer.get(id);
        if (game == null || !game.alive.contains(id) || game.phase != Phase.PLAY) return;
        if (game.tick - game.lastHit.getOrDefault(id, -100L) < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        game.lastHit.put(id, game.tick);
        game.lastActivity = game.tick;

        // Hitting it twice in a row: out.
        if (id.equals(game.lastHitter)) {
            eliminate(game, player, "hit the ball twice in a row");
            return;
        }

        // Stealing it from someone who was right next to it, or hitting it before it reached the wall: a warning.
        Location centre = ballCentre(game);
        boolean serve = game.lastHitter == null && game.pending == null;
        if (!serve) {
            double mine = player.getLocation().distance(centre);
            for (UUID otherId : game.alive) {
                if (otherId.equals(id)) continue;
                Player other = Bukkit.getPlayer(otherId);
                if (other == null || !other.getWorld().equals(centre.getWorld())) continue;
                double theirs = other.getLocation().distance(centre);
                if (theirs <= STEAL_RADIUS && theirs < mine) {
                    if (warn(game, player, "you stole the ball from " + other.getName())) return;
                    break;
                }
            }
            if (game.pending != null && !game.pending.equals(id)) {
                if (warn(game, player, "you hit the ball before it reached the wall")) return;
            }
        }

        velocity.setY(HIT_LIFT); // Smash hits send the ball higher than a football kick
        game.ball.setVelocity(velocity);
        game.pending = id;
        game.pendingSince = game.tick;
        game.lastHitter = id;
    }

    /** Gives a warning. Returns true if that put the player out (two warnings). */
    private boolean warn(Game game, Player player, String reason) {
        int count = game.warnings.merge(player.getUniqueId(), 1, Integer::sum);
        if (count >= MAX_WARNINGS) {
            eliminate(game, player, "two warnings (" + reason + ")");
            return true;
        }
        player.sendMessage(Component.text("Warning " + count + "/" + MAX_WARNINGS + ": " + reason + ". One more and you're out.",
                NamedTextColor.RED, TextDecoration.BOLD));
        broadcastExcept(game, player, Component.text(player.getName() + " got a warning (" + count + "/" + MAX_WARNINGS + ").",
                NamedTextColor.YELLOW));
        return false;
    }

    // ------------------------------------------------------------------ out and finished

    private void eliminate(Game game, Player player, String reason) {
        UUID id = player.getUniqueId();
        if (!game.alive.remove(id)) return;
        byPlayer.remove(id);
        release(game, player);
        if (game.pending != null && game.pending.equals(id)) game.pending = null;
        if (id.equals(game.lastHitter)) game.lastHitter = null;

        Component message = Component.text(player.getName() + " is out: " + reason + ".", NamedTextColor.RED, TextDecoration.BOLD);
        player.sendMessage(Component.text("You are out: " + reason + ".", NamedTextColor.RED, TextDecoration.BOLD));
        broadcast(game, message);

        if (game.alive.size() <= 1) {
            finish(game);
        } else {
            updateBar(game);
            resetAndServe(game);
        }
    }

    private void resetAndServe(Game game) {
        game.pending = null;
        game.lastHitter = null;
        beginCountdown(game, SERVE_COUNTDOWN_SECONDS);
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
                ? Component.text(winner.getName() + " wins Smash!", NamedTextColor.GOLD, TextDecoration.BOLD)
                : Component.text("Smash ended with no winner.", NamedTextColor.YELLOW);
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

        // "You won!", fireworks around the winner (once they are back where they were), and the level up sound.
        celebration.finish(everyone, winner);
    }

    /** Puts a player back: their old game mode and the place they were before. */
    private void release(Game game, Player player) {
        player.hideBossBar(game.bar);
        GameMode mode = game.modes.get(player.getUniqueId());
        if (mode != null) player.setGameMode(mode);
        Location origin = game.origins.get(player.getUniqueId());
        if (origin != null) player.teleport(origin);
    }

    /** /match leave while playing Smash: you're out. Returns false if the player wasn't playing. */
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

    private Location ballCentre(Game game) {
        return game.ball.getBoundingBox().getCenter().toLocation(game.ball.getWorld());
    }

    private void resetBall(Game game) {
        if (game.ball == null) return;
        game.ball.teleport(game.spawn.clone().add(0, 1, 0));
        game.ball.setGravity(false);
        game.ball.setVelocity(new Vector(0, 0, 0));
    }

    private void updateBar(Game game) {
        int left = game.alive.size();
        game.bar.name(Component.text("Smash - " + left + " player" + (left == 1 ? "" : "s") + " left",
                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
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

    private void broadcastExcept(Game game, Player except, Component message) {
        for (Player p : players(game)) {
            if (!p.equals(except)) p.sendMessage(message);
        }
    }
}
