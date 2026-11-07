package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
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
import org.bukkit.inventory.meta.SkullMeta;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * /training: practise in a football stadium of your choice.
 *
 * Free Play is a football to kick around for as long as you like, on your own or with friends you invite. Goals count
 * like in a normal match, there is just no clock. Dodge is a solo version with obstacles sliding across the pitch between
 * you and the goal: if you or the ball touch one the run resets, get the ball into the goal without touching one and you
 * score a point (and it resets). The lime concrete in your hotbar starts it (puts the ball down) and the red concrete stops it.
 */
final class TrainingManager implements CommandExecutor, TabCompleter, Listener {

    private enum Mode { FREE_PLAY, DODGE }

    private enum MenuType { MODE, STADIUMS, INVITE }

    private static final int SLOT_FREE_PLAY = 11;
    private static final int SLOT_DODGE = 15;
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;

    private static final int HOTBAR_START = 3;  // the lime concrete
    private static final int HOTBAR_INVITE = 4; // the diamond (the host of a Free Play only)
    private static final int HOTBAR_STOP = 5;   // the red concrete

    private static final int MAX_MEMBERS = 10;
    private static final long INVITE_MILLIS = 60_000;
    private static final int GOAL_PAUSE_TICKS = 60;      // after a Free Play goal, the ball comes back after 3 seconds
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

    /** An obstacle: a block that slides from side to side across the pitch. It is drawn as a block display and touching it is checked by hand. */
    private static final class Obstacle {
        private final BlockDisplay display;
        private final Location anchor; // the middle of its path, at floor level
        private final Vector side;
        private final double amplitude;
        private double omega;
        private double phase;
        private final Set<Cell> shown = new HashSet<>(); // the blocks currently shown to a Bedrock player for it

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

        /** What counts as touching it (for the player or the ball): taller than it looks, so a slope in the floor or a jump can't get past it. */
        private BoundingBox hitBox(long tick) {
            double x = centreX(tick);
            double z = centreZ(tick);
            return new BoundingBox(x - OBSTACLE_WIDTH / 2, anchor.getY() - 1.5, z - OBSTACLE_WIDTH / 2,
                    x + OBSTACLE_WIDTH / 2, anchor.getY() + OBSTACLE_HEIGHT + 1.0, z + OBSTACLE_WIDTH / 2);
        }
    }

    /** One block position. */
    private record Cell(int x, int y, int z) {
        private Location at(World world) {
            return new Location(world, x, y, z);
        }
    }

    /** One player in a session and what to give back to them when they leave it. */
    private static final class Member {
        private final Location origin;
        private final GameMode savedMode;
        private final ItemStack savedStart;
        private final ItemStack savedInvite;
        private final ItemStack savedStop;
        private final boolean usesInviteSlot;
        private long lastHit = -100;

        private Member(Location origin, GameMode savedMode, ItemStack savedStart, ItemStack savedInvite, ItemStack savedStop,
                       boolean usesInviteSlot) {
            this.origin = origin;
            this.savedMode = savedMode;
            this.savedStart = savedStart;
            this.savedInvite = savedInvite;
            this.savedStop = savedStop;
            this.usesInviteSlot = usesInviteSlot;
        }
    }

    private static final class Session {
        private final UUID owner;
        private final Mode mode;
        private final Stadium stadium;
        private final World world;
        private final Map<UUID, Member> members = new LinkedHashMap<>();
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

        private boolean running;
        private long tick;
        private BukkitTask task;
        private SulfurCube ball;
        private Vector lastVelocity; // its velocity the last time it was inside the bounds, for bouncing
        private long lastReset = -1000;
        private UUID lastKicker;

        // Free Play
        private int redScore;
        private int blueScore;
        private int ballWait; // ticks until the ball comes back after a goal

        // Dodge
        private Location start;
        private Vector forward;
        private final List<Obstacle> obstacles = new ArrayList<>();
        private int points;
        private int hits;
        private boolean bedrock; // the Dodge player is on Bedrock: the obstacles are shown as blocks sent only to them

        private Session(UUID owner, Mode mode, Stadium stadium) {
            this.owner = owner;
            this.mode = mode;
            this.stadium = stadium;
            this.world = stadium.center.getWorld();
        }
    }

