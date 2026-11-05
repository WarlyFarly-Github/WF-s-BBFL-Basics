package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * /training: practise on your own in a football stadium of your choice.
 *
 * Free Play is just a football to kick around for as long as you like. Dodge is the same, but there are obstacles
 * sliding across the pitch between you and the goal: touch one and the run resets, get the ball into the goal without
 * touching one and you score a point (and it resets). In both, the lime concrete in your hotbar starts the training
 * (puts the ball down) and the red concrete stops it and takes you back to where you were.
 */
final class TrainingManager implements CommandExecutor, TabCompleter, Listener {

    private enum Mode { FREE_PLAY, DODGE }

    private enum MenuType { MODE, STADIUMS }

    private static final int SLOT_FREE_PLAY = 11;
    private static final int SLOT_DODGE = 15;
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;

    private static final int HOTBAR_START = 3; // the lime concrete
    private static final int HOTBAR_STOP = 5;  // the red concrete

    private static final int HIT_COOLDOWN_TICKS = 4;
    private static final int OUT_RESET_COOLDOWN_TICKS = 40;
    private static final double BOUNCE_MAX_DISTANCE = 4.0;

    private static final double OBSTACLE_WIDTH = 1.4;
    private static final double OBSTACLE_HEIGHT = 2.6;
    private static final double[] OBSTACLE_POSITIONS = {0.40, 0.60, 0.78}; // along the way from the start to the goal

    private static final class Gui implements InventoryHolder {
        private final MenuType type;
        private final Mode mode;
        private final int page;
        private final int pages;
        private Inventory inventory;

