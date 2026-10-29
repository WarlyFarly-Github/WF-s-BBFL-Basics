package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * /minigames: a chest menu (like /match create) to pick a minigame and invite someone to it. The only minigame so far
 * is Duels, a 5 minute 1v1 quickmatch with no red and blue teams.
 */
final class MinigameManager implements CommandExecutor, TabCompleter, Listener {

    enum Minigame {
        DUELS("Duels", "1v1, 5 minutes"),
        SMASH("Smash", "hit the ball against the wall, 2-" + SmashManager.MAX_PLAYERS + " players"),
        ONEBOUNCE("One-Bounce", "punch the ball to others, one bounce only, 2-" + OneBounceManager.MAX_PLAYERS + " players");

        final String label;
        final String description;

        Minigame(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private enum MenuType { MAIN, SELECT, INVITE }

    private static final class Gui implements InventoryHolder {
        private final MenuType type;
        private final int page;
        private final int pages;
        private Inventory inventory;

        private Gui(MenuType type, int page, int pages) {
            this.type = type;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** A pending invite, kept under the invited player's id. */
    private record Invite(UUID host, Minigame game, long time) {}

    private static final long INVITE_TIMEOUT_MS = 60_000;

    // Same layout as the match menus.
    private static final int SLOT_SELECT = 11;
    private static final int SLOT_INVITE = 15;
    private static final int SLOT_DUELS = 11;
    private static final int SLOT_ONEBOUNCE = 13;
    private static final int SLOT_SMASH = 15;
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_PAGE = 22;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;
    private static final int MAX_PAGES = 64;

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final SmashManager smash;
    private final OneBounceManager oneBounce;
    private final NamespacedKey targetKey;
    private final Map<UUID, Minigame> selected = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    /** Lobbies for the games with more than two players: the host, and everyone who has accepted their invite so far. */
    private final Map<UUID, Set<UUID>> smashLobbies = new HashMap<>();
    private final Map<UUID, Minigame> lobbyGames = new HashMap<>();

    MinigameManager(JavaPlugin plugin, MatchManager matches, SmashManager smash, OneBounceManager oneBounce) {
        this.plugin = plugin;
        this.matches = matches;
        this.smash = smash;
        this.oneBounce = oneBounce;
        this.targetKey = new NamespacedKey(plugin, "minigame_target");
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "accept" -> accept(player);
            case "decline" -> decline(player);
            case "start" -> startLobby(player);
            default -> error(player, "Usage: /minigames");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("accept", "decline", "start").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
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

    private Inventory newMenu(Gui holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        return inventory;
    }

    /** Whether the player is free to start a minigame; tells them why not if they aren't. */
    private boolean checkFree(Player player) {
        if (matches.isInMatch(player.getUniqueId())) {
            error(player, "You are already in a match.");
            return false;
        }
        if (matches.isWatching(player.getUniqueId())) {
            error(player, "You are watching a match. Use /match leave first.");
            return false;
        }
        return true;
    }

    /** Main menu: Select Minigame (lime concrete) and Invite Players (diamond). */
    private void openMainMenu(Player player) {
        if (!checkFree(player)) return;
        Minigame game = selected.get(player.getUniqueId());

        Gui holder = new Gui(MenuType.MAIN, 0, 0);
        Inventory inventory = newMenu(holder, "Minigames");
        inventory.setItem(SLOT_SELECT, button(Material.LIME_CONCRETE, "Select Minigame", NamedTextColor.GREEN,
                plain("Selected: " + (game == null ? "none" : game.label), NamedTextColor.GRAY)));
        inventory.setItem(SLOT_INVITE, button(Material.DIAMOND, "Invite Players", NamedTextColor.BLUE,
                game == null ? plain("Select a minigame first", NamedTextColor.RED)
                        : plain("Invite one player to " + game.label, NamedTextColor.GRAY)));
        player.openInventory(inventory);
    }

    /** Select menu: one button per minigame (just Duels for now), in the middle. */
    private void openSelectMenu(Player player) {
        Gui holder = new Gui(MenuType.SELECT, 0, 0);
        Inventory inventory = newMenu(holder, "Select Minigame");
        inventory.setItem(SLOT_DUELS, button(Material.DIAMOND_SWORD, Minigame.DUELS.label, NamedTextColor.GOLD,
                plain(Minigame.DUELS.description, NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_ONEBOUNCE, button(sulfurCubeSpawnEgg(), Minigame.ONEBOUNCE.label, NamedTextColor.GOLD,
                plain(Minigame.ONEBOUNCE.description, NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_SMASH, button(sulfurWall(), Minigame.SMASH.label, NamedTextColor.GOLD,
                plain(Minigame.SMASH.description, NamedTextColor.GRAY),
                plain("Click to select", NamedTextColor.YELLOW)));
        inventory.setItem(SLOT_BACK, backButton());
        player.openInventory(inventory);
    }

    /** Invite menu: paged player heads, same layout as the match menus. */
    private void openInviteMenu(Player player, int page) {
        List<ItemStack> entries = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) continue;
            if (matches.isInMatch(other.getUniqueId()) || matches.isWatching(other.getUniqueId())) continue;
            entries.add(createHead(other));
        }

        int pages = Math.max(1, Math.min(MAX_PAGES, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));

        Gui holder = new Gui(MenuType.INVITE, page, pages);
        Inventory inventory = newMenu(holder, "Invite Player");
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= entries.size()) break;
            inventory.setItem(i, entries.get(index));
        }
        inventory.setItem(SLOT_PREVIOUS, arrowBanner("Previous Page", false));
        inventory.setItem(SLOT_PAGE, pageMarker(page, pages));
        inventory.setItem(SLOT_NEXT, arrowBanner("Next Page", true));
        inventory.setItem(SLOT_BACK, backButton());
        player.openInventory(inventory);
    }

    /** The sulfur wall block (found by name, so a different name for it in the API can't stop the plugin loading). */
    private static Material sulfurWall() {
        for (String name : List.of("SULFUR_WALL", "SULFUR_BRICK_WALL", "POLISHED_SULFUR_WALL", "SULFUR_BRICKS_WALL")) {
            Material material = Material.matchMaterial(name);
            if (material != null) return material;
        }
        return Material.SANDSTONE_WALL;
    }

    /** The sulfur cube spawn egg (found by name, falling back to the slime egg if it has a different name). */
    private static Material sulfurCubeSpawnEgg() {
        for (String name : List.of("SULFUR_CUBE_SPAWN_EGG", "SULFUR_SPAWN_EGG")) {
            Material material = Material.matchMaterial(name);
            if (material != null) return material;
        }
        return Material.SLIME_SPAWN_EGG;
    }

    private ItemStack backButton() {
        return button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE);
    }

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

    private ItemStack pageMarker(int page, int pages) {
        ItemStack item = new ItemStack(Material.PAPER, page);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold("Page Number", NamedTextColor.WHITE));
        meta.lore(List.of(plain("Page " + page + " of " + pages, NamedTextColor.GRAY)));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createHead(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile());
        meta.displayName(bold(target.getName(), NamedTextColor.GOLD));
        meta.lore(List.of(plain("Click to invite", NamedTextColor.YELLOW)));
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        head.setItemMeta(meta);
        return head;
    }

    /** Menu clicks only ever act on the next tick, never inside the click event. */
    private void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui menu)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) return;

