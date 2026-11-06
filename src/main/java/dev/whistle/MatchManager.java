package dev.whistle;

import dev.whistle.Match.Team;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Handles /match, the invite GUI, invites and the football itself. */
final class MatchManager implements CommandExecutor, TabCompleter, Listener {

    private static final long INVITE_TIMEOUT_MS = 60_000;
    private static final int GOLDEN_GOAL_SECONDS = 5 * 60; // the extra period after a draw: the next goal wins
    private static final int COUNTDOWN_SECONDS = 5;   // length of every freeze (start and after a goal)
    // Limits for the timings menu (Match Setup > clock).
    private static final int MIN_MATCH_MINUTES = 2;        // total match length: 2, 4, 6 ... minutes (split into two halves)
    private static final int MAX_MATCH_MINUTES = 90;
    private static final int MATCH_STEP_MINUTES = 2;
    private static final int MIN_HALF_TIME_SECONDS = 30;   // half time: 30 seconds, 1 minute, 1:30 ...
    private static final int MAX_HALF_TIME_SECONDS = 600;
    private static final int HALF_TIME_STEP_SECONDS = 30;
    private static final List<String> SUBCOMMANDS =
            List.of("create", "invite", "stadium", "start", "goal", "end", "leave", "accept", "decline", "watch", "sub", "cards");

    /** team == null: a referee invite. substitute: the invite is for the team's bench rather than its main players. */
    private record Invite(UUID host, Team team, long time, boolean substitute) {}

    /**
     * TEAMS > TEAM_ROLE (Main / Substitute) > PLAYERS (invite) or SUBS (the bench) > SUB_OUT (who comes off).
     * SUB_TEAMS is the shortcut referees get from /match sub.
     */
    private enum MenuType { MAIN, TIMINGS, TEAMS, TEAM_ROLE, SUB_TEAMS, STADIUMS, PLAYERS, SUBS, SUB_OUT, CARDS }

    /** The cards a referee can show. Two yellows are a red. */
    private enum Card { YELLOW, RED }

    /** Marks one of our chest menus so clicks can be recognised, and remembers which one it is. */
    private static final class MenuGui implements InventoryHolder {
        private final MenuType type;
        private final int page;   // 1-based, only used by the paged menus
        private final int pages;  // total pages, only used by the paged menus
        private final Team team;  // which team the player menu invites to
        private final boolean substitute; // PLAYERS: invite as a substitute rather than a main player
        private final UUID incoming;      // SUB_OUT: the substitute who is coming on
        private final Card card;          // CARDS: which card is being shown
        private Inventory inventory;

        private MenuGui(MenuType type, int page, int pages, Team team) {
            this(type, page, pages, team, false, null);
        }

        private MenuGui(MenuType type, int page, int pages, Team team, boolean substitute, UUID incoming) {
            this(type, page, pages, team, substitute, incoming, null);
        }

