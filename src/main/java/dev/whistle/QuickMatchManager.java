package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quickmatches: anyone can /votematch a stadium and length (or use the /quickmatch menu, which needs no typing, for
 * example on Bedrock). Once enough players have voted for the same match, a
 * 60 second lobby opens (anyone can click to join), and then the match starts like a league match, but with a single
 * period of the voted length and no half time.
 */
final class QuickMatchManager implements CommandExecutor, TabCompleter, Listener {

    static final int MIN_VOTES = 4;
    static final int DEFAULT_MINUTES = 5;
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 10;
    /** Time from the match opening to kick-off. The last 5 seconds are the normal frozen start countdown. */
    static final int LOBBY_SECONDS = 60;
    static final int FREEZE_SECONDS = 5;
    private static final long VOTE_EXPIRY_MS = 10 * 60_000L;

    private record Option(String stadiumKey, int minutes) {}

    private static final class Lobby {
        private final Option option;
        private final Stadium stadium;
        private final Set<UUID> players = new LinkedHashSet<>();
        private int secondsLeft = LOBBY_SECONDS;
        private BukkitTask task;

        private Lobby(Option option, Stadium stadium) {
            this.option = option;
            this.stadium = stadium;
        }
    }

    private final JavaPlugin plugin;
    private final StadiumManager stadiums;
    private final MatchManager matches;
    private final LobbyManager lobbyLocations;
    private final BukkitTask expiryTask;
    private final NamespacedKey menuKey;
    private final LobbyBall lobbyBall; // a football to kick around while waiting in the lobby

    /** Where each player was before being sent to the quickmatch lobby, so they can be sent back. */
    private final Map<UUID, Location> origins = new HashMap<>();

    /** option -> (voter -> time of vote). One vote per player: voting for something else moves the vote. */
    private final Map<Option, Map<UUID, Long>> votes = new LinkedHashMap<>();
    private final Map<Option, Lobby> lobbies = new LinkedHashMap<>();

