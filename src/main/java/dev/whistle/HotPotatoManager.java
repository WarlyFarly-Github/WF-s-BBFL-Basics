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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Hot Potato: the ball is a ticking time bomb. When its fuse runs out it blows up everyone near it, so kick it away
 * to other players (on an ice floor, so be careful where it slides). Two teams; a team that has nobody left loses.
 * The ball is a normal football: kicks, bounds and sliding on ice all work as in a match, and the floor itself is
 * built by hand in the arena (nothing here changes it).
 */
final class HotPotatoManager implements Listener {

    static final int MAX_PLAYERS = 10;

    private static final int FIRST_COUNTDOWN_SECONDS = 5;
    private static final int ROUND_COUNTDOWN_SECONDS = 3;  // the pause before every new bomb
    private static final int RESULT_SECONDS = 10;          // everyone can fly around the arena afterwards
    private static final int HIT_COOLDOWN_TICKS = 4;
    private static final int DEFAULT_FUSE_MIN_SECONDS = 12;
    private static final int DEFAULT_FUSE_MAX_SECONDS = 25;
    private static final double DEFAULT_BLAST_RADIUS = 4.0;
    private static final double MAX_BLAST_RADIUS = 9.0;
    private static final double BLAST_GROWTH = 1.0;        // a bomb that catches nobody makes the next one bigger
    private static final double BOUNCE_MAX_DISTANCE = 4.0; // further out than this the ball is put back in the middle
    private static final int OUT_RESET_COOLDOWN_TICKS = 40;
    private static final int RING_TICKS = 60;              // the blast ring is shown for the last 3 seconds of a fuse

    private enum Phase { COUNTDOWN, PLAY, RESULT }

    private static final class Game {
        private final HotPotatoStadium stadium;
        private final World world;
        private final Location middle;
        private final boolean twoTeams;
        private final int fuseMinTicks;
        private final int fuseMaxTicks;
        private final double baseRadius;

        private final Map<UUID, Match.Team> teams = new LinkedHashMap<>();
        private final Set<UUID> active = new LinkedHashSet<>(); // everyone taking part, until they leave
        private final Set<UUID> alive = new LinkedHashSet<>();  // the ones who haven't been blown up
        private final Map<UUID, Location> spots = new HashMap<>();
        private final Map<UUID, Location> origins = new HashMap<>();
        private final Map<UUID, GameMode> modes = new HashMap<>();
        private final Map<UUID, ItemStack> chestplates = new HashMap<>();
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private final Map<UUID, Long> lastBoundsWarning = new HashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);

        private Phase phase = Phase.COUNTDOWN;
        private int phaseTicks;
        private int countdownLeft;
        private long tick;
        private boolean firstRound = true;
        private BukkitTask task;

        private SulfurCube ball;
        private Vector lastVelocity; // its velocity the last time it was inside the bounds, for bouncing
        private long lastReset = -1000;
        private int fuseTicks;
        private int fuseTotal;
        private int dangerStage = -1;
        private long lastTickSound;
        private double radius;
        private int bombs; // bombs that have gone off

