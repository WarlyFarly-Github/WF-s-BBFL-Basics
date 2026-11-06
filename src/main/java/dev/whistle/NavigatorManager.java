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
 * them, for example on Bedrock.
 */
final class NavigatorManager implements Listener {

    static final int SLOT = 8; // the 9th slot of the hotbar

    private static final int SLOT_QUICKMATCH = 10;
    private static final int SLOT_MINIGAMES = 12;
    private static final int SLOT_TRAINING = 14;
    private static final int SLOT_STADIUMS = 16;

    private static final class Gui implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;

    NavigatorManager(JavaPlugin plugin) {
        this.plugin = plugin;
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
                Component.text("quickmatch, minigames, training and stadiums.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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
        if (event.getInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player && event.getClickedInventory() == event.getInventory()) {
                menuClick(player, event.getSlot());
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
        Gui holder = new Gui();
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
        player.openInventory(inventory);
    }

    private ItemStack button(Material material, String name, String description, String command) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to use " + command, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return item;
    }

    private void menuClick(Player player, int slot) {
        String command = switch (slot) {
            case SLOT_QUICKMATCH -> "quickmatch";
            case SLOT_MINIGAMES -> "lobby minigames";
            case SLOT_TRAINING -> "training";
            case SLOT_STADIUMS -> "stadium list";
            default -> null;
        };
        if (command == null) return;
        // Next tick: the menu is closed first, and a command that opens its own menu isn't closed again straight after.
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            player.performCommand(command);
        });
    }
}
