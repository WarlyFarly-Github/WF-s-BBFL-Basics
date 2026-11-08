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
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Color Cover: two teams, red and blue, fight over the floor of an arena. Every player gets their own football, and
 * wherever it touches the ground it paints that block in the player's team colour (coloured concrete). Balls can be
 * dribbled or punched, and an enemy can knock your ball away. When time runs out the team that has painted the most
 * of the arena wins.
 *
 * Painting really changes blocks, so every block's original state is written to a journal file as it is painted. The
 * floor is put back when the game ends, and anything left over after a crash is put back the next time the plugin starts.
 */
final class ColorCoverManager implements Listener {

    static final int MAX_PLAYERS = 10;

    private static final int DEFAULT_DURATION_SECONDS = 180;
    private static final int MIN_DURATION_SECONDS = 30;
    private static final int MAX_DURATION_SECONDS = 1800;
    private static final int FIRST_COUNTDOWN_SECONDS = 5;
    private static final int RESULT_SECONDS = 10;        // how long everyone can fly around the finished arena (spectating)
    private static final int HIT_COOLDOWN_TICKS = 4;     // one swing, one hit
    private static final double HIT_LIFT = 0.0;          // a punch doesn't lift the ball at all: it slides along the floor
    // The ball slides like it is on ice. Ice has a friction of 0.98 and ordinary ground 0.6, so each tick a ball on the
    // ground gets its horizontal speed multiplied by their ratio, which cancels most of the ground's friction.
    private static final double ICE_FRICTION = 0.98;
    private static final double GROUND_FRICTION = 0.6;
    private static final double SLIDE_BOOST = ICE_FRICTION / GROUND_FRICTION;
    private static final double SLIDE_MAX_SPEED = 1.8;   // blocks per tick, so a hard punch can't make it tunnel through things
    private static final double SLIDE_STOP_SPEED = 0.004;
    private static final double BOUNCE_MAX_DISTANCE = 4.0; // further out than this the ball was launched away: back to its owner
    private static final double PAINT_STEP = 0.4;        // a fast ball paints a block every this many blocks along its path
    private static final int MAX_PAINT_STEPS = 12;
    private static final int OUT_RESET_COOLDOWN_TICKS = 20;
    private static final int STEP_UP = 1;                // the floor may be this far above the spawn's feet level...
    private static final int STEP_DOWN = 3;              // ...or this far below it (the spawn block's own floor is 1 below)
    private static final double SPAWN_SPACING = 1.5;     // blocks between teammates on their spawn line
    private static final int RESTORE_PER_TICK = 1500;    // blocks put back per tick after a game
    private static final String RED_GLOW_TEAM = "wfcc_red";   // scoreboard teams, only for the colour of the balls' glow
    private static final String BLUE_GLOW_TEAM = "wfcc_blue";

    private enum Phase { COUNTDOWN, PLAY, RESULT }

    /** One floor block of an arena that can be painted. */
    private static final class Cell {
        private final int x;
        private final int y;
        private final int z;
        private BlockData original; // null until it has been painted for the first time
        private Match.Team owner;   // null while unpainted

        private Cell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A player's football. */
    private static final class PlayerBall {
        private final UUID owner;
        private final Match.Team team;
        private SulfurCube entity;
        private boolean wasOnGround;
        private double lastX;
        private double lastZ;
        private int lastY;
        private long lastReset = -1000;
        private Vector lastVelocity; // its velocity the last time it was inside the bounds, for bouncing

        private PlayerBall(UUID owner, Match.Team team) {
            this.owner = owner;
            this.team = team;
        }
    }

    private static final class Game {
        private final ColorCoverStadium stadium;
        private final World world;
        private final Location middle;
        private final Map<Long, Cell> cells = new HashMap<>();
        private final Map<UUID, Match.Team> teams = new LinkedHashMap<>();
        private final Set<UUID> active = new LinkedHashSet<>();
        private final Map<UUID, PlayerBall> balls = new LinkedHashMap<>();
        private final Map<UUID, Location> origins = new HashMap<>();
        private final Map<UUID, GameMode> modes = new HashMap<>();
        private final Map<UUID, ItemStack> chestplates = new HashMap<>();
        private final Map<UUID, Long> lastHit = new HashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        private final List<String> pendingJournal = new ArrayList<>();
        private final Path journal;
        private final boolean twoTeams;
        private final int totalTicks;
        private int red;
        private int blue;
        private Phase phase = Phase.COUNTDOWN;
        private int countdownLeft;
        private int ticksLeft;
        private long tick;
        private long phaseTicks;
        private BukkitTask task;