        private Game(HotPotatoStadium stadium, World world, boolean twoTeams, int fuseMinTicks, int fuseMaxTicks,
                     double baseRadius) {
            this.stadium = stadium;
            this.world = world;
            this.middle = stadium.middle();
            this.twoTeams = twoTeams;
            this.fuseMinTicks = fuseMinTicks;
            this.fuseMaxTicks = fuseMaxTicks;
            this.baseRadius = baseRadius;
            this.radius = baseRadius;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final HotPotatoStadiums stadiums;
    private final Celebration celebration;
    private final NamespacedKey jerseyKey;
    private final Random random = new Random();
    private final List<Game> games = new ArrayList<>();
    private final Map<UUID, Game> byPlayer = new HashMap<>();

    HotPotatoManager(JavaPlugin plugin, MatchManager matches, HotPotatoStadiums stadiums, Celebration celebration) {
        this.plugin = plugin;
        this.matches = matches;
        this.stadiums = stadiums;
        this.celebration = celebration;
        this.jerseyKey = new NamespacedKey(plugin, "hotpotato_jersey");
    }

    boolean isPlaying(UUID id) {
        return byPlayer.containsKey(id);
    }

    /** Where this player's game is played (for spectators in the minigames lobby), or null if they aren't playing. */
    MinigameArena arenaOf(UUID id) {
        Game game = byPlayer.get(id);
        if (game == null) return null;
        return new MinigameArena(game.middle.clone().add(0, 3, 0), l -> game.stadium.boundsContain(l));
    }

    boolean ownsBall(Entity entity) {
        for (Game game : games) {
            if (game.ball != null && game.ball.equals(entity)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ starting

    String start(List<Player> players) {
        return start(players, false);
    }

    /**
     * Starts a game for these players on a random finished Hot Potato arena nobody is using and that has enough
     * starting positions for them. The players are split into a red and a blue team. With bypass (operators only, from
     * /challenge bypass) one player can play alone (on the red team) to practise. Returns a problem or null.
     */
    String start(List<Player> players, boolean bypass) {
        List<Player> ready = new ArrayList<>(players.stream()
                .filter(p -> p.isOnline() && !matches.isInMatch(p.getUniqueId()) && !matches.isWatching(p.getUniqueId()))
                .toList());
        if (ready.size() < (bypass ? 1 : 2)) return "Hot Potato needs at least 2 free players.";
        if (ready.size() > MAX_PLAYERS) return "Hot Potato can have at most " + MAX_PLAYERS + " players.";

        // Teams as even as possible; which team gets the extra player (when it's odd) is random.
        boolean twoTeams = ready.size() >= 2;
        boolean redFirst = random.nextBoolean();
        int redCount = twoTeams ? (ready.size() + (redFirst ? 1 : 0)) / 2 : ready.size();
        int blueCount = ready.size() - redCount;

        List<HotPotatoStadium> free = new ArrayList<>();
        List<Boolean> swapped = new ArrayList<>(); // whether the bigger team has to be blue to fit this arena
        boolean anyFinished = false;
        for (HotPotatoStadium stadium : stadiums.all()) {
            if (!stadium.isComplete()) continue;
            anyFinished = true;
            if (inUse(stadium)) continue;
            int red = stadium.capacity(Match.Team.RED);
            int blue = stadium.capacity(Match.Team.BLUE);
            if (red >= redCount && blue >= blueCount) {
                free.add(stadium);
                swapped.add(false);
            } else if (red >= blueCount && blue >= redCount) {
                free.add(stadium);
                swapped.add(true);
            }
        }
        if (!anyFinished) return "There is no finished Hot Potato arena yet. An operator needs to make one with /hpstadium.";
        if (free.isEmpty()) {
            return "No free Hot Potato arena has enough starting positions for " + ready.size() + " players.";
        }
        int pick = random.nextInt(free.size());
        HotPotatoStadium stadium = free.get(pick);
        if (swapped.get(pick)) {
            int swap = redCount;
            redCount = blueCount;
            blueCount = swap;
        }
        World world = stadium.bounds.get(0).getWorld();

        int minSeconds = Math.max(5, Math.min(300, plugin.getConfig().getInt("hot-potato.fuse-min-seconds", DEFAULT_FUSE_MIN_SECONDS)));
        int maxSeconds = Math.max(minSeconds, Math.min(300, plugin.getConfig().getInt("hot-potato.fuse-max-seconds", DEFAULT_FUSE_MAX_SECONDS)));
        double radius = Math.max(2.0, Math.min(MAX_BLAST_RADIUS,
                plugin.getConfig().getDouble("hot-potato.blast-radius", DEFAULT_BLAST_RADIUS)));

        Game game = new Game(stadium, world, twoTeams, minSeconds * 20, maxSeconds * 20, radius);
        games.add(game);

        Collections.shuffle(ready, random);
        int redIndex = 0;
        int blueIndex = 0;
        for (int i = 0; i < ready.size(); i++) {
            Player p = ready.get(i);
            UUID id = p.getUniqueId();
            Match.Team team = i < redCount ? Match.Team.RED : Match.Team.BLUE;
            int index = team == Match.Team.RED ? redIndex++ : blueIndex++;

            game.teams.put(id, team);
            game.active.add(id);
            game.alive.add(id);
            game.origins.put(id, p.getLocation().clone());
            game.modes.put(id, p.getGameMode());
            game.spots.put(id, spotFor(game, stadium.positions(team).get(index)));
            byPlayer.put(id, game);

            p.teleport(game.spots.get(id));
            p.setGameMode(GameMode.ADVENTURE);
            giveJersey(game, p, team);
            p.showBossBar(game.bar);
            p.sendMessage(Component.text("Hot Potato has started on " + stadium.name + "! You are on the ", NamedTextColor.GREEN)
                    .append(Component.text(team.label + " team", team.color, TextDecoration.BOLD))
                    .append(Component.text(". The ball is a bomb with a hidden fuse: when it goes off it blows up everyone "
                            + "close to it. Kick it away to other players, and watch out: the floor is slippery! "
                            + "A team with nobody left loses.", NamedTextColor.GREEN)));
        }

        game.ball = spawnBall(game);
        game.countdownLeft = FIRST_COUNTDOWN_SECONDS;
        updateBar(game);
        announceCountdown(game);
        game.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(game), 1L, 1L);
        return null;
    }

    private boolean inUse(HotPotatoStadium stadium) {
        for (Game game : games) {
            if (game.stadium == stadium) return true;
        }
        return false;
    }

    /** A starting position, standing in the middle of its block and facing the middle of the arena. */
    private Location spotFor(Game game, Location position) {
        Location spot = HotPotatoStadium.centreOf(position);
        Vector toMiddle = game.middle.toVector().subtract(spot.toVector()).setY(0);
        if (toMiddle.lengthSquared() < 1.0E-4) toMiddle = new Vector(0, 0, 1);
        spot.setDirection(toMiddle);
        return spot;
    }

    // ------------------------------------------------------------------ the ball

    private SulfurCube spawnBall(Game game) {
        SulfurCube cube = matches.createBallEntity(game.middle.clone().add(0, 1, 0),
                Component.text("Hot Potato", NamedTextColor.WHITE, TextDecoration.BOLD), true);
        cube.setMaximumNoDamageTicks(0); // it can be kicked again straight away
        cube.setNoDamageTicks(0);
        if (game.phase != Phase.PLAY) cube.setGravity(false);
        game.lastVelocity = null;
        game.dangerStage = -1;
        return cube;
    }

    private void removeBall(Game game) {
        if (game.ball != null) game.ball.remove();
        game.ball = null;
    }

    /** Called by the match code when a player punches a ball. It is an ordinary football kick. */
    void onHit(Player kicker, Entity entity, Vector velocity) {
        UUID id = kicker.getUniqueId();
        Game game = byPlayer.get(id);
        if (game == null || game.phase != Phase.PLAY || !game.alive.contains(id)) return;
        if (game.ball == null || !game.ball.equals(entity)) return;
        if (game.tick - game.lastHit.getOrDefault(id, -100L) < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        game.lastHit.put(id, game.tick);

        entity.setVelocity(velocity); // the usual football kick: what happens on the floor (ice...) is up to the floor
    }

    // ------------------------------------------------------------------ the game loop

    private void tick(Game game) {
        game.tick++;
        switch (game.phase) {
            case COUNTDOWN -> {
                keepBall(game, true);
                game.phaseTicks++;
                if (game.phaseTicks % 20 == 0) {
                    game.countdownLeft--;
                    if (game.countdownLeft <= 0) startRound(game);
                    else announceCountdown(game);
                }
            }
            case PLAY -> playTick(game);
            case RESULT -> {
                game.phaseTicks++;
                if (game.phaseTicks >= RESULT_SECONDS * 20) close(game);
            }
        }
    }

    /** Makes sure the ball exists (replacing one that is truly gone) and, in a countdown, holds it still. */
    private void keepBall(Game game, boolean frozen) {
        if (game.ball == null || game.ball.isDead()) {
            if (game.ball != null) game.ball.remove();
            game.ball = spawnBall(game);
        }
        if (frozen) {
            game.ball.setGravity(false);
            game.ball.setVelocity(new Vector(0, 0, 0));
        }
    }

    private void announceCountdown(Game game) {
        boolean first = game.firstRound;
        game.bar.name(Component.text(first ? "Hot Potato starts in " + game.countdownLeft
                : "Next bomb in " + game.countdownLeft, NamedTextColor.GREEN, TextDecoration.BOLD));
        game.bar.progress(1.0f);
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Match.Team team = game.teams.get(id);
            Component subtitle = first ? Component.text("You are on the " + team.label + " team", team.color)
                    : Component.text(game.alive.contains(id) ? "Get ready!" : "You are out - watch the next bomb", NamedTextColor.GRAY);
            p.showTitle(Title.title(Component.text(String.valueOf(game.countdownLeft), NamedTextColor.GOLD, TextDecoration.BOLD),
                    subtitle, Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 1.0f);
        }
    }

    /** The countdown is over: the fuse is lit. */
    private void startRound(Game game) {
        game.phase = Phase.PLAY;
        game.phaseTicks = 0;
        game.firstRound = false;
        game.fuseTotal = game.fuseMinTicks + random.nextInt(game.fuseMaxTicks - game.fuseMinTicks + 1);
        game.fuseTicks = game.fuseTotal;
        game.lastTickSound = game.tick;
        if (game.ball != null) game.ball.setGravity(true);
        updateBar(game);
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 2.0f);
            if (game.alive.contains(id)) {
                p.sendActionBar(Component.text("The bomb is ticking! Kick it away!", NamedTextColor.RED, TextDecoration.BOLD));
            }
        }
    }

    private void playTick(Game game) {
        keepBall(game, false);
        checkBounds(game);
        if (game.ball == null) return;

        game.fuseTicks--;
        fuseEffects(game);
        if (game.fuseTicks <= 0) {
            explode(game);
            return;
        }
        if (game.tick % 10 == 0) updateBar(game);
    }

    /** The ticking: it speeds up and gets higher as the fuse runs down, and the ball turns yellow and then red. */
    private void fuseEffects(Game game) {
        SulfurCube ball = game.ball;
        Location at = ball.getBoundingBox().getCenter().toLocation(game.world);
        double fraction = Math.max(0.0, Math.min(1.0, game.fuseTicks / (double) game.fuseTotal));

        long interval = Math.max(3, Math.round(4 + 16 * fraction));
        if (game.tick - game.lastTickSound >= interval) {
            game.lastTickSound = game.tick;
            game.world.playSound(at, "block.note_block.hat", 1.6f, (float) (0.7 + 1.3 * (1 - fraction)));
        }
        if (game.tick % 2 == 0) game.world.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.6, 0), 2, 0.12, 0.12, 0.12, 0.01);

        int stage = fraction > 0.5 ? 0 : fraction > 0.25 ? 1 : 2;
        if (stage != game.dangerStage) {
            game.dangerStage = stage;
            NamedTextColor color = stage == 0 ? NamedTextColor.WHITE : stage == 1 ? NamedTextColor.YELLOW : NamedTextColor.RED;
            ball.customName(Component.text("Hot Potato", color, TextDecoration.BOLD));
        }

        // The last seconds: a ring on the floor shows how far the blast will reach.
        if (game.fuseTicks <= RING_TICKS && game.tick % 4 == 0) {
            Particle.DustOptions red = new Particle.DustOptions(Color.RED, 1.3f);
            for (int i = 0; i < 28; i++) {
                double angle = i * (Math.PI * 2 / 28);
                game.world.spawnParticle(Particle.DUST, at.getX() + Math.cos(angle) * game.radius, at.getY() - 0.4,
                        at.getZ() + Math.sin(angle) * game.radius, 1, 0, 0, 0, 0, red);
            }
        }
    }