        int slot = event.getSlot();
        switch (menu.type) {
            case MAIN -> {
                if (slot == SLOT_SELECT) {
                    later(() -> openSelectMenu(player));
                } else if (slot == SLOT_INVITE) {
                    if (selected.get(player.getUniqueId()) == null) {
                        // Not allowed yet: back to the menu, with the reason in chat.
                        later(() -> {
                            openMainMenu(player);
                            error(player, "Select a minigame first, then invite a player.");
                        });
                    } else {
                        later(() -> openInviteMenu(player, 1));
                    }
                }
            }
            case SELECT -> {
                if (slot == SLOT_DUELS || slot == SLOT_ONEBOUNCE || slot == SLOT_SMASH) {
                    Minigame game = slot == SLOT_DUELS ? Minigame.DUELS
                            : slot == SLOT_ONEBOUNCE ? Minigame.ONEBOUNCE : Minigame.SMASH;
                    selected.put(player.getUniqueId(), game);
                    later(() -> {
                        openMainMenu(player);
                        player.sendMessage(Component.text("Selected " + game.label + ".", NamedTextColor.GREEN));
                    });
                } else if (slot == SLOT_BACK) {
                    later(() -> openMainMenu(player));
                }
            }
            case INVITE -> {
                if (slot == SLOT_BACK) {
                    later(() -> openMainMenu(player));
                } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
                    int target = menu.page + (slot == SLOT_NEXT ? 1 : -1);
                    if (target >= 1 && target <= menu.pages) later(() -> openInviteMenu(player, target));
                } else if (slot < PAGE_SIZE) {
                    String id = clicked.getItemMeta() == null ? null
                            : clicked.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
                    if (id == null) return;
                    Player target = Bukkit.getPlayer(UUID.fromString(id));
                    later(() -> {
                        if (target == null) {
                            error(player, "That player is no longer online.");
                            openInviteMenu(player, menu.page);
                        } else {
                            sendInvite(player, target);
                        }
                    });
                }
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ invites

    private boolean expired(Invite invite) {
        return System.currentTimeMillis() - invite.time() > INVITE_TIMEOUT_MS;
    }

    private void sendInvite(Player host, Player target) {
        Minigame game = selected.get(host.getUniqueId());
        if (game == null) {
            openMainMenu(host);
            error(host, "Select a minigame first, then invite a player.");
            return;
        }
        if (!checkFree(host)) return;
        if (target.getUniqueId().equals(host.getUniqueId())) {
            error(host, "You can't invite yourself.");
            return;
        }
        if (matches.isInMatch(target.getUniqueId()) || matches.isWatching(target.getUniqueId())) {
            error(host, target.getName() + " is busy.");
            return;
        }

        int outstanding = 0;
        for (Map.Entry<UUID, Invite> entry : invites.entrySet()) {
            Invite existing = entry.getValue();
            if (existing.host().equals(host.getUniqueId()) && !expired(existing)) {
                outstanding++;
                if (game == Minigame.DUELS) {
                    // Duels: only one other player can be invited at a time.
                    Player other = Bukkit.getPlayer(entry.getKey());
                    error(host, "You have already invited " + (other == null ? "a player" : other.getName())
                            + ". You can only invite one player.");
                    host.closeInventory();
                    return;
                }
            }
        }
        if (game != Minigame.DUELS) {
            int joined = smashLobbies.getOrDefault(host.getUniqueId(), Set.of()).size();
            if (1 + joined + outstanding >= SmashManager.MAX_PLAYERS) {
                error(host, game.label + " can have at most " + SmashManager.MAX_PLAYERS + " players, counting you.");
                return;
            }
        }
        Invite pending = invites.get(target.getUniqueId());
        if (pending != null && !expired(pending)) {
            error(host, target.getName() + " already has a pending invite.");
            return;
        }

        invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), game, System.currentTimeMillis()));