    QuickMatchManager(JavaPlugin plugin, StadiumManager stadiums, MatchManager matches, LobbyManager lobbyLocations) {
        this.plugin = plugin;
        this.stadiums = stadiums;
        this.matches = matches;
        this.lobbyLocations = lobbyLocations;
        this.menuKey = new NamespacedKey(plugin, "quickmatch_menu");
        this.lobbyBall = new LobbyBall(plugin, matches, () -> lobbyLocations.get(LobbyManager.QUICKMATCH), origins::size);
        matches.addLobbyBall(lobbyBall);
        // Votes expire on their own, so check regularly (every 10 seconds) rather than only when someone votes.
        this.expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::purgeExpiredVotes, 200L, 200L);
    }

    // ------------------------------------------------------------------ lobby teleport

    /** Teleports the player to the quickmatch lobby (if an operator has set one), remembering where they were. */
    private void sendToLobby(Player player) {
        Location lobby = lobbyLocations.get(LobbyManager.QUICKMATCH);
        if (lobby == null) return;
        origins.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
        player.teleport(lobby);
        lobbyBall.refresh(); // a football as soon as someone is in the lobby
        player.sendMessage(Component.text("You were teleported to the quickmatch lobby. Use /match leave to go back.",
                NamedTextColor.GRAY));
    }

    /** Teleports the player back to where they were before the lobby, if they were sent there. */
    private void sendBack(UUID id) {
        Location origin = origins.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (origin != null && player != null && player.isOnline()) player.teleport(origin);
        lobbyBall.refresh(); // and no football once the lobby is empty
    }

    private boolean hasVote(UUID id) {
        return votes.values().stream().anyMatch(v -> v.containsKey(id));
    }

    void shutdown() {
        expiryTask.cancel();
        new ArrayList<>(origins.keySet()).forEach(this::sendBack);
        lobbies.values().forEach(l -> {
            if (l.task != null) l.task.cancel();
        });
        lobbies.clear();
        votes.clear();
    }

    /** True if a lobby is waiting to start on this stadium, so nothing else may use it. */
    boolean isReserved(Stadium stadium) {
        return lobbies.values().stream().anyMatch(l -> l.stadium == stadium);
    }

    // ------------------------------------------------------------------ /votematch

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            openMainMenu(player); // no typing needed
            return true;
        }
        if (args.length > 2) {
            error(player, "Usage: /votematch <stadium> [minutes " + MIN_MINUTES + "-" + MAX_MINUTES + "]   or just /quickmatch");
            return true;
        }

        Stadium stadium = stadiums.get(args[0]);
        if (stadium == null) {
            error(player, "No stadium called " + args[0] + ".");
            return true;
        }
        if (!stadium.isComplete()) {
            error(player, stadium.name + " isn't finished, so it can't be used for matches yet.");
            return true;
        }

        int minutes = DEFAULT_MINUTES;
        if (args.length == 2) {
            try {
                minutes = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                minutes = -1;
            }
            if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
                error(player, "The length must be a whole number of minutes from " + MIN_MINUTES + " to " + MAX_MINUTES + ".");
                return true;
            }
        }

        vote(player, stadium, minutes);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return stadiums.names().stream()
                    .filter(n -> n.toLowerCase().startsWith(prefix))
                    .filter(n -> {
                        Stadium s = stadiums.get(n);
                        return s != null && s.isComplete();
                    })
                    .toList();
        }
        if (args.length == 2) {
            List<String> lengths = new ArrayList<>();
            for (int i = MIN_MINUTES; i <= MAX_MINUTES; i++) lengths.add(String.valueOf(i));
            return lengths.stream().filter(n -> n.startsWith(args[1])).toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------ the menu (no typing, for example for Bedrock players)

    private enum MenuType { MAIN, LENGTH, STADIUMS, OPEN }

    private static final int SLOT_VOTE = 11;
    private static final int SLOT_OPEN = 15;
    private static final int SLOT_LEAVE = 22;
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;

    private static final class Gui implements InventoryHolder {
        private final MenuType type;
        private final int minutes;
        private final int page;
        private final int pages;
        private Inventory inventory;

        private Gui(MenuType type, int minutes, int page, int pages) {
            this.type = type;
            this.minutes = minutes;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** /quickmatch: vote for a match, join one that is already open, or leave. */
    private void openMainMenu(Player player) {
        purgeExpiredVotes();
        UUID id = player.getUniqueId();
        Gui holder = new Gui(MenuType.MAIN, 0, 0, 0);
        Inventory inventory = newMenu(holder, "Quickmatch");
        inventory.setItem(SLOT_VOTE, button(Material.CLOCK, "Vote for a Quickmatch", NamedTextColor.GOLD,
                plain("Choose a length, then a stadium.", NamedTextColor.GRAY),
                plain("When " + MIN_VOTES + " players vote for the same match,", NamedTextColor.GRAY),
                plain("it opens for everyone to join.", NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        int open = lobbies.size() + (int) votes.keySet().stream().filter(o -> !lobbies.containsKey(o)).count();
        inventory.setItem(SLOT_OPEN, button(Material.COMPASS, "Open Quickmatches", NamedTextColor.GOLD,
                plain("Join a match someone has already voted for.", NamedTextColor.GRAY),
                plain(open == 0 ? "None right now" : open + " available", open == 0 ? NamedTextColor.RED : NamedTextColor.GREEN),
                plain("Click to select", NamedTextColor.YELLOW)));
        if (lobbyOf(id) != null || hasVote(id)) {
            inventory.setItem(SLOT_LEAVE, button(Material.RED_CONCRETE, "Leave Quickmatch", NamedTextColor.RED,
                    plain(lobbyOf(id) != null ? "You are in a quickmatch lobby." : "You have voted for a quickmatch.",
                            NamedTextColor.GRAY),
                    plain("Click to leave and go back", NamedTextColor.YELLOW)));
        }
        player.openInventory(inventory);
    }

    /** Step one of voting: how long the match is. */
    private void openLengthMenu(Player player) {
        Gui holder = new Gui(MenuType.LENGTH, 0, 0, 0);
        Inventory inventory = newMenu(holder, "Quickmatch - Choose Length");
        for (int minutes = MIN_MINUTES; minutes <= MAX_MINUTES; minutes++) {
            int slot = minutes <= 5 ? 1 + minutes : 10 + (minutes - 5); // two rows of five
            ItemStack item = button(Material.CLOCK, minutes + (minutes == 1 ? " minute" : " minutes"), NamedTextColor.GOLD,
                    plain(minutes == DEFAULT_MINUTES ? "The usual length" : "A " + minutes + " minute match", NamedTextColor.GRAY),
                    plain("Click to choose", NamedTextColor.YELLOW));
            item.setAmount(minutes);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, "minutes:" + minutes);
            if (minutes == DEFAULT_MINUTES) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE));
        player.openInventory(inventory);
    }

    /** Step two of voting: which stadium (the icon is the stadium's /stadium block). */
    private void openStadiumMenu(Player player, int minutes, int page) {
        purgeExpiredVotes();
        List<Stadium> available = new ArrayList<>();
        for (String name : stadiums.names()) {
            Stadium stadium = stadiums.get(name);
            if (stadium != null && stadium.isComplete()) available.add(stadium);
        }
        int pages = Math.max(1, (available.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(1, Math.min(page, pages));

        Gui holder = new Gui(MenuType.STADIUMS, minutes, page, pages);
        Inventory inventory = newMenu(holder, "Quickmatch - " + minutes + (minutes == 1 ? " minute" : " minutes") + " - Stadium");
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= available.size()) break;
            Stadium stadium = available.get(index);
            Option option = new Option(stadium.name.toLowerCase(), minutes);

            List<Component> lore = new ArrayList<>();
            Lobby lobby = lobbies.get(option);
            if (lobby != null) {
                lore.add(plain("Open now: " + lobby.players.size() + " joined, starts in " + lobby.secondsLeft + "s", NamedTextColor.GREEN));
                lore.add(plain("Click to join", NamedTextColor.YELLOW));
            } else if (matches.stadiumInUse(stadium) || isReserved(stadium)) {
                lore.add(plain("In use right now", NamedTextColor.RED));
            } else {
                int voted = votes.getOrDefault(option, Map.of()).size();
                lore.add(plain("Votes: " + voted + "/" + MIN_VOTES, NamedTextColor.GRAY));
                lore.add(plain("Click to vote for a " + minutes + " minute match here", NamedTextColor.YELLOW));
            }
            inventory.setItem(i, stadiumItem(stadium, option, lore));
        }
        if (available.isEmpty()) {
            inventory.setItem(13, button(Material.BARRIER, "No stadiums yet", NamedTextColor.RED,
                    plain("An operator needs to finish a stadium first.", NamedTextColor.GRAY)));
        }
        addPaging(inventory, pages);
        player.openInventory(inventory);
    }

    /** Matches that are open to join, and matches that already have votes. */
    private void openOpenMenu(Player player) {
        purgeExpiredVotes();
        List<Option> options = new ArrayList<>(lobbies.keySet());
        for (Option option : votes.keySet()) {
            if (!lobbies.containsKey(option)) options.add(option);
        }

        Gui holder = new Gui(MenuType.OPEN, 0, 0, 0);
        Inventory inventory = newMenu(holder, "Open Quickmatches");
        int slot = 0;
        for (Option option : options) {
            if (slot >= PAGE_SIZE) break;
            Stadium stadium = stadiums.get(option.stadiumKey());
            if (stadium == null) continue;
            List<Component> lore = new ArrayList<>();
            lore.add(plain(option.minutes() + (option.minutes() == 1 ? " minute" : " minutes"), NamedTextColor.GRAY));
            Lobby lobby = lobbies.get(option);
            if (lobby != null) {
                lore.add(plain("Open now: " + lobby.players.size() + " joined, starts in " + lobby.secondsLeft + "s", NamedTextColor.GREEN));
                lore.add(plain("Click to join", NamedTextColor.YELLOW));
            } else {
                lore.add(plain("Votes: " + votes.get(option).size() + "/" + MIN_VOTES, NamedTextColor.GRAY));
                lore.add(plain("Click to vote for it", NamedTextColor.YELLOW));
            }
            inventory.setItem(slot++, stadiumItem(stadium, option, lore));
        }
        if (slot == 0) {
            inventory.setItem(13, button(Material.BARRIER, "Nothing open right now", NamedTextColor.RED,
                    plain("Go back and vote for a quickmatch yourself.", NamedTextColor.GRAY)));
        }
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE));
        player.openInventory(inventory);
    }

    private ItemStack stadiumItem(Stadium stadium, Option option, List<Component> lore) {
        ItemStack item = button(stadium.block != null ? stadium.block : Material.PLAYER_HEAD, stadium.name,
                NamedTextColor.GOLD, lore.toArray(new Component[0]));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING,
                "option:" + option.stadiumKey() + ":" + option.minutes());
        item.setItemMeta(meta);
        return item;
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

        switch (menu.type) {
            case MAIN -> {
                if (slot == SLOT_VOTE) later(() -> openLengthMenu(player));
                else if (slot == SLOT_OPEN) later(() -> openOpenMenu(player));
                else if (slot == SLOT_LEAVE) {
                    later(() -> {
                        leaveLobby(player);
                        openMainMenu(player);
                    });
                }
            }
            case LENGTH -> {
                if (slot == SLOT_BACK) {
                    later(() -> openMainMenu(player));
                    return;
                }
                String key = menuValue(event.getCurrentItem());
                if (key != null && key.startsWith("minutes:")) {
                    int minutes = Integer.parseInt(key.substring("minutes:".length()));
                    later(() -> openStadiumMenu(player, minutes, 1));
                }
            }
            case STADIUMS, OPEN -> {
                if (slot == SLOT_BACK) {
                    later(() -> {
                        if (menu.type == MenuType.STADIUMS) openLengthMenu(player);
                        else openMainMenu(player);
                    });
                } else if ((slot == SLOT_PREVIOUS || slot == SLOT_NEXT) && menu.type == MenuType.STADIUMS) {
                    int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
                    if (target >= 1 && target <= menu.pages) later(() -> openStadiumMenu(player, menu.minutes, target));
                } else if (slot < PAGE_SIZE) {
                    String key = menuValue(event.getCurrentItem());
                    if (key == null || !key.startsWith("option:")) return;
                    String[] parts = key.split(":");
                    Stadium stadium = stadiums.get(parts[1]);
                    int minutes = Integer.parseInt(parts[2]);
                    later(() -> {
                        player.closeInventory();
                        if (stadium == null || !stadium.isComplete()) error(player, "That stadium isn't available any more.");
                        else vote(player, stadium, minutes);
                    });
                }
            }
        }
    }

    private String menuValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
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

    // ------------------------------------------------------------------ votes

    private void vote(Player player, Stadium stadium, int minutes) {
        purgeExpiredVotes();
        UUID id = player.getUniqueId();
        Option option = new Option(stadium.name.toLowerCase(), minutes);

        if (matches.isInMatch(id)) {
            error(player, "You are already in a match.");
            return;
        }
        if (matches.isWatching(id)) {
            error(player, "You are watching a match. Use /match leave first.");
            return;
        }
        Lobby current = lobbyOf(id);
        Lobby wanted = lobbies.get(option);
        if (current != null && current != wanted) {
            error(player, "You have already joined another quickmatch. Use /match leave first.");
            return;
        }

        // The match is already open: voting for it again (clicking the message) joins it.
        if (wanted != null) {
            join(player, wanted);
            return;
        }

        if (matches.stadiumInUse(stadium) || isReserved(stadium)) {
            error(player, stadium.name + " is being used for a match right now. Try again later or pick another stadium.");
            return;
        }

        Map<UUID, Long> voters = votes.computeIfAbsent(option, k -> new LinkedHashMap<>());
        if (voters.containsKey(id)) {
            error(player, "You have already voted for this match.");
            return;
        }
        removeVotes(id); // a player has one vote at a time
        voters = votes.computeIfAbsent(option, k -> new LinkedHashMap<>());
        voters.put(id, System.currentTimeMillis());
        sendToLobby(player);

        // Clicking this message votes for the same match, which sends it again.
        Component message = Component.text(player.getName() + " has voted for a " + minutes + " minute quickmatch on "
                        + stadium.name + "! Click to vote for this match, or use /quickmatch.", NamedTextColor.GOLD)
                .clickEvent(ClickEvent.runCommand("/votematch " + stadium.name + " " + minutes))
                .hoverEvent(HoverEvent.showText(Component.text("Click to vote for this match", NamedTextColor.YELLOW)));
        Bukkit.getServer().sendMessage(message);

        if (voters.size() >= MIN_VOTES) openLobby(option, stadium);
    }

    private void purgeExpiredVotes() {
        long now = System.currentTimeMillis();
        Set<UUID> expired = new HashSet<>();
        votes.values().forEach(v -> v.entrySet().removeIf(e -> {
            if (now - e.getValue() <= VOTE_EXPIRY_MS) return false;
            expired.add(e.getKey());
            return true;
        }));
        votes.values().removeIf(Map::isEmpty);
        // Anyone whose only vote ran out goes back to where they were.
        for (UUID id : expired) {
            if (!hasVote(id) && lobbyOf(id) == null) sendBack(id);
        }
    }

    private void removeVotes(UUID id) {
        votes.values().forEach(v -> v.remove(id));
        votes.values().removeIf(Map::isEmpty);
    }

    // ------------------------------------------------------------------ lobby

    private void openLobby(Option option, Stadium stadium) {
        Map<UUID, Long> voters = votes.remove(option);
        Lobby lobby = new Lobby(option, stadium);
        if (voters != null) {
            for (UUID id : voters.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !matches.isInMatch(id) && lobby.players.size() < Match.MAX_PER_TEAM * 2) {
                    lobby.players.add(id);
                } else {
                    sendBack(id); // no room (or gone), so don't leave them waiting in the lobby
                }
            }
        }
        lobbies.put(option, lobby);

        Component message = Component.text("A " + option.minutes() + " minute quickmatch is starting soon on "
                        + stadium.name + "! Click to join match.", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/votematch " + stadium.name + " " + option.minutes()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to join the match", NamedTextColor.YELLOW)));
        Bukkit.getServer().sendMessage(message);

        lobby.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(lobby), 20L, 20L);
    }

    private void join(Player player, Lobby lobby) {
        UUID id = player.getUniqueId();
        if (lobby.players.contains(id)) {
            error(player, "You have already joined this quickmatch.");
            return;
        }
        if (lobby.players.size() >= Match.MAX_PER_TEAM * 2) {
            error(player, "This quickmatch is full (" + Match.MAX_PER_TEAM * 2 + " players).");
            return;
        }
        removeVotes(id);
        lobby.players.add(id);
        sendToLobby(player);

        Component joined = Component.text(player.getName() + " joined the quickmatch on " + lobby.stadium.name + " ("
                + lobby.players.size() + "/" + Match.MAX_PER_TEAM * 2 + ").", NamedTextColor.GREEN);
        for (UUID member : lobby.players) {
            Player p = Bukkit.getPlayer(member);
            if (p != null) p.sendMessage(joined);
        }
    }

    private void tick(Lobby lobby) {
        lobby.secondsLeft--;

        // The last 5 seconds are the normal frozen countdown, so the match itself takes over there.
        if (lobby.secondsLeft <= FREEZE_SECONDS) {
            lobby.task.cancel();
            lobbies.remove(lobby.option);
            begin(lobby);
            return;
        }

        Component bar = Component.text("Quickmatch on " + lobby.stadium.name + " starts in " + lobby.secondsLeft + "s ("
                + lobby.players.size() + "/" + Match.MAX_PER_TEAM * 2 + " players)", NamedTextColor.GOLD, TextDecoration.BOLD);
        for (UUID id : lobby.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendActionBar(bar);
        }
    }

    private void begin(Lobby lobby) {
        List<Player> players = new ArrayList<>();
        for (UUID id : lobby.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        if (matches.startQuickMatch(lobby.stadium, lobby.option.minutes(), players, origins)) {
            // The match moves them to their stadium positions itself, so they no longer need sending back.
            players.forEach(p -> origins.remove(p.getUniqueId()));
        } else {
            Component cancelled = Component.text("The quickmatch on " + lobby.stadium.name
                    + " was cancelled: not enough players or the stadium is no longer free.", NamedTextColor.RED);
            players.forEach(p -> p.sendMessage(cancelled));
            lobby.players.forEach(this::sendBack);
        }
    }

    private Lobby lobbyOf(UUID id) {
        for (Lobby lobby : lobbies.values()) {
            if (lobby.players.contains(id)) return lobby;
        }
        return null;
    }

    /** /match leave while voting or waiting in a lobby. Returns true if the player was doing either. */
    boolean leaveLobby(Player player) {
        UUID id = player.getUniqueId();
        Lobby lobby = lobbyOf(id);
        if (lobby != null) {
            lobby.players.remove(id);
            sendBack(id);
            player.sendMessage(Component.text("You left the quickmatch.", NamedTextColor.YELLOW));
            return true;
        }
        if (hasVote(id)) {
            removeVotes(id);
            sendBack(id);
            player.sendMessage(Component.text("You withdrew your quickmatch vote.", NamedTextColor.YELLOW));
            return true;
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        removeVotes(id);
        Lobby lobby = lobbyOf(id);
        if (lobby != null) lobby.players.remove(id);
        sendBack(id); // otherwise they'd log back in standing in the lobby
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