    private record Invite(Session session, long time) { }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final StadiumManager stadiums;
    private final NamespacedKey itemKey;
    private final Random random = new Random();
    private final List<Session> all = new ArrayList<>();
    private final Map<UUID, Session> byPlayer = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final BlockData obstacleBlock = Material.RED_CONCRETE.createBlockData(); // what Bedrock players see

    TrainingManager(JavaPlugin plugin, MatchManager matches, StadiumManager stadiums) {
        this.plugin = plugin;
        this.matches = matches;
        this.stadiums = stadiums;
        this.itemKey = new NamespacedKey(plugin, "training_item");
    }

    boolean isTraining(UUID id) {
        return byPlayer.containsKey(id);
    }

    boolean isUsing(Stadium stadium) {
        for (Session session : all) {
            if (session.stadium == stadium) return true;
        }
        return false;
    }

    boolean ownsBall(Entity entity) {
        for (Session session : all) {
            if (session.ball != null && session.ball.equals(entity)) return true;
        }
        return false;
    }

    /** /match leave while training: the same as the red concrete. Returns false if the player wasn't training. */
    boolean leave(Player player) {
        Session session = byPlayer.get(player.getUniqueId());
        if (session == null) return false;
        leaveSession(session, player.getUniqueId());
        return true;
    }

    void shutdown() {
        for (Session session : new ArrayList<>(all)) stop(session);
    }