        private Gui(MenuType type, Mode mode, int page, int pages) {
            this.type = type;
            this.mode = mode;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** An obstacle: a block that slides from side to side across the pitch. It is drawn as a block display and its box is checked by hand. */
    private static final class Obstacle {
        private final BlockDisplay display;
        private final Location anchor; // the middle of its path, at floor level
        private final Vector side;
        private final double amplitude;
        private double omega;
        private double phase;

        private Obstacle(BlockDisplay display, Location anchor, Vector side, double amplitude, double omega, double phase) {
            this.display = display;
            this.anchor = anchor;
            this.side = side;
            this.amplitude = amplitude;
            this.omega = omega;
            this.phase = phase;
        }

        private double centreX(long tick) {
            return anchor.getX() + side.getX() * amplitude * Math.sin(phase + omega * tick);
        }

        private double centreZ(long tick) {
            return anchor.getZ() + side.getZ() * amplitude * Math.sin(phase + omega * tick);
        }

        private BoundingBox box(long tick) {
            double x = centreX(tick);
            double z = centreZ(tick);
            return new BoundingBox(x - OBSTACLE_WIDTH / 2, anchor.getY(), z - OBSTACLE_WIDTH / 2,
                    x + OBSTACLE_WIDTH / 2, anchor.getY() + OBSTACLE_HEIGHT, z + OBSTACLE_WIDTH / 2);
        }
    }

    private static final class Session {
        private final UUID player;
        private final Mode mode;
        private final Stadium stadium;
        private final World world;
        private final Location origin;
        private final GameMode savedMode;
        private final ItemStack savedStart;
        private final ItemStack savedStop;
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

        private boolean running;
        private long tick;
        private BukkitTask task;
        private SulfurCube ball;
        private Vector lastVelocity; // its velocity the last time it was inside the bounds, for bouncing
        private long lastHit = -100;
        private long lastReset = -1000;

        // Dodge
        private Location start;
        private Vector forward;
        private final List<Obstacle> obstacles = new ArrayList<>();
        private int points;
        private int hits;

        private Session(UUID player, Mode mode, Stadium stadium, Location origin, GameMode savedMode, ItemStack savedStart,
                        ItemStack savedStop) {
            this.player = player;
            this.mode = mode;
            this.stadium = stadium;
            this.world = stadium.center.getWorld();
            this.origin = origin;
            this.savedMode = savedMode;
            this.savedStart = savedStart;
            this.savedStop = savedStop;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final StadiumManager stadiums;
    private final NamespacedKey itemKey;
    private final Random random = new Random();
    private final Map<UUID, Session> sessions = new HashMap<>();

    TrainingManager(JavaPlugin plugin, MatchManager matches, StadiumManager stadiums) {
        this.plugin = plugin;
        this.matches = matches;
        this.stadiums = stadiums;
        this.itemKey = new NamespacedKey(plugin, "training_item");
    }

    boolean isTraining(UUID id) {
        return sessions.containsKey(id);
    }

    boolean isUsing(Stadium stadium) {
        for (Session session : sessions.values()) {
            if (session.stadium == stadium) return true;
        }
        return false;
    }

    boolean ownsBall(Entity entity) {
        for (Session session : sessions.values()) {
            if (session.ball != null && session.ball.equals(entity)) return true;
        }
        return false;
    }

    /** /match leave while training: the same as the red concrete. Returns false if the player wasn't training. */
    boolean leave(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        stop(session, true);
        return true;
    }

    void shutdown() {
        for (Session session : new ArrayList<>(sessions.values())) stop(session, true);
    }

    // ------------------------------------------------------------------ the command and its menus

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can train.", NamedTextColor.RED));
            return true;
        }
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            error(player, "You are already training. Use the red concrete in your hotbar to stop.");
            return true;
        }
        if (matches.isInMatch(id) || matches.isWatching(id)) {
            error(player, "Leave your match (or stop watching) before you start training.");
            return true;
        }
        openModeMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void openModeMenu(Player player) {
        Gui holder = new Gui(MenuType.MODE, null, 0, 0);
        Inventory inventory = newMenu(holder, "Training");
        inventory.setItem(SLOT_FREE_PLAY, button(Material.LEATHER_BOOTS, "Free Play", NamedTextColor.GOLD,
                plain("A football to kick around for as long as you like,", NamedTextColor.GRAY),
                plain("in the stadium of your choice.", NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_DODGE, button(Material.FEATHER, "Dodge", NamedTextColor.GOLD,
                plain("Dribble to the goal past sliding obstacles.", NamedTextColor.GRAY),
                plain("Touch one and it resets; score and you get a point.", NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        player.openInventory(inventory);
    }

    private void openStadiumMenu(Player player, Mode mode, int page) {
        List<Stadium> available = new ArrayList<>();
        for (String name : stadiums.names()) {
            Stadium stadium = stadiums.get(name);
            if (stadium != null && stadium.isComplete()) available.add(stadium);
        }
        int pages = Math.max(1, (available.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));

        Gui holder = new Gui(MenuType.STADIUMS, mode, page, pages);
        Inventory inventory = newMenu(holder, (mode == Mode.FREE_PLAY ? "Free Play" : "Dodge") + " - Choose Stadium");
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= available.size()) break;
            Stadium stadium = available.get(index);
            boolean free = matches.stadiumFree(stadium);
            ItemStack item = button(Material.GRASS_BLOCK, stadium.name, NamedTextColor.GOLD,
                    free ? plain("Click to train here", NamedTextColor.YELLOW) : plain("In use right now", NamedTextColor.RED));
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "stadium:" + stadium.name);
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }
        if (available.isEmpty()) {
            inventory.setItem(13, button(Material.BARRIER, "No stadiums yet", NamedTextColor.RED,
                    plain("An operator needs to finish a stadium first.", NamedTextColor.GRAY)));
        }
        if (pages > 1) {
            inventory.setItem(SLOT_PREVIOUS, button(Material.ARROW, "Previous Page", NamedTextColor.WHITE));
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "Next Page", NamedTextColor.WHITE));
        }
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getSlot();

        if (menu.type == MenuType.MODE) {
            if (slot == SLOT_FREE_PLAY) later(() -> openStadiumMenu(player, Mode.FREE_PLAY, 1));
            else if (slot == SLOT_DODGE) later(() -> openStadiumMenu(player, Mode.DODGE, 1));
            return;
        }

        if (slot == SLOT_BACK) {
            later(() -> openModeMenu(player));
        } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
            int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
            if (target >= 1 && target <= menu.pages) later(() -> openStadiumMenu(player, menu.mode, target));
        } else if (slot < PAGE_SIZE) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String key = clicked.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
            if (key == null || !key.startsWith("stadium:")) return;
            Stadium stadium = stadiums.get(key.substring("stadium:".length()));
            later(() -> {
                player.closeInventory();
                if (stadium == null) error(player, "That stadium no longer exists.");
                else begin(player, menu.mode, stadium);
            });
        }
    }

    // ------------------------------------------------------------------ starting and stopping a session

