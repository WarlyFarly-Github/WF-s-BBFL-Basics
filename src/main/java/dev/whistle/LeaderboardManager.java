package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /leaderboard: all-time football stats (quickest goal, matches won, goals scored, time played) kept in stats.yml,
 * shown in a chest menu like the plugin's other menus.
 */
final class LeaderboardManager implements CommandExecutor, TabCompleter, Listener {

    enum Category {
        QUICKEST("Quickest Goal", Material.FEATHER, "quickest"),
        WINS("Most Matches Won", Material.TOTEM_OF_UNDYING, "wins"),
        GOALS("Most Goals Scored", Material.SNOWBALL, "goals"),
        PLAYTIME("Most Time Played", Material.CLOCK, "playtime");

        final String title;
        final Material icon;
        final String key;

        Category(String title, Material icon, String key) {
            this.title = title;
            this.icon = icon;
            this.key = key;
        }
    }

    private static final class Stats {
        String name = "Unknown";
        int goals;
        int wins;
        long playSeconds;
        int quickestTicks; // match-clock ticks of the player's fastest goal; 0 = no goal yet
    }

    private record Entry(UUID id, Stats stats) { }

    private static final class Gui implements InventoryHolder {
        private final Category category; // null = the main menu
        private final int page;
        private final int pages;
        private Inventory inventory;

        private Gui(Category category, int page, int pages) {
            this.category = category;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // Same layout as the other menus: 3 rows, two rows of heads, controls on the bottom row.
    private static final int[] CATEGORY_SLOTS = {10, 12, 14, 16};
    private static final int SLOT_PREVIOUS = 21;
    private static final int SLOT_PAGE = 22;
    private static final int SLOT_NEXT = 23;
    private static final int SLOT_BACK = 26;
    private static final int PAGE_SIZE = 18;
    private static final int MAX_PAGES = 64;
    private static final TextColor GOLD = TextColor.color(0xFFD700);
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Stats> stats = new HashMap<>();
    private boolean dirty;

    LeaderboardManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 600L, 600L);
    }

    // ------------------------------------------------------------------ recording

    private Stats statsOf(UUID id) {
        Stats s = stats.computeIfAbsent(id, k -> new Stats());
        Player online = Bukkit.getPlayer(id);
        String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(id).getName();
        if (name != null) s.name = name;
        dirty = true;
        return s;
    }

    /** A goal scored by this player, playTicks into the match (on the match clock, not counting freezes or pauses). */
    void recordGoal(UUID id, int playTicks) {
        Stats s = statsOf(id);
        s.goals++;
        int ticks = Math.max(1, playTicks);
        if (s.quickestTicks == 0 || ticks < s.quickestTicks) s.quickestTicks = ticks;
    }

    void recordWin(UUID id) {
        statsOf(id).wins++;
    }