        private Game(ColorCoverStadium stadium, World world, Path journal, boolean twoTeams, int totalTicks) {
            this.stadium = stadium;
            this.world = world;
            this.middle = stadium.middle();
            this.journal = journal;
            this.twoTeams = twoTeams;
            this.totalTicks = totalTicks;
            this.ticksLeft = totalTicks;
        }
    }

    /** A finished game's floor being put back a few thousand blocks per tick. */
    private static final class RestoreJob {
        private final ColorCoverStadium stadium;
        private final World world;
        private final ArrayDeque<Cell> queue;
        private final Path journal;
        private BukkitTask task;

        private RestoreJob(ColorCoverStadium stadium, World world, ArrayDeque<Cell> queue, Path journal) {
            this.stadium = stadium;
            this.world = world;
            this.queue = queue;
            this.journal = journal;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final ColorCoverStadiums stadiums;
    private final Celebration celebration;
    private final Random random = new Random();
    private final Map<UUID, Game> byPlayer = new HashMap<>();
    private final List<Game> games = new ArrayList<>();
    private final List<RestoreJob> restores = new ArrayList<>();
    private final NamespacedKey jerseyKey;
    private final File journalFolder;
    private final BlockData redPaint = Material.RED_CONCRETE.createBlockData();
    private final BlockData bluePaint = Material.BLUE_CONCRETE.createBlockData();
    private boolean journalWarned;

    ColorCoverManager(JavaPlugin plugin, MatchManager matches, ColorCoverStadiums stadiums, Celebration celebration) {
        this.plugin = plugin;
        this.matches = matches;
        this.stadiums = stadiums;
        this.celebration = celebration;
        this.jerseyKey = new NamespacedKey(plugin, "colorcover_jersey");
        this.journalFolder = new File(plugin.getDataFolder(), "colorcover-restore");
        removeGlowTeams(); // leftovers from a crash or a reload

        // Floors left painted by a crash or a lost game are put back once the worlds are up.
        Bukkit.getScheduler().runTask(plugin, this::recoverJournals);
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
            for (PlayerBall ball : game.balls.values()) {
                if (ball.entity != null && ball.entity.equals(entity)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ starting

    String start(List<Player> players) {
        return start(players, false);
    }

    /**
     * Starts a game for these players on a random finished Color Cover arena nobody is using. The players are split
     * into a red and a blue team. With bypass (operators only, from /challenge bypass) one player can play alone
     * (on the red team) to practise. Returns a problem or null.
     */
    String start(List<Player> players, boolean bypass) {
        List<Player> ready = new ArrayList<>(players.stream()
                .filter(p -> p.isOnline() && !matches.isInMatch(p.getUniqueId()) && !matches.isWatching(p.getUniqueId()))
                .toList());
        if (ready.size() < (bypass ? 1 : 2)) return "Color Cover needs at least 2 free players.";
        if (ready.size() > MAX_PLAYERS) return "Color Cover can have at most " + MAX_PLAYERS + " players.";

        List<ColorCoverStadium> free = new ArrayList<>();
        boolean anyFinished = false;
        for (ColorCoverStadium stadium : stadiums.all()) {
            if (!stadium.isComplete()) continue;
            anyFinished = true;
            if (!inUse(stadium)) free.add(stadium);
        }
        if (!anyFinished) return "There is no finished Color Cover arena yet. An operator needs to make one with /ccstadium.";
        if (free.isEmpty()) return "Every Color Cover arena is in use right now, so Color Cover can't start.";
        ColorCoverStadium stadium = free.get(random.nextInt(free.size()));
        World world = stadium.bounds.get(0).getWorld();

        Map<Long, Cell> cells = scan(stadium, world);
        if (cells.isEmpty()) return "The Color Cover arena " + stadium.name + " has no floor that can be painted.";

        // Random teams, as even as possible. The bigger team (with an odd number of players) is random too.
        Collections.shuffle(ready, random);
        boolean twoTeams = ready.size() >= 2;
        boolean redFirst = random.nextBoolean();
        Map<Match.Team, List<Player>> sides = new LinkedHashMap<>();
        sides.put(Match.Team.RED, new ArrayList<>());
        sides.put(Match.Team.BLUE, new ArrayList<>());
        for (int i = 0; i < ready.size(); i++) {
            boolean red = twoTeams ? (i % 2 == 0) == redFirst : true;
            sides.get(red ? Match.Team.RED : Match.Team.BLUE).add(ready.get(i));
        }

        int seconds = Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS,
                plugin.getConfig().getInt("color-cover.duration-seconds", DEFAULT_DURATION_SECONDS)));
        Path journal = journalFolder.toPath().resolve("game-" + System.currentTimeMillis() + "-" + random.nextInt(100000) + ".txt");
        Game game = new Game(stadium, world, journal, twoTeams, seconds * 20);
        game.cells.putAll(cells);
        games.add(game);
        createGlowTeams();

        for (Map.Entry<Match.Team, List<Player>> side : sides.entrySet()) {
            Match.Team team = side.getKey();
            List<Player> members = side.getValue();
            Location spawn = ColorCoverStadium.centreOf(stadium.spawnOf(team));
            Vector toMiddle = game.middle.toVector().subtract(spawn.toVector()).setY(0);
            if (toMiddle.lengthSquared() < 1.0E-4) toMiddle = new Vector(0, 0, 1);
            toMiddle.normalize();
            Vector across = new Vector(-toMiddle.getZ(), 0, toMiddle.getX()); // the line the team lines up along

            for (int i = 0; i < members.size(); i++) {
                Player p = members.get(i);
                UUID id = p.getUniqueId();
                game.teams.put(id, team);
                game.active.add(id);
                game.origins.put(id, p.getLocation().clone());
                game.modes.put(id, p.getGameMode());
                byPlayer.put(id, game);

                double offset = (i - (members.size() - 1) / 2.0) * SPAWN_SPACING;
                Location spot = spawn.clone().add(across.getX() * offset, 0, across.getZ() * offset);
                spot.setDirection(toMiddle);
                p.teleport(spot);
                p.setGameMode(GameMode.ADVENTURE);
                giveJersey(game, p, team);
                p.showBossBar(game.bar);
                p.sendMessage(Component.text("Color Cover has started on " + stadium.name + "! You are on the ", NamedTextColor.GREEN)
                        .append(Component.text(team.label + " team", team.color, TextDecoration.BOLD))
                        .append(Component.text(". Your football paints the floor in your team's colour wherever it touches the "
                                + "ground: dribble it or punch it around to cover as much of the arena as you can. "
                                + "The team with the most blocks wins!", NamedTextColor.GREEN)));

                PlayerBall ball = new PlayerBall(id, team);
                game.balls.put(id, ball);
            }
        }
        for (PlayerBall ball : game.balls.values()) ball.entity = spawnBall(game, ball);

        game.countdownLeft = FIRST_COUNTDOWN_SECONDS;
        updateBar(game);
        announceCountdown(game);
        game.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(game), 1L, 1L);
        return null;
    }

    private boolean inUse(ColorCoverStadium stadium) {
        for (Game game : games) {
            if (game.stadium == stadium) return true;
        }
        for (RestoreJob job : restores) {
            if (job.stadium == stadium) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the arena floor

    /**
     * Finds the floor blocks of the arena: for every column inside the bounds, the topmost solid block within a few
     * blocks of the spawns' height, as long as it is a plain full block (no slab, stair, chest, furnace...) with open
     * space above it. A column whose first solid block is anything else (or liquid) is simply not part of the game.
     */
    private Map<Long, Cell> scan(ColorCoverStadium stadium, World world) {
        int feetMax = Math.max(stadium.redSpawn.getBlockY(), stadium.blueSpawn.getBlockY());
        int feetMin = Math.min(stadium.redSpawn.getBlockY(), stadium.blueSpawn.getBlockY());
        int top = Math.min(feetMax + STEP_UP, world.getMaxHeight() - 3);
        int bottom = Math.max(feetMin - STEP_DOWN, world.getMinHeight());

        Map<Long, Cell> cells = new HashMap<>();
        for (int x = stadium.minX(); x <= stadium.maxX(); x++) {
            for (int z = stadium.minZ(); z <= stadium.maxZ(); z++) {
                if (!stadium.contains(x + 0.5, z + 0.5)) continue;
                for (int y = top; y >= bottom; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isLiquid()) break;
                    if (block.isPassable()) continue;
                    if (isPaintable(block)) {
                        Block above = world.getBlockAt(x, y + 1, z);
                        if (above.isPassable() && !above.isLiquid()) cells.put(key(x, y, z), new Cell(x, y, z));
                    }
                    break; // the first solid block is the floor of this column, painted or not
                }
            }
        }
        return cells;
    }

    /** A full, solid cube that isn't a container or anything else with data that painting would destroy. */
    private boolean isPaintable(Block block) {
        Material type = block.getType();
        if (type.isAir() || !type.isOccluding()) return false;
        return !(block.getState(false) instanceof TileState);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    // ------------------------------------------------------------------ the balls

    private Location ballSpot(Game game, PlayerBall ball) {
        ColorCoverStadium stadium = game.stadium;
        Location base = ColorCoverStadium.centreOf(stadium.spawnOf(ball.team));
        Player owner = Bukkit.getPlayer(ball.owner);
        if (owner != null && owner.isOnline() && owner.getWorld().equals(game.world)
                && stadium.contains(owner.getLocation().getX(), owner.getLocation().getZ())) {
            base = owner.getLocation();
        }
        Vector direction = game.middle.toVector().subtract(base.toVector()).setY(0);
        if (direction.lengthSquared() < 1.0E-4) direction = new Vector(0, 0, 1);
        Location spot = base.clone().add(direction.normalize().multiply(1.5)).add(0, 1, 0);
        if (!stadium.contains(spot.getX(), spot.getZ())) spot = base.clone().add(0, 1, 0);
        return spot;
    }

    private SulfurCube spawnBall(Game game, PlayerBall ball) {
        Player owner = Bukkit.getPlayer(ball.owner);
        String name = owner == null ? "A player" : owner.getName();
        SulfurCube cube = matches.createBallEntity(ballSpot(game, ball),
                Component.text(name + "'s Football", ball.team.color, TextDecoration.BOLD), true);
        cube.setMaximumNoDamageTicks(0); // it can be tapped again straight away
        cube.setNoDamageTicks(0);
        glowTeam(ball.team).addEntity(cube); // the glow outline is in the team's colour
        if (game.phase == Phase.COUNTDOWN) cube.setGravity(false);
        ball.wasOnGround = false;
        return cube;
    }

    private void removeBall(PlayerBall ball) {
        if (ball.entity == null) return;
        try {
            glowTeam(ball.team).removeEntity(ball.entity);
        } catch (IllegalStateException ignored) {
            // the scoreboard team was already removed
        }
        ball.entity.remove();
        ball.entity = null;
    }

    private org.bukkit.scoreboard.Team glowTeam(Match.Team team) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        String name = team == Match.Team.RED ? RED_GLOW_TEAM : BLUE_GLOW_TEAM;
        org.bukkit.scoreboard.Team glow = board.getTeam(name);
        if (glow == null) glow = board.registerNewTeam(name);
        glow.color(team.color);
        return glow;
    }

    private void createGlowTeams() {
        glowTeam(Match.Team.RED);
        glowTeam(Match.Team.BLUE);
    }

    private void removeGlowTeams() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (String name : List.of(RED_GLOW_TEAM, BLUE_GLOW_TEAM)) {
            org.bukkit.scoreboard.Team team = board.getTeam(name);
            if (team != null) team.unregister();
        }
    }

    /** Called by the match code when a player punches a ball. Any ball in the game can be hit, not just your own. */
    void onHit(Player kicker, Entity entity, Vector velocity) {
        UUID id = kicker.getUniqueId();
        Game game = byPlayer.get(id);
        if (game == null || game.phase != Phase.PLAY || !game.active.contains(id)) return;
        boolean ours = false;
        for (PlayerBall ball : game.balls.values()) {
            if (ball.entity != null && ball.entity.equals(entity)) {
                ours = true;
                break;
            }
        }
        if (!ours) return;
        if (game.tick - game.lastHit.getOrDefault(id, -100L) < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        game.lastHit.put(id, game.tick);

        velocity.setY(HIT_LIFT);
        entity.setVelocity(velocity);
    }

    // ------------------------------------------------------------------ the game loop

    private void tick(Game game) {
        game.tick++;
        switch (game.phase) {
            case COUNTDOWN -> {
                keepBalls(game, true);
                game.phaseTicks++;
                if (game.phaseTicks % 20 == 0) {
                    game.countdownLeft--;
                    if (game.countdownLeft <= 0) startPlay(game);
                    else announceCountdown(game);
                }
            }
            case PLAY -> {
                playTick(game);
                game.ticksLeft--;
                if (game.tick % 10 == 0) updateBar(game);
                if (game.ticksLeft <= 0) showResult(game, null);
            }
            case RESULT -> {
                game.phaseTicks++;
                if (game.phaseTicks >= RESULT_SECONDS * 20L) {
                    close(game);
                    return;
                }
            }
        }
        if (game.tick % 20 == 0) flushJournal(game);
    }

    private void announceCountdown(Game game) {
        game.bar.name(Component.text("Color Cover starts in " + game.countdownLeft, NamedTextColor.GREEN, TextDecoration.BOLD));
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Match.Team team = game.teams.get(id);
            p.showTitle(Title.title(Component.text(String.valueOf(game.countdownLeft), NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("You are on the " + team.label + " team", team.color),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 1.0f);
        }
    }

    private void startPlay(Game game) {
        game.phase = Phase.PLAY;
        game.phaseTicks = 0;
        for (PlayerBall ball : game.balls.values()) {
            if (ball.entity != null) ball.entity.setGravity(true);
        }
        updateBar(game);
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.playSound(p.getLocation(), "block.note_block.harp", 1.0f, 2.0f);
            p.sendActionBar(Component.text("Go! Cover the arena in your colour!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    /** Keeps every ball alive; during the countdown they hang in front of their owners. */
    private void keepBalls(Game game, boolean frozen) {
        for (PlayerBall ball : game.balls.values()) {
            if (ball.entity == null || !ball.entity.isValid()) ball.entity = spawnBall(game, ball);
            if (frozen) {
                ball.entity.setGravity(false);
                ball.entity.setVelocity(new Vector(0, 0, 0));
            }
        }
    }

    private void playTick(Game game) {
        keepBalls(game, false);
        for (PlayerBall ball : game.balls.values()) {
            slide(ball);
            paintBall(game, ball);
            checkBounds(game, ball); // every tick, like a normal match, so a fast ball can't get far past the wall
        }
    }

    /** Makes a ball on the ground slide as if it were on ice: it keeps most of its speed instead of rolling to a stop. */
    private void slide(PlayerBall ball) {
        SulfurCube cube = ball.entity;
        if (cube == null || !cube.isOnGround()) return;
        Vector v = cube.getVelocity();
        double speed = Math.hypot(v.getX(), v.getZ());
        if (speed < SLIDE_STOP_SPEED) return;
        double scale = Math.min(speed * SLIDE_BOOST, SLIDE_MAX_SPEED) / speed;
        cube.setVelocity(new Vector(v.getX() * scale, v.getY(), v.getZ() * scale));
    }

    /**
     * Paints the block under the ball if it is on the ground. A fast ball covers more than a block a tick, so while it
     * keeps rolling on the ground at the same height the blocks along its path are painted too.
     */
    private void paintBall(Game game, PlayerBall ball) {
        SulfurCube cube = ball.entity;
        if (cube == null) return;
        boolean onGround = cube.isOnGround();
        BoundingBox box = cube.getBoundingBox();
        double x = box.getCenterX();
        double z = box.getCenterZ();
        int y = (int) Math.floor(box.getMinY() - 0.05);

        if (onGround) {
            if (ball.wasOnGround && ball.lastY == y) {
                double dx = x - ball.lastX;
                double dz = z - ball.lastZ;
                int steps = (int) Math.min(MAX_PAINT_STEPS, Math.ceil(Math.hypot(dx, dz) / PAINT_STEP));
                for (int i = 1; i < steps; i++) {
                    double t = (double) i / steps;
                    paintAt(game, ball.team, ball.lastX + dx * t, ball.lastZ + dz * t, y);
                }
            }
            paintAt(game, ball.team, x, z, y);
        }
        ball.wasOnGround = onGround;
        ball.lastX = x;
        ball.lastZ = z;
        ball.lastY = y;
    }

    private void paintAt(Game game, Match.Team team, double x, double z, int y) {
        Cell cell = game.cells.get(key((int) Math.floor(x), y, (int) Math.floor(z)));
        if (cell == null || cell.owner == team) return;

        Block block = game.world.getBlockAt(cell.x, cell.y, cell.z);
        if (cell.original == null) {
            cell.original = block.getBlockData(); // the first time only: this is what gets put back
            game.pendingJournal.add(game.world.getName() + "\t" + cell.x + "\t" + cell.y + "\t" + cell.z + "\t"
                    + cell.original.getAsString());
        }
        if (cell.owner == Match.Team.RED) game.red--;
        else if (cell.owner == Match.Team.BLUE) game.blue--;
        cell.owner = team;
        if (team == Match.Team.RED) game.red++;
        else game.blue++;

        block.setBlockData(team == Match.Team.RED ? redPaint : bluePaint, false); // false: no block updates
        game.world.spawnParticle(Particle.DUST, cell.x + 0.5, cell.y + 1.1, cell.z + 0.5, 2, 0.25, 0.05, 0.25, 0,
                new Particle.DustOptions(Color.fromRGB(team.color.value()), 1.0f));
    }

    /**
     * The bounds work as they do in a normal match: a ball that crosses them is put back just inside and bounces off
     * (harder the faster it hit), and its owner sees "Out of bounds!". Only a ball that fell out of the arena, or was
     * launched far away, goes back to its owner.
     */
    private void checkBounds(Game game, PlayerBall ball) {
        SulfurCube cube = ball.entity;
        if (cube == null) return;
        BoundingBox box = cube.getBoundingBox();

        boolean lost = !cube.getWorld().equals(game.world)
                || box.getMinY() < game.world.getMinHeight() - 4
                || box.getMinY() < Math.min(game.stadium.redSpawn.getBlockY(), game.stadium.blueSpawn.getBlockY()) - 12;
        if (lost) {
            returnToOwner(game, ball, "fell out of the arena");
            return;
        }
        if (game.stadium.contains(box.getCenterX(), box.getCenterZ())) {
            ball.lastVelocity = cube.getVelocity().clone();
            return;
        }
        bounceOffBounds(game, ball, box);
    }

    /** The ball crossed the bounds: put it back at the wall and reflect its velocity, softer than it arrived. */
    private void bounceOffBounds(Game game, PlayerBall ball, BoundingBox box) {
        SulfurCube cube = ball.entity;
        Location centre = new Location(cube.getWorld(), box.getCenterX(), box.getCenterY(), box.getCenterZ());
        ColorCoverStadium.BoundsHit hit = game.stadium.nearestBoundsEdge(centre);
        if (hit == null || hit.distance() > BOUNCE_MAX_DISTANCE) {
            returnToOwner(game, ball, "went out of the arena");
            return;
        }

        Vector v = cube.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) < 0.01 && ball.lastVelocity != null) v = ball.lastVelocity.clone();

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
        ball.lastVelocity = bounced.clone();
        ball.wasOnGround = false; // no paint line across the teleport
        Player owner = Bukkit.getPlayer(ball.owner);
        if (owner != null) owner.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    /** A ball that is lost (fell out of the arena or was launched far away) goes back to its owner. */
    private void returnToOwner(Game game, PlayerBall ball, String what) {
        SulfurCube cube = ball.entity;
        if (cube == null || game.tick - ball.lastReset < OUT_RESET_COOLDOWN_TICKS) return;
        ball.lastReset = game.tick;
        cube.teleport(ballSpot(game, ball));
        cube.setVelocity(new Vector(0, 0, 0));
        ball.wasOnGround = false;
        ball.lastVelocity = null;
        Player owner = Bukkit.getPlayer(ball.owner);
        if (owner != null) {
            owner.sendActionBar(Component.text("Your ball " + what + ". It's back by you.", NamedTextColor.YELLOW));
        }
    }

    // ------------------------------------------------------------------ the scoreboard

    private int total(Game game) {
        return Math.max(1, game.cells.size());
    }

    private static String percent(int part, int total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    private static String clock(int ticks) {
        int seconds = (ticks + 19) / 20;
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private void updateBar(Game game) {
        int total = total(game);
        if (game.phase == Phase.COUNTDOWN) return; // the countdown has its own text
        if (game.phase == Phase.RESULT) {
            game.bar.progress(1.0f);
            return;
        }
        Component name = Component.text("Red " + percent(game.red, total), NamedTextColor.RED, TextDecoration.BOLD);
        if (game.twoTeams) {
            name = name.append(Component.text("  |  ", NamedTextColor.GRAY))
                    .append(Component.text("Blue " + percent(game.blue, total), NamedTextColor.BLUE, TextDecoration.BOLD));
        } else {
            name = name.append(Component.text(" covered", NamedTextColor.GRAY));
        }
        name = name.append(Component.text("  |  " + clock(game.ticksLeft), NamedTextColor.YELLOW, TextDecoration.BOLD));
        game.bar.name(name);
        game.bar.progress(Math.max(0.0f, Math.min(1.0f, game.ticksLeft / (float) game.totalTicks)));
    }

    // ------------------------------------------------------------------ the end

    /**
     * Time is up (or one team has no players left, which makes the other team win by forfeit): the balls go, the
     * result is shown, and everyone can look at the painted arena for a few seconds before they are sent back.
     */
    private void showResult(Game game, Match.Team forfeitWinner) {
        game.phase = Phase.RESULT;
        game.phaseTicks = 0;
        for (PlayerBall ball : game.balls.values()) removeBall(ball);
        updateBar(game);

        int total = total(game);
        Match.Team winner = null;
        if (forfeitWinner != null) winner = forfeitWinner;
        else if (game.twoTeams && game.red != game.blue) winner = game.red > game.blue ? Match.Team.RED : Match.Team.BLUE;

        Component scores = Component.text("Red " + percent(game.red, total), NamedTextColor.RED, TextDecoration.BOLD);
        if (game.twoTeams) {
            scores = scores.append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text("Blue " + percent(game.blue, total), NamedTextColor.BLUE, TextDecoration.BOLD));
        }
        Component verdict;
        if (!game.twoTeams) {
            verdict = Component.text("You covered " + percent(game.red, total) + " of the arena.", NamedTextColor.GOLD, TextDecoration.BOLD);
        } else if (winner != null) {
            verdict = Component.text(winner.label + " team wins!", winner.color, TextDecoration.BOLD);
        } else {
            verdict = Component.text("It's a draw!", NamedTextColor.GOLD, TextDecoration.BOLD);
        }
        Component unpainted = Component.text("  (" + percent(total - game.red - game.blue, total) + " unpainted)", NamedTextColor.GRAY);
        game.bar.name(scores.append(unpainted)); // the bossbar keeps showing the final score while everyone looks around
        Component summary = Component.text("Color Cover is over! ", NamedTextColor.GREEN).append(scores).append(unpainted);

        List<Player> everyone = new ArrayList<>();
        List<Player> winners = new ArrayList<>();
        for (UUID id : game.active) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            everyone.add(p);
            p.setGameMode(GameMode.SPECTATOR); // fly around the painted arena until the result is over
            p.sendMessage(summary);
            p.sendMessage(verdict);
            p.sendMessage(Component.text("You can fly around the arena for " + RESULT_SECONDS + " seconds.", NamedTextColor.GRAY));
            boolean won = winner != null && game.teams.get(id) == winner;
            if (won) {
                winners.add(p);
            } else {
                // Winners get the "You won!" title from the celebration; everyone else sees the result.
                p.showTitle(Title.title(verdict, scores,
                        Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(750))));
            }
        }
        celebration.finish(everyone, winners);
    }

    /** The result has been shown: everyone goes home, and the arena floor is put back. */
    private void close(Game game) {
        if (game.task != null) game.task.cancel();
        for (UUID id : new ArrayList<>(game.active)) {
            Player p = Bukkit.getPlayer(id);
            byPlayer.remove(id);
            if (p != null) release(game, p);
        }
        game.active.clear();
        for (PlayerBall ball : game.balls.values()) removeBall(ball);
        game.balls.clear();
        games.remove(game);
        if (games.isEmpty()) removeGlowTeams();
        beginRestore(game);
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
        PlayerBall ball = game.balls.remove(id);
        if (ball != null) removeBall(ball);
        release(game, player);

        for (UUID other : game.active) {
            Player p = Bukkit.getPlayer(other);
            if (p != null) p.sendMessage(Component.text(player.getName() + " left Color Cover.", NamedTextColor.YELLOW));
        }

        if (game.active.isEmpty()) {
            close(game);
        } else if (game.phase != Phase.RESULT && game.twoTeams) {
            // A team with nobody left forfeits, so the other team wins (the paint so far stays on the floor).
            boolean redLeft = game.teams.entrySet().stream()
                    .anyMatch(e -> e.getValue() == Match.Team.RED && game.active.contains(e.getKey()));
            boolean blueLeft = game.teams.entrySet().stream()
                    .anyMatch(e -> e.getValue() == Match.Team.BLUE && game.active.contains(e.getKey()));
            if (!redLeft || !blueLeft) {
                Match.Team remaining = redLeft ? Match.Team.RED : Match.Team.BLUE;
                for (UUID other : game.active) {
                    Player p = Bukkit.getPlayer(other);
                    if (p != null) {
                        p.sendMessage(Component.text("The " + (redLeft ? "blue" : "red") + " team has no players left.",
                                NamedTextColor.YELLOW));
                    }
                }
                showResult(game, remaining);
            }
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
            for (PlayerBall ball : game.balls.values()) removeBall(ball);
            game.balls.clear();

            // The floor goes back right now: nothing runs after the plugin is disabled.
            flushJournal(game);
            for (Cell cell : game.cells.values()) restoreCell(game.world, cell);
            deleteJournal(game.journal);
        }
        games.clear();
        for (RestoreJob job : new ArrayList<>(restores)) {
            if (job.task != null) job.task.cancel();
            while (!job.queue.isEmpty()) restoreCell(job.world, job.queue.poll());
            deleteJournal(job.journal);
        }
        restores.clear();
        removeGlowTeams();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer());
    }

    // ------------------------------------------------------------------ putting the floor back

    private void restoreCell(World world, Cell cell) {
        if (cell.original == null) return;
        world.getBlockAt(cell.x, cell.y, cell.z).setBlockData(cell.original, false);
        cell.original = null;
    }

    private void beginRestore(Game game) {
        ArrayDeque<Cell> painted = new ArrayDeque<>();
        for (Cell cell : game.cells.values()) {
            if (cell.original != null) painted.add(cell);
        }
        if (painted.isEmpty()) {
            deleteJournal(game.journal);
            return;
        }
        flushJournal(game); // so the file lists everything, even if the server dies halfway through putting it back
        RestoreJob job = new RestoreJob(game.stadium, game.world, painted, game.journal);
        restores.add(job);
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < RESTORE_PER_TICK && !job.queue.isEmpty(); i++) restoreCell(job.world, job.queue.poll());
            if (job.queue.isEmpty()) {
                job.task.cancel();
                restores.remove(job);
                deleteJournal(job.journal);
            }
        }, 1L, 1L);
    }