    private void begin(Player player, Mode mode, Stadium stadium) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id) || matches.isInMatch(id) || matches.isWatching(id)) {
            error(player, "You can't start training right now.");
            return;
        }
        if (!stadium.isComplete() || !stadium.hasCenter()) {
            error(player, stadium.name + " isn't finished.");
            return;
        }
        if (!matches.stadiumFree(stadium)) {
            error(player, stadium.name + " is being used right now.");
            return;
        }

        ItemStack savedStart = player.getInventory().getItem(HOTBAR_START);
        ItemStack savedStop = player.getInventory().getItem(HOTBAR_STOP);
        Session session = new Session(id, mode, stadium, player.getLocation().clone(), player.getGameMode(),
                savedStart == null ? null : savedStart.clone(), savedStop == null ? null : savedStop.clone());
        sessions.put(id, session);

        Location spot = stadium.center.clone();
        if (mode == Mode.DODGE) {
            prepareDodge(session);
            spot = session.start.clone();
        } else {
            Location toGoal = goalCentre(stadium);
            if (toGoal != null) {
                Vector direction = toGoal.toVector().subtract(spot.toVector()).setY(0);
                if (direction.lengthSquared() > 1.0E-4) spot.setDirection(direction);
            }
        }
        player.teleport(spot);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().setItem(HOTBAR_START, createItem(true));
        player.getInventory().setItem(HOTBAR_STOP, createItem(false));
        player.showBossBar(session.bar);
        updateBar(session);

        player.sendMessage(Component.text((mode == Mode.FREE_PLAY ? "Free Play" : "Dodge") + " on " + stadium.name
                + ": right-click the ", NamedTextColor.GREEN)
                .append(Component.text("lime concrete", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" to start and the ", NamedTextColor.GREEN))
                .append(Component.text("red concrete", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" to stop.", NamedTextColor.GREEN)));
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
    }

    /** The lime concrete: puts the ball down (and, in Dodge, a fresh run with its obstacles). */
    private void startRun(Session session, Player player) {
        session.running = true;
        if (session.mode == Mode.DODGE) {
            resetRun(session, player);
        } else {
            removeBall(session);
            session.ball = spawnBall(session, freePlayBallSpot(session, player));
        }
        updateBar(session);
        player.sendActionBar(Component.text(session.mode == Mode.FREE_PLAY ? "Free Play started" : "Run started - go!",
                NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    /** The red concrete: ends the training and puts everything back. */
    private void stop(Session session, boolean home) {
        sessions.remove(session.player);
        if (session.task != null) session.task.cancel();
        removeBall(session);
        removeObstacles(session);

        Player player = Bukkit.getPlayer(session.player);
        if (player == null) return;
        player.hideBossBar(session.bar);
        player.getInventory().setItem(HOTBAR_START, session.savedStart);
        player.getInventory().setItem(HOTBAR_STOP, session.savedStop);
        player.setGameMode(session.savedMode);
        if (home) player.teleport(session.origin);
        if (session.mode == Mode.DODGE) {
            player.sendMessage(Component.text("Dodge finished: " + session.points + " point" + (session.points == 1 ? "" : "s")
                    + ", " + session.hits + " hit" + (session.hits == 1 ? "" : "s") + ".", NamedTextColor.GOLD));
        } else {
            player.sendMessage(Component.text("Training stopped.", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) stop(session, true);
    }

    // ------------------------------------------------------------------ the hotbar items

    private ItemStack createItem(boolean start) {
        ItemStack item = button(start ? Material.LIME_CONCRETE : Material.RED_CONCRETE, start ? "Start" : "Stop",
                start ? NamedTextColor.GREEN : NamedTextColor.RED,
                plain(start ? "Right-click to put the ball down and start." : "Right-click to stop training and go back.",
                        NamedTextColor.GRAY));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, start ? "start" : "stop");
        item.setItemMeta(meta);
        return item;
    }

    private String itemAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return "start".equals(value) || "stop".equals(value) ? value : null;
    }

    @EventHandler
    public void onItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String action = itemAction(event.getItem());
        if (action == null) return;
        event.setCancelled(true); // the concrete is never placed

        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.getInventory().setItem(event.getHand(), null); // a left-over item from a session that is long gone
            return;
        }
        if (action.equals("stop")) stop(session, true);
        else startRun(session, player);
    }

    /** The two items stay where they are for as long as the session lasts. */
    @EventHandler
    public void onItemMove(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) return;
        if (!(event.getWhoClicked() instanceof Player player) || !sessions.containsKey(player.getUniqueId())) return;
        if (itemAction(event.getCurrentItem()) != null || itemAction(event.getCursor()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (itemAction(event.getItemDrop().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (itemAction(event.getMainHandItem()) != null || itemAction(event.getOffHandItem()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && sessions.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ the ball

    private SulfurCube spawnBall(Session session, Location where) {
        SulfurCube cube = matches.createBallEntity(where,
                Component.text("Football", NamedTextColor.YELLOW, TextDecoration.BOLD), true);
        cube.setMaximumNoDamageTicks(0);
        cube.setNoDamageTicks(0);
        session.lastVelocity = null;
        return cube;
    }

    private void removeBall(Session session) {
        if (session.ball != null) session.ball.remove();
        session.ball = null;
    }

    /** Free Play: the ball goes down a couple of blocks in front of the player (or in the middle if that is out of bounds). */
    private Location freePlayBallSpot(Session session, Player player) {
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1.0E-4) direction = new Vector(0, 0, 1);
        Location spot = player.getLocation().add(direction.normalize().multiply(2.0));
        if (!inside(session.stadium, spot.getX(), spot.getZ())) spot = session.stadium.center.clone();
        return spot;
    }

    /** Called by the match code when a player punches a ball: an ordinary football kick. */
    void onHit(Player kicker, Entity entity, Vector velocity) {
        Session session = sessions.get(kicker.getUniqueId());
        if (session == null || !session.running || session.ball == null || !session.ball.equals(entity)) return;
        if (session.tick - session.lastHit < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        session.lastHit = session.tick;
        entity.setVelocity(velocity);
    }

    // ------------------------------------------------------------------ the loop

    private void tick(Session session) {
        Player player = Bukkit.getPlayer(session.player);
        if (player == null || !player.isOnline()) {
            stop(session, false);
            return;
        }
        session.tick++;
        if (!session.running) return;

        if (session.ball == null || session.ball.isDead()) { // replaced only when it is really gone
            removeBall(session);
            session.ball = spawnBall(session, session.mode == Mode.DODGE ? ballStart(session) : session.stadium.center.clone());
        }

        if (session.mode == Mode.DODGE) {
            moveObstacles(session);
            for (Obstacle obstacle : session.obstacles) collideBall(session, obstacle);
            if (hitByObstacle(session, player)) {
                session.hits++;
                player.showTitle(Title.title(Component.text("Hit!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("You touched an obstacle", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
                player.playSound(player.getLocation(), "entity.player.hurt", 1.0f, 1.0f);
                resetRun(session, player);
                updateBar(session);
                return;
            }
            Location ballCentre = session.ball.getBoundingBox().getCenter().toLocation(session.world);
            if (session.stadium.goalContains(Match.Team.BLUE, ballCentre)) {
                session.points++;
                player.showTitle(Title.title(Component.text("Point!", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text(session.points + " point" + (session.points == 1 ? "" : "s"), NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
                player.playSound(player.getLocation(), "entity.player.levelup", 1.0f, 1.2f);
                resetRun(session, player);
                updateBar(session);
                return;
            }
        }
        checkBounds(session);
    }

    private void updateBar(Session session) {
        if (session.mode == Mode.DODGE) {
            session.bar.name(Component.text("Dodge - Points: " + session.points + "  |  Hits: " + session.hits,
                    NamedTextColor.GREEN, TextDecoration.BOLD));
        } else {
            session.bar.name(Component.text("Free Play - " + (session.running ? "right-click the red concrete to stop"
                    : "right-click the lime concrete to start"), NamedTextColor.GREEN, TextDecoration.BOLD));
        }
    }

    // ------------------------------------------------------------------ bounds (like a normal match)

    /**
     * The bounds work as they do in a normal match: a ball that crosses them is put back just inside and bounces
     * off, harder the faster it hit. The goals count as inside. A ball that fell out of the stadium goes back.
     */
    private void checkBounds(Session session) {
        SulfurCube cube = session.ball;
        if (cube == null) return;
        BoundingBox box = cube.getBoundingBox();
        Location centre = new Location(session.world, box.getCenterX(), box.getCenterY(), box.getCenterZ());

        boolean lost = !cube.getWorld().equals(session.world) || box.getMinY() < session.world.getMinHeight() - 4
                || box.getMinY() < session.stadium.center.getBlockY() - 12;
        if (lost) {
            returnBall(session, "fell out of the stadium");
            return;
        }
        if (!session.stadium.hasBounds() || session.stadium.boundsContain(centre)
                || session.stadium.goalContains(Match.Team.RED, centre) || session.stadium.goalContains(Match.Team.BLUE, centre)) {
            session.lastVelocity = cube.getVelocity().clone();
            return;
        }
        bounceOffBounds(session, centre);
    }

    private void bounceOffBounds(Session session, Location centre) {
        SulfurCube cube = session.ball;
        Stadium.BoundsHit hit = session.stadium.nearestBoundsEdge(centre);
        if (hit == null || hit.distance() > BOUNCE_MAX_DISTANCE) {
            returnBall(session, "went out of the stadium");
            return;
        }

        Vector v = cube.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) < 0.01 && session.lastVelocity != null) v = session.lastVelocity.clone();

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

        Location target = cube.getLocation().clone();
        target.add(hit.x() + hit.nx() * MatchManager.WALL_INSET - centre.getX(), 0,
                hit.z() + hit.nz() * MatchManager.WALL_INSET - centre.getZ());
        cube.teleport(target);
        cube.setVelocity(bounced);
        session.lastVelocity = bounced.clone();
        Player player = Bukkit.getPlayer(session.player);
        if (player != null) player.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    private void returnBall(Session session, String what) {
        if (session.ball == null || session.tick - session.lastReset < OUT_RESET_COOLDOWN_TICKS) return;
        session.lastReset = session.tick;
        Player player = Bukkit.getPlayer(session.player);
        if (session.mode == Mode.DODGE) {
            if (player != null) resetRun(session, player);
        } else {
            session.ball.teleport(session.stadium.center.clone());
            session.ball.setVelocity(new Vector(0, 0, 0));
            session.lastVelocity = null;
        }
        if (player != null) player.sendActionBar(Component.text("The ball " + what + ".", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------------ Dodge

    /** The middle of the goal Blue defends: the one the player attacks. */
    private Location goalCentre(Stadium stadium) {
        Location[] corners = stadium.goals.get(Match.Team.BLUE);
        if (corners == null) return null;
        double x = 0;
        double z = 0;
        int n = 0;
        for (Location corner : corners) {
            if (corner == null) return null;
            x += corner.getBlockX() + 0.5;
            z += corner.getBlockZ() + 0.5;
            n++;
        }
        return n == 0 ? null : new Location(stadium.center.getWorld(), x / n, stadium.center.getY(), z / n);
    }

    /** Works out where the run starts and builds the obstacles between there and the goal. */
    private void prepareDodge(Session session) {
        Stadium stadium = session.stadium;
        Location centre = stadium.center;
        Location goal = goalCentre(stadium);
        Vector forward = goal == null ? new Vector(0, 0, 1)
                : goal.toVector().subtract(centre.toVector()).setY(0);
        double halfLength = Math.max(6.0, forward.length());
        if (forward.lengthSquared() < 1.0E-4) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());

        // The run starts behind the middle of the pitch, so there is a long way to go.
        Location start = centre.clone().subtract(forward.clone().multiply(halfLength * 0.35));
        if (!inside(stadium, start.getX(), start.getZ())) start = centre.clone();
        start.setDirection(forward);
        double length = Math.max(8.0, halfLength + start.toVector().subtract(centre.toVector()).length()
                * (start.equals(centre) ? 0 : 1)); // distance from the start to the goal, roughly
        session.start = start;
        session.forward = forward;

        // How far the obstacles can slide: as far as the bounds allow, measured at the middle obstacle.
        Location middle = start.clone().add(forward.clone().multiply(length * OBSTACLE_POSITIONS[1]));
        double reach = 0;
        for (double offset = 0.5; offset <= 9.0; offset += 0.5) {
            Location a = middle.clone().add(side.clone().multiply(offset));
            Location b = middle.clone().subtract(side.clone().multiply(offset));
            if (!inside(stadium, a.getX(), a.getZ()) || !inside(stadium, b.getX(), b.getZ())) break;
            reach = offset;
        }
        double amplitude = Math.max(1.5, reach - OBSTACLE_WIDTH / 2);

        for (double fraction : OBSTACLE_POSITIONS) {
            Location anchor = start.clone().add(forward.clone().multiply(length * fraction));
            anchor.setY(centre.getY());
            double omega = 0.045 + random.nextDouble() * 0.04;
            double phase = random.nextDouble() * Math.PI * 2;
            BlockDisplay display = session.world.spawn(anchor, BlockDisplay.class, d -> {
                d.setBlock(Material.RED_CONCRETE.createBlockData());
                d.setTransformation(new Transformation(new Vector3f((float) (-OBSTACLE_WIDTH / 2), 0f, (float) (-OBSTACLE_WIDTH / 2)),
                        new AxisAngle4f(), new Vector3f((float) OBSTACLE_WIDTH, (float) OBSTACLE_HEIGHT, (float) OBSTACLE_WIDTH),
                        new AxisAngle4f()));
                d.setTeleportDuration(2);   // glides between the positions we set every tick
                d.setPersistent(false);     // never saved, so a crash can't leave one behind
            });
            session.obstacles.add(new Obstacle(display, anchor, side.clone(), amplitude, omega, phase));
        }
    }

    private void removeObstacles(Session session) {
        for (Obstacle obstacle : session.obstacles) obstacle.display.remove();
        session.obstacles.clear();
    }

    private Location ballStart(Session session) {
        Location spot = session.start.clone().add(session.forward.clone().multiply(2.0));
        spot.setY(session.stadium.center.getY());
        return spot;
    }

    /** A fresh run: the player and the ball back at the start, and the obstacles on new, random paths. */
    private void resetRun(Session session, Player player) {
        session.lastReset = session.tick;
        player.teleport(session.start);
        player.setVelocity(new Vector(0, 0, 0));
        removeBall(session);
        session.ball = spawnBall(session, ballStart(session));
        for (Obstacle obstacle : session.obstacles) {
            obstacle.omega = 0.045 + random.nextDouble() * 0.04;
            obstacle.phase = random.nextDouble() * Math.PI * 2 - obstacle.omega * session.tick; // starts at a random point
        }
    }

    private void moveObstacles(Session session) {
        for (Obstacle obstacle : session.obstacles) {
            obstacle.display.teleport(new Location(session.world, obstacle.centreX(session.tick), obstacle.anchor.getY(),
                    obstacle.centreZ(session.tick)));
        }
    }

    private boolean hitByObstacle(Session session, Player player) {
        BoundingBox body = player.getBoundingBox().expand(0.05);
        for (Obstacle obstacle : session.obstacles) {
            if (obstacle.box(session.tick).overlaps(body)) return true;
        }
        return false;
    }

    /** The ball is solid to the obstacles: it is pushed out of one and bounces off it. */
    private void collideBall(Session session, Obstacle obstacle) {
        SulfurCube ball = session.ball;
        if (ball == null) return;
        BoundingBox b = ball.getBoundingBox();
        BoundingBox o = obstacle.box(session.tick);
        if (!o.overlaps(b)) return;

        double toPlusX = o.getMaxX() - b.getMinX();
        double toMinusX = b.getMaxX() - o.getMinX();
        double toPlusZ = o.getMaxZ() - b.getMinZ();
        double toMinusZ = b.getMaxZ() - o.getMinZ();
        double least = Math.min(Math.min(toPlusX, toMinusX), Math.min(toPlusZ, toMinusZ));

        Vector v = ball.getVelocity();
        double dx = 0;
        double dz = 0;
        if (least == toPlusX) {
            dx = toPlusX + 0.05;
            v.setX(Math.abs(v.getX()) * 0.8 + 0.15);
        } else if (least == toMinusX) {
            dx = -(toMinusX + 0.05);
            v.setX(-Math.abs(v.getX()) * 0.8 - 0.15);
        } else if (least == toPlusZ) {
            dz = toPlusZ + 0.05;
            v.setZ(Math.abs(v.getZ()) * 0.8 + 0.15);
        } else {
            dz = -(toMinusZ + 0.05);
            v.setZ(-Math.abs(v.getZ()) * 0.8 - 0.15);
        }
        ball.teleport(ball.getLocation().add(dx, 0, dz));
        ball.setVelocity(v);
        session.world.playSound(ball.getLocation(), "block.stone.hit", 1.0f, 1.2f);
    }

    // ------------------------------------------------------------------ small helpers

    /** Whether a spot is inside the stadium's bounds (anywhere counts if it has none). */
    private boolean inside(Stadium stadium, double x, double z) {
        return !stadium.hasBounds() || stadium.boundsContain(new Location(stadium.center.getWorld(), x, stadium.center.getY(), z));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        if (lore.length > 0) meta.lore(List.of(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private Inventory newMenu(Gui holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        return inventory;
    }
}
