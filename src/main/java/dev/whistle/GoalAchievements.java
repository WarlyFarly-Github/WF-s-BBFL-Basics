package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Goal achievements: reaching 100, 250, 500, 1000... goals scored in total (the leaderboard's goals, so own goals don't
 * count) unlocks an achievement and a celebration with fireworks. /achievements shows them in a menu.
 *
 * Nothing extra is stored: an achievement is unlocked when the player's total is at or above its goal count, so
 * players who already have goals from before this existed simply have those achievements already.
 */
final class GoalAchievements implements Listener, CommandExecutor {

    private record Achievement(int goals, String name, Material icon) { }

    // Add or change milestones here. The menu has room for up to 9 (one row); they are centred in the middle row.
    private static final List<Achievement> ACHIEVEMENTS = List.of(
            new Achievement(100, "Rising Star", Material.COPPER_INGOT),
            new Achievement(250, "Sharpshooter", Material.IRON_INGOT),
            new Achievement(500, "Golden Boot", Material.GOLD_INGOT),
            new Achievement(1000, "Legend", Material.DIAMOND),
            new Achievement(2500, "Hall of Famer", Material.EMERALD),
            new Achievement(5000, "Unstoppable", Material.NETHERITE_INGOT),
            new Achievement(10000, "Immortal", Material.NETHER_STAR));

    private static final int SLOT_TOTAL = 4; // top row, centre: the player's goal total
    private static final int BAR_LENGTH = 10;

    private final JavaPlugin plugin;
    private final LeaderboardManager leaderboard;
    private final Celebration celebration;

    GoalAchievements(JavaPlugin plugin, LeaderboardManager leaderboard, Celebration celebration) {
        this.plugin = plugin;
        this.leaderboard = leaderboard;
        this.celebration = celebration;
    }

    private static final class Gui implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------------ unlocking

    /** Called by the leaderboard each time a player scores; total is their goals including this one. */
    void onGoal(UUID id, int total) {
        for (int i = 0; i < ACHIEVEMENTS.size(); i++) {
            Achievement achievement = ACHIEVEMENTS.get(i);
            if (achievement.goals() != total) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) return;

            player.sendMessage(Component.text("Achievement unlocked: ", NamedTextColor.GOLD)
                    .append(Component.text(achievement.name(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" (" + achievement.goals() + " goals scored). /achievements to see them all.",
                            NamedTextColor.GRAY)));
            // Bigger milestones get more fireworks.
            celebration.achievement(player,
                    Component.text(achievement.name() + " - " + achievement.goals() + " goals", NamedTextColor.YELLOW),
                    8 + 2 * i);
            return;
        }
    }

    // ------------------------------------------------------------------ the menu

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can view achievements.", NamedTextColor.RED));
            return true;
        }
        openMenu(player);
        return true;
    }

    private void openMenu(Player player) {
        int total = leaderboard.goalsOf(player.getUniqueId());
        Gui holder = new Gui();
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Achievements", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;

        inventory.setItem(SLOT_TOTAL, totalItem(total));
        int first = 9 + (9 - ACHIEVEMENTS.size()) / 2;
        for (int i = 0; i < ACHIEVEMENTS.size(); i++) {
            inventory.setItem(first + i, achievementItem(ACHIEVEMENTS.get(i), total));
        }
        player.openInventory(inventory);
    }

    private ItemStack totalItem(int total) {
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Own goals don't count.", NamedTextColor.GRAY));
        Achievement next = null;
        for (Achievement a : ACHIEVEMENTS) {
            if (total < a.goals()) {
                next = a;
                break;
            }
        }
        lore.add(Component.empty());
        if (next == null) {
            lore.add(plain("Every achievement unlocked!", NamedTextColor.GREEN));
        } else {
            int left = next.goals() - total;
            lore.add(plain("Next: " + next.name() + " in " + left + (left == 1 ? " goal" : " goals"), NamedTextColor.YELLOW));
        }
        return item(Material.FIREWORK_STAR, "Goals scored: " + total, NamedTextColor.GOLD, lore, false);
    }

    private ItemStack achievementItem(Achievement achievement, int total) {
        boolean unlocked = total >= achievement.goals();
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Score " + achievement.goals() + " goals in total.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        if (unlocked) {
            lore.add(plain("\u2714 Unlocked", NamedTextColor.GREEN));
            return item(achievement.icon(), achievement.name(), NamedTextColor.GOLD, lore, true);
        }
        int percent = (int) (total * 100L / achievement.goals());
        int filled = Math.min(BAR_LENGTH, total * BAR_LENGTH / achievement.goals());
        lore.add(plain("Locked", NamedTextColor.RED));
        lore.add(Component.text("\u2588".repeat(filled), NamedTextColor.GREEN)
                .append(Component.text("\u2591".repeat(BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + total + " / " + achievement.goals() + " (" + percent + "%)", NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false));
        return item(Material.GRAY_DYE, achievement.name(), NamedTextColor.GRAY, lore, false);
    }

    private ItemStack item(Material material, String name, NamedTextColor color, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (glint) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    /** The menu is for looking at only: nothing can be taken out of it or put into it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }
}