    private void flushJournal(Game game) {
        if (game.pendingJournal.isEmpty()) return;
        try {
            Files.createDirectories(game.journal.getParent());
            Files.write(game.journal, game.pendingJournal, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            game.pendingJournal.clear();
        } catch (IOException e) {
            if (!journalWarned) {
                journalWarned = true;
                plugin.getLogger().warning("Could not write the Color Cover restore journal (a crash would leave painted "
                        + "blocks behind): " + e.getMessage());
            }
        }
    }

    private void deleteJournal(Path journal) {
        try {
            Files.deleteIfExists(journal);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete " + journal.getFileName() + ": " + e.getMessage());
        }
    }

    /** At startup: puts back every block a previous run painted and never restored (a crash, or a kill during a game). */
    private void recoverJournals() {
        File[] files = journalFolder.listFiles((dir, name) -> name.endsWith(".txt"));
        if (files == null) return;
        for (File file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not read " + file.getName() + ": " + e.getMessage());
                continue;
            }
            int restored = 0;
            boolean complete = true;
            for (String line : lines) {
                String[] parts = line.split("\t", 5);
                if (parts.length < 5) continue;
                World world = Bukkit.getWorld(parts[0]);
                if (world == null) {
                    complete = false; // that world isn't loaded (yet): keep the file for next time
                    continue;
                }
                try {
                    BlockData data = Bukkit.createBlockData(parts[4]);
                    world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))
                            .setBlockData(data, false);
                    restored++;
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipped a bad line in " + file.getName() + ": " + line);
                }
            }
            if (restored > 0) {
                plugin.getLogger().info("Put back " + restored + " blocks left painted by an unfinished Color Cover game.");
            }
            if (complete) deleteJournal(file.toPath());
        }
    }

    // ------------------------------------------------------------------ jerseys

    private ItemStack createJersey(Match.Team team) {
        ItemStack item = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(team.color.value()));
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

    // ------------------------------------------------------------------ keeping it friendly

    /** Nobody gets hurt in Color Cover (so nobody dies and drops their things). */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && byPlayer.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Spectators stay inside the arena after the game, and nobody can jump to a player outside it. */
    @EventHandler
    public void onSpectate(PlayerTeleportEvent event) {
        Game game = byPlayer.get(event.getPlayer().getUniqueId());
        if (game != null && game.phase == Phase.RESULT && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
        }
    }

    /** Everyone is frozen in place (but can look around) during the countdown, and kept in the arena once it is over. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Game game = byPlayer.get(event.getPlayer().getUniqueId());
        if (game == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (game.phase == Phase.RESULT) {
            if (to != null && !game.stadium.contains(to.getX(), to.getZ())) {
                Location fixed = from.clone();
                fixed.setYaw(to.getYaw());
                fixed.setPitch(to.getPitch());
                event.setTo(fixed);
            }
            return;
        }
        if (game.phase != Phase.COUNTDOWN) return;
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }
}