    // ------------------------------------------------------------------ bounds

    /**
     * The bounds work as they do in a normal match: a ball that crosses them is put back just inside and bounces off
     * (harder the faster it hit). Only a ball that fell out of the arena, or was launched far away, goes back to the
     * middle.
     */
    private void checkBounds(Game game) {
        SulfurCube cube = game.ball;
        if (cube == null) return;
        BoundingBox box = cube.getBoundingBox();

        boolean lost = !cube.getWorld().equals(game.world)
                || box.getMinY() < game.world.getMinHeight() - 4
                || box.getMinY() < game.middle.getBlockY() - 12;
        if (lost) {
            returnToMiddle(game, "fell out of the arena");
            return;
        }
        if (game.stadium.contains(box.getCenterX(), box.getCenterZ())) {
            game.lastVelocity = cube.getVelocity().clone();
            return;
        }
        bounceOffBounds(game, box);
    }

    private void bounceOffBounds(Game game, BoundingBox box) {
        SulfurCube cube = game.ball;
        Location centre = new Location(cube.getWorld(), box.getCenterX(), box.getCenterY(), box.getCenterZ());
        HotPotatoStadium.BoundsHit hit = game.stadium.nearestBoundsEdge(centre);
        if (hit == null || hit.distance() > BOUNCE_MAX_DISTANCE) {
            returnToMiddle(game, "went out of the arena");
            return;
        }

        Vector v = cube.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) < 0.01 && game.lastVelocity != null) v = game.lastVelocity.clone();

        // Split the velocity into the part going into the wall and the part sliding along it.
        double intoArena = v.getX() * hit.nx() + v.getZ() * hit.nz();
        double impact = Math.max(0, -intoArena);
        double slideX = v.getX() - intoArena * hit.nx();
        double slideZ = v.getZ() - intoArena * hit.nz();

        double restitution = Math.min(MatchManager.BOUNCE_MAX, MatchManager.BOUNCE_MIN + MatchManager.BOUNCE_PER_SPEED * impact);
        double bounce = Math.max(impact * restitution, MatchManager.BOUNCE_MIN_SPEED);
        Vector bounced = new Vector(
                slideX * MatchManager.WALL_SLIDE_KEEP + hit.nx() * bounce,
                v.getY(),
                slideZ * MatchManager.WALL_SLIDE_KEEP + hit.nz() * bounce);

        // Just inside the wall (keeping its height), so it doesn't hit the wall again next tick.
        Location target = cube.getLocation().clone();
        target.add(hit.x() + hit.nx() * MatchManager.WALL_INSET - centre.getX(), 0,
                hit.z() + hit.nz() * MatchManager.WALL_INSET - centre.getZ());
        cube.teleport(target);
        cube.setVelocity(bounced);
        game.lastVelocity = bounced.clone();
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    private void returnToMiddle(Game game, String what) {
        SulfurCube cube = game.ball;
        if (cube == null || game.tick - game.lastReset < OUT_RESET_COOLDOWN_TICKS) return;
        game.lastReset = game.tick;
        cube.teleport(game.middle.clone().add(0, 1, 0));
        cube.setVelocity(new Vector(0, 0, 0));
        game.lastVelocity = null;
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(Component.text("The ball " + what + ". It's back in the middle.", NamedTextColor.YELLOW));
        }
    }

    // ------------------------------------------------------------------ the explosion

    /** The fuse ran out: everyone near the ball is blown up (no damage to the world), then the next bomb or the result. */
    private void explode(Game game) {
        SulfurCube ball = game.ball;
        Location at = ball.getBoundingBox().getCenter().toLocation(game.world);
        game.world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        game.world.playSound(at, "entity.generic.explode", 4.0f, 0.8f);
        removeBall(game);
        game.bombs++;

        double radiusSquared = game.radius * game.radius;
        List<Player> victims = new ArrayList<>();
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (p.getLocation().add(0, 0.9, 0).distanceSquared(at) <= radiusSquared) victims.add(p);
        }
        for (Player victim : victims) blowUp(game, victim);

        if (victims.isEmpty()) {
            game.radius = Math.min(MAX_BLAST_RADIUS, game.radius + BLAST_GROWTH);
            broadcast(game, Component.text("Nobody was caught! The next bomb is bigger.", NamedTextColor.GOLD));
        } else {
            game.radius = game.baseRadius;
        }

        int red = aliveCount(game, Match.Team.RED);
        int blue = aliveCount(game, Match.Team.BLUE);
        if (game.twoTeams) {
            if (red == 0 || blue == 0) {
                showResult(game, red == 0 && blue == 0 ? null : red == 0 ? Match.Team.BLUE : Match.Team.RED);
                return;
            }
        } else if (red == 0) {
            showResult(game, null);
            return;
        }
        beginRound(game);
    }

    private void blowUp(Game game, Player player) {
        UUID id = player.getUniqueId();
        game.alive.remove(id);
        Match.Team team = game.teams.get(id);
        game.world.spawnParticle(Particle.EXPLOSION, player.getLocation().add(0, 1, 0), 1);
        player.setGameMode(GameMode.SPECTATOR); // out: watch the rest from inside the arena
        player.showTitle(Title.title(Component.text("Blown up!", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("You're out. Watch the rest of the game.", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(500))));
        broadcast(game, Component.text(player.getName(), team.color)
                .append(Component.text(" was blown up!", NamedTextColor.RED)));
    }

    private int aliveCount(Game game, Match.Team team) {
        int count = 0;
        for (UUID id : game.alive) {
            if (game.teams.get(id) == team) count++;
        }
        return count;
    }

    private void broadcast(Game game, Component message) {
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(message);
        }
    }

    /** A new bomb: everyone still in goes back to their starting position, frozen, for a few seconds. */
    private void beginRound(Game game) {
        game.phase = Phase.COUNTDOWN;
        game.phaseTicks = 0;
        game.countdownLeft = ROUND_COUNTDOWN_SECONDS;
        for (UUID id : game.alive) {
            Player p = Bukkit.getPlayer(id);
            Location spot = game.spots.get(id);
            if (p != null && spot != null) p.teleport(spot);
        }
        game.ball = spawnBall(game);
        announceCountdown(game);
    }

    // ------------------------------------------------------------------ the bar

    private void updateBar(Game game) {
        if (game.phase == Phase.COUNTDOWN) return; // the countdown has its own text
        if (game.phase == Phase.RESULT) {
            game.bar.progress(1.0f);
            return;
        }
        Component name = Component.text("Red " + aliveCount(game, Match.Team.RED), NamedTextColor.RED, TextDecoration.BOLD);
        if (game.twoTeams) {
            name = name.append(Component.text("  |  ", NamedTextColor.GRAY))
                    .append(Component.text("Blue " + aliveCount(game, Match.Team.BLUE), NamedTextColor.BLUE, TextDecoration.BOLD))
                    .append(Component.text(" still in", NamedTextColor.GRAY));
        } else {
            name = name.append(Component.text(" still in", NamedTextColor.GRAY));
        }
        name = name.append(Component.text("  |  Bombs: " + game.bombs, NamedTextColor.YELLOW, TextDecoration.BOLD));
        game.bar.name(name);
        game.bar.progress(1.0f);
    }

    // ------------------------------------------------------------------ the end

    /**
     * One team has nobody left (so the other wins), or everyone was blown up (a draw). Everyone can then fly around
     * the arena for a few seconds before they are sent back.
     */
    private void showResult(Game game, Match.Team winner) {
        game.phase = Phase.RESULT;
        game.phaseTicks = 0;
        removeBall(game);

        Component verdict;
        if (!game.twoTeams) {
            verdict = Component.text("You survived " + Math.max(0, game.bombs - 1) + " bomb" + (game.bombs - 1 == 1 ? "" : "s")
                    + ".", NamedTextColor.GOLD, TextDecoration.BOLD);
        } else if (winner != null) {
            verdict = Component.text(winner.label + " team wins!", winner.color, TextDecoration.BOLD);
        } else {
            verdict = Component.text("Everyone was blown up. It's a draw!", NamedTextColor.GOLD, TextDecoration.BOLD);
        }
        game.bar.name(verdict);
        game.bar.progress(1.0f);

        List<Player> everyone = new ArrayList<>();
        List<Player> winners = new ArrayList<>();
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            everyone.add(p);
            p.setGameMode(GameMode.SPECTATOR); // fly around the arena until the result is over
            p.sendMessage(Component.text("Hot Potato is over! ", NamedTextColor.GREEN).append(verdict));
            p.sendMessage(Component.text("You can fly around the arena for " + RESULT_SECONDS + " seconds.", NamedTextColor.GRAY));
            if (winner != null && game.teams.get(id) == winner) {
                winners.add(p);
            } else {
                // Winners get the "You won!" title from the celebration; everyone else sees the result.
                p.showTitle(Title.title(verdict, Component.empty(),
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750))));
            }
        }
        celebration.finish(everyone, winners);
    }

    /** The result has been shown: everyone goes home. */
    private void close(Game game) {
        if (game.task != null) game.task.cancel();
        for (UUID id : new ArrayList<>(game.active)) {
            Player p = Bukkit.getPlayer(id);
            byPlayer.remove(id);
            if (p != null) release(game, p);
        }
        game.active.clear();
        removeBall(game);
        games.remove(game);
    }

    /** Hands a player back: bossbar, game mode, chestplate and where they were. */
    private void release(Game game, Player player) {
        UUID id = player.getUniqueId();
        player.hideBossBar(game.bar);
        ItemStack saved = game.chestplates.remove(id);
        if (saved != null) player.getInventory().setChestplate(saved.getType() == Material.AIR ? null : saved);
        GameMode mode = game.modes.get(id);
        if (mode != null) player.setGameMode(mode);
        Location origin = game.origins.get(id);
        if (origin != null) player.teleport(origin);
    }

    /** /match leave while playing, or leaving the server: you're out. Returns false if the player wasn't playing. */
    boolean leave(Player player) {
        UUID id = player.getUniqueId();
        Game game = byPlayer.remove(id);
        if (game == null) return false;
        game.active.remove(id);
        game.alive.remove(id);
        release(game, player);

        for (UUID other : game.active) {
            Player p = Bukkit.getPlayer(other);
            if (p != null) p.sendMessage(Component.text(player.getName() + " left Hot Potato.", NamedTextColor.YELLOW));
        }

        if (game.active.isEmpty()) {
            close(game);
        } else if (game.phase != Phase.RESULT && game.twoTeams) {
            int red = aliveCount(game, Match.Team.RED);
            int blue = aliveCount(game, Match.Team.BLUE);
            if (red == 0 || blue == 0) showResult(game, red == 0 && blue == 0 ? null : red == 0 ? Match.Team.BLUE : Match.Team.RED);
        }
        return true;
    }

    void shutdown() {
        for (Game game : new ArrayList<>(games)) {
            if (game.task != null) game.task.cancel();
            for (UUID id : new ArrayList<>(game.active)) {
                Player p = Bukkit.getPlayer(id);
                byPlayer.remove(id);
                if (p != null) release(game, p);
            }
            game.active.clear();
            removeBall(game);
        }
        games.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer());
    }

    // ------------------------------------------------------------------ jerseys and protection

    private ItemStack createJersey(Match.Team team) {
        ItemStack item = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(org.bukkit.Color.fromRGB(team.color.value()));
        meta.displayName(Component.text(team.label + " Team Jersey", team.color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(jerseyKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isJersey(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(jerseyKey, PersistentDataType.BYTE);
    }

    /** Puts the team jersey on, remembering whatever chestplate the player was wearing. */
    private void giveJersey(Game game, Player player, Match.Team team) {
        ItemStack current = player.getInventory().getChestplate();
        game.chestplates.putIfAbsent(player.getUniqueId(), current == null ? new ItemStack(Material.AIR) : current);
        player.getInventory().setChestplate(createJersey(team));
    }

    /** Players can't take the jersey off during a game. */
    @EventHandler
    public void onJerseyClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !byPlayer.containsKey(player.getUniqueId())) return;
        if (event.getSlotType() == InventoryType.SlotType.ARMOR || isJersey(event.getCurrentItem())
                || isJersey(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    /** The only thing that hurts in this game is the bomb, and that is handled by the game. */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && byPlayer.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nobody jumps to a player outside the arena while spectating, and nobody still in the game can teleport out of
     * it (ender pearls, commands...). Moves made by plugins (the game's own, and the lobby's) are always allowed.
     */
    @EventHandler
    public void onSpectate(PlayerTeleportEvent event) {
        Game game = byPlayer.get(event.getPlayer().getUniqueId());
        if (game == null) return;
        boolean spectating = spectating(game, event.getPlayer().getUniqueId());
        if (spectating && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            return;
        }
        if (spectating || event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        Location to = event.getTo();
        if (to != null && !game.stadium.boundsContain(to)) event.setCancelled(true);
    }

    /**
     * Players still in the game can't leave the arena while a bomb is ticking. A step over the edge is undone (and
     * their sliding stopped); someone who is somehow already outside is put back just inside the nearest edge.
     */
    private void keepInside(PlayerMoveEvent event, Game game) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || game.stadium.contains(to.getX(), to.getZ())) return;

        Location fixed;
        if (game.stadium.contains(from.getX(), from.getZ())) {
            fixed = from.clone();
        } else {
            fixed = pulledInside(game, to);
        }
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);

        Player player = event.getPlayer();
        Vector v = player.getVelocity();
        player.setVelocity(new Vector(0, v.getY(), 0)); // no sliding on into the edge again

        UUID id = player.getUniqueId();
        if (game.tick - game.lastBoundsWarning.getOrDefault(id, -100L) >= 20) {
            game.lastBoundsWarning.put(id, game.tick);
            player.sendActionBar(Component.text("You can't leave the arena!", NamedTextColor.RED, TextDecoration.BOLD));
        }
    }

    /** Just inside the bounds, at the edge nearest to this point. */
    private Location pulledInside(Game game, Location outside) {
        Location point = new Location(game.world, outside.getX(), outside.getY(), outside.getZ());
        HotPotatoStadium.BoundsHit hit = game.stadium.nearestBoundsEdge(point);
        if (hit == null) return new Location(game.world, game.middle.getX(), outside.getY(), game.middle.getZ());
        // The normal points from the player to the edge, so carrying on a little further is back inside.
        return new Location(game.world, hit.x() + hit.nx() * 0.8, outside.getY(), hit.z() + hit.nz() * 0.8);
    }

    private boolean spectating(Game game, UUID id) {
        return game.phase == Phase.RESULT || !game.alive.contains(id);
    }

    /** Everyone is frozen in place (but can look around) during a countdown; during play and as spectators, nobody leaves the arena. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Game game = byPlayer.get(event.getPlayer().getUniqueId());
        if (game == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (spectating(game, event.getPlayer().getUniqueId())) {
            if (!game.stadium.contains(to.getX(), to.getZ())) {
                Location fixed = from.clone();
                fixed.setYaw(to.getYaw());
                fixed.setPitch(to.getPitch());
                event.setTo(fixed);
            }
            return;
        }
        if (game.phase == Phase.PLAY) {
            keepInside(event, game); // the bomb is ticking: nobody leaves the arena
            return;
        }
        if (game.phase != Phase.COUNTDOWN) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return; // just looking around
        Location fixed = from.clone();
        fixed.setYaw(to.getYaw());
        fixed.setPitch(to.getPitch());
        event.setTo(fixed);
    }
}