    // ------------------------------------------------------------------ the command and its menus

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can train.", NamedTextColor.RED));
            return true;
        }
        UUID id = player.getUniqueId();
        if (args.length >= 1) {
            switch (args[0].toLowerCase()) {
                case "invite" -> {
                    if (args.length < 2) {
                        error(player, "Usage: /training invite <player>");
                        return true;
                    }
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) error(player, args[1] + " isn't online.");
                    else invite(player, target);
                    return true;
                }
                case "accept" -> {
                    accept(player);
                    return true;
                }
                case "decline" -> {
                    decline(player);
                    return true;
                }
                default -> {
                    error(player, "Usage: /training [invite <player>|accept|decline]");
                    return true;
                }
            }
        }
        if (byPlayer.containsKey(id)) {
            error(player, "You are already training. Use the red concrete in your hotbar to stop.");
            return true;
        }
        if (matches.isInMatch(id) || matches.isWatching(id)) {
            error(player, "Leave your match (or stop watching) before you start training.");
            return true;
        }
        if (matches.inLobby(id)) {
            error(player, "Leave the minigames lobby first (/lobby leave).");
            return true;
        }
        openModeMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("invite", "accept", "decline").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invite")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }

    private void openModeMenu(Player player) {
        Gui holder = new Gui(MenuType.MODE, null, 0, 0);
        Inventory inventory = newMenu(holder, "Training");
        inventory.setItem(SLOT_FREE_PLAY, button(Material.LEATHER_BOOTS, "Free Play", NamedTextColor.GOLD,
                plain("A football to kick around for as long as you like,", NamedTextColor.GRAY),
                plain("in the stadium of your choice. Goals count, and you", NamedTextColor.GRAY),
                plain("can invite friends to play with you.", NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_DODGE, button(Material.FEATHER, "Dodge", NamedTextColor.GOLD,
                plain("Dribble to the goal past sliding obstacles.", NamedTextColor.GRAY),
                plain("If you or the ball touch one it resets; score and", NamedTextColor.GRAY),
                plain("you get a point.", NamedTextColor.GRAY),
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
            // The icon is the stadium's /stadium block, like in the match stadium menu.
            ItemStack item = button(stadium.block != null ? stadium.block : Material.PLAYER_HEAD, stadium.name,
                    NamedTextColor.GOLD,
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
        addPaging(inventory, pages);
        player.openInventory(inventory);
    }

    /** The host's list of players to invite to their Free Play. */
    private void openInviteMenu(Player host, int page) {
        Session session = byPlayer.get(host.getUniqueId());
        if (session == null || session.mode != Mode.FREE_PLAY || !host.getUniqueId().equals(session.owner)) return;

        List<Player> candidates = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            UUID id = other.getUniqueId();
            if (id.equals(host.getUniqueId()) || byPlayer.containsKey(id) || matches.isInMatch(id) || matches.isWatching(id)) continue;
            candidates.add(other);
        }
        int pages = Math.max(1, (candidates.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));

        Gui holder = new Gui(MenuType.INVITE, session.mode, page, pages);
        Inventory inventory = newMenu(holder, "Invite to Free Play");
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= candidates.size()) break;
            Player other = candidates.get(index);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(other);
            meta.displayName(Component.text(other.getName(), NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(plain("Click to invite", NamedTextColor.YELLOW)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "player:" + other.getUniqueId());
            head.setItemMeta(meta);
            inventory.setItem(i, head);
        }
        addPaging(inventory, pages);
        host.openInventory(inventory);
    }

    private void addPaging(Inventory inventory, int pages) {
        if (pages > 1) {
            inventory.setItem(SLOT_PREVIOUS, button(Material.ARROW, "Previous Page", NamedTextColor.WHITE));
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "Next Page", NamedTextColor.WHITE));
        }
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE));
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
            later(() -> {
                if (menu.type == MenuType.INVITE) player.closeInventory();
                else openModeMenu(player);
            });
        } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
            int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
            if (target >= 1 && target <= menu.pages) {
                later(() -> {
                    if (menu.type == MenuType.INVITE) openInviteMenu(player, target);
                    else openStadiumMenu(player, menu.mode, target);
                });
            }
        } else if (slot < PAGE_SIZE) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            String key = clicked.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
            if (key == null) return;
            if (menu.type == MenuType.STADIUMS && key.startsWith("stadium:")) {
                Stadium stadium = stadiums.get(key.substring("stadium:".length()));
                later(() -> {
                    player.closeInventory();
                    if (stadium == null) error(player, "That stadium no longer exists.");
                    else begin(player, menu.mode, stadium);
                });
            } else if (menu.type == MenuType.INVITE && key.startsWith("player:")) {
                Player target = Bukkit.getPlayer(UUID.fromString(key.substring("player:".length())));
                later(() -> {
                    player.closeInventory();
                    if (target == null) error(player, "That player is no longer online.");
                    else invite(player, target);
                });
            }
        }
    }

    // ------------------------------------------------------------------ starting and stopping a session

    private void begin(Player player, Mode mode, Stadium stadium) {
        UUID id = player.getUniqueId();
        if (byPlayer.containsKey(id) || matches.isInMatch(id) || matches.isWatching(id) || matches.inLobby(id)) {
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

        Session session = new Session(id, mode, stadium);
        session.bedrock = mode == Mode.DODGE && BedrockSupport.isBedrock(player);
        all.add(session);
        Location spot = stadium.center.clone();
        if (mode == Mode.DODGE) {
            prepareDodge(session);
            spot = session.start.clone();
        } else {
            faceGoal(stadium, spot);
        }
        addMember(session, player, spot, true);
        updateBar(session);

        player.sendMessage(Component.text((mode == Mode.FREE_PLAY ? "Free Play" : "Dodge") + " on " + stadium.name
                + ": right-click the ", NamedTextColor.GREEN)
                .append(Component.text("lime concrete", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" to start and the ", NamedTextColor.GREEN))
                .append(Component.text("red concrete", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" to stop." + (mode == Mode.FREE_PLAY
                        ? " The diamond invites friends." : ""), NamedTextColor.GREEN)));
        session.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(session), 1L, 1L);
    }

    /** Puts a player into a session: their hotbar items, adventure mode and the bar, and where they stand. */
    private void addMember(Session session, Player player, Location spot, boolean host) {
        UUID id = player.getUniqueId();
        boolean inviteSlot = host && session.mode == Mode.FREE_PLAY;
        ItemStack savedStart = player.getInventory().getItem(HOTBAR_START);
        ItemStack savedInvite = player.getInventory().getItem(HOTBAR_INVITE);
        ItemStack savedStop = player.getInventory().getItem(HOTBAR_STOP);
        session.members.put(id, new Member(player.getLocation().clone(), player.getGameMode(),
                savedStart == null ? null : savedStart.clone(), savedInvite == null ? null : savedInvite.clone(),
                savedStop == null ? null : savedStop.clone(), inviteSlot));
        byPlayer.put(id, session);

        player.teleport(spot);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().setItem(HOTBAR_START, createItem("start"));
        if (inviteSlot) player.getInventory().setItem(HOTBAR_INVITE, createItem("invite"));
        player.getInventory().setItem(HOTBAR_STOP, createItem("stop"));
        player.showBossBar(session.bar);
    }

    /** Takes a player out of a session and gives back everything of theirs. */
    private void removeMember(Session session, UUID id, boolean home) {
        Member member = session.members.remove(id);
        byPlayer.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (member == null || player == null) return;
        player.hideBossBar(session.bar);
        player.getInventory().setItem(HOTBAR_START, member.savedStart);
        if (member.usesInviteSlot) player.getInventory().setItem(HOTBAR_INVITE, member.savedInvite);
        player.getInventory().setItem(HOTBAR_STOP, member.savedStop);
        player.setGameMode(member.savedMode);
        if (home) player.teleport(member.origin);
    }

    /** The red concrete: a guest leaves; if the host leaves, the whole session ends. */
    private void leaveSession(Session session, UUID id) {
        if (id.equals(session.owner)) {
            stop(session);
            return;
        }
        Player leaver = Bukkit.getPlayer(id);
        removeMember(session, id, true);
        if (leaver != null) {
            leaver.sendMessage(Component.text("You left Free Play.", NamedTextColor.YELLOW));
            for (UUID other : session.members.keySet()) {
                Player p = Bukkit.getPlayer(other);
                if (p != null) p.sendMessage(Component.text(leaver.getName() + " left.", NamedTextColor.YELLOW));
            }
        }
        if (session.members.isEmpty()) stop(session);
    }

    /** Ends the session for everyone in it. */
    private void stop(Session session) {
        all.remove(session);
        if (session.task != null) session.task.cancel();
        removeBall(session);
        removeObstacles(session);
        for (UUID id : new ArrayList<>(session.members.keySet())) {
            Player p = Bukkit.getPlayer(id);
            removeMember(session, id, true);
            if (p == null) continue;
            if (session.mode == Mode.DODGE) {
                p.sendMessage(Component.text("Dodge finished: " + session.points + " point" + (session.points == 1 ? "" : "s")
                        + ", " + session.hits + " hit" + (session.hits == 1 ? "" : "s") + ".", NamedTextColor.GOLD));
            } else if (session.redScore + session.blueScore > 0) {
                p.sendMessage(Component.text("Free Play over. Final score: Red " + session.redScore + " - "
                        + session.blueScore + " Blue.", NamedTextColor.GOLD));
            } else {
                p.sendMessage(Component.text("Training stopped.", NamedTextColor.YELLOW));
            }
        }
    }

    /** The lime concrete: puts the ball down (and, in Dodge, a fresh run with its obstacles). */
    private void startRun(Session session, Player player) {
        session.running = true;
        session.ballWait = 0;
        if (session.mode == Mode.DODGE) {
            resetRun(session, player);
        } else {
            removeBall(session);
            session.ball = spawnBall(session, freePlayBallSpot(session, player));
        }
        updateBar(session);
        player.sendActionBar(Component.text(session.mode == Mode.FREE_PLAY ? "Ball down!" : "Run started - go!",
                NamedTextColor.GREEN, TextDecoration.BOLD));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = byPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) leaveSession(session, event.getPlayer().getUniqueId());
        invites.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ inviting friends to Free Play

    private void invite(Player host, Player target) {
        Session session = byPlayer.get(host.getUniqueId());
        if (session == null || session.mode != Mode.FREE_PLAY || !host.getUniqueId().equals(session.owner)) {
            error(host, "Only the host of a Free Play can invite players.");
            return;
        }
        UUID id = target.getUniqueId();
        if (id.equals(host.getUniqueId())) {
            error(host, "You can't invite yourself.");
            return;
        }
        if (byPlayer.containsKey(id) || matches.isInMatch(id) || matches.isWatching(id)) {
            error(host, target.getName() + " is busy right now.");
            return;
        }
        if (session.members.size() >= MAX_MEMBERS) {
            error(host, "Free Play can have at most " + MAX_MEMBERS + " players.");
            return;
        }

        invites.put(id, new Invite(session, System.currentTimeMillis()));
        target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" invited you to Free Play on " + session.stadium.name + ". ", NamedTextColor.GRAY))
                .append(BedrockSupport.respondButtons(target, "/training")));
        host.sendMessage(Component.text("Invited " + target.getName() + " to your Free Play.", NamedTextColor.GREEN));
    }

    private void accept(Player player) {
        UUID id = player.getUniqueId();
        Invite invite = invites.remove(id);
        if (invite == null || System.currentTimeMillis() - invite.time() > INVITE_MILLIS || !all.contains(invite.session())) {
            error(player, "You have no Free Play invitation (it may have expired or ended).");
            return;
        }
        Session session = invite.session();
        if (byPlayer.containsKey(id) || matches.isInMatch(id) || matches.isWatching(id)) {
            error(player, "Leave what you are doing first.");
            return;
        }
        if (session.members.size() >= MAX_MEMBERS) {
            error(player, "That Free Play is full.");
            return;
        }

        Location spot = session.stadium.center.clone();
        faceGoal(session.stadium, spot);
        addMember(session, player, spot, false);
        String name = player.getName();
        for (UUID other : session.members.keySet()) {
            Player p = Bukkit.getPlayer(other);
            if (p == null) continue;
            if (other.equals(id)) {
                p.sendMessage(Component.text("You joined Free Play on " + session.stadium.name + ". Right-click the red concrete "
                        + "to leave.", NamedTextColor.GREEN));
            } else {
                p.sendMessage(Component.text(name + " joined.", NamedTextColor.GREEN));
            }
        }
    }

    private void decline(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null) {
            error(player, "You have no Free Play invitation.");
            return;
        }
        player.sendMessage(Component.text("Invitation declined.", NamedTextColor.YELLOW));
        Player host = Bukkit.getPlayer(invite.session().owner);
        if (host != null) host.sendMessage(Component.text(player.getName() + " declined your invitation.", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------------ the hotbar items

    private ItemStack createItem(String action) {
        ItemStack item = switch (action) {
            case "start" -> button(Material.LIME_CONCRETE, "Start", NamedTextColor.GREEN,
                    plain("Right-click to put the ball down and start.", NamedTextColor.GRAY));
            case "invite" -> button(Material.DIAMOND, "Invite Players", NamedTextColor.AQUA,
                    plain("Right-click to invite friends to your Free Play.", NamedTextColor.GRAY));
            default -> button(Material.RED_CONCRETE, "Stop", NamedTextColor.RED,
                    plain("Right-click to stop training and go back.", NamedTextColor.GRAY));
        };
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private String itemAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return "start".equals(value) || "stop".equals(value) || "invite".equals(value) ? value : null;
    }

    @EventHandler
    public void onItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String action = itemAction(event.getItem());
        if (action == null) return;
        event.setCancelled(true); // the concrete is never placed

        Player player = event.getPlayer();
        Session session = byPlayer.get(player.getUniqueId());
        if (session == null) {
            player.getInventory().setItem(event.getHand(), null); // a left-over item from a session that is long gone
            return;
        }
        switch (action) {
            case "stop" -> leaveSession(session, player.getUniqueId());
            case "invite" -> openInviteMenu(player, 1);
            default -> startRun(session, player);
        }
    }

    /** The items stay where they are for as long as the session lasts. */
    @EventHandler
    public void onItemMove(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) return;
        if (!(event.getWhoClicked() instanceof Player player) || !byPlayer.containsKey(player.getUniqueId())) return;
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
        if (event.getEntity() instanceof Player player && byPlayer.containsKey(player.getUniqueId())) event.setCancelled(true);
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
        Session session = byPlayer.get(kicker.getUniqueId());
        if (session == null || !session.running || session.ball == null || !session.ball.equals(entity)) return;
        Member member = session.members.get(kicker.getUniqueId());
        if (member == null || session.tick - member.lastHit < HIT_COOLDOWN_TICKS) return; // one swing, one hit
        member.lastHit = session.tick;
        session.lastKicker = kicker.getUniqueId();
        entity.setVelocity(velocity);
    }

    // ------------------------------------------------------------------ the loop

    private void tick(Session session) {
        Player owner = Bukkit.getPlayer(session.owner);
        if (owner == null || !owner.isOnline()) {
            stop(session);
            return;
        }
        session.tick++;
        // Bedrock players can't see block displays, so the obstacles are shown to them as blocks instead.
        if (session.bedrock) updateBedrockBlocks(session, owner);
        if (!session.running) return;

        if (session.mode == Mode.FREE_PLAY) {
            if (session.ballWait > 0) { // a goal was just scored: the ball comes back in a moment
                if (--session.ballWait == 0) session.ball = spawnBall(session, session.stadium.center.clone());
                return;
            }
        }

        if (session.ball == null || session.ball.isDead()) { // replaced only when it is really gone
            removeBall(session);
            session.ball = spawnBall(session, session.mode == Mode.DODGE ? ballStart(session) : session.stadium.center.clone());
        }

        if (session.mode == Mode.DODGE) {
            moveObstacles(session);
            String hitBy = obstacleHit(session, owner);
            if (hitBy != null) {
                session.hits++;
                owner.showTitle(Title.title(Component.text("Hit!", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text(hitBy, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
                owner.playSound(owner.getLocation(), "entity.player.hurt", 1.0f, 1.0f);
                resetRun(session, owner);
                updateBar(session);
                return;
            }
            Location ballCentre = session.ball.getBoundingBox().getCenter().toLocation(session.world);
            if (session.stadium.goalContains(Match.Team.BLUE, ballCentre)) {
                session.points++;
                owner.showTitle(Title.title(Component.text("Point!", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text(session.points + " point" + (session.points == 1 ? "" : "s"), NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
                owner.playSound(owner.getLocation(), "entity.player.levelup", 1.0f, 1.2f);
                resetRun(session, owner);
                updateBar(session);
                return;
            }
        }
        checkBounds(session);

        if (session.mode == Mode.FREE_PLAY && session.ball != null) {
            // Goals count as in a normal match: the ball in the goal a team defends is a goal for the other team.
            Location centre = session.ball.getBoundingBox().getCenter().toLocation(session.world);
            if (session.stadium.goalContains(Match.Team.RED, centre)) goalScored(session, Match.Team.BLUE);
            else if (session.stadium.goalContains(Match.Team.BLUE, centre)) goalScored(session, Match.Team.RED);
        }
    }

    /** Free Play goal: the score goes up, everyone sees it, and the ball comes back in the middle after 3 seconds. */
    private void goalScored(Session session, Match.Team scorer) {
        if (scorer == Match.Team.RED) session.redScore++;
        else session.blueScore++;
        removeBall(session);
        session.ballWait = GOAL_PAUSE_TICKS;

        String by = session.lastKicker == null ? null : Bukkit.getOfflinePlayer(session.lastKicker).getName();
        Title title = Title.title(Component.text("GOAL!", scorer.color, TextDecoration.BOLD),
                Component.text((by == null ? "" : "Scored by " + by + "  ") + "Red " + session.redScore + " - "
                        + session.blueScore + " Blue", NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(400)));
        for (UUID id : session.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showTitle(title);
            p.playSound(p.getLocation(), "entity.player.levelup", 1.0f, 1.0f);
        }
        session.lastKicker = null;
        updateBar(session);
    }

    private void updateBar(Session session) {
        if (session.mode == Mode.DODGE) {
            session.bar.name(Component.text("Dodge - Points: " + session.points + "  |  Hits: " + session.hits,
                    NamedTextColor.GREEN, TextDecoration.BOLD));
        } else if (!session.running) {
            session.bar.name(Component.text("Free Play - right-click the lime concrete to start", NamedTextColor.GREEN,
                    TextDecoration.BOLD));
        } else {
            session.bar.name(Component.text("Red " + session.redScore, NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text("  -  ", NamedTextColor.GRAY))
                    .append(Component.text(session.blueScore + " Blue", NamedTextColor.BLUE, TextDecoration.BOLD)));
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
        for (UUID id : session.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    private void returnBall(Session session, String what) {
        if (session.ball == null || session.tick - session.lastReset < OUT_RESET_COOLDOWN_TICKS) return;
        session.lastReset = session.tick;
        Player owner = Bukkit.getPlayer(session.owner);
        if (session.mode == Mode.DODGE) {
            if (owner != null) resetRun(session, owner);
        } else {
            session.ball.teleport(session.stadium.center.clone());
            session.ball.setVelocity(new Vector(0, 0, 0));
            session.lastVelocity = null;
        }
        for (UUID id : session.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(Component.text("The ball " + what + ".", NamedTextColor.YELLOW));
        }
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

    private void faceGoal(Stadium stadium, Location spot) {
        Location goal = goalCentre(stadium);
        if (goal == null) return;
        Vector direction = goal.toVector().subtract(spot.toVector()).setY(0);
        if (direction.lengthSquared() > 1.0E-4) spot.setDirection(direction);
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
        double length = Math.max(8.0, halfLength + start.toVector().subtract(centre.toVector()).length()); // start to goal
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

    /**
     * Bedrock players can't see block displays, and particles linger on their screen too long to follow a moving
     * obstacle. So for them the obstacles are drawn with real blocks that only they are told about (block changes sent
     * to that one player): the blocks the obstacle's box covers, moved a cell at a time as it slides, and put back to
     * what is really there when they are no longer covered. Nothing changes in the world.
     */
    private void updateBedrockBlocks(Session session, Player player) {
        double half = OBSTACLE_WIDTH / 2;
        for (Obstacle obstacle : session.obstacles) {
            // Before the run starts the blocks sit at the middle of their path; afterwards they slide.
            double x = session.running ? obstacle.centreX(session.tick) : obstacle.anchor.getX();
            double z = session.running ? obstacle.centreZ(session.tick) : obstacle.anchor.getZ();
            int y0 = (int) Math.floor(obstacle.anchor.getY() + 1.0E-6);
            int y1 = (int) Math.floor(obstacle.anchor.getY() + OBSTACLE_HEIGHT - 1.0E-6);

            Set<Cell> target = new HashSet<>();
            for (int cx = (int) Math.floor(x - half); cx <= (int) Math.floor(x + half - 1.0E-6); cx++) {
                for (int cz = (int) Math.floor(z - half); cz <= (int) Math.floor(z + half - 1.0E-6); cz++) {
                    for (int cy = y0; cy <= y1; cy++) target.add(new Cell(cx, cy, cz));
                }
            }
            for (Cell cell : target) {
                if (!obstacle.shown.contains(cell)) player.sendBlockChange(cell.at(session.world), obstacleBlock);
            }
            for (Cell cell : obstacle.shown) {
                if (!target.contains(cell)) restoreCell(session, player, cell);
            }
            obstacle.shown.clear();
            obstacle.shown.addAll(target);
        }
    }

    /** Tells the player what is really in that spot again. */
    private void restoreCell(Session session, Player player, Cell cell) {
        Location at = cell.at(session.world);
        player.sendBlockChange(at, at.getBlock().getBlockData());
    }

    private void removeObstacles(Session session) {
        Player owner = session.bedrock ? Bukkit.getPlayer(session.owner) : null;
        for (Obstacle obstacle : session.obstacles) {
            if (owner != null) {
                for (Cell cell : obstacle.shown) restoreCell(session, owner, cell);
            }
            obstacle.shown.clear();
            obstacle.display.remove();
        }
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

    /**
     * Whether the player or the ball is touching an obstacle: returns what to tell the player ("You touched an
     * obstacle" / "The ball touched an obstacle"), or null if neither is. The block is drawn a tick or two behind where
     * it really is (it glides to each new spot), so where it was recently counts too, and the hit box is taller than the
     * block, so a slope or a jump can't get past it.
     */
    private String obstacleHit(Session session, Player player) {
        BoundingBox body = player.getBoundingBox().expand(0.1);
        BoundingBox ball = session.ball == null ? null : session.ball.getBoundingBox().expand(0.05);
        if (session.bedrock) {
            // A Bedrock player sees (and is stopped by) whole blocks, so touching one of those is what counts.
            for (Obstacle obstacle : session.obstacles) {
                for (Cell cell : obstacle.shown) {
                    BoundingBox block = new BoundingBox(cell.x(), cell.y(), cell.z(), cell.x() + 1, cell.y() + 1, cell.z() + 1);
                    if (block.overlaps(body)) return "You touched an obstacle";
                    if (ball != null && block.overlaps(ball)) return "The ball touched an obstacle";
                }
            }
            return null;
        }
        for (Obstacle obstacle : session.obstacles) {
            for (int back = 0; back <= 2; back++) {
                BoundingBox hitBox = obstacle.hitBox(session.tick - back);
                if (hitBox.overlaps(body)) return "You touched an obstacle";
                if (ball != null && hitBox.overlaps(ball)) return "The ball touched an obstacle";
            }
        }
        return null;
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