        private MenuGui(MenuType type, int page, int pages, Team team, boolean substitute, UUID incoming, Card card) {
            this.card = card;
            this.type = type;
            this.page = page;
            this.pages = pages;
            this.team = team;
            this.substitute = substitute;
            this.incoming = incoming;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // Slot layout (all menus are 3 rows).
    // Match Setup: Cancel, Invite, Timings (the clock, in the middle), Stadium, Start - every other slot of the middle row.
    private static final int SLOT_CANCEL = 9;
    private static final int SLOT_INVITE = 11;
    private static final int SLOT_TIMINGS = 13;
    private static final int SLOT_STADIUM = 15;
    private static final int SLOT_START = 17;
    // The invite menu: Red, Invite Server, Referees, Blue, evenly spaced.
    private static final int SLOT_RED_TEAM = 10;
    private static final int SLOT_SERVER_INVITE = 12;
    private static final int SLOT_REFEREE = 14;
    private static final int SLOT_BLUE_TEAM = 16;
    // Two-choice menus (Main / Substitute, and Red / Blue for referees).
    private static final int SLOT_LEFT = 11;
    private static final int SLOT_RIGHT = 15;
    private static final int SLOT_INVITE_SUB = 18;
    private static final int SLOT_CARD_RED_ROW = 0;   // cards menu: Red team's heads start here (5 slots)
    private static final int SLOT_CARD_BLUE_ROW = 18; // and Blue team's here
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_PAGE = 22;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;   // two rows of heads
    private static final int MAX_PAGES = 64;   // the page number is the banner's stack size

    private final JavaPlugin plugin;
    private final DiscordAnnouncer discord;
    private final StadiumManager stadiums;
    private final NamespacedKey ballKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey jerseyKey;
    private final NamespacedKey stadiumKey;
    private final NamespacedKey refereeWhistleKey;
    private final NamespacedKey cardKey;
    private Supplier<ItemStack> whistleSupplier; // the plugin's Whistle item, which referees are given
    private final Map<UUID, Match> matchByPlayer = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    private static final String RED_SCOREBOARD_TEAM = "bbfl_red";
    private static final String BLUE_SCOREBOARD_TEAM = "bbfl_blue";

    /** The scoreboard team a player was on before the match, put back afterwards. */
    private final Map<UUID, String> previousScoreboardTeams = new HashMap<>();

    /** Name tag colours go through TAB when it is installed, because TAB overrides scoreboard teams. */
    private final TabNameTags tabNames;

    /** Set after construction; quickmatch lobbies reserve stadiums and hold players who haven't joined yet. */
    private QuickMatchManager quick;

    void setQuickMatches(QuickMatchManager quick) {
        this.quick = quick;
    }

    private TeamPrefixes prefixes;
    private SpectatorManager spectators;
    private SmashManager smash;
    private OneBounceManager oneBounce;

    void setOneBounce(OneBounceManager oneBounce) {
        this.oneBounce = oneBounce;
    }

    private ColorCoverManager colorCover;

    void setColorCover(ColorCoverManager colorCover) {
        this.colorCover = colorCover;
    }

    private final List<LobbyBall> lobbyBalls = new ArrayList<>();

    /** A lobby's football (the minigames lobby and the quickmatch lobby each have one). */
    void addLobbyBall(LobbyBall ball) {
        lobbyBalls.add(ball);
    }

    private MinigamesLobbyManager minigamesLobby;

    void setMinigamesLobby(MinigamesLobbyManager minigamesLobby) {
        this.minigamesLobby = minigamesLobby;
    }

    /** True if the player is in the minigames lobby. */
    boolean inLobby(UUID id) {
        return minigamesLobby != null && minigamesLobby.isInLobby(id);
    }

    private TrainingManager training;

    void setTraining(TrainingManager training) {
        this.training = training;
    }

    /** True if nothing is using this stadium: no match, no quickmatch lobby that reserved it, no training session. */
    boolean stadiumFree(Stadium stadium) {
        return !stadiumInUse(stadium) && (quick == null || !quick.isReserved(stadium));
    }

    private RouletteManager roulette;

    void setRoulette(RouletteManager roulette) {
        this.roulette = roulette;
    }

    /** True while this match still has players (it has not ended or been cancelled). */
    boolean isLive(Match match) {
        return matchByPlayer.containsValue(match);
    }

    /** True if both players are playing in the same match. */
    boolean inSameMatch(Player a, Player b) {
        Match match = matchByPlayer.get(a.getUniqueId());
        return match != null && match == matchByPlayer.get(b.getUniqueId());
    }

    private HotPotatoManager hotPotato;

    void setHotPotato(HotPotatoManager hotPotato) {
        this.hotPotato = hotPotato;
    }
    private Celebration celebration;

    void setCelebration(Celebration celebration) {
        this.celebration = celebration;
    }

    void setSmash(SmashManager smash) {
        this.smash = smash;
    }

    void setWhistle(Supplier<ItemStack> whistleSupplier) {
        this.whistleSupplier = whistleSupplier;
    }

    void setSpectators(SpectatorManager spectators) {
        this.spectators = spectators;
    }

    /** Matches that have started and have a stadium, so can be watched. */
    List<Match> runningMatches() {
        return new LinkedHashSet<>(matchByPlayer.values()).stream()
                .filter(m -> m.started && m.stadium != null).toList();
    }

    boolean isWatching(UUID id) {
        return spectators != null && spectators.isWatching(id);
    }

    void setPrefixes(TeamPrefixes prefixes) {
        this.prefixes = prefixes;
    }

    MatchManager(JavaPlugin plugin, StadiumManager stadiums) {
        this.plugin = plugin;
        this.tabNames = new TabNameTags(plugin);
        removeScoreboardTeams(); // leftovers from a crash or a reload
        this.stadiums = stadiums;
        this.discord = new DiscordAnnouncer(plugin);
        this.ballKey = new NamespacedKey(plugin, "football");
        this.refereeWhistleKey = new NamespacedKey(plugin, "referee_whistle");
        this.cardKey = new NamespacedKey(plugin, "referee_card");
        this.targetKey = new NamespacedKey(plugin, "invite_target");
        this.jerseyKey = new NamespacedKey(plugin, "jersey");
        this.stadiumKey = new NamespacedKey(plugin, "stadium_choice");

        // Checks for the ball entering a goal area or hitting the bounds every tick (a hard kick moves ~1.5 blocks a tick).
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkGoals, 1L, 1L);

        // Balls are persistent, so one can outlive a crash, reload or lost game and sit around as a second, untracked
        // ball. Nothing is running yet when the plugin starts, so every ball already loaded is a leftover.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (SulfurCube cube : world.getEntitiesByClass(SulfurCube.class)) {
                    if (isBall(cube) && !isTrackedBall(cube)) cube.remove();
                }
            }
        });
    }

    /** True if a match, Smash, One-Bounce or Color Cover game is currently using this ball. */
    private boolean isTrackedBall(Entity ball) {
        return matchOfBall(ball) != null
                || (smash != null && smash.ownsBall(ball))
                || (oneBounce != null && oneBounce.ownsBall(ball))
                || (colorCover != null && colorCover.ownsBall(ball))
                || (hotPotato != null && hotPotato.ownsBall(ball))
                || (training != null && training.ownsBall(ball))
                || lobbyBalls.stream().anyMatch(b -> b.owns(ball));
    }

    /** Removes any of our balls near this spot that no game is using, before a fresh one is spawned there. */
    void removeStrayBalls(Location around) {
        for (Entity entity : around.getWorld().getNearbyEntities(around, 64, 64, 64)) {
            if (isBall(entity) && !isTrackedBall(entity)) entity.remove();
        }
    }

    /** Leftover balls in chunks that load later (after a restart or when someone walks near) are cleaned up too. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (isBall(entity) && !isTrackedBall(entity)) entity.remove();
        }
    }

    /** Removes every ball on shutdown so none are left behind. */
    void shutdown() {
        if (spectators != null) spectators.shutdown();
        if (smash != null) smash.shutdown();
        if (oneBounce != null) oneBounce.shutdown();
        if (colorCover != null) colorCover.shutdown();
        if (hotPotato != null) hotPotato.shutdown();
        if (roulette != null) roulette.shutdown();
        if (training != null) training.shutdown();
        lobbyBalls.forEach(LobbyBall::shutdown);
        new ArrayList<>(matchByPlayer.values()).forEach(m -> {
            cancelTasks(m);
            m.onlineAudience().forEach(p -> restoreChestplate(m, p));
            if (m.ball != null) m.ball.remove();
        });
        removeScoreboardTeams();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            error(player, "Usage: /match <create|invite|stadium|start|goal|sub|cards|end|leave|accept|decline|watch>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "invite" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("server")) {
                    // /match invite server: invite the whole Discord server (operators only).
                    if (!player.hasPermission("wfsbbfl.match.invite.server")) {
                        error(player, "Only operators can invite the whole server.");
                    } else {
                        discord.inviteServer(player, hostedMatchQuietly(player));
                    }
                } else if (args.length >= 2) {
                    error(player, "Usage: /match invite" + (player.hasPermission("wfsbbfl.match.invite.server") ? " [server]" : ""));
                } else {
                    Match match = hostedMatch(player);
                    if (match != null) openTeamMenu(player);
                }
            }
            case "stadium" -> chooseStadium(player, args);
            case "start" -> start(player);
            case "goal" -> goal(player, args);
            case "sub" -> substituteCommand(player, args);
            case "cards" -> cardsCommand(player);
            case "end" -> {
                Match match = hostedMatch(player);
                if (match != null) endMatch(match, "The match was ended by the host.");
            }
            case "watch" -> {
                if (spectators != null) spectators.watch(player, args);
            }
            case "leave" -> leave(player);
            case "accept" -> accept(player);
            case "decline" -> decline(player);
            default -> error(player, "Unknown subcommand. Use /match <create|invite|stadium|start|goal|sub|cards|end|leave|accept|decline|watch>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args[0].equalsIgnoreCase("watch") && spectators != null) {
            return spectators.completions(args);
        }
        if (args[0].equalsIgnoreCase("sub") && sender instanceof Player subSender && (args.length == 2 || args.length == 3)) {
            Match match = officialMatchQuiet(subSender);
            if (match == null) return List.of();
            List<String> names = new ArrayList<>();
            for (Team team : Team.values()) {
                List<UUID> ids = new ArrayList<>(args.length == 2 ? match.subs(team) : match.teamSet(team));
                if (args.length == 2) {
                    match.sentOff.forEach((offId, offTeam) -> { if (offTeam == team) ids.add(offId); });
                }
                for (UUID id : ids) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.getName().toLowerCase().startsWith(args[args.length - 1].toLowerCase())) names.add(p.getName());
                }
            }
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("goal")) {
            return List.of("red", "blue", "remove");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("goal") && args[1].equalsIgnoreCase("remove")) {
            return List.of("red", "blue");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("invite") && sender.hasPermission("wfsbbfl.match.invite.server")) {
            return "server".startsWith(args[1].toLowerCase()) ? List.of("server") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stadium")) {
            return stadiums.names().stream().filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create") && sender.hasPermission("wfsbbfl.bypass")) {
            return List.of("bypass");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("goal") && !args[1].equalsIgnoreCase("remove")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }

    private void create(Player host, String[] args) {
        if (!host.hasPermission("wfsbbfl.match.create")) {
            error(host, "Only operators can start league matches. Use /votematch <stadium> [minutes] to vote for a quickmatch.");
            return;
        }
        boolean bypass = false;
        if (args.length >= 2) {
            if (!args[1].equalsIgnoreCase("bypass")) {
                error(host, "Usage: /match create" + (host.hasPermission("wfsbbfl.bypass") ? " [bypass]" : ""));
                return;
            }
            if (!host.hasPermission("wfsbbfl.bypass")) {
                error(host, "You don't have permission to bypass.");
                return;
            }
            bypass = true;
        }

        if (matchByPlayer.containsKey(host.getUniqueId())) {
            error(host, "You are already in a match! Use /match leave first.");
            return;
        }
        if (isWatching(host.getUniqueId()) || (smash != null && smash.isPlaying(host.getUniqueId()))
                || (oneBounce != null && oneBounce.isPlaying(host.getUniqueId()))
                || (colorCover != null && colorCover.isPlaying(host.getUniqueId()))
                || (hotPotato != null && hotPotato.isPlaying(host.getUniqueId()))
                || (training != null && training.isTraining(host.getUniqueId()))) {
            error(host, "You are watching or playing something. Use /match leave first.");
            return;
        }
        if (stadiums.names().isEmpty()) {
            error(host, "There are no stadiums yet. A builder needs to make one with /stadium create first.");
            return;
        }
        boolean othersOnline = Bukkit.getOnlinePlayers().stream()
                .anyMatch(p -> !p.getUniqueId().equals(host.getUniqueId()));
        if (!othersOnline && !bypass) {
            error(host, "There are no other players online!");
            return;
        }

        Match match = new Match(host.getUniqueId());
        match.bypass = bypass;
        matchByPlayer.put(host.getUniqueId(), match);
        host.sendMessage(Component.text("Match created! You are on the ", NamedTextColor.GREEN)
                .append(Component.text("Red", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" team. Use the menu to choose a stadium, invite players and start.", NamedTextColor.GREEN)));

        openMainMenu(host);
    }

    private void start(Player host) {
        Match match = hostedMatch(host);
        if (match == null) return;
        beginMatch(host, match);
    }

    /** Why the match can't start yet, or null if it is ready. */
    private String startProblem(Match match) {
        if (match.stadium == null) {
            return "You must choose a stadium before starting (Change Stadium, or /match stadium <name>).";
        }
        if (!match.stadium.isComplete()) {
            return "The stadium " + match.stadium.name + " isn't finished (still needs: "
                    + String.join(", ", match.stadium.missingParts()) + "). Choose another stadium.";
        }
        if (!match.started && (stadiumInUse(match.stadium) || (quick != null && quick.isReserved(match.stadium)))) {
            return "The stadium " + match.stadium.name + " is being used by another match right now.";
        }
        int red = match.size(Team.RED);
        int blue = match.size(Team.BLUE);
        if (!match.started && !match.bypass && (red == 0 || blue == 0)) {
            return "Not enough players! Each team needs at least one player (Red " + red + ", Blue " + blue
                    + "). Invite players and wait for them to accept.";
        }
        return null;
    }

    /** Starts the match (or moves the ball if it is already running). Returns false if it can't start yet. */
    private boolean beginMatch(Player host, Match match) {
        String problem = startProblem(match);
        if (problem != null) {
            error(host, problem);
            return false;
        }

        boolean firstStart = !match.started;
        match.started = true;
        match.ballStart = host.getLocation().clone();
        spawnBall(match, host.getLocation());
        match.pausedVelocity = null; // a new ball: nothing to give back when play resumes
        if (isFrozen(match)) freezeBall(match);

        if (firstStart) {
            for (Player p : match.onlineMembers()) {
                match.startPositions.put(p.getUniqueId(), p.getLocation().clone());
                match.showBar(p);
                giveJersey(match, p);
            }
            for (Player sideline : match.onlineAudience()) {
                if (!match.isSideline(sideline.getUniqueId())) continue; // referees and substitutes
                match.showBar(sideline);
                if (spectators != null) spectators.addSideline(sideline, match); // the stadium is known now
            }
            match.broadcast(Component.text("The match has started!", NamedTextColor.GREEN, TextDecoration.BOLD));
            startTimer(match);
        } else {
            host.sendMessage(Component.text("The ball was moved to your location.", NamedTextColor.YELLOW));
        }
        return true;
    }

    /** Playing in a football match or in a Smash game. */
    boolean isInMatch(UUID id) {
        return matchByPlayer.containsKey(id) || (smash != null && smash.isPlaying(id))
                || (oneBounce != null && oneBounce.isPlaying(id))
                || (colorCover != null && colorCover.isPlaying(id))
                || (hotPotato != null && hotPotato.isPlaying(id))
                || (training != null && training.isTraining(id));
    }

    /** True if a running match is being played on this stadium. */
    boolean stadiumInUse(Stadium stadium) {
        return matchByPlayer.values().stream().anyMatch(m -> m.started && m.stadium == stadium)
                || (training != null && training.isUsing(stadium));
    }

    /**
     * Starts a quickmatch for these players: teams are shuffled and balanced, then the normal 5 second frozen
     * start countdown runs. Returns false if it can't start (fewer than 2 players, or the stadium is taken).
     */
    boolean startQuickMatch(Stadium stadium, int minutes, List<Player> players, Map<UUID, Location> origins) {
        return launchQuickMatch(stadium, minutes, players, origins, false, false);
    }

    /**
     * Starts a duel: a 5 minute quickmatch between exactly two players on a random finished stadium nobody is
     * using. Returns null on success, or a message saying why it couldn't start.
     */
    String startDuel(Player a, Player b) {
        Stadium stadium = pickFreeStadium();
        if (stadium == null) return "No finished stadium is free right now, so the duel can't start.";
        boolean started = launchQuickMatch(stadium, 5, List.of(a, b), Map.of(), true, false);
        return started ? null : "The duel couldn't start.";
    }

    /**
     * Starts Roulette: a 5 minute quickmatch with teams on a random finished stadium nobody is using, where everyone
     * gets a random power every 30 seconds. Returns null on success, or a message saying why it couldn't start.
     */
    String startRoulette(List<Player> players) {
        if (players.size() < 2) return "Roulette needs at least 2 players, even with bypass.";
        if (players.size() > RouletteManager.MAX_PLAYERS) {
            return "Roulette can have at most " + RouletteManager.MAX_PLAYERS + " players.";
        }
        Stadium stadium = pickFreeStadium();
        if (stadium == null) return "No finished stadium is free right now, so Roulette can't start.";
        boolean started = launchQuickMatch(stadium, RouletteManager.MINUTES, players, Map.of(), false, true);
        return started ? null : "Roulette couldn't start.";
    }

    /** A random finished stadium that no match is on and no quickmatch lobby has reserved, or null. */
    private Stadium pickFreeStadium() {
        List<Stadium> free = new ArrayList<>();
        for (String name : stadiums.names()) {
            Stadium stadium = stadiums.get(name);
            if (stadium != null && stadium.isComplete() && !stadiumInUse(stadium)
                    && (quick == null || !quick.isReserved(stadium))) {
                free.add(stadium);
            }
        }
        return free.isEmpty() ? null : free.get(new java.util.Random().nextInt(free.size()));
    }

    private boolean launchQuickMatch(Stadium stadium, int minutes, List<Player> players, Map<UUID, Location> origins,
                                     boolean duel, boolean rouletteMode) {
        List<Player> ready = new ArrayList<>(players.stream()
                .filter(p -> p.isOnline() && !matchByPlayer.containsKey(p.getUniqueId())).toList());
        if (ready.size() < 2 || !stadium.isComplete() || stadiumInUse(stadium)) return false;

        Match match = new Match();
        match.stadium = stadium;
        match.playSeconds = minutes * 60;
        match.started = true;
        match.roulette = rouletteMode;
        match.ballStart = stadium.center.clone();

        Collections.shuffle(ready);
        if (duel) match.setDuel(ready.get(0).getName(), ready.get(1).getName());
        for (int i = 0; i < ready.size(); i++) {
            Team team = i % 2 == 0 ? Team.RED : Team.BLUE;
            Player p = ready.get(i);
            match.add(p.getUniqueId(), team);
            matchByPlayer.put(p.getUniqueId(), match);
            match.startPositions.put(p.getUniqueId(), origins.getOrDefault(p.getUniqueId(), p.getLocation()).clone());
            match.showBar(p);
            giveJersey(match, p);
            if (duel) {
                Player opponent = ready.get(1 - i);
                p.sendMessage(Component.text("The duel has started on " + stadium.name + "! You are playing against ",
                                NamedTextColor.GREEN)
                        .append(Component.text(opponent.getName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                        .append(Component.text(".", NamedTextColor.GREEN)));
            } else if (rouletteMode) {
                p.sendMessage(Component.text("Roulette has started! You are on the ", NamedTextColor.GREEN)
                        .append(Component.text(team.label, team.color, TextDecoration.BOLD))
                        .append(Component.text(" team. Every 30 seconds everyone gets a random power: some help, some hurt!",
                                NamedTextColor.GREEN)));
            } else {
                p.sendMessage(Component.text("The quickmatch has started! You are on the ", NamedTextColor.GREEN)
                        .append(Component.text(team.label, team.color, TextDecoration.BOLD))
                        .append(Component.text(" team.", NamedTextColor.GREEN)));
            }
        }
        startTimer(match);
        if (rouletteMode && roulette != null) roulette.register(match);
        return true;
    }

    /** Cancels a match that hasn't started: no overview, no announcements except to anyone who had already joined. */
    private void cancelMatch(Match match) {
        announceToDiscord(match, true);
        cancelTasks(match);
        if (match.ball != null) match.ball.remove();
        for (Player p : match.onlineAudience()) {
            restoreChestplate(match, p);
            leaveStands(p, true);
            match.hideBar(p);
            if (!p.getUniqueId().equals(match.host)) {
                p.sendMessage(Component.text("The host cancelled the match.", NamedTextColor.YELLOW));
            }
        }
        for (UUID id : new ArrayList<>(matchByPlayer.keySet())) {
            if (matchByPlayer.get(id) == match) matchByPlayer.remove(id);
        }
        invites.values().removeIf(invite -> invite.host().equals(match.host));
    }

    /** The match this player hosts or referees, with an error message if they do neither. */
    private Match officialMatch(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You don't have a match. Use /match create first.");
            return null;
        }
        if (!player.getUniqueId().equals(match.host) && !match.referees.contains(player.getUniqueId())) {
            error(player, "Only the match host or a referee can do that.");
            return null;
        }
        return match;
    }

    /** /match goal <red|blue> [player] adds a goal; /match goal remove <red|blue> takes one away. Host and referees. */
    private void goal(Player official, String[] args) {
        Match match = officialMatch(official);
        if (match == null) return;
        if (!match.started) {
            error(official, "The match hasn't started yet.");
            return;
        }
        boolean remove = args.length >= 2 && args[1].equalsIgnoreCase("remove");
        int teamArg = remove ? 2 : 1;
        if (args.length <= teamArg
                || !(args[teamArg].equalsIgnoreCase("red") || args[teamArg].equalsIgnoreCase("blue"))) {
            error(official, "Usage: /match goal <red|blue> [player]   or   /match goal remove <red|blue>");
            return;
        }
        Team team = args[teamArg].equalsIgnoreCase("red") ? Team.RED : Team.BLUE;

        if (remove) {
            removeGoal(match, team, official);
            return;
        }
        if (match.paused) {
            error(official, "Play is paused. Resume it with the whistle first.");
            return;
        }
        if (match.phase == Match.Phase.COUNTDOWN) {
            error(official, "You can't score during a countdown.");
            return;
        }
        if (match.referees.contains(official.getUniqueId())) {
            // A referee's goal says who added it (and still names the scorer if the referee gave one).
            String subtitle = args.length > teamArg + 1
                    ? "Scored by " + args[teamArg + 1] + " (added by " + official.getName() + ")"
                    : "Added by " + official.getName();
            scoreGoalWithSubtitle(match, team, subtitle);
            return;
        }
        String scorer = args.length > teamArg + 1 ? args[teamArg + 1] : lastScorerName(match);
        scoreGoal(match, team, scorer);
    }

    private void removeGoal(Match match, Team team, Player official) {
        int score = team == Team.RED ? match.redScore : match.blueScore;
        if (score <= 0) {
            error(official, "The " + team.label + " team has no goals to remove.");
            return;
        }
        match.removeGoal(team);
        Component message = Component.text(official.getName() + " removed a goal from the ", NamedTextColor.YELLOW)
                .append(Component.text(team.label + " team", team.color))
                .append(Component.text(". Score: " + match.redScore + " - " + match.blueScore, NamedTextColor.YELLOW));
        for (Player p : everyoneIn(match)) p.sendMessage(message);
    }

    /** Whoever last kicked the ball, otherwise the host. */
    private String lastScorerName(Match match) {
        UUID id = match.lastKicker != null ? match.lastKicker : match.host;
        if (id == null) return "Unknown";
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : "Unknown";
    }

    /** Adds the goal, shows the title and starts the 5 second freeze. */
    private void scoreGoal(Match match, Team team, String scorer) {
        scoreGoalWithSubtitle(match, team, "Scored by " + scorer);
    }

    /** As scoreGoal, but with the exact text for the goal title's subtitle (referees' goals say who added them). */
    private void scoreGoalWithSubtitle(Match match, Team team, String subtitleText) {
        if (match.phase == Match.Phase.COUNTDOWN) return;
        match.addGoal(team);

        // The title stays up for the whole freeze; the countdown itself runs on the action bar.
        Component headline = match.duel
                ? Component.text("Goal!", NamedTextColor.GOLD, TextDecoration.BOLD)
                : Component.text(team.label + " Goal!", team.color, TextDecoration.BOLD);
        Title title = Title.title(
                headline,
                Component.text(subtitleText, NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500)));
        for (Player p : match.onlineAudience()) {
            p.showTitle(title);
        }

        if (match.goldenGoal) { // the golden goal wins the match on the spot
            endMatch(match, "Golden goal! " + (match.redScore > match.blueScore ? "Red" : "Blue") + " team wins!");
            return;
        }
        beginCountdown(match, Match.CountdownType.GOAL, match.phase);
    }

    /** Detects the ball entering a goal area of the match's stadium. */
    private void checkGoals() {
        for (Match match : new LinkedHashSet<>(matchByPlayer.values())) {
            if (!match.started || match.phase == Match.Phase.COUNTDOWN || match.paused || match.stadium == null) continue;
            SulfurCube ball = match.ball;
            if (ball == null || !ball.isValid()) continue;

            Location ballCenter = ball.getBoundingBox().getCenter().toLocation(ball.getWorld());
            boolean scored = false;
            for (Team defending : Team.values()) {
                if (match.stadium.goalContains(defending, ballCenter)) {
                    Team scoring = defending == Team.RED ? Team.BLUE : Team.RED;
                    scoreGoal(match, scoring, lastScorerName(match));
                    scored = true;
                    break;
                }
            }
            if (scored) continue; // goals win over the bounds, so goals just outside the bounds still count

            if (match.stadium.hasBounds()) {
                if (match.stadium.boundsContain(ballCenter)) {
                    match.lastInBounds = ball.getLocation().clone();
                    match.lastVelocity = ball.getVelocity().clone();
                } else {
                    bounceOffBounds(match, ball, ballCenter);
                }
            }
        }
    }

    // How the wall bounces the ball: the faster it hits, the bigger the share of its speed it keeps (but always "slight").
    static final double BOUNCE_MIN = 0.10;           // share of the impact speed kept by a very slow hit
    static final double BOUNCE_PER_SPEED = 0.30;     // extra share kept per block/tick of impact speed
    static final double BOUNCE_MAX = 0.55;           // the most that is ever kept
    static final double BOUNCE_MIN_SPEED = 0.05;     // a gentle push back in, so the ball never sticks to the wall
    static final double WALL_SLIDE_KEEP = 0.90;      // speed kept along the wall (a little friction)
    static final double WALL_INSET = 0.3;            // how far inside the edge the ball is put back
    private static final double BOUNCE_MAX_DISTANCE = 4.0;   // further out than this it was teleported, not bounced

    /** The ball crossed the bounds: put it back at the wall and reflect its velocity, softer than it arrived. */
    private void bounceOffBounds(Match match, SulfurCube ball, Location ballCenter) {
        Stadium.BoundsHit hit = match.stadium.nearestBoundsEdge(ballCenter);
        if (hit == null || hit.distance() > BOUNCE_MAX_DISTANCE) {
            returnBallToBounds(match, ball); // far outside (teleported or launched): the old "put it back" rule
            return;
        }

        Vector v = ball.getVelocity();
        if (Math.hypot(v.getX(), v.getZ()) < 0.01 && match.lastVelocity != null) v = match.lastVelocity.clone();

        // Split the velocity into the part going into the wall and the part sliding along it.
        double intoPitch = v.getX() * hit.nx() + v.getZ() * hit.nz();
        double impact = Math.max(0, -intoPitch); // how fast it hit the wall
        double slideX = v.getX() - intoPitch * hit.nx();
        double slideZ = v.getZ() - intoPitch * hit.nz();

        double restitution = Math.min(BOUNCE_MAX, BOUNCE_MIN + BOUNCE_PER_SPEED * impact);
        double bounce = Math.max(impact * restitution, BOUNCE_MIN_SPEED);

        Vector bounced = new Vector(
                slideX * WALL_SLIDE_KEEP + hit.nx() * bounce,
                v.getY(),
                slideZ * WALL_SLIDE_KEEP + hit.nz() * bounce);

        // Move the ball just inside the wall (keeping its height) so it doesn't hit the wall again next tick.
        Location target = ball.getLocation().clone();
        target.add(hit.x() + hit.nx() * WALL_INSET - ballCenter.getX(), 0,
                hit.z() + hit.nz() * WALL_INSET - ballCenter.getZ());
        ball.teleport(target);
        ball.setVelocity(bounced);
        match.lastInBounds = target.clone();
        match.lastVelocity = bounced.clone();
        announceOutOfBounds(match);
    }

    private void announceOutOfBounds(Match match) {
        for (Player p : match.onlineAudience()) {
            p.sendActionBar(Component.text("Out of bounds!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    /** The ball left the stadium bounds: put it back where it was last inside, standing still. */
    private void returnBallToBounds(Match match, SulfurCube ball) {
        Location back = match.lastInBounds;
        if (back == null && match.stadium.hasCenter()) back = match.stadium.center.clone();
        if (back == null) return;

        ball.teleport(back.clone());
        ball.setVelocity(new Vector(0, 0, 0));
        announceOutOfBounds(match);
    }

    private void chooseStadium(Player host, String[] args) {
        Match match = hostedMatch(host);
        if (match == null) return;
        if (args.length < 2) {
            error(host, "Usage: /match stadium <name>");
            return;
        }
        Stadium stadium = stadiums.get(args[1]);
        if (stadium == null) {
            error(host, "No stadium called " + args[1] + ".");
            return;
        }
        applyStadium(host, match, stadium);
    }

    private void applyStadium(Player host, Match match, Stadium stadium) {
        List<String> missing = stadium.missingParts();
        if (!missing.isEmpty()) {
            error(host, stadium.name + " isn't finished, so it can't be used yet. It still needs: "
                    + String.join(", ", missing) + ".");
            return;
        }
        match.stadium = stadium;
        host.sendMessage(Component.text("Stadium set to " + stadium.name + ".", NamedTextColor.GREEN));
    }

    /** Where the ball is placed during freezes: the stadium centre if there is one. */
    private Location ballSpawnPoint(Match match) {
        if (match.stadium != null && match.stadium.hasCenter()) return match.stadium.center.clone();
        return match.ballStart;
    }

    private void leave(Player player) {
        if (quick != null && quick.leaveLobby(player)) return; // waiting in a quickmatch lobby
        if (spectators != null && spectators.stop(player, true)) return; // watching a match
        if (smash != null && smash.leave(player)) return; // playing Smash
        if (oneBounce != null && oneBounce.leave(player)) return; // playing One-Bounce
        if (colorCover != null && colorCover.leave(player)) return; // playing Color Cover
        if (hotPotato != null && hotPotato.leave(player)) return; // playing Hot Potato
        if (training != null && training.leave(player)) return; // training
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You are not in a match.");
            return;
        }
        if (player.getUniqueId().equals(match.host)) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
            return;
        }
        removeMember(match, player);
        player.sendMessage(Component.text("You left the match.", NamedTextColor.YELLOW));
        match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
        afterMemberLeft(match);
        endQuickMatchIfEmpty(match);
    }

    /** A quickmatch can't carry on once a whole team has gone. */
    private void endQuickMatchIfEmpty(Match match) {
        if (match.quick && (match.size(Team.RED) == 0 || match.size(Team.BLUE) == 0)) {
            endMatch(match, match.duel ? "Your opponent left, so the duel was ended."
                    : "Not enough players are left, so the quickmatch was ended.");
        }
    }

    private void accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || System.currentTimeMillis() - invite.time() > INVITE_TIMEOUT_MS) {
            error(player, "You have no pending invite.");
            return;
        }
        Match match = matchByPlayer.get(invite.host());
        if (match == null || !invite.host().equals(match.host)) {
            error(player, "That match no longer exists.");
            return;
        }
        if (matchByPlayer.containsKey(player.getUniqueId())) {
            error(player, "You are already in a match!");
            return;
        }
        if (isWatching(player.getUniqueId())) {
            error(player, "You are watching a match. Use /match leave first, then accept the invite.");
            invites.put(player.getUniqueId(), invite); // keep the invite so they can still accept it
            return;
        }
        if (invite.team() == null) { // referee
            if (match.referees.size() >= Match.MAX_REFEREES) {
                error(player, "This match already has " + Match.MAX_REFEREES + " referees.");
                return;
            }
            match.referees.add(player.getUniqueId());
            matchByPlayer.put(player.getUniqueId(), match);
            giveRefereeKit(match, player);
            match.broadcast(Component.text(player.getName(), NamedTextColor.YELLOW)
                    .append(Component.text(" joined as a referee.", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("You are a referee. Blow your whistle to pause and resume play, and use "
                    + "/match goal <red|blue> or /match goal remove <red|blue> to change the score.", NamedTextColor.GREEN));
            return;
        }
        if (invite.substitute()) {
            Team team = invite.team();
            if (match.subs(team).size() >= Match.MAX_SUBS_PER_TEAM) {
                error(player, "The " + team.label + " team already has " + Match.MAX_SUBS_PER_TEAM + " substitutes.");
                return;
            }
            match.subs(team).add(player.getUniqueId());
            matchByPlayer.put(player.getUniqueId(), match);
            giveSubKit(match, player);
            match.broadcast(Component.text(player.getName(), NamedTextColor.YELLOW)
                    .append(Component.text(" joined as a ", NamedTextColor.GRAY))
                    .append(Component.text(team.label + " substitute", team.color))
                    .append(Component.text(".", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("You are a " + team.label + " substitute. Wait on the side: a referee "
                    + "will sub you on.", NamedTextColor.GREEN));
            return;
        }
        if (match.isFull(invite.team())) {
            error(player, "The " + invite.team().label + " team is full!");
            return;
        }

        match.add(player.getUniqueId(), invite.team());
        matchByPlayer.put(player.getUniqueId(), match);
        if (match.started) {
            match.showBar(player);
            match.startPositions.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
            giveJersey(match, player);
        }

        match.broadcast(Component.text(player.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" joined the ", NamedTextColor.GRAY))
                .append(Component.text(invite.team().label + " team", invite.team().color))
                .append(Component.text(" (" + match.size(invite.team()) + "/" + Match.MAX_PER_TEAM + ")", NamedTextColor.GRAY)));
    }

    private void decline(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null) {
            error(player, "You have no pending invite.");
            return;
        }
        player.sendMessage(Component.text("Invite declined.", NamedTextColor.YELLOW));
        Player host = Bukkit.getPlayer(invite.host());
        if (host != null) {
            host.sendMessage(Component.text(player.getName() + " declined your invite.", NamedTextColor.YELLOW));
        }
    }

    /** Returns the match this player hosts, or tells them why not. */
    private Match hostedMatch(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You don't have a match. Use /match create first.");
            return null;
        }
        if (!player.getUniqueId().equals(match.host)) {
            error(player, "Only the match host can do that.");
            return null;
        }
        return match;
    }

    // ------------------------------------------------------------------ menus

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component bold(String text, NamedTextColor color) {
        return Component.text(text, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack button(Material material, String name, NamedTextColor color, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold(name, color));
        if (lore.length > 0) meta.lore(List.of(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private Inventory newMenu(MenuGui holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        return inventory;
    }

    /** The host's own match, only if it hasn't been taken away from under an open menu. */
    private Match menuMatch(Player host) {
        Match match = matchByPlayer.get(host.getUniqueId());
        if (match == null || !host.getUniqueId().equals(match.host)) return null;
        return match;
    }

    /** First menu of /match create: cancel, invite, timings, change stadium, start. */
    private void openMainMenu(Player host) {
        Match match = menuMatch(host);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.MAIN, 0, 0, null);
        Inventory inventory = newMenu(holder, "Match Setup");

        inventory.setItem(SLOT_CANCEL, button(Material.RED_CONCRETE, "Cancel Match", NamedTextColor.RED,
                plain("Cancels this match.", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_INVITE, button(Material.DIAMOND, "Invite Players", NamedTextColor.BLUE,
                plain("Red: " + match.size(Team.RED) + "/" + Match.MAX_PER_TEAM
                        + "   Blue: " + match.size(Team.BLUE) + "/" + Match.MAX_PER_TEAM, NamedTextColor.GRAY)));
        inventory.setItem(SLOT_TIMINGS, button(Material.CLOCK, "Match Timings", NamedTextColor.GOLD,
                plain("Match length: " + describeSeconds(match.playSeconds * 2), NamedTextColor.GRAY),
                plain("Half time: " + describeSeconds(match.halfTimeSeconds), NamedTextColor.GRAY),
                plain("Click to change the timings.", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_STADIUM, button(Material.BIRCH_LOG, "Change Stadium", NamedTextColor.BLUE,
                plain("Current: " + (match.stadium == null ? "none chosen" : match.stadium.name), NamedTextColor.GRAY)));
        inventory.setItem(SLOT_START, button(Material.LIME_CONCRETE, "Start Match", NamedTextColor.GREEN));
        host.openInventory(inventory);
    }

    /** "30 seconds", "1 minute", "1 minute 30 seconds", "10 minutes". */
    private static String describeSeconds(int seconds) {
        int minutes = seconds / 60;
        int rest = seconds % 60;
        String result = minutes == 0 ? "" : minutes + (minutes == 1 ? " minute" : " minutes");
        if (rest != 0) result += (result.isEmpty() ? "" : " ") + rest + (rest == 1 ? " second" : " seconds");
        return result;
    }

    /** Timings menu: total match length (the two halves share it) and the half-time break. */
    private void openTimingsMenu(Player host) {
        Match match = menuMatch(host);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.TIMINGS, 0, 0, null);
        Inventory inventory = newMenu(holder, "Match Timings");
        inventory.setItem(SLOT_LEFT, matchLengthItem(match));
        inventory.setItem(SLOT_RIGHT, halfTimeItem(match));
        inventory.setItem(SLOT_BACK, backButton());
        host.openInventory(inventory);
    }

    private ItemStack matchLengthItem(Match match) {
        return button(Material.CLOCK, "Total Match Length", NamedTextColor.GOLD,
                plain("Match length: " + describeSeconds(match.playSeconds * 2), NamedTextColor.YELLOW),
                plain("Each half: " + describeSeconds(match.playSeconds), NamedTextColor.GRAY),
                plain("Left click: +" + MATCH_STEP_MINUTES + " minutes", NamedTextColor.GREEN),
                plain("Right click: -" + MATCH_STEP_MINUTES + " minutes", NamedTextColor.RED));
    }

    private ItemStack halfTimeItem(Match match) {
        return button(Material.GRAY_CUSHION, "Half Time Length", NamedTextColor.GOLD,
                plain("Half time: " + describeSeconds(match.halfTimeSeconds), NamedTextColor.YELLOW),
                plain("Players can move freely until the last 5 seconds.", NamedTextColor.GRAY),
                plain("Left click: +" + HALF_TIME_STEP_SECONDS + " seconds", NamedTextColor.GREEN),
                plain("Right click: -" + HALF_TIME_STEP_SECONDS + " seconds", NamedTextColor.RED));
    }

    /** Invite menu: pick which team to invite players to. */
    private void openTeamMenu(Player host) {
        Match match = menuMatch(host);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.TEAMS, 0, 0, null);
        Inventory inventory = newMenu(holder, "Choose Team");
        inventory.setItem(SLOT_RED_TEAM, teamItem(match, Team.RED));
        if (host.hasPermission("wfsbbfl.match.invite.server")) {
            // A goat horn: the call that gets everyone's attention.
            inventory.setItem(SLOT_SERVER_INVITE, button(Material.GOAT_HORN, "Invite Server", NamedTextColor.AQUA,
                    plain("Invites the whole Discord server to this match.", NamedTextColor.GRAY),
                    plain(match.serverInvited ? "Already sent for this match. Click to send again."
                            : "Click to send the invite.", match.serverInvited ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        }
        inventory.setItem(SLOT_REFEREE, button(Material.WHITE_CANDLE, "Referees", NamedTextColor.WHITE,
                plain(match.referees.size() + "/" + Match.MAX_REFEREES + " referees", NamedTextColor.GRAY),
                plain("Click to invite a referee.", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_BLUE_TEAM, teamItem(match, Team.BLUE));
        inventory.setItem(SLOT_BACK, backButton());
        host.openInventory(inventory);
    }

    private ItemStack teamItem(Match match, Team team) {
        return teamItem(match, team, team.label + " Team", team.color,
                plain(match.size(team) + "/" + Match.MAX_PER_TEAM + " players", NamedTextColor.GRAY));
    }

    /** A chestplate in the team's colour with a custom name and lore. */
    private ItemStack teamItem(Match match, Team team, String name, NamedTextColor nameColor, Component... lore) {
        ItemStack item = button(Material.LEATHER_CHESTPLATE, name, nameColor, lore);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(team.color.value()));
        meta.addItemFlags(ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backButton() {
        return button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE);
    }

    /** Team menu, step two: Main players or Substitutes for this team. */
    private void openTeamRoleMenu(Player host, Team team) {
        Match match = menuMatch(host);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.TEAM_ROLE, 0, 0, team);
        Inventory inventory = newMenu(holder, team.label + " Team");
        inventory.setItem(SLOT_LEFT, teamItem(match, team, "Main", NamedTextColor.WHITE,
                plain(match.size(team) + "/" + Match.MAX_PER_TEAM + " players", NamedTextColor.GRAY),
                plain("Click to invite a main player.", NamedTextColor.GRAY)));
        // A bench: where the players waiting to be subbed on sit.
        inventory.setItem(SLOT_RIGHT, button(Material.OAK_STAIRS, "Substitute", team.color,
                plain(match.subs(team).size() + "/" + Match.MAX_SUBS_PER_TEAM + " substitutes", NamedTextColor.GRAY),
                plain("Click to invite or sub on a substitute.", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_BACK, backButton());
        host.openInventory(inventory);
    }

    /** For referees (and the host): pick the team whose substitutes to sub on. */
    private void openSubTeamMenu(Player player) {
        Match match = officialMatchQuiet(player);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.SUB_TEAMS, 0, 0, null);
        Inventory inventory = newMenu(holder, "Substitutions");
        inventory.setItem(SLOT_LEFT, teamItem(match, Team.RED, Team.RED.label + " Team", Team.RED.color,
                plain(match.subs(Team.RED).size() + " substitutes", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_RIGHT, teamItem(match, Team.BLUE, Team.BLUE.label + " Team", Team.BLUE.color,
                plain(match.subs(Team.BLUE).size() + " substitutes", NamedTextColor.GRAY)));
        inventory.setItem(SLOT_BACK, backButton());
        player.openInventory(inventory);
    }

    /** The stadium list, the player list for invites, a team's substitutes and the "who comes off" list share one layout. */
    private void openPagedMenu(Player host, MenuType type, int page, Team team) {
        openPagedMenu(host, type, page, team, false, null);
    }

    private void openPagedMenu(Player host, MenuType type, int page, Team team, boolean substitute, UUID incoming) {
        boolean official = type == MenuType.SUBS || type == MenuType.SUB_OUT;
        Match match = official ? officialMatchQuiet(host) : menuMatch(host);
        if (match == null) return;

        List<ItemStack> entries = new ArrayList<>();
        switch (type) {
            case STADIUMS -> {
                for (String name : stadiums.names()) {
                    Stadium stadium = stadiums.get(name);
                    if (stadium != null) entries.add(createStadiumItem(stadium));
                }
            }
            case SUBS -> {
                for (UUID id : match.subs(team)) {
                    Player sub = Bukkit.getPlayer(id);
                    if (sub == null) continue;
                    Match.Vacancy empty = match.vacancyFor(team);
                    entries.add(createHead(sub, empty == null ? "Click to sub on"
                            : "Click to take " + empty.name() + "'s place"));
                }
                // Players sent off with a red card are listed too, so a referee can bring them back on.
                for (Map.Entry<UUID, Team> sentOff : match.sentOff.entrySet()) {
                    if (sentOff.getValue() != team) continue;
                    Player off = Bukkit.getPlayer(sentOff.getKey());
                    if (off == null) continue;
                    Match.Vacancy empty = match.vacancyFor(team);
                    entries.add(createHead(off, empty != null ? "Sent off - click to take " + empty.name() + "'s place"
                            : !match.isFull(team) ? "Sent off - click to bring back on"
                            : "Sent off - click to sub on"));
                }
            }
            case SUB_OUT -> {
                for (UUID id : match.teamSet(team)) {
                    Player main = Bukkit.getPlayer(id);
                    if (main != null) entries.add(createHead(main, "Click to take off"));
                }
            }
            default -> {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (other.getUniqueId().equals(host.getUniqueId())) continue;
                    if (matchByPlayer.containsKey(other.getUniqueId())) continue;
                    entries.add(createHead(other));
                }
            }
        }

        int pages = Math.max(1, Math.min(MAX_PAGES, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));

        MenuGui holder = new MenuGui(type, page, pages, team, substitute, incoming);
        String title = switch (type) {
            case STADIUMS -> "Choose Stadium";
            case SUBS -> team.label + " Substitutes";
            case SUB_OUT -> {
                Player in = incoming == null ? null : Bukkit.getPlayer(incoming);
                yield "Take off for " + (in == null ? "the substitute" : in.getName());
            }
            default -> team == null ? "Invite a Referee"
                    : substitute ? "Invite a " + team.label + " Substitute" : "Invite to " + team.label + " Team";
        };
        Inventory inventory = newMenu(holder, title);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            inventory.setItem(i, entries.get(index));
        }

        if (type == MenuType.SUBS && host.getUniqueId().equals(match.host)) {
            inventory.setItem(SLOT_INVITE_SUB, button(Material.EMERALD, "Invite Substitute", NamedTextColor.GREEN,
                    plain("Invite a player as a " + team.label + " substitute.", NamedTextColor.GRAY)));
        }
        inventory.setItem(SLOT_PREVIOUS, arrowBanner("Previous Page", false));
        inventory.setItem(SLOT_PAGE, pageMarker(page, pages));
        inventory.setItem(SLOT_NEXT, arrowBanner("Next Page", true));
        inventory.setItem(SLOT_BACK, backButton());
        host.openInventory(inventory);
    }

    /** White banner with a red arrow: the lozenge with one half covered. */
    private ItemStack arrowBanner(String name, boolean next) {
        ItemStack item = new ItemStack(Material.WHITE_BANNER);
        BannerMeta meta = (BannerMeta) item.getItemMeta();
        meta.addPattern(new Pattern(DyeColor.RED, PatternType.RHOMBUS));
        meta.addPattern(new Pattern(DyeColor.WHITE, next ? PatternType.HALF_VERTICAL : PatternType.HALF_VERTICAL_RIGHT));
        meta.displayName(bold(name, NamedTextColor.WHITE));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    /** Paper whose stack size is the current page number. */
    private ItemStack pageMarker(int page, int pages) {
        ItemStack item = new ItemStack(Material.PAPER, page);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold("Page Number", NamedTextColor.WHITE));
        meta.lore(List.of(plain("Page " + page + " of " + pages, NamedTextColor.GRAY)));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createStadiumItem(Stadium stadium) {
        ItemStack item = new ItemStack(stadium.block != null ? stadium.block : Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold(stadium.name, NamedTextColor.GOLD));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.lore(List.of(
                statusLore("Centre point", stadium.hasCenter()),
                statusLore("Stands point", stadium.hasStands()),
                statusLore("Red goal", stadium.goalComplete(Team.RED)),
                statusLore("Blue goal", stadium.goalComplete(Team.BLUE)),
                statusLore("Bounds", stadium.hasBounds()),
                statusLore("Player positions", stadium.positionsComplete(Team.RED) && stadium.positionsComplete(Team.BLUE)),
                Component.empty(),
                stadium.isComplete() ? plain("Click to select", NamedTextColor.YELLOW)
                        : plain("Not finished: can't be used yet", NamedTextColor.RED)));
        meta.getPersistentDataContainer().set(stadiumKey, PersistentDataType.STRING, stadium.name);
        item.setItemMeta(meta);
        return item;
    }

    private Component statusLore(String label, boolean set) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(set ? Component.text("set", NamedTextColor.GREEN) : Component.text("not set", NamedTextColor.RED))
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack createHead(Player target) {
        return createHead(target, "Click to invite");
    }

    private ItemStack createHead(Player target, String action) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile());
        meta.displayName(bold(target.getName(), NamedTextColor.GOLD));
        meta.lore(List.of(plain(action, NamedTextColor.YELLOW)));
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING,
                target.getUniqueId().toString());
        head.setItemMeta(meta);
        return head;
    }

    /** Menu clicks only ever act on the next tick, never inside the click event. */
    private void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuGui menu)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player host)) return;
        if (event.getCurrentItem() == null) return;

        boolean official = menu.type == MenuType.SUB_TEAMS || menu.type == MenuType.SUBS || menu.type == MenuType.SUB_OUT
                || menu.type == MenuType.CARDS;
        Match match = official ? officialMatchQuiet(host) : menuMatch(host);
        if (match == null) {
            later(host::closeInventory);
            return;
        }

        int slot = event.getSlot();
        switch (menu.type) {
            case MAIN -> {
                if (match.started) {
                    later(host::closeInventory);
                    return;
                }
                switch (slot) {
                    case SLOT_CANCEL -> later(() -> {
                        host.closeInventory();
                        cancelMatch(match);
                    });
                    case SLOT_INVITE -> later(() -> openTeamMenu(host));
                    case SLOT_TIMINGS -> later(() -> openTimingsMenu(host));
                    case SLOT_STADIUM -> later(() -> openPagedMenu(host, MenuType.STADIUMS, 1, null));
                    case SLOT_START -> later(() -> {
                        if (beginMatch(host, match)) host.closeInventory();
                        else openMainMenu(host); // not ready: warn in chat and show the menu again
                    });
                    default -> { }
                }
            }
            case TIMINGS -> {
                if (match.started) {
                    later(host::closeInventory);
                    return;
                }
                boolean raise = !event.isRightClick(); // left click goes up, right click goes down; both wrap round
                switch (slot) {
                    case SLOT_LEFT -> {
                        int minutes = match.playSeconds * 2 / 60 + (raise ? MATCH_STEP_MINUTES : -MATCH_STEP_MINUTES);
                        if (minutes > MAX_MATCH_MINUTES) minutes = MIN_MATCH_MINUTES;
                        else if (minutes < MIN_MATCH_MINUTES) minutes = MAX_MATCH_MINUTES;
                        match.playSeconds = minutes * 60 / 2; // the two halves share the total
                        event.getView().getTopInventory().setItem(SLOT_LEFT, matchLengthItem(match));
                        host.playSound(Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.6f, 1.2f));
                    }
                    case SLOT_RIGHT -> {
                        int seconds = match.halfTimeSeconds + (raise ? HALF_TIME_STEP_SECONDS : -HALF_TIME_STEP_SECONDS);
                        if (seconds > MAX_HALF_TIME_SECONDS) seconds = MIN_HALF_TIME_SECONDS;
                        else if (seconds < MIN_HALF_TIME_SECONDS) seconds = MAX_HALF_TIME_SECONDS;
                        match.halfTimeSeconds = seconds;
                        event.getView().getTopInventory().setItem(SLOT_RIGHT, halfTimeItem(match));
                        host.playSound(Sound.sound(Key.key("ui.button.click"), Sound.Source.MASTER, 0.6f, 1.2f));
                    }
                    case SLOT_BACK -> later(() -> openMainMenu(host));
                    default -> { }
                }
            }
            case TEAMS -> {
                switch (slot) {
                    case SLOT_RED_TEAM -> later(() -> openTeamRoleMenu(host, Team.RED));
                    case SLOT_BLUE_TEAM -> later(() -> openTeamRoleMenu(host, Team.BLUE));
                    case SLOT_REFEREE -> later(() -> openPagedMenu(host, MenuType.PLAYERS, 1, null)); // null team = referee
                    case SLOT_SERVER_INVITE -> {
                        if (host.hasPermission("wfsbbfl.match.invite.server")) {
                            later(() -> {
                                discord.inviteServer(host, match);
                                openTeamMenu(host); // refreshes the "already sent" line
                            });
                        }
                    }
                    case SLOT_BACK -> later(() -> {
                        if (match.started) host.closeInventory();
                        else openMainMenu(host);
                    });
                    default -> { }
                }
            }
            case CARDS -> {
                if (slot == SLOT_BACK) {
                    later(host::closeInventory);
                    return;
                }
                ItemStack clicked = event.getCurrentItem();
                if (!clicked.hasItemMeta()) return;
                String id = clicked.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
                if (id == null) return;
                Player target = Bukkit.getPlayer(UUID.fromString(id));
                later(() -> {
                    host.closeInventory();
                    if (target == null) error(host, "That player is no longer online.");
                    else giveCard(host, match, target, menu.card);
                });
            }
            case TEAM_ROLE -> {
                switch (slot) {
                    case SLOT_LEFT -> later(() -> openPagedMenu(host, MenuType.PLAYERS, 1, menu.team, false, null)); // Main
                    case SLOT_RIGHT -> later(() -> openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null));   // Substitute
                    case SLOT_BACK -> later(() -> openTeamMenu(host));
                    default -> { }
                }
            }
            case SUB_TEAMS -> {
                switch (slot) {
                    case SLOT_LEFT -> later(() -> openPagedMenu(host, MenuType.SUBS, 1, Team.RED, false, null));
                    case SLOT_RIGHT -> later(() -> openPagedMenu(host, MenuType.SUBS, 1, Team.BLUE, false, null));
                    case SLOT_BACK -> later(host::closeInventory);
                    default -> { }
                }
            }
            case STADIUMS, PLAYERS, SUBS, SUB_OUT -> {
                if (slot == SLOT_BACK) {
                    later(() -> {
                        switch (menu.type) {
                            case PLAYERS -> {
                                if (menu.team == null) openTeamMenu(host);
                                else if (menu.substitute) openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null);
                                else openTeamRoleMenu(host, menu.team);
                            }
                            case SUBS -> {
                                if (host.getUniqueId().equals(match.host)) openTeamRoleMenu(host, menu.team);
                                else openSubTeamMenu(host);
                            }
                            case SUB_OUT -> openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null);
                            default -> openMainMenu(host);
                        }
                    });
                } else if (slot == SLOT_INVITE_SUB && menu.type == MenuType.SUBS) {
                    if (host.getUniqueId().equals(match.host)) {
                        later(() -> openPagedMenu(host, MenuType.PLAYERS, 1, menu.team, true, null));
                    }
                } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
                    int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
                    if (target >= 1 && target <= menu.pages) {
                        later(() -> openPagedMenu(host, menu.type, target, menu.team, menu.substitute, menu.incoming));
                    }
                } else if (slot < PAGE_SIZE) {
                    handleEntryClick(host, match, menu, event.getCurrentItem());
                }
            }
        }
    }

    private void handleEntryClick(Player host, Match match, MenuGui menu, ItemStack item) {
        if (!item.hasItemMeta()) return;
        var data = item.getItemMeta().getPersistentDataContainer();

        if (menu.type == MenuType.STADIUMS) {
            String choice = data.get(stadiumKey, PersistentDataType.STRING);
            if (choice == null) return;
            Stadium stadium = stadiums.get(choice);
            if (stadium == null) {
                error(host, "That stadium no longer exists.");
                return;
            }
            applyStadium(host, match, stadium);
            later(() -> openMainMenu(host));
        } else {
            String id = data.get(targetKey, PersistentDataType.STRING);
            if (id == null) return;
            Player target = Bukkit.getPlayer(UUID.fromString(id));
            if (target == null) {
                error(host, "That player is no longer online.");
                return;
            }
            if (menu.type == MenuType.SUBS) {
                if (match.vacancyFor(menu.team) != null) {
                    // A player disconnected: the substitute simply takes their place.
                    fillVacancy(host, match, menu.team, target.getUniqueId());
                    later(() -> openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null));
                } else if (match.sentOff.get(target.getUniqueId()) == menu.team && !match.isFull(menu.team)) {
                    // Sent off and the team is a player short: they just come back on, nobody has to come off.
                    returnSentOff(host, match, menu.team, target);
                    later(() -> openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null));
                } else {
                    // A substitute was picked: now choose which player comes off.
                    later(() -> openPagedMenu(host, MenuType.SUB_OUT, 1, menu.team, false, target.getUniqueId()));
                }
            } else if (menu.type == MenuType.SUB_OUT) {
                boolean done = substitute(host, match, menu.team, menu.incoming, target);
                later(() -> openPagedMenu(host, MenuType.SUBS, 1, menu.team, false, null));
                if (!done) return;
            } else {
                invite(host, target, menu.team, menu.substitute);
            }
        }
    }

    /** Pressing Escape (or the inventory key) on a menu before the match starts just opens Match Setup again. */
    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuGui)) return;
        if (event.getReason() != InventoryCloseEvent.Reason.PLAYER) return; // ignore closes made by the plugin or server
        if (!(event.getPlayer() instanceof Player host)) return;

        Match match = menuMatch(host);
        if (match == null || match.started) return;
        later(() -> {
            if (host.isOnline() && menuMatch(host) == match && !match.started) openMainMenu(host);
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuGui) {
            event.setCancelled(true);
        }
    }

    private void invite(Player host, Player target, Team team, boolean substitute) {
        Match match = matchByPlayer.get(host.getUniqueId());
        if (match == null || !host.getUniqueId().equals(match.host)) return;

        if (matchByPlayer.containsKey(target.getUniqueId())) {
            error(host, target.getName() + " is already in a match.");
            return;
        }
        if (team == null) { // a referee
            if (match.referees.size() >= Match.MAX_REFEREES) {
                error(host, "A match can have at most " + Match.MAX_REFEREES + " referees.");
                return;
            }
            invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), null, System.currentTimeMillis(), false));
            target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                    .append(Component.text(" invited you to referee a football match. ", NamedTextColor.GRAY))
                    .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/match accept")))
                    .append(Component.text(" "))
                    .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/match decline"))));
            host.sendMessage(Component.text("Invited " + target.getName() + " as a referee.", NamedTextColor.GREEN));
            return;
        }
        if (substitute) {
            if (match.subs(team).size() >= Match.MAX_SUBS_PER_TEAM) {
                error(host, "The " + team.label + " team already has " + Match.MAX_SUBS_PER_TEAM + " substitutes.");
                return;
            }
            invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), team, System.currentTimeMillis(), true));
            target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                    .append(Component.text(" invited you to be a substitute for the ", NamedTextColor.GRAY))
                    .append(Component.text(team.label + " team", team.color))
                    .append(Component.text(". ", NamedTextColor.GRAY))
                    .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/match accept")))
                    .append(Component.text(" "))
                    .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/match decline"))));
            host.sendMessage(Component.text("Invited " + target.getName() + " as a ", NamedTextColor.GREEN)
                    .append(Component.text(team.label + " substitute", team.color))
                    .append(Component.text(".", NamedTextColor.GREEN)));
            return;
        }
        if (match.isFull(team)) {
            error(host, "The " + team.label + " team is full (" + Match.MAX_PER_TEAM + "/" + Match.MAX_PER_TEAM + ").");
            return;
        }

        invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), team, System.currentTimeMillis(), false));

        target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" invited you to the ", NamedTextColor.GRAY))
                .append(Component.text(team.label + " team", team.color))
                .append(Component.text(" in a football match. ", NamedTextColor.GRAY))
                .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/match accept")))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/match decline"))));

        host.sendMessage(Component.text("Invited " + target.getName() + " to the ", NamedTextColor.GREEN)
                .append(Component.text(team.label + " team", team.color))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    // ------------------------------------------------------------------ the ball

    private void spawnBall(Match match, Location location) {
        if (match.ball != null) match.ball.remove();
        match.lastInBounds = null; // a fresh ball has not been seen inside the bounds yet

        match.ball = createBallEntity(location, Component.text("Football", NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    /** A ball (sulfur cube with no AI, marked as ours so kicks and protections apply), also used by Smash. */
    SulfurCube createBallEntity(Location location, Component name) {
        return createBallEntity(location, name, true);
    }

    /** As above; with pushable false, walking or bumping into the ball does nothing (only hits move it). */
    SulfurCube createBallEntity(Location location, Component name, boolean pushable) {
        return location.getWorld().spawn(location, SulfurCube.class, cube -> {
            cube.setCollidable(pushable);
            if (!pushable) {
                cube.setMaximumNoDamageTicks(0); // challenge balls can be tapped again straight away
                cube.setNoDamageTicks(0);
            }
            cube.setSize(2);
            cube.setAdult();
            cube.setAgeLock(true);
            cube.customName(name);
            cube.setCustomNameVisible(true);
            cube.setGlowing(true);
            cube.setAware(false);              // no AI: it only moves when kicked
            cube.setPersistent(true);
            cube.setRemoveWhenFarAway(false);
            cube.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** The velocity a kick by this player gives the ball: where they face, harder when sprinting. */
    private Vector kickVelocity(Player kicker) {
        Vector direction = kicker.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() < 1.0E-4) direction = new Vector(0, 0, 1);
        double power = (kicker.isSprinting() ? 1.5 : 1.0) * (roulette == null ? 1.0 : roulette.kickMultiplier(kicker));
        return direction.normalize().multiply(power).setY(0.35);
    }

    private boolean isBall(Entity entity) {
        return entity instanceof SulfurCube
                && entity.getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE);
    }

    private boolean isFrozen(Match match) {
        return match.started && (match.phase == Match.Phase.COUNTDOWN || match.paused);
    }

    /**
     * The half-time break before its final five seconds: the clock runs and the ball is held at the centre, but
     * players walk about freely. In the last five seconds everyone is put back on their positions and frozen.
     */
    private boolean isHalfTimeBreak(Match match) {
        return match.started && match.phase == Match.Phase.COUNTDOWN
                && match.countdownType == Match.CountdownType.HALF_TIME && match.countdownLeft > COUNTDOWN_SECONDS;
    }

    private void freezeBall(Match match) {
        if (match.ball == null) return;
        match.ball.setGravity(false);
        match.ball.setVelocity(new Vector(0, 0, 0));
    }

    private Match matchOfBall(Entity ball) {
        for (Match m : matchByPlayer.values()) {
            if (m.ball != null && m.ball.equals(ball)) return m;
        }
        return null;
    }

    /** During a countdown (and the last five seconds of half time) players can look around but can't move. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Match match = matchByPlayer.get(event.getPlayer().getUniqueId());
        if (match == null || !isFrozen(match) || isHalfTimeBreak(match)) return;
        if (match.isSideline(event.getPlayer().getUniqueId())) return; // referees and substitutes can walk around

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            event.setTo(fixed);
        }
    }

    /** The ball takes no damage; hitting it kicks it in the direction you're facing. */
    @EventHandler
    public void onBallDamage(EntityDamageEvent event) {
        if (!isBall(event.getEntity())) return;
        event.setCancelled(true);

        if (smash != null && smash.ownsBall(event.getEntity())) {
            // Smash judges every hit itself (turns, warnings, the wall) and applies the kick if it is allowed.
            // Only a punch (a direct melee attack) counts as a hit, nothing else.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                smash.onHit(kicker, kickVelocity(kicker));
            }
            return;
        }

        if (oneBounce != null && oneBounce.ownsBall(event.getEntity())) {
            // One-Bounce decides who may hit the ball and when; only a punch counts.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                oneBounce.onHit(kicker, kickVelocity(kicker));
            }
            return;
        }

        for (LobbyBall lobbyBall : lobbyBalls) {
            if (!lobbyBall.owns(event.getEntity())) continue;
            // A lobby football: an ordinary kick, for anyone waiting in the lobby.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                lobbyBall.onHit(kicker, event.getEntity(), kickVelocity(kicker));
            }
            return;
        }

        if (training != null && training.ownsBall(event.getEntity())) {
            // Training: an ordinary football kick on the player's own ball.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                training.onHit(kicker, event.getEntity(), kickVelocity(kicker));
            }
            return;
        }

        if (hotPotato != null && hotPotato.ownsBall(event.getEntity())) {
            // Hot Potato: a normal football kick on a bomb that blows up near whoever it is closest to.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                hotPotato.onHit(kicker, event.getEntity(), kickVelocity(kicker));
            }
            return;
        }

        if (colorCover != null && colorCover.ownsBall(event.getEntity())) {
            // Color Cover: every player has their own ball, and any player in the game may punch any of them.
            if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker
                    && byEntity.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && !isWatching(kicker.getUniqueId())) {
                colorCover.onHit(kicker, event.getEntity(), kickVelocity(kicker));
            }
            return;
        }

        Match ballMatch = matchOfBall(event.getEntity());
        if (ballMatch == null) {
            // Not part of any match or minigame: a stray ball (it may have no gravity). Get rid of it.
            event.getEntity().remove();
            return;
        }
        if (isFrozen(ballMatch)) return; // ball is frozen during the countdown

        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker) {
            if (isWatching(kicker.getUniqueId())) return; // spectators in the stands can't touch the ball
            Match kickerMatch = matchByPlayer.get(kicker.getUniqueId());
            if (kickerMatch != null && kickerMatch.isSideline(kicker.getUniqueId())) return; // referees and substitutes don't play
            if (kickerMatch != null) kickerMatch.lastKicker = kicker.getUniqueId();
            event.getEntity().setVelocity(kickVelocity(kicker));
        }
    }

    /** Stops players bucketing, shearing or feeding the ball. */
    @EventHandler
    public void onBallInteract(PlayerInteractEntityEvent event) {
        if (isBall(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void onBallExplode(ExplosionPrimeEvent event) {
        if (isBall(event.getEntity())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ match timer

    /** Starts the match: a 5 second freeze, then the first half. */
    private void startTimer(Match match) {
        match.secondsLeft = match.playSeconds;
        match.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickTimer(match), 20L, 20L);
        beginCountdown(match, Match.CountdownType.START, Match.Phase.FIRST_HALF);
    }

    /** Ticks once a second. Play time is paused while a countdown is running. */
    private void tickTimer(Match match) {
        if (match.phase == Match.Phase.COUNTDOWN || match.paused) return;

        match.secondsLeft--;
        if (match.secondsLeft > 0) {
            updateTimerBar(match);
            return;
        }
        if (!match.quick && match.phase == Match.Phase.FIRST_HALF) {
            match.secondsLeft = match.playSeconds;
            beginCountdown(match, Match.CountdownType.HALF_TIME, Match.Phase.SECOND_HALF);
        } else if (!match.goldenGoal && match.redScore == match.blueScore) {
            beginGoldenGoal(match); // level at full time: five more minutes, and the next goal wins
        } else if (match.goldenGoal) {
            endMatch(match, "Full time! Nobody scored in the golden goal period.");
        } else {
            endMatch(match, "Full time!");
        }
    }

    /** Full time and the score is level: a 5 minute golden goal period, where the first goal wins the match. */
    private void beginGoldenGoal(Match match) {
        match.goldenGoal = true;
        match.secondsLeft = GOLDEN_GOAL_SECONDS;
        match.broadcast(Component.text("Full time, and it's level! Golden goal: the next goal wins the match.",
                NamedTextColor.GOLD, TextDecoration.BOLD));
        beginCountdown(match, Match.CountdownType.GOLDEN_GOAL, match.phase);
    }

    /**
     * Freezes everyone and the ball for 5 seconds, then carries on in resumePhase.
     * Used for the start of the match, half time and after every goal.
     */
    private void beginCountdown(Match match, Match.CountdownType type, Match.Phase resumePhase) {
        if (match.countdownTask != null) match.countdownTask.cancel();

        match.phase = Match.Phase.COUNTDOWN;
        match.countdownType = type;
        match.resumePhase = resumePhase;
        match.countdownLeft = type == Match.CountdownType.HALF_TIME ? match.halfTimeSeconds : COUNTDOWN_SECONDS;

        if (type == Match.CountdownType.HALF_TIME) {
            returnSentOffPlayers(match);
            // Half time: players move freely and stay where they are; only the ball goes back to the centre.
            // Everyone is put on their positions when the last five seconds begin (see the task below).
            Location ballSpawn = ballSpawnPoint(match);
            if (ballSpawn != null) spawnBall(match, ballSpawn);
            freezeBall(match);
            showCountdown(match);
        } else {
            // Every other freeze (start, goal): players go to their team's stadium positions, the ball to the centre.
            resetPositions(match);
            freezeBall(match);
            showCountdown(match);
            playFreezeSound(match, 1.0f); // 5
        }

        match.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            match.countdownLeft--;
            if (match.countdownLeft <= 0) {
                match.countdownTask.cancel();
                endCountdown(match);
            } else {
                if (match.countdownType == Match.CountdownType.HALF_TIME) {
                    if (match.countdownLeft == COUNTDOWN_SECONDS) {
                        resetPositions(match); // the 5 second countdown: back on the pitch, on the positions, frozen
                    }
                    freezeBall(match); // nobody can nudge the ball while players walk about
                }
                showCountdown(match);
                // A ding every second: 5 (when it starts), 4, 3, 2, 1. Half time only dings for its last five seconds.
                if (match.countdownType != Match.CountdownType.HALF_TIME || match.countdownLeft <= COUNTDOWN_SECONDS) {
                    playFreezeSound(match, 1.0f);
                }
            }
        }, 20L, 20L);
    }

    private void resetPositions(Match match) {
        teleportToPositions(match);
        Location ballSpawn = ballSpawnPoint(match);
        if (ballSpawn != null) spawnBall(match, ballSpawn);
    }

    /**
     * Puts each team's players on that team's stadium positions in a random order. Nobody stands on the same
     * position they had in the previous freeze. Falls back to where players were at the start if the stadium has
     * no positions.
     */
    private void teleportToPositions(Match match) {
        for (Team team : Team.values()) {
            List<Player> players = match.onlineMembers().stream()
                    .filter(p -> match.teamOf(p.getUniqueId()) == team).toList();
            if (players.isEmpty()) continue;

            if (match.stadium == null || !match.stadium.positionsComplete(team)) {
                for (Player p : players) {
                    Location start = match.startPositions.get(p.getUniqueId());
                    if (start != null) p.teleport(start);
                }
                continue;
            }

            Location[] spots = match.stadium.positions.get(team);
            int[] chosen = new int[players.size()];
            boolean ok = assignSpots(0, players, spots.length, match.lastPosition, new boolean[spots.length], chosen);
            if (!ok) { // can't avoid a repeat (cannot normally happen); just spread them out
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < spots.length; i++) order.add(i);
                Collections.shuffle(order);
                for (int i = 0; i < chosen.length; i++) chosen[i] = order.get(i % order.size());
            }
            for (int i = 0; i < players.size(); i++) {
                Player p = players.get(i);
                p.teleport(spots[chosen[i]].clone());
                match.lastPosition.put(p.getUniqueId(), chosen[i]);
            }
        }
    }

    /** Random assignment of players to different spots where nobody gets their previous spot again. */
    private boolean assignSpots(int index, List<Player> players, int spots, Map<UUID, Integer> last,
                                boolean[] used, int[] chosen) {
        if (index == players.size()) return true;
        Integer previous = last.get(players.get(index).getUniqueId());

        List<Integer> options = new ArrayList<>();
        for (int s = 0; s < spots; s++) {
            if (!used[s] && (previous == null || previous != s)) options.add(s);
        }
        Collections.shuffle(options);
        for (int s : options) {
            used[s] = true;
            chosen[index] = s;
            if (assignSpots(index + 1, players, spots, last, used, chosen)) return true;
            used[s] = false;
        }
        return false;
    }

    private void endCountdown(Match match) {
        match.phase = match.resumePhase;
        if (match.ball != null) match.ball.setGravity(true);
        updateTimerBar(match);

        Component subtitle = switch (match.countdownType) {
            case START -> Component.text("First half", NamedTextColor.GRAY);
            case HALF_TIME -> Component.text("Second half", NamedTextColor.GRAY);
            case GOAL -> Component.empty();
            case GOLDEN_GOAL -> Component.text("Golden goal: the next goal wins", NamedTextColor.GOLD);
        };
        Title title = Title.title(
                Component.text("Kick off!", NamedTextColor.GREEN, TextDecoration.BOLD), subtitle);
        for (Player p : match.onlineAudience()) p.showTitle(title);
        playFreezeSound(match, 2.0f); // freeze ends: one octave higher

        if (match.pauseAfterCountdown) { // someone disconnected during the countdown
            match.pauseAfterCountdown = false;
            setPaused(match, true);
            announcePause(match, match.pauseReason == null ? "Play is paused" : match.pauseReason);
            match.pauseReason = null;
        }
    }

    /** Note block "ding" (the bell). Pitch 1.0 is the normal note, 2.0 is exactly one octave up. */
    private void playFreezeSound(Match match, float pitch) {
        Sound ding = Sound.sound(Key.key("block.note_block.bell"), Sound.Source.MASTER, 1.0f, pitch);
        for (Player p : match.onlineAudience()) p.playSound(ding);
    }

    private void updateTimerBar(Match match) {
        int seconds = Math.max(match.secondsLeft, 0);
        String time = String.format("%d:%02d", seconds / 60, seconds % 60);
        String half = match.goldenGoal ? "Golden Goal"
                : match.duel ? "Duel" : match.roulette ? "Roulette" : match.quick ? "Quickmatch"
                : match.phase == Match.Phase.FIRST_HALF ? "First Half" : "Second Half";

        match.timerBar.progress(Math.min(1.0f, seconds / (float) (match.goldenGoal ? GOLDEN_GOAL_SECONDS : match.playSeconds)));
        if (match.paused) {
            match.timerBar.color(BossBar.Color.RED);
            match.timerBar.name(Component.text("Paused - " + time, NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }
        match.timerBar.color(BossBar.Color.YELLOW);
        match.timerBar.name(Component.text(half + " - " + time, NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    private void showCountdown(Match match) {
        int n = match.countdownLeft;
        String label = switch (match.countdownType) {
            case START -> "Match starts in " + n;
            case HALF_TIME -> "Half Time - second half starts in " + String.format("%d:%02d", n / 60, n % 60);
            case GOAL -> "Goal! Play resumes in " + n;
            case GOLDEN_GOAL -> "Golden goal starts in " + n;
        };
        match.timerBar.color(BossBar.Color.GREEN);
        match.timerBar.progress(match.countdownType == Match.CountdownType.HALF_TIME
                ? Math.min(1.0f, n / (float) match.halfTimeSeconds) : 1.0f);
        match.timerBar.name(Component.text(label, NamedTextColor.GREEN, TextDecoration.BOLD));

        if (match.countdownType == Match.CountdownType.HALF_TIME) {
            // The half-time clock sits on the action bar, just above the hotbar, for the whole break.
            String clock = String.format("%d:%02d", n / 60, n % 60);
            Component actionBar = Component.text("Half Time - " + clock, NamedTextColor.GOLD, TextDecoration.BOLD);
            for (Player p : match.onlineAudience()) p.sendActionBar(actionBar);

            if (n == match.halfTimeSeconds) {
                // One title when half time starts.
                Title halfTime = Title.title(Component.text("Half Time", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Second half starts in " + clock, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ofMillis(500)));
                for (Player p : match.onlineAudience()) p.showTitle(halfTime);
                playFreezeSound(match, 0.5f);
            } else if (n == 15 || n == 10) {
                // The 15 and 10 second warnings.
                Title warning = Title.title(Component.text(n + " seconds", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("until the second half", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(500)));
                for (Player p : match.onlineAudience()) p.showTitle(warning);
                playFreezeSound(match, 0.75f);
            }
            if (n > COUNTDOWN_SECONDS) return; // the big 5-4-3-2-1 titles are only for the last five seconds
        }

        if (match.countdownType == Match.CountdownType.GOAL) {
            // The goal title is still on screen, so count down on the action bar instead.
            Component actionBar = Component.text("Play resumes in " + n, NamedTextColor.GOLD, TextDecoration.BOLD);
            for (Player p : match.onlineAudience()) p.sendActionBar(actionBar);
            return;
        }

        Component subtitle = Component.text(
                match.countdownType == Match.CountdownType.START ? "Match starts in..."
                        : match.countdownType == Match.CountdownType.GOLDEN_GOAL ? "Golden goal starts in..."
                        : "Second half starts in...",
                NamedTextColor.GRAY);
        Title title = Title.title(
                Component.text(String.valueOf(n), NamedTextColor.GOLD, TextDecoration.BOLD),
                subtitle,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        for (Player p : match.onlineAudience()) p.showTitle(title);
    }

    private void cancelTasks(Match match) {
        if (match.timerTask != null) match.timerTask.cancel();
        if (match.countdownTask != null) match.countdownTask.cancel();
    }

    // ------------------------------------------------------------------ team jerseys

    private ItemStack createJersey(Team team) {
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

    /** Equips the team jersey, remembering whatever chestplate the player was wearing. */
    private void giveJersey(Match match, Player player) {
        // Duels have no teams to show: no jersey, and names are left exactly as they are (TAB included).
        if (!match.duel) {
            ItemStack current = player.getInventory().getChestplate();
            match.savedChestplates.putIfAbsent(player.getUniqueId(),
                    current == null || isJersey(current) ? new ItemStack(Material.AIR) : current);
            player.getInventory().setChestplate(createJersey(match.teamOf(player.getUniqueId())));
            applyNameColor(player, match.teamOf(player.getUniqueId()));
        }

        // Everyone plays in adventure mode (no breaking or placing blocks); the old mode comes back afterwards.
        match.savedGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
    }

    private void restoreChestplate(Match match, Player player) {
        if (roulette != null) roulette.clearPowers(player); // Roulette powers end with the match
        removeRefereeWhistle(player);
        if (!match.duel) clearNameColor(player);
        GameMode savedMode = match.savedGameModes.remove(player.getUniqueId());
        if (savedMode != null) player.setGameMode(savedMode);
        ItemStack saved = match.savedChestplates.remove(player.getUniqueId());
        if (saved == null) return;
        player.getInventory().setChestplate(saved.getType() == Material.AIR ? null : saved);
    }

    // ------------------------------------------------------------------ name tag colours

    private Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private boolean isOurScoreboardTeam(org.bukkit.scoreboard.Team team) {
        return team.getName().equals(RED_SCOREBOARD_TEAM) || team.getName().equals(BLUE_SCOREBOARD_TEAM);
    }

    /** Colours the player's name tag with their team colour: through TAB if it is installed, otherwise a scoreboard team. */
    private void applyNameColor(Player player, Team team) {
        // With TAB, set a temporary name tag prefix (set by operators with /setprefix, then a space) instead of
        // using a scoreboard team.
        boolean red = team == Team.RED;
        String tag = prefixes != null ? prefixes.tag(red) : (red ? "&c" : "&9");
        if (tabNames.isPresent() && tabNames.setPrefix(player, tag)) return;

        Scoreboard board = mainScoreboard();
        String teamName = team == Team.RED ? RED_SCOREBOARD_TEAM : BLUE_SCOREBOARD_TEAM;
        org.bukkit.scoreboard.Team ours = board.getTeam(teamName);
        if (ours == null) ours = board.registerNewTeam(teamName);
        ours.color(team.color);
        ours.prefix(prefixes != null ? prefixes.component(red) : Component.empty());

        org.bukkit.scoreboard.Team current = board.getEntryTeam(player.getName());
        if (current != null && current.equals(ours)) return;
        if (current != null && !isOurScoreboardTeam(current)) {
            previousScoreboardTeams.putIfAbsent(player.getUniqueId(), current.getName());
        }
        ours.addEntry(player.getName()); // also takes them off any other team on this scoreboard
    }

    private void clearNameColor(Player player) {
        tabNames.resetPrefix(player);

        Scoreboard board = mainScoreboard();
        org.bukkit.scoreboard.Team current = board.getEntryTeam(player.getName());
        if (current != null && isOurScoreboardTeam(current)) current.removeEntry(player.getName());

        String previous = previousScoreboardTeams.remove(player.getUniqueId());
        if (previous != null) {
            org.bukkit.scoreboard.Team old = board.getTeam(previous);
            if (old != null) old.addEntry(player.getName());
        }
    }

    private void removeScoreboardTeams() {
        Scoreboard board = mainScoreboard();
        for (String name : List.of(RED_SCOREBOARD_TEAM, BLUE_SCOREBOARD_TEAM)) {
            org.bukkit.scoreboard.Team team = board.getTeam(name);
            if (team != null) team.unregister();
        }
        previousScoreboardTeams.clear();
    }

    /** Players can't take the jersey off or swap it out during a match. */
    @EventHandler
    public void onJerseyClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null || !match.savedChestplates.containsKey(player.getUniqueId())) return;

        boolean chestSlot = event.getSlotType() == InventoryType.SlotType.ARMOR && event.getSlot() == 38;
        if (chestSlot || isJersey(event.getCurrentItem())) {
            event.setCancelled(true);
        }
    }

    /** The jersey never drops on death; it comes back when the player respawns. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (matchByPlayer.containsKey(event.getPlayer().getUniqueId())) {
            event.getDrops().removeIf(this::isJersey);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null || !match.savedChestplates.containsKey(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (matchByPlayer.get(player.getUniqueId()) == match) giveJersey(match, player);
        });
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        invites.remove(player.getUniqueId());

        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) return;
        if (player.getUniqueId().equals(match.host)) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
        } else {
            // Someone who was on the pitch in a match with referees: play pauses until the referees sub someone on.
            Team playing = match.teamSet(Team.RED).contains(player.getUniqueId()) ? Team.RED
                    : match.teamSet(Team.BLUE).contains(player.getUniqueId()) ? Team.BLUE : null;
            boolean pauseForThem = playing != null && match.started && !match.referees.isEmpty();
            Location spot = player.getLocation().clone();

            removeMember(match, player);
            if (!pauseForThem) match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
            afterMemberLeft(match);
            if (pauseForThem) playerDisconnected(match, playing, player.getName(), spot);
            endQuickMatchIfEmpty(match);
        }
    }

    // ------------------------------------------------------------------ yellow and red cards

    private ItemStack createCardItem(Card card) {
        boolean yellow = card == Card.YELLOW;
        ItemStack item = new ItemStack(yellow ? Material.YELLOW_BANNER : Material.RED_BANNER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold(yellow ? "Yellow Card" : "Red Card", yellow ? NamedTextColor.YELLOW : NamedTextColor.RED));
        meta.lore(List.of(plain("Right-click to show this card to a player.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(cardKey, PersistentDataType.STRING, card.name());
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasCardItem(Player player, Card card) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            if (card.name().equals(item.getItemMeta().getPersistentDataContainer().get(cardKey, PersistentDataType.STRING))) {
                return true;
            }
        }
        return false;
    }

    /** /match cards: a referee who lost their cards gets them back. */
    private void cardsCommand(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null || !match.referees.contains(player.getUniqueId())) {
            error(player, "Only referees have cards.");
            return;
        }
        for (Card card : Card.values()) {
            if (!hasCardItem(player, card)) player.getInventory().addItem(createCardItem(card));
        }
        player.sendMessage(Component.text("You have your yellow and red cards.", NamedTextColor.GREEN));
    }

    /** Right-clicking a card opens the cards menu for it (the banner is never placed). */
    @EventHandler
    public void onCardUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;
        String card = item.getItemMeta().getPersistentDataContainer().get(cardKey, PersistentDataType.STRING);
        if (card == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null || !match.referees.contains(player.getUniqueId())) {
            error(player, "Only a referee of a match can show cards.");
            return;
        }
        openCardsMenu(player, Card.valueOf(card));
    }

    /** Heads of everyone on the pitch, Red team on the top row and Blue team on the bottom row. */
    private void openCardsMenu(Player referee, Card card) {
        Match match = officialMatchQuiet(referee);
        if (match == null) return;
        if (!match.started) {
            error(referee, "Cards can only be shown once the match has started.");
            return;
        }

        boolean yellow = card == Card.YELLOW;
        MenuGui holder = new MenuGui(MenuType.CARDS, 0, 0, null, false, null, card);
        Inventory inventory = newMenu(holder, yellow ? "Show a Yellow Card" : "Show a Red Card");
        int redSlot = SLOT_CARD_RED_ROW;
        int blueSlot = SLOT_CARD_BLUE_ROW;
        for (Team team : Team.values()) {
            for (UUID id : match.teamSet(team)) {
                Player player = Bukkit.getPlayer(id);
                if (player == null) continue;
                ItemStack head = createHead(player, yellow ? "Click to show a yellow card" : "Click to show a red card");
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.lore(List.of(plain(team.label + " team", team.color),
                        plain("Yellow cards: " + match.yellows.getOrDefault(id, 0), NamedTextColor.GRAY),
                        plain(yellow ? "Click to show a yellow card" : "Click to show a red card", NamedTextColor.YELLOW)));
                head.setItemMeta(meta);
                inventory.setItem(team == Team.RED ? redSlot++ : blueSlot++, head);
            }
        }
        inventory.setItem(13, createCardItem(card));
        inventory.setItem(7, teamItem(match, Team.RED, "Red Team", Team.RED.color));
        inventory.setItem(25, teamItem(match, Team.BLUE, "Blue Team", Team.BLUE.color));
        inventory.setItem(SLOT_BACK, backButton());
        referee.openInventory(inventory);
    }

    /** The team a player is playing for on the pitch, or null (referees, substitutes and sent-off players aren't). */
    private Team pitchTeamOf(Match match, UUID id) {
        if (match.teamSet(Team.RED).contains(id)) return Team.RED;
        return match.teamSet(Team.BLUE).contains(id) ? Team.BLUE : null;
    }

    private void giveCard(Player referee, Match match, Player target, Card card) {
        if (!match.started) {
            error(referee, "Cards can only be shown once the match has started.");
            return;
        }
        Team team = pitchTeamOf(match, target.getUniqueId());
        if (team == null) {
            error(referee, target.getName() + " isn't on the pitch.");
            return;
        }
        if (card == Card.YELLOW) yellowCard(referee, match, target, team);
        else redCard(referee, match, target, team, false);
    }

    private void showCardTitle(Match match, Title title, String soundKey) {
        Sound sound = Sound.sound(Key.key(soundKey), Sound.Source.PLAYER, 1.0f, 1.0f);
        for (Player p : everyoneIn(match)) {
            p.showTitle(title);
            p.playSound(sound);
        }
    }

    private void yellowCard(Player referee, Match match, Player target, Team team) {
        int count = match.yellows.merge(target.getUniqueId(), 1, Integer::sum);
        if (count >= 2) { // a second yellow is a red
            match.broadcast(Component.text("Second yellow card for " + target.getName() + "! That is a red card.",
                    NamedTextColor.RED, TextDecoration.BOLD));
            redCard(referee, match, target, team, true);
            return;
        }
        showCardTitle(match, Title.title(Component.text("Yellow Card", NamedTextColor.YELLOW),
                Component.text(target.getName() + " (" + team.label + " team)")), "block.note_block.pling");
        match.broadcast(Component.text(referee.getName() + " showed a yellow card to ", NamedTextColor.YELLOW)
                .append(Component.text(target.getName(), team.color))
                .append(Component.text(". (1/2)", NamedTextColor.YELLOW)));
        target.sendMessage(Component.text("One more yellow card and you are sent off.", NamedTextColor.YELLOW));
    }

    /**
     * A player is sent off to the stands. If their team has a substitute, play pauses and the referee has to sub
     * someone on (the substitution menu opens). If nobody can be subbed on the game goes on a player short: in the
     * first half until half time, in the second half for the rest of the match.
     */
    private void redCard(Player referee, Match match, Player target, Team team, boolean secondYellow) {
        UUID id = target.getUniqueId();
        Location spot = target.getLocation().clone();
        boolean replace = hasAvailableSub(match, team);

        match.teamSet(team).remove(id);
        match.sentOff.put(id, team);
        restoreChestplate(match, target);
        giveSubKit(match, target); // up to the stands. They aren't a substitute, so they can't be subbed back on

        showCardTitle(match, Title.title(Component.text("Red Card", NamedTextColor.RED),
                Component.text(target.getName() + " (" + team.label + " team)"
                        + (secondYellow ? " - second yellow" : ""))), "entity.wither.hurt");
        match.broadcast(Component.text(referee.getName() + " sent off ", NamedTextColor.RED)
                .append(Component.text(target.getName(), team.color))
                .append(Component.text(secondYellow ? " for a second yellow card." : " with a red card.", NamedTextColor.RED)));

        if (replace) {
            match.vacancies.add(new Match.Vacancy(team, target.getName(), spot, id));
            if (!match.paused) {
                if (match.phase == Match.Phase.COUNTDOWN) {
                    match.pauseAfterCountdown = true;
                    match.pauseReason = target.getName() + " was sent off";
                } else {
                    setPaused(match, true);
                    announcePause(match, target.getName() + " was sent off");
                }
            }
            later(() -> openPagedMenu(referee, MenuType.SUBS, 1, team, false, null)); // sub on a new player
        } else if (isFirstHalf(match)) {
            match.returnAtHalfTime.add(id);
            match.broadcast(Component.text("Nobody can be subbed on, so the " + team.label + " team plays a player short "
                    + "until half time.", NamedTextColor.YELLOW));
        } else {
            match.broadcast(Component.text("Nobody can be subbed on, so the " + team.label + " team plays a player short "
                    + "for the rest of the match.", NamedTextColor.YELLOW));
        }
    }

    private boolean isFirstHalf(Match match) {
        if (match.quick) return false; // one period: a sending off lasts the whole match
        Match.Phase phase = match.phase == Match.Phase.COUNTDOWN ? match.resumePhase : match.phase;
        return phase == Match.Phase.FIRST_HALF;
    }

    /** Brings a sent-off player back on when their team is a player short, at the middle of the pitch. */
    private boolean returnSentOff(Player official, Match match, Team team, Player player) {
        UUID id = player.getUniqueId();
        if (match.sentOff.get(id) != team) {
            error(official, player.getName() + " wasn't sent off.");
            return false;
        }
        if (match.isFull(team)) {
            error(official, "The " + team.label + " team is full.");
            return false;
        }

        match.sentOff.remove(id);
        match.returnAtHalfTime.remove(id);
        leaveStands(player, false);
        match.teamSet(team).add(id);
        giveJersey(match, player);
        Location spot = match.stadium != null && match.stadium.hasCenter() ? match.stadium.center
                : match.ball != null ? match.ball.getLocation() : null;
        if (spot != null) player.teleport(spot);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);
        if (match.paused) dropToGround(player);

        player.showTitle(Title.title(Component.text("You're back on!", NamedTextColor.GREEN),
                Component.text("Brought on by " + official.getName())));
        Component message = Component.text(official.getName() + " brought ", NamedTextColor.YELLOW)
                .append(Component.text(player.getName(), team.color))
                .append(Component.text(" back on.", NamedTextColor.YELLOW));
        for (Player p : everyoneIn(match)) p.sendMessage(message);
        return true;
    }

    /** At half time, players sent off in the first half with nobody to replace them come back on. */
    private void returnSentOffPlayers(Match match) {
        for (UUID id : new ArrayList<>(match.returnAtHalfTime)) {
            match.returnAtHalfTime.remove(id);
            Team team = match.sentOff.get(id);
            Player player = Bukkit.getPlayer(id);
            if (team == null || player == null) continue;
            match.sentOff.remove(id);
            if (match.isFull(team)) {
                match.sentOff.put(id, team); // someone took their place in the meantime: they stay off
                player.sendMessage(Component.text("The " + team.label + " team is full, so you stay off.", NamedTextColor.YELLOW));
                continue;
            }
            leaveStands(player, false);
            match.teamSet(team).add(id);
            giveJersey(match, player);
            player.sendMessage(Component.text("Your sending off is over: you are back on for the second half.",
                    NamedTextColor.GREEN));
        }
    }

    // ------------------------------------------------------------------ disconnects

    private boolean hasAvailableSub(Match match, Team team) {
        for (UUID id : match.subs(team)) {
            if (Bukkit.getPlayer(id) != null) return true;
        }
        return false;
    }

    private void notifyReferees(Match match, Component message) {
        for (UUID id : match.referees) {
            Player referee = Bukkit.getPlayer(id);
            if (referee != null) referee.sendMessage(message);
        }
    }

    /**
     * A player on the pitch disconnected. Play pauses. If their team has a substitute, the referees have to sub
     * someone on for them before they can resume; if nobody is left to sub, they can resume straight away.
     */
    private void playerDisconnected(Match match, Team team, String name, Location spot) {
        boolean canReplace = hasAvailableSub(match, team);
        if (canReplace) match.vacancies.add(new Match.Vacancy(team, name, spot, null));

        if (!match.paused) {
            if (match.phase == Match.Phase.COUNTDOWN) {
                match.pauseAfterCountdown = true; // the countdown can't be paused: do it as soon as it ends
                match.pauseReason = name + " disconnected";
            } else {
                setPaused(match, true);
                announcePause(match, name + " disconnected");
            }
        }
        sendDisconnectInstructions(match, name, canReplace);
    }

    private void announcePause(Match match, String reason) {
        Title title = Title.title(Component.text("Play Paused", NamedTextColor.RED), Component.text(reason));
        Sound levelUp = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.PLAYER, 1.0f, 1.0f);
        for (Player p : everyoneIn(match)) {
            p.showTitle(title);
            p.playSound(levelUp);
        }
    }

    private void sendDisconnectInstructions(Match match, String name, boolean canReplace) {
        match.broadcast(Component.text(name + " disconnected, so play is paused.", NamedTextColor.YELLOW));
        if (canReplace) {
            notifyReferees(match, Component.text("Sub someone on for " + name + " before play can resume. ",
                            NamedTextColor.YELLOW)
                    .append(Component.text("[Open substitutions]", NamedTextColor.GREEN, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/match sub"))));
        } else {
            notifyReferees(match, Component.text("There is nobody to sub on for " + name
                    + ". Blow your whistle to resume play when you are ready.", NamedTextColor.YELLOW));
        }
    }

    /** After anyone leaves a started match: nobody left to referee, or nobody left to fill a spot, frees the pause. */
    private void afterMemberLeft(Match match) {
        if (!match.started) return;
        if (match.paused && match.referees.isEmpty()) {
            // Nobody could ever resume it, so the last referee leaving does.
            match.vacancies.clear();
            match.pauseAfterCountdown = false;
            setPaused(match, false);
            match.broadcast(Component.text("There are no referees left, so play resumed.", NamedTextColor.YELLOW));
            return;
        }
        if (match.vacancies.isEmpty()) return;
        boolean changed = false;
        for (java.util.Iterator<Match.Vacancy> it = match.vacancies.iterator(); it.hasNext(); ) {
            Match.Vacancy vacancy = it.next();
            if (hasAvailableSub(match, vacancy.team())) continue;
            it.remove();
            changed = true;
            // A red card with nobody left to sub on: they sit out until half time (or the rest of the match).
            if (vacancy.sentOffId() != null && isFirstHalf(match)) match.returnAtHalfTime.add(vacancy.sentOffId());
        }
        if (changed && match.vacancies.isEmpty() && match.paused) {
            notifyReferees(match, Component.text("There is nobody left to sub on, so you can resume play with your "
                    + "whistle.", NamedTextColor.YELLOW));
        }
    }

    /** Puts a substitute on in the place of a player who disconnected, where that player was standing. */
    private boolean fillVacancy(Player official, Match match, Team team, UUID inId) {
        Match.Vacancy vacancy = match.vacancyFor(team);
        Player in = inId == null ? null : Bukkit.getPlayer(inId);
        if (vacancy == null) {
            error(official, "The " + team.label + " team has no empty spot.");
            return false;
        }
        if (in == null || match.benchTeamOf(inId) != team) {
            error(official, "That substitute isn't available.");
            return false;
        }

        match.vacancies.remove(vacancy);
        match.subs(team).remove(inId);
        match.sentOff.remove(inId); // a sent-off player can be brought back on too
        match.returnAtHalfTime.remove(inId);
        match.teamSet(team).add(inId);
        leaveStands(in, false);
        giveJersey(match, in);
        in.teleport(vacancy.spot());
        in.setVelocity(new Vector(0, 0, 0));
        in.setFallDistance(0f);
        if (match.paused) dropToGround(in);

        in.showTitle(Title.title(Component.text("You're on!", NamedTextColor.GREEN),
                Component.text("Taking " + vacancy.name() + "'s place")));
        Component message = Component.text(official.getName() + " subbed ", NamedTextColor.YELLOW)
                .append(Component.text(in.getName(), team.color))
                .append(Component.text(" on for " + vacancy.name() + (vacancy.sentOffId() == null
                        ? ", who disconnected." : ", who was sent off."), NamedTextColor.YELLOW));
        for (Player p : everyoneIn(match)) p.sendMessage(message);
        if (match.vacancies.isEmpty()) {
            notifyReferees(match, Component.text("Every empty spot is filled. Blow your whistle to resume play.",
                    NamedTextColor.GREEN));
        }
        return true;
    }

    // ------------------------------------------------------------------ referees and the whistle

    /** The match this player hosts or referees, or null (no error message). */
    private Match officialMatchQuiet(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) return null;
        UUID id = player.getUniqueId();
        return id.equals(match.host) || match.referees.contains(id) ? match : null;
    }

    /** A new substitute waits on the side in adventure mode and sees the match bars. */
    private void giveSubKit(Match match, Player player) {
        match.savedGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
        if (match.started) match.showBar(player);
        if (spectators != null) spectators.addSideline(player, match); // to the stands, like a watcher
    }

    /** Takes a referee or substitute out of the stands (sent home if they are leaving, not if they are going on). */
    private void leaveStands(Player player, boolean home) {
        if (spectators != null) spectators.removeSideline(player, home);
    }

    /** /match sub opens the substitutions menu; /match sub <substitute> <player to take off> does it directly. */
    private void substituteCommand(Player player, String[] args) {
        Match match = officialMatch(player);
        if (match == null) return;
        if (args.length == 1) {
            // The Substitution GUI. With an empty spot waiting it opens straight onto that team's substitutes.
            if (match.vacancies.isEmpty()) openSubTeamMenu(player);
            else openPagedMenu(player, MenuType.SUBS, 1, match.vacancies.get(0).team(), false, null);
            return;
        }
        if (args.length == 2) { // /match sub <substitute>: fills the spot of a player who disconnected
            Player in = Bukkit.getPlayerExact(args[1]);
            Team team = in == null ? null : match.benchTeamOf(in.getUniqueId());
            if (team == null) {
                error(player, args[1] + " isn't a substitute or a sent-off player in this match.");
            } else {
                fillVacancy(player, match, team, in.getUniqueId());
            }
            return;
        }
        if (args.length != 3) {
            error(player, "Usage: /match sub   or   /match sub <substitute> <player to take off>");
            return;
        }
        Player in = Bukkit.getPlayerExact(args[1]);
        Player out = Bukkit.getPlayerExact(args[2]);
        if (in == null || out == null) {
            error(player, "Both players need to be online.");
            return;
        }
        Team team = match.benchTeamOf(in.getUniqueId());
        if (team == null) {
            error(player, in.getName() + " isn't a substitute or a sent-off player in this match.");
            return;
        }
        substitute(player, match, team, in.getUniqueId(), out);
    }

    /** Takes {@code out} off and puts the substitute on in their place, on the spot where {@code out} was standing. */
    private boolean substitute(Player official, Match match, Team team, UUID inId, Player out) {
        if (!match.started) {
            error(official, "Substitutions are only possible once the match has started.");
            return false;
        }
        if (match.vacancyFor(team) != null) {
            error(official, "A player disconnected: sub someone on for " + match.vacancyFor(team).name()
                    + " first (/match sub <substitute>).");
            return false;
        }
        Player in = inId == null ? null : Bukkit.getPlayer(inId);
        if (in == null) {
            error(official, "That substitute is no longer online.");
            return false;
        }
        if (match.benchTeamOf(inId) != team) {
            error(official, in.getName() + " isn't a " + team.label + " substitute or a sent-off " + team.label + " player.");
            return false;
        }
        if (!match.teamSet(team).contains(out.getUniqueId())) {
            error(official, out.getName() + " isn't playing for the " + team.label + " team.");
            return false;
        }

        Location spot = out.getLocation();
        match.teamSet(team).remove(out.getUniqueId());
        match.subs(team).remove(inId);
        match.sentOff.remove(inId); // a sent-off player can be subbed back on
        match.returnAtHalfTime.remove(inId);
        match.teamSet(team).add(inId);
        match.subs(team).add(out.getUniqueId());

        // The player coming off goes back to the sidelines; the substitute gets the jersey and takes their place.
        restoreChestplate(match, out);
        leaveStands(in, false); // off the stands and onto the pitch
        giveSubKit(match, out); // and the player coming off goes up to the stands
        giveJersey(match, in);
        in.teleport(spot);
        in.setVelocity(new Vector(0, 0, 0));
        in.setFallDistance(0f);

        in.showTitle(Title.title(Component.text("You're on!", NamedTextColor.GREEN),
                Component.text("Substituted for " + out.getName())));
        out.showTitle(Title.title(Component.text("Substituted off", NamedTextColor.YELLOW),
                Component.text("Replaced by " + in.getName())));
        Component message = Component.text(official.getName() + " substituted ", NamedTextColor.YELLOW)
                .append(Component.text(out.getName(), team.color))
                .append(Component.text(" off for ", NamedTextColor.YELLOW))
                .append(Component.text(in.getName(), team.color))
                .append(Component.text(".", NamedTextColor.YELLOW));
        for (Player p : everyoneIn(match)) p.sendMessage(message);
        return true;
    }

    /** Everyone who should see what happens in this match: both teams, the referees and the people watching. */
    private List<Player> everyoneIn(Match match) {
        List<Player> everyone = new ArrayList<>(match.onlineAudience());
        if (spectators != null) everyone.addAll(spectators.watchersOf(match));
        return everyone;
    }

    private ItemStack createRefereeWhistle() {
        ItemStack item = whistleSupplier == null ? new ItemStack(Material.GOAT_HORN) : whistleSupplier.get();
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(plain("Referee whistle: blow it to pause and resume play.", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(refereeWhistleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** A new referee gets the whistle (and, like players, adventure mode so they can't break the stadium). */
    private void giveRefereeKit(Match match, Player player) {
        match.savedGameModes.putIfAbsent(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.ADVENTURE);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(createRefereeWhistle(),
                createCardItem(Card.YELLOW), createCardItem(Card.RED));
        leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        if (match.started) match.showBar(player);
        if (spectators != null) spectators.addSideline(player, match); // to the stands, like a watcher
    }

    /** Takes back the referee whistle (only that one: an operator's own Whistle stays). */
    private void removeRefereeWhistle(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !item.hasItemMeta()) continue;
            var data = item.getItemMeta().getPersistentDataContainer();
            if (data.has(refereeWhistleKey, PersistentDataType.BYTE) || data.has(cardKey, PersistentDataType.STRING)) {
                player.getInventory().setItem(slot, null); // the referee whistle and the cards go with the job
            }
        }
    }

    /**
     * Someone blew a Whistle. If they are in a match (playing, refereeing or watching) only the people in that match
     * and its watchers get the title and sound, and a referee's whistle also pauses or resumes play. Returns false if
     * they are not in a match, so the caller can do the normal server-wide whistle.
     */
    boolean blowWhistle(Player player) {
        UUID id = player.getUniqueId();
        Match match = matchByPlayer.get(id);
        if (match == null && spectators != null) match = spectators.matchOf(id);
        if (match == null) return false;

        Component headline = Component.text("Whistle Blown!", NamedTextColor.YELLOW);
        Component subtitle = Component.text("by " + player.getName());
        if (match.referees.contains(id)) {
            if (match.paused && !match.vacancies.isEmpty()) {
                player.sendActionBar(Component.text("Sub someone on for " + match.vacancies.get(0).name()
                        + " before resuming play.", NamedTextColor.RED));
            } else if (match.started && match.phase != Match.Phase.COUNTDOWN) {
                boolean pausing = !match.paused;
                setPaused(match, pausing);
                headline = pausing ? Component.text("Play Paused", NamedTextColor.RED)
                        : Component.text("Play Resumed", NamedTextColor.GREEN);
                subtitle = Component.text("Whistle blown by " + player.getName());
            } else {
                player.sendActionBar(Component.text("Play can only be paused while the ball is in play.",
                        NamedTextColor.RED));
            }
        }

        Title title = Title.title(headline, subtitle);
        Sound levelUp = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.PLAYER, 1.0f, 1.0f);
        for (Player p : everyoneIn(match)) {
            p.showTitle(title);
            p.playSound(levelUp);
        }
        return true;
    }

    /** Moves a player straight down onto the block below them (nothing happens if they are already standing). */
    private void dropToGround(Player player) {
        Location at = player.getLocation();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(at, new Vector(0, -1, 0), 320,
                FluidCollisionMode.NEVER, true);
        if (hit == null) return;
        double groundY = hit.getHitPosition().getY();
        if (at.getY() - groundY < 0.05) return;

        Location target = at.clone();
        target.setY(groundY);
        player.teleport(target);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);
    }

    /** Pauses or resumes play: the clock stops, the ball hangs where it is, and players can't move or kick. */
    private void setPaused(Match match, boolean paused) {
        match.paused = paused;
        if (paused) {
            // Held in mid-air they would be kicked for flying, so everyone playing is set down on the ground first.
            for (Player p : match.onlineMembers()) dropToGround(p);
        }
        SulfurCube ball = match.ball;
        if (ball != null) {
            if (paused) {
                match.pausedVelocity = ball.getVelocity().clone();
                freezeBall(match);
            } else {
                ball.setGravity(true);
                if (match.pausedVelocity != null) ball.setVelocity(match.pausedVelocity);
                match.pausedVelocity = null;
            }
        }
        updateTimerBar(match);
    }

    private void removeMember(Match match, Player player) {
        restoreChestplate(match, player);
        leaveStands(player, true);
        match.remove(player.getUniqueId());
        matchByPlayer.remove(player.getUniqueId());
        match.hideBar(player);
    }

    private void endMatch(Match match, String reason) {
        announceToDiscord(match, !match.started); // ended before kick-off counts as cancelled
        cancelTasks(match);
        if (match.ball != null) match.ball.remove();
        Component message = Component.text(reason, NamedTextColor.YELLOW);

        Component redLine = match.duel
                ? Component.text(match.duelName(Team.RED) + ": ", NamedTextColor.GOLD)
                : Component.text("Red Team: ", NamedTextColor.RED);
        Component blueLine = match.duel
                ? Component.text(match.duelName(Team.BLUE) + ": ", NamedTextColor.GOLD)
                : Component.text("Blue Team: ", NamedTextColor.BLUE);
        Component overview = Component.text("Overview:", NamedTextColor.GOLD, TextDecoration.BOLD)
                .appendNewline()
                .append(redLine.append(Component.text(match.redScore, NamedTextColor.WHITE)))
                .appendNewline()
                .append(blueLine.append(Component.text(match.blueScore, NamedTextColor.WHITE)))
                .appendNewline()
                .append(resultLine(match));

        List<Player> members = new ArrayList<>(match.onlineMembers());
        for (Player p : match.onlineAudience()) {
            restoreChestplate(match, p);
            leaveStands(p, true);
            match.hideBar(p);
            p.sendMessage(message);
            p.sendMessage(overview);
        }
        if (match.duel && celebration != null) {
            // Duels are a challenge: the winner (if the score isn't level) gets the celebration, everyone the sound.
            Player winner = null;
            if (match.redScore != match.blueScore) {
                Team winning = match.redScore > match.blueScore ? Team.RED : Team.BLUE;
                for (Player p : members) {
                    if (match.teamOf(p.getUniqueId()) == winning) winner = p;
                }
            }
            celebration.finish(members, winner);
        }
        for (UUID id : new ArrayList<>(matchByPlayer.keySet())) {
            if (matchByPlayer.get(id) == match) matchByPlayer.remove(id);
        }
        invites.values().removeIf(invite -> invite.host().equals(match.host));
        if (spectators != null) spectators.matchEnded(match);
    }

    /** The match's host, if this is a match its player hosts (no error message when they don't). */
    private Match hostedMatchQuietly(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        return match != null && player.getUniqueId().equals(match.host) ? match : null;
    }

    /** If the whole Discord server was invited to this match, tells Discord that it is over (or was cancelled). */
    private void announceToDiscord(Match match, boolean cancelled) {
        if (!match.serverInvited) return;
        match.serverInvited = false; // only once, however many ways there are to end a match
        String red = match.duel ? match.duelName(Team.RED) : "Red Team";
        String blue = match.duel ? match.duelName(Team.BLUE) : "Blue Team";
        String host = match.host == null ? null : Bukkit.getOfflinePlayer(match.host).getName();

        String result;
        int diff = Math.abs(match.redScore - match.blueScore);
        if (diff == 0) {
            result = "The match ended in a draw!";
        } else {
            String winner = match.redScore > match.blueScore ? red : blue;
            result = winner + " has won by " + diff + (diff == 1 ? " goal!" : " goals!");
        }

        discord.matchUpdate(cancelled ? "match-cancelled-message" : "match-ended-message", Map.of(
                "player", host == null ? "The host" : host,
                "stadium", match.stadium == null ? "the stadium" : match.stadium.name,
                "red", red,
                "blue", blue,
                "redscore", String.valueOf(match.redScore),
                "bluescore", String.valueOf(match.blueScore),
                "result", result));
    }

    private Component resultLine(Match match) {
        int diff = Math.abs(match.redScore - match.blueScore);
        if (diff == 0) {
            return Component.text("The match ended in a draw!", NamedTextColor.YELLOW, TextDecoration.BOLD);
        }
        Team winner = match.redScore > match.blueScore ? Team.RED : Team.BLUE;
        if (match.duel) {
            return Component.text(match.duelName(winner), NamedTextColor.WHITE, TextDecoration.BOLD)
                    .append(Component.text(" has won by " + diff + (diff == 1 ? " goal!" : " goals!"),
                            NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        return Component.text(winner.label, winner.color, TextDecoration.BOLD)
                .append(Component.text(" has won by " + diff + (diff == 1 ? " goal!" : " goals!"),
                        NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
