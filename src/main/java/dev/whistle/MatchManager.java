package dev.whistle;

import dev.whistle.Match.Team;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
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
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Handles /match, the invite GUI, invites and the football itself. */
final class MatchManager implements CommandExecutor, TabCompleter, Listener {

    private static final long INVITE_TIMEOUT_MS = 60_000;
    private static final int HALF_SECONDS = 5 * 60;   // length of each half
    private static final int COUNTDOWN_SECONDS = 5;   // break between the halves
    private static final List<String> SUBCOMMANDS =
            List.of("create", "invite", "stadium", "start", "goal", "end", "leave", "accept", "decline");

    private record Invite(UUID host, Team team, long time) {}

    private enum MenuType { MAIN, TEAMS, STADIUMS, PLAYERS }

    /** Marks one of our chest menus so clicks can be recognised, and remembers which one it is. */
    private static final class MenuGui implements InventoryHolder {
        private final MenuType type;
        private final int page;   // 1-based, only used by the paged menus
        private final int pages;  // total pages, only used by the paged menus
        private final Team team;  // which team the player menu invites to
        private Inventory inventory;

        private MenuGui(MenuType type, int page, int pages, Team team) {
            this.type = type;
            this.page = page;
            this.pages = pages;
            this.team = team;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // Slot layout (all menus are 3 rows).
    private static final int SLOT_CANCEL = 10;
    private static final int SLOT_INVITE = 12;
    private static final int SLOT_STADIUM = 14;
    private static final int SLOT_START = 16;
    private static final int SLOT_RED_TEAM = 11;
    private static final int SLOT_BLUE_TEAM = 15;
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_PAGE = 22;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;   // two rows of heads
    private static final int MAX_PAGES = 64;   // the page number is the banner's stack size

    private final JavaPlugin plugin;
    private final StadiumManager stadiums;
    private final NamespacedKey ballKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey jerseyKey;
    private final NamespacedKey stadiumKey;
    private final Map<UUID, Match> matchByPlayer = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    MatchManager(JavaPlugin plugin, StadiumManager stadiums) {
        this.plugin = plugin;
        this.stadiums = stadiums;
        this.ballKey = new NamespacedKey(plugin, "football");
        this.targetKey = new NamespacedKey(plugin, "invite_target");
        this.jerseyKey = new NamespacedKey(plugin, "jersey");
        this.stadiumKey = new NamespacedKey(plugin, "stadium_choice");

        // Checks for the ball entering a goal area every 2 ticks.
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkGoals, 2L, 2L);
    }

