package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The navigator: a compass in the 9th hotbar slot of every player. Right-clicking it opens a menu with every command a
 * normal player needs (the quickmatch menu, the minigames lobby, training and the stadium list), so nobody has to type
 * them, for example on Bedrock. Its Tutorials section opens a second menu with the Builder's Guide.
 */
final class NavigatorManager implements Listener {

    static final int SLOT = 8; // the 9th slot of the hotbar

    // Laid out like the Select Challenge menu: four buttons spaced along the top row, three in the bottom row.
    private static final int SLOT_QUICKMATCH = 1;
    private static final int SLOT_MINIGAMES = 3;
    private static final int SLOT_TRAINING = 5;
    private static final int SLOT_STADIUMS = 7;
    private static final int SLOT_CHALLENGES = 20;
    private static final int SLOT_LEADERBOARD = 22;
    private static final int SLOT_TUTORIALS = 24;

    // The Tutorials menu.
    private static final int SLOT_BUILDERS_GUIDE = 13;
    private static final int SLOT_LEAVE_TUTORIAL = 15;
    private static final int SLOT_BACK = 22;

    private enum Kind { MAIN, TUTORIALS }

    private static final class Gui implements InventoryHolder {
        private final Kind kind;
        private Inventory inventory;

        private Gui(Kind kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final BuildersGuide guide;
    private final NamespacedKey itemKey;

    NavigatorManager(JavaPlugin plugin, BuildersGuide guide) {
        this.plugin = plugin;
        this.guide = guide;
        this.itemKey = new NamespacedKey(plugin, "navigator");
        // Anyone who is online when the plugin loads gets one, and a lost one (creative mode, say) comes back.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::ensure), 20L, 100L);
    }

    // ------------------------------------------------------------------ the item

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Navigator", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click to open the menu:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("quickmatch, minigames, challenges, training, stadiums, leaderboards and tutorials.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isNavigator(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    /** Makes sure the player has exactly one navigator, in the 9th slot. Whatever was in that slot is moved elsewhere. */
    private void ensure(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(SLOT);
        if (!isNavigator(current)) {
            inventory.setItem(SLOT, null);
            if (current != null) {
                for (ItemStack leftover : inventory.addItem(current).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            inventory.setItem(SLOT, createItem());
        }
        // Any extra navigators (copies from creative mode, for instance) are removed.
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot != SLOT && isNavigator(contents[slot])) inventory.setItem(slot, null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensure(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> ensure(player)); // after the new inventory exists
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> ensure(player));
    }

    /** It never drops on death (a new one comes with the respawn). */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isNavigator);
    }

    // ------------------------------------------------------------------ keeping it in the slot

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isNavigator(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isNavigator(event.getMainHandItem()) || isNavigator(event.getOffHandItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui gui) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getInventory()) {
                menuClick(player, gui.kind, event.getSlot());
            }
            return;
        }
        if (isNavigator(event.getCurrentItem()) || isNavigator(event.getCursor())) {
            event.setCancelled(true);
        } else if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == SLOT) {
            event.setCancelled(true); // a number key that would swap something into (and so out of) the navigator's slot
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (isNavigator(event.getOldCursor())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ the menu

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isNavigator(event.getItem())) return;
        event.setCancelled(true);
        openMenu(event.getPlayer());
    }

    private void openMenu(Player player) {
        Gui holder = new Gui(Kind.MAIN);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Navigator", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        inventory.setItem(SLOT_QUICKMATCH, button(Material.CLOCK, "Quickmatch",
                "Vote for a quickmatch or join one that is open.", "/quickmatch"));
        inventory.setItem(SLOT_MINIGAMES, button(Material.NETHER_STAR, "Minigames",
                "Join the minigames lobby: a vote picks the next game.", "/lobby minigames"));
        inventory.setItem(SLOT_TRAINING, button(Material.LEATHER_BOOTS, "Training",
                "Practise on your own: Free Play and Dodge.", "/training"));
        inventory.setItem(SLOT_STADIUMS, button(Material.MAP, "Stadium List",
                "See every stadium and who built it.", "/stadium list"));
        inventory.setItem(SLOT_CHALLENGES, button(Material.TARGET, "Challenges",
                "Pick a challenge and invite players to it.", "/challenge"));
        inventory.setItem(SLOT_LEADERBOARD, button(Material.GOLD_INGOT, "Leaderboard",
                "All-time stats: quickest goal, wins, win %, goals and time played.", "/leaderboard"));
        inventory.setItem(SLOT_TUTORIALS, button(Material.BOOK, "Tutorials",
                "Interactive guides, like how to build a stadium.", "Tutorials"));
        player.openInventory(inventory);
    }

    /** The Tutorials section: one button per tutorial (just the Builder's Guide for now). */
    private void openTutorials(Player player) {
        Gui holder = new Gui(Kind.TUTORIALS);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Tutorials", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        boolean inGuide = guide.isActive(player.getUniqueId());
        inventory.setItem(SLOT_BUILDERS_GUIDE, button(Material.BOOK, "Builder's Guide",
                "Learn how to set up a stadium, step by step, in a practice world. Finish it to become a Builder.",
                "/tutorial builders-guide"));
        if (inGuide) {
            inventory.setItem(SLOT_LEAVE_TUTORIAL, button(Material.RED_CONCRETE, "Leave Tutorial",
                    "Stop the Builder's Guide and go back to where you were.", "/tutorial leave"));
        }
        inventory.setItem(SLOT_BACK, button(Material.ARROW, "Back", "Back to the navigator.", "the navigator"));
        player.openInventory(inventory);
    }

    private ItemStack button(Material material, String name, String description, String command) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text((command.startsWith("/") ? "Click to use " : "Click to open ") + command, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private void menuClick(Player player, Kind kind, int slot) {
        if (kind == Kind.TUTORIALS) {
            tutorialsClick(player, slot);
            return;
        }
        if (slot == SLOT_TUTORIALS) {
            Bukkit.getScheduler().runTask(plugin, () -> openTutorials(player)); // next tick, like the other buttons
            return;
        }
        String command = switch (slot) {
            case SLOT_QUICKMATCH -> "quickmatch";
            case SLOT_MINIGAMES -> "lobby minigames";
            case SLOT_TRAINING -> "training";
            case SLOT_STADIUMS -> "stadium list";
            case SLOT_CHALLENGES -> "challenge";
            case SLOT_LEADERBOARD -> "leaderboard";
            default -> null;
        };
        if (command == null) return;
        // Games would pull a player out of the Builder's Guide, so only these two are open to them.
        if (guide.isActive(player.getUniqueId()) && !command.equals("stadium list") && !command.equals("leaderboard")) {
            player.sendMessage(Component.text("Finish the Builder's Guide first, or leave it with /tutorial leave.", NamedTextColor.RED));
            return;
        }
        // Next tick: the menu is closed first, and a command that opens its own menu isn't closed again straight after.
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            player.performCommand(command);
        });
    }

    private void tutorialsClick(Player player, int slot) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (slot) {
                case SLOT_BUILDERS_GUIDE -> {
                    player.closeInventory();
                    guide.start(player);
                }
                case SLOT_LEAVE_TUTORIAL -> {
                    player.closeInventory();
                    if (guide.isActive(player.getUniqueId())) guide.leave(player);
                }
                case SLOT_BACK -> openMenu(player);
                default -> { }
            }
        });
    }
}