        target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" challenged you to " + game.label + " (" + game.description + "). ",
                        NamedTextColor.GRAY))
                .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/minigames accept")))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/minigames decline"))));
        host.sendMessage(Component.text("Invited " + target.getName() + " to " + game.label + ".", NamedTextColor.GREEN));
        if (game != Minigame.DUELS) {
            host.sendMessage(Component.text("Invite more players, or use /minigames start once enough have accepted.",
                    NamedTextColor.GRAY));
        }
        host.closeInventory();
    }

    private void accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || expired(invite)) {
            error(player, "You have no pending minigame invite.");
            return;
        }
        Player host = Bukkit.getPlayer(invite.host());
        if (host == null) {
            error(player, "The player who invited you has gone offline.");
            return;
        }
        if (!checkFree(player)) return;
        if (matches.isInMatch(host.getUniqueId()) || matches.isWatching(host.getUniqueId())) {
            error(player, host.getName() + " is busy now.");
            return;
        }

        if (invite.game() != Minigame.DUELS) {
            joinLobby(host, player, invite.game());
            return;
        }

        String problem = matches.startDuel(host, player);
        if (problem != null) {
            error(player, problem);
            error(host, problem);
        }
    }

    private void joinLobby(Player host, Player player, Minigame game) {
        Set<UUID> lobby = smashLobbies.computeIfAbsent(host.getUniqueId(), k -> new LinkedHashSet<>());
        lobby.add(player.getUniqueId());
        lobbyGames.put(host.getUniqueId(), game);
        int total = 1 + lobby.size();
        player.sendMessage(Component.text("You joined " + host.getName() + "'s " + game.label + " game. Waiting for them to start it.",
                NamedTextColor.GREEN));
        host.sendMessage(Component.text(player.getName() + " joined. " + total + " players ready. ", NamedTextColor.GREEN)
                .append(Component.text("[Start " + game.label + "]", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/minigames start"))));
        if (total >= SmashManager.MAX_PLAYERS) startLobby(host);
    }

    /** /minigames start: the host starts their game (Smash or One-Bounce) with everyone who has accepted so far. */
    private void startLobby(Player host) {
        Set<UUID> lobby = smashLobbies.get(host.getUniqueId());
        Minigame game = lobbyGames.get(host.getUniqueId());
        if (lobby == null || game == null) {
            error(host, "You haven't invited anyone. Use /minigames, select Smash or One-Bounce and invite players.");
            return;
        }
        List<Player> players = new ArrayList<>();
        players.add(host);
        for (UUID id : lobby) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        if (players.size() < 2) {
            error(host, "Nobody has accepted yet. " + game.label + " needs at least 2 players.");
            return;
        }
        String problem = game == Minigame.ONEBOUNCE ? oneBounce.start(players) : smash.start(players);
        if (problem != null) {
            players.forEach(p -> error(p, problem));
            return;
        }
        smashLobbies.remove(host.getUniqueId());
        lobbyGames.remove(host.getUniqueId());
        invites.values().removeIf(invite -> invite.host().equals(host.getUniqueId())); // late invites are void
    }

    private void decline(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || expired(invite)) {
            error(player, "You have no pending minigame invite.");
            return;
        }
        player.sendMessage(Component.text("Declined the invite.", NamedTextColor.YELLOW));
        Player host = Bukkit.getPlayer(invite.host());
        if (host != null) host.sendMessage(Component.text(player.getName() + " declined your invite.", NamedTextColor.YELLOW));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        selected.remove(id);
        invites.remove(id);
        smashLobbies.remove(id);
        lobbyGames.remove(id);
        smashLobbies.values().forEach(lobby -> lobby.remove(id));
        invites.values().removeIf(invite -> invite.host().equals(id));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