    /** Removes every ball on shutdown so none are left behind. */
    void shutdown() {
        new ArrayList<>(matchByPlayer.values()).forEach(m -> {
            cancelTasks(m);
            m.onlineMembers().forEach(p -> restoreChestplate(m, p));
            if (m.ball != null) m.ball.remove();
        });
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            error(player, "Usage: /match <create|invite|stadium|start|goal|end|leave|accept|decline>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "invite" -> {
                Match match = hostedMatch(player);
                if (match != null) openTeamMenu(player);
            }
            case "stadium" -> chooseStadium(player, args);
            case "start" -> start(player);
            case "goal" -> goal(player, args);
            case "end" -> {
                Match match = hostedMatch(player);
                if (match != null) endMatch(match, "The match was ended by the host.");
            }
            case "leave" -> leave(player);
            case "accept" -> accept(player);
            case "decline" -> decline(player);
            default -> error(player, "Unknown subcommand. Use /match <create|invite|stadium|start|goal|end|leave|accept|decline>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("goal")) {
            return List.of("red", "blue");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stadium")) {
            return stadiums.names().stream().filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create") && sender.hasPermission("wfsbbfl.bypass")) {
            return List.of("bypass");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("goal")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }

    private void create(Player host, String[] args) {
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
        if (isFrozen(match)) freezeBall(match);

        if (firstStart) {
            for (Player p : match.onlineMembers()) {
                match.startPositions.put(p.getUniqueId(), p.getLocation().clone());
                match.showBar(p);
                giveJersey(match, p);
            }
            match.broadcast(Component.text("The match has started!", NamedTextColor.GREEN, TextDecoration.BOLD));
            startTimer(match);
        } else {
            host.sendMessage(Component.text("The ball was moved to your location.", NamedTextColor.YELLOW));
        }
        return true;
    }

    /** Cancels a match that hasn't started: no overview, no announcements except to anyone who had already joined. */
    private void cancelMatch(Match match) {
        cancelTasks(match);
        if (match.ball != null) match.ball.remove();
        for (Player p : match.onlineMembers()) {
            restoreChestplate(match, p);
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

    private void goal(Player host, String[] args) {
        Match match = hostedMatch(host);
        if (match == null) return;
        if (!match.started) {
            error(host, "The match hasn't started yet.");
            return;
        }
        if (match.phase == Match.Phase.COUNTDOWN) {
            error(host, "You can't score during a countdown.");
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("red") || args[1].equalsIgnoreCase("blue"))) {
            error(host, "Usage: /match goal <red|blue> [player]");
            return;
        }
        Team team = args[1].equalsIgnoreCase("red") ? Team.RED : Team.BLUE;
        String scorer = args.length >= 3 ? args[2] : lastScorerName(match);
        scoreGoal(match, team, scorer);
    }

    /** Whoever last kicked the ball, otherwise the host. */
    private String lastScorerName(Match match) {
        UUID id = match.lastKicker != null ? match.lastKicker : match.host;
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : "Unknown";
    }

    /** Adds the goal, shows the title and starts the 5 second freeze. */
    private void scoreGoal(Match match, Team team, String scorer) {
        if (match.phase == Match.Phase.COUNTDOWN) return;
        match.addGoal(team);

        // The title stays up for the whole freeze; the countdown itself runs on the action bar.
        Title title = Title.title(
                Component.text(team.label + " Goal!", team.color, TextDecoration.BOLD),
                Component.text("Scored by " + scorer, NamedTextColor.WHITE),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(4), Duration.ofMillis(500)));
        for (Player p : match.onlineMembers()) {
            p.showTitle(title);
        }

        beginCountdown(match, Match.CountdownType.GOAL, match.phase);
    }

    /** Detects the ball entering a goal area of the match's stadium. */
    private void checkGoals() {
        for (Match match : new LinkedHashSet<>(matchByPlayer.values())) {
            if (!match.started || match.phase == Match.Phase.COUNTDOWN || match.stadium == null) continue;
            SulfurCube ball = match.ball;
            if (ball == null || !ball.isValid()) continue;

            Location ballCenter = ball.getBoundingBox().getCenter().toLocation(ball.getWorld());
            for (Team defending : Team.values()) {
                if (match.stadium.goalContains(defending, ballCenter)) {
                    Team scoring = defending == Team.RED ? Team.BLUE : Team.RED;
                    scoreGoal(match, scoring, lastScorerName(match));
                    break;
                }
            }
        }
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
        match.stadium = stadium;
        host.sendMessage(Component.text("Stadium set to " + stadium.name + ".", NamedTextColor.GREEN));
        if (!stadium.hasCenter()) {
            host.sendMessage(Component.text("It has no centre point yet, so the ball will use your start position.",
                    NamedTextColor.YELLOW));
        }
        for (Team team : Team.values()) {
            if (!stadium.goalComplete(team)) {
                host.sendMessage(Component.text("The " + team.label
                        + " goal isn't set up yet, so goals there won't be detected.", NamedTextColor.YELLOW));
            }
        }
    }

    /** Where the ball is placed during freezes: the stadium centre if there is one. */
    private Location ballSpawnPoint(Match match) {
        if (match.stadium != null && match.stadium.hasCenter()) return match.stadium.center.clone();
        return match.ballStart;
    }

    private void leave(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You are not in a match.");
            return;
        }
        if (match.host.equals(player.getUniqueId())) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
            return;
        }
        removeMember(match, player);
        player.sendMessage(Component.text("You left the match.", NamedTextColor.YELLOW));
        match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
    }