    void addPlaySeconds(UUID id, int seconds) {
        statsOf(id).playSeconds += seconds;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) continue;
            Stats s = new Stats();
            s.name = section.getString("name", "Unknown");
            s.goals = section.getInt("goals");
            s.wins = section.getInt("wins");
            s.playSeconds = section.getLong("play-seconds");
            s.quickestTicks = section.getInt("quickest-ticks");
            stats.put(id, s);
        }
    }

    private void saveIfDirty() {
        if (dirty) save();
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            String path = "players." + e.getKey();
            Stats s = e.getValue();
            yaml.set(path + ".name", s.name);
            yaml.set(path + ".goals", s.goals);
            yaml.set(path + ".wins", s.wins);
            yaml.set(path + ".play-seconds", s.playSeconds);
            yaml.set(path + ".quickest-ticks", s.quickestTicks);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save stats.yml: " + ex.getMessage());
        }
    }

    void shutdown() {
        save();
    }

    // ------------------------------------------------------------------ ranking and formatting

    private long value(Category category, Stats s) {
        return switch (category) {
            case QUICKEST -> s.quickestTicks;
            case WINS -> s.wins;
            case GOALS -> s.goals;
            case PLAYTIME -> s.playSeconds;
        };
    }

    private List<Entry> ranking(Category category) {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
            if (value(category, e.getValue()) > 0) list.add(new Entry(e.getKey(), e.getValue()));
        }
        Comparator<Entry> byValue = Comparator.comparingLong(e -> value(category, e.stats()));
        if (category != Category.QUICKEST) byValue = byValue.reversed(); // quickest: lowest time wins
        list.sort(byValue.thenComparing(e -> e.stats().name.toLowerCase(Locale.ROOT)));
        return list;
    }

    private String format(Category category, Stats s) {
        return switch (category) {
            case QUICKEST -> formatGoalTime(s.quickestTicks);
            case WINS -> s.wins + (s.wins == 1 ? " win" : " wins");
            case GOALS -> s.goals + (s.goals == 1 ? " goal" : " goals");
            case PLAYTIME -> formatDuration(s.playSeconds);
        };
    }

    private static String formatGoalTime(int ticks) {
        double seconds = ticks / 20.0;
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
        int whole = (int) seconds;
        return String.format(Locale.ROOT, "%d:%02d.%d", whole / 60, whole % 60, (int) ((seconds - whole) * 10));
    }

    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            openMain(player);
            return true;
        }
        for (Category category : Category.values()) {
            if (category.key.equalsIgnoreCase(args[0])) {
                openList(player, category, 1);
                return true;
            }
        }
        player.sendMessage(Component.text("Usage: /leaderboard [quickest|wins|goals|playtime]", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return java.util.Arrays.stream(Category.values()).map(c -> c.key)
                .filter(k -> k.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------ menus

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component bold(String text, TextColor color) {
        return Component.text(text, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private Inventory newMenu(Gui holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(title, NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        return inventory;
    }

    private void openMain(Player player) {
        Gui holder = new Gui(null, 0, 0);
        Inventory inventory = newMenu(holder, "Leaderboard");
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            inventory.setItem(CATEGORY_SLOTS[i], categoryButton(player, categories[i]));
        }
        player.openInventory(inventory);
    }

    private ItemStack categoryButton(Player viewer, Category category) {
        List<Entry> ranking = ranking(category);
        List<Component> lore = new ArrayList<>();
        if (ranking.isEmpty()) {
            lore.add(plain("Nobody is on this board yet.", NamedTextColor.GRAY));
        } else {
            Entry top = ranking.get(0);
            lore.add(plain("#1 " + top.stats().name + ": " + format(category, top.stats()), GOLD));
        }
        int rank = -1;
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).id().equals(viewer.getUniqueId())) rank = i + 1;
        }
        Stats mine = stats.get(viewer.getUniqueId());
        if (rank > 0 && mine != null) {
            lore.add(plain("You: " + format(category, mine) + " (#" + rank + ")", NamedTextColor.GREEN));
        } else {
            lore.add(plain("You: not ranked yet", NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(plain("Click to see the top players.", NamedTextColor.YELLOW));

        ItemStack item = new ItemStack(category.icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold(category.title, NamedTextColor.GOLD));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private void openList(Player player, Category category, int page) {
        List<Entry> ranking = ranking(category);
        int pages = Math.max(1, Math.min(MAX_PAGES, (ranking.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        page = Math.max(1, Math.min(page, pages));

        Gui holder = new Gui(category, page, pages);
        Inventory inventory = newMenu(holder, category.title);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = (page - 1) * PAGE_SIZE + i;
            if (index >= ranking.size()) break;
            inventory.setItem(i, headFor(ranking.get(index), index + 1, category, player));
        }
        inventory.setItem(SLOT_PREVIOUS, arrowBanner("Previous Page", false));
        inventory.setItem(SLOT_PAGE, pageMarker(page, pages));
        inventory.setItem(SLOT_NEXT, arrowBanner("Next Page", true));
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "Back", NamedTextColor.DARK_BLUE));
        player.openInventory(inventory);
    }

    private ItemStack headFor(Entry entry, int rank, Category category, Player viewer) {
        TextColor color = rank == 1 ? GOLD : rank == 2 ? SILVER : rank == 3 ? BRONZE : NamedTextColor.WHITE;
        boolean mine = entry.id().equals(viewer.getUniqueId());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.id()));
        meta.displayName(bold("#" + rank + " " + entry.stats().name, color));
        List<Component> lore = new ArrayList<>();
        lore.add(plain(category.title + ": " + format(category, entry.stats()), NamedTextColor.GRAY));
        if (mine) lore.add(plain("That's you!", NamedTextColor.GREEN));
        meta.lore(lore);
        if (mine) meta.setEnchantmentGlintOverride(true);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack button(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(bold(name, color));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    /** White banner with a red arrow (same as the other paged menus). */
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

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui gui)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        int slot = event.getSlot();
        if (gui.category == null) {
            Category[] categories = Category.values();
            for (int i = 0; i < categories.length; i++) {
                if (slot == CATEGORY_SLOTS[i]) {
                    Category chosen = categories[i];
                    Bukkit.getScheduler().runTask(plugin, () -> openList(player, chosen, 1));
                }
            }
            return;
        }
        if (slot == SLOT_BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
        } else if (slot == SLOT_PREVIOUS || slot == SLOT_NEXT) {
            int target = gui.page + (slot == SLOT_NEXT ? 1 : -1);
            if (target < 1 || target > gui.pages) return;
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, gui.category, target));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }
}