    private void accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || System.currentTimeMillis() - invite.time() > INVITE_TIMEOUT_MS) {
            error(player, "You have no pending invite.");
            return;
        }
        Match match = matchByPlayer.get(invite.host());
        if (match == null || !match.host.equals(invite.host())) {
            error(player, "That match no longer exists.");
            return;
        }
        if (matchByPlayer.containsKey(player.getUniqueId())) {
            error(player, "You are already in a match!");
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
        if (!match.host.equals(player.getUniqueId())) {
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
        if (match == null || !match.host.equals(host.getUniqueId())) return null;
        return match;
    }

    /** First menu of /match create: cancel, invite, change stadium, start. */
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
        inventory.setItem(SLOT_STADIUM, button(Material.BIRCH_LOG, "Change Stadium", NamedTextColor.BLUE,
                plain("Current: " + (match.stadium == null ? "none chosen" : match.stadium.name), NamedTextColor.GRAY)));
        inventory.setItem(SLOT_START, button(Material.LIME_CONCRETE, "Start Match", NamedTextColor.GREEN));
        host.openInventory(inventory);
    }

    /** Invite menu: pick which team to invite players to. */
    private void openTeamMenu(Player host) {
        Match match = menuMatch(host);
        if (match == null) return;

        MenuGui holder = new MenuGui(MenuType.TEAMS, 0, 0, null);
        Inventory inventory = newMenu(holder, "Choose Team");
        inventory.setItem(SLOT_RED_TEAM, teamItem(match, Team.RED));
        inventory.setItem(SLOT_BLUE_TEAM, teamItem(match, Team.BLUE));
        inventory.setItem(SLOT_BACK, backButton());
        host.openInventory(inventory);
    }

    private ItemStack teamItem(Match match, Team team) {
        ItemStack item = button(Material.LEATHER_CHESTPLATE, team.label + " Team", team.color,
                plain(match.size(team) + "/" + Match.MAX_PER_TEAM + " players", NamedTextColor.GRAY));
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(team.color.value()));
        meta.addItemFlags(ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backButton() {
        return button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE);
    }

    /** The stadium list (MenuType.STADIUMS) and the player list for a team (MenuType.PLAYERS) share one layout. */
    private void openPagedMenu(Player host, MenuType type, int page, Team team) {
        Match match = menuMatch(host);
        if (match == null) return;

        List<ItemStack> entries = new ArrayList<>();
        if (type == MenuType.STADIUMS) {
            for (String name : stadiums.names()) {
                Stadium stadium = stadiums.get(name);
                if (stadium != null) entries.add(createStadiumItem(stadium));
            }
        } else {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.getUniqueId().equals(host.getUniqueId())) continue;
                if (matchByPlayer.containsKey(other.getUniqueId())) continue;
                entries.add(createHead(other));
            }
        }

        int pages = Math.max(1, Math.min(MAX_PAGES, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));

        MenuGui holder = new MenuGui(type, page, pages, team);
        String title = type == MenuType.STADIUMS ? "Choose Stadium" : "Invite to " + team.label + " Team";
        Inventory inventory = newMenu(holder, title);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            inventory.setItem(i, entries.get(index));
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
                statusLore("Red goal", stadium.goalComplete(Team.RED)),
                statusLore("Blue goal", stadium.goalComplete(Team.BLUE)),
                Component.empty(),
                plain("Click to select", NamedTextColor.YELLOW)));
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
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile());
        meta.displayName(bold(target.getName(), NamedTextColor.GOLD));
        meta.lore(List.of(plain("Click to invite", NamedTextColor.YELLOW)));
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

        Match match = menuMatch(host);
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
                    case SLOT_STADIUM -> later(() -> openPagedMenu(host, MenuType.STADIUMS, 1, null));
                    case SLOT_START -> later(() -> {
                        if (beginMatch(host, match)) host.closeInventory();
                        else openMainMenu(host); // not ready: warn in chat and show the menu again
                    });
                    default -> { }
                }
            }
            case TEAMS -> {
                switch (slot) {
                    case SLOT_RED_TEAM -> later(() -> openPagedMenu(host, MenuType.PLAYERS, 1, Team.RED));
                    case SLOT_BLUE_TEAM -> later(() -> openPagedMenu(host, MenuType.PLAYERS, 1, Team.BLUE));
                    case SLOT_BACK -> later(() -> {
                        if (match.started) host.closeInventory();
                        else openMainMenu(host);
                    });
                    default -> { }
                }
            }
            case STADIUMS, PLAYERS -> {
                if (slot == SLOT_BACK) {
                    later(() -> {
                        if (menu.type == MenuType.PLAYERS) openTeamMenu(host);
                        else openMainMenu(host);
                    });
                } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
                    int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
                    if (target >= 1 && target <= menu.pages) {
                        later(() -> openPagedMenu(host, menu.type, target, menu.team));
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
            invite(host, target, menu.team);
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

    private void invite(Player host, Player target, Team team) {
        Match match = matchByPlayer.get(host.getUniqueId());
        if (match == null || !match.host.equals(host.getUniqueId())) return;

        if (matchByPlayer.containsKey(target.getUniqueId())) {
            error(host, target.getName() + " is already in a match.");
            return;
        }
        if (match.isFull(team)) {
            error(host, "The " + team.label + " team is full (" + Match.MAX_PER_TEAM + "/" + Match.MAX_PER_TEAM + ").");
            return;
        }

        invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), team, System.currentTimeMillis()));

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

        match.ball = location.getWorld().spawn(location, SulfurCube.class, cube -> {
            cube.setSize(2);
            cube.setAdult();
            cube.setAgeLock(true);
            cube.customName(Component.text("Football", NamedTextColor.YELLOW, TextDecoration.BOLD));
            cube.setCustomNameVisible(true);
            cube.setGlowing(true);
            cube.setAware(false);              // no AI: it only moves when kicked
            cube.setPersistent(true);
            cube.setRemoveWhenFarAway(false);
            cube.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private boolean isBall(Entity entity) {
        return entity instanceof SulfurCube
                && entity.getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE);
    }

    private boolean isFrozen(Match match) {
        return match.started && match.phase == Match.Phase.COUNTDOWN;
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

    /** During the half-time countdown players can look around but can't move. */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Match match = matchByPlayer.get(event.getPlayer().getUniqueId());
        if (match == null || !isFrozen(match)) return;

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

        Match ballMatch = matchOfBall(event.getEntity());
        if (ballMatch != null && isFrozen(ballMatch)) return; // ball is frozen during the countdown

        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker) {
            Vector direction = kicker.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() < 1.0E-4) {
                direction = new Vector(0, 0, 1);
            }
            Match kickerMatch = matchByPlayer.get(kicker.getUniqueId());
            if (kickerMatch != null) kickerMatch.lastKicker = kicker.getUniqueId();
            double power = kicker.isSprinting() ? 1.5 : 1.0;
            Vector velocity = direction.normalize().multiply(power).setY(0.35);
            event.getEntity().setVelocity(velocity);
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
        match.secondsLeft = HALF_SECONDS;
        match.timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickTimer(match), 20L, 20L);
        beginCountdown(match, Match.CountdownType.START, Match.Phase.FIRST_HALF);
    }

    /** Ticks once a second. Play time is paused while a countdown is running. */
    private void tickTimer(Match match) {
        if (match.phase == Match.Phase.COUNTDOWN) return;

        match.secondsLeft--;
        if (match.secondsLeft > 0) {
            updateTimerBar(match);
            return;
        }
        if (match.phase == Match.Phase.FIRST_HALF) {
            match.secondsLeft = HALF_SECONDS;
            beginCountdown(match, Match.CountdownType.HALF_TIME, Match.Phase.SECOND_HALF);
        } else {
            endMatch(match, "Full time!");
        }
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
        match.countdownLeft = COUNTDOWN_SECONDS;

        // After a goal or at half time, everyone (and the ball) goes back to the start positions.
        if (type != Match.CountdownType.START) {
            resetPositions(match);
        } else if (match.stadium != null && match.stadium.hasCenter()) {
            spawnBall(match, match.stadium.center.clone());
        }
        freezeBall(match);
        showCountdown(match);

        match.countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            match.countdownLeft--;
            if (match.countdownLeft <= 0) {
                match.countdownTask.cancel();
                endCountdown(match);
            } else {
                showCountdown(match);
            }
        }, 20L, 20L);
    }

    private void resetPositions(Match match) {
        for (Player p : match.onlineMembers()) {
            Location start = match.startPositions.get(p.getUniqueId());
            if (start != null) p.teleport(start);
        }
        Location ballSpawn = ballSpawnPoint(match);
        if (ballSpawn != null) spawnBall(match, ballSpawn);
    }

    private void endCountdown(Match match) {
        match.phase = match.resumePhase;
        if (match.ball != null) match.ball.setGravity(true);
        updateTimerBar(match);

        Component subtitle = switch (match.countdownType) {
            case START -> Component.text("First half", NamedTextColor.GRAY);
            case HALF_TIME -> Component.text("Second half", NamedTextColor.GRAY);
            case GOAL -> Component.empty();
        };
        Title title = Title.title(
                Component.text("Kick off!", NamedTextColor.GREEN, TextDecoration.BOLD), subtitle);
        for (Player p : match.onlineMembers()) p.showTitle(title);
    }

    private void updateTimerBar(Match match) {
        int seconds = Math.max(match.secondsLeft, 0);
        String time = String.format("%d:%02d", seconds / 60, seconds % 60);
        String half = match.phase == Match.Phase.FIRST_HALF ? "First Half" : "Second Half";

        match.timerBar.color(BossBar.Color.YELLOW);
        match.timerBar.progress(Math.min(1.0f, seconds / (float) HALF_SECONDS));
        match.timerBar.name(Component.text(half + " - " + time, NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    private void showCountdown(Match match) {
        int n = match.countdownLeft;
        String label = switch (match.countdownType) {
            case START -> "Match starts in " + n;
            case HALF_TIME -> "Half Time - second half starts in " + n;
            case GOAL -> "Goal! Play resumes in " + n;
        };
        match.timerBar.color(BossBar.Color.GREEN);
        match.timerBar.progress(1.0f);
        match.timerBar.name(Component.text(label, NamedTextColor.GREEN, TextDecoration.BOLD));

        if (match.countdownType == Match.CountdownType.GOAL) {
            // The goal title is still on screen, so count down on the action bar instead.
            Component actionBar = Component.text("Play resumes in " + n, NamedTextColor.GOLD, TextDecoration.BOLD);
            for (Player p : match.onlineMembers()) p.sendActionBar(actionBar);
            return;
        }

        Component subtitle = Component.text(
                match.countdownType == Match.CountdownType.START ? "Match starts in..." : "Second half starts in...",
                NamedTextColor.GRAY);
        Title title = Title.title(
                Component.text(String.valueOf(n), NamedTextColor.GOLD, TextDecoration.BOLD),
                subtitle,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)));
        for (Player p : match.onlineMembers()) p.showTitle(title);
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
        ItemStack current = player.getInventory().getChestplate();
        match.savedChestplates.putIfAbsent(player.getUniqueId(),
                current == null || isJersey(current) ? new ItemStack(Material.AIR) : current);
        player.getInventory().setChestplate(createJersey(match.teamOf(player.getUniqueId())));
    }

    private void restoreChestplate(Match match, Player player) {
        ItemStack saved = match.savedChestplates.remove(player.getUniqueId());
        if (saved == null) return;
        player.getInventory().setChestplate(saved.getType() == Material.AIR ? null : saved);
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
        if (match.host.equals(player.getUniqueId())) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
        } else {
            removeMember(match, player);
            match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
        }
    }

    private void removeMember(Match match, Player player) {
        restoreChestplate(match, player);
        match.remove(player.getUniqueId());
        matchByPlayer.remove(player.getUniqueId());
        match.hideBar(player);
    }

    private void endMatch(Match match, String reason) {
        cancelTasks(match);
        if (match.ball != null) match.ball.remove();
        Component message = Component.text(reason, NamedTextColor.YELLOW);

        Component overview = Component.text("Overview:", NamedTextColor.GOLD, TextDecoration.BOLD)
                .appendNewline()
                .append(Component.text("Red Team: ", NamedTextColor.RED)
                        .append(Component.text(match.redScore, NamedTextColor.WHITE)))
                .appendNewline()
                .append(Component.text("Blue Team: ", NamedTextColor.BLUE)
                        .append(Component.text(match.blueScore, NamedTextColor.WHITE)))
                .appendNewline()
                .append(resultLine(match));

        for (Player p : match.onlineMembers()) {
            restoreChestplate(match, p);
            match.hideBar(p);
            p.sendMessage(message);
            p.sendMessage(overview);
        }
        for (UUID id : new ArrayList<>(matchByPlayer.keySet())) {
            if (matchByPlayer.get(id) == match) matchByPlayer.remove(id);
        }
        invites.values().removeIf(invite -> invite.host().equals(match.host));
    }

    private Component resultLine(Match match) {
        int diff = Math.abs(match.redScore - match.blueScore);
        if (diff == 0) {
            return Component.text("The match ended in a draw!", NamedTextColor.YELLOW, TextDecoration.BOLD);
        }
        Team winner = match.redScore > match.blueScore ? Team.RED : Team.BLUE;
        return Component.text(winner.label, winner.color, TextDecoration.BOLD)
                .append(Component.text(" has won by " + diff + (diff == 1 ? " goal!" : " goals!"),
                        NamedTextColor.YELLOW, TextDecoration.BOLD));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
