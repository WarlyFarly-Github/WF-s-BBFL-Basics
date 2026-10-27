package dev.whistle;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WFsBBFLBasics extends JavaPlugin implements Listener {

    private NamespacedKey whistleKey;
    private MatchManager matches;
    private final Map<UUID, Long> lastBlown = new HashMap<>();

    @Override
    public void onEnable() {
        whistleKey = new NamespacedKey(this, "whistle");
        getServer().getPluginManager().registerEvents(this, this);

        StadiumManager stadiums = new StadiumManager(this);
        PluginCommand stadiumCommand = getCommand("stadium");
        if (stadiumCommand != null) {
            stadiumCommand.setExecutor(stadiums);
            stadiumCommand.setTabCompleter(stadiums);
        }

        matches = new MatchManager(this, stadiums);
        getServer().getPluginManager().registerEvents(matches, this);
        PluginCommand matchCommand = getCommand("match");
        if (matchCommand != null) {
            matchCommand.setExecutor(matches);
            matchCommand.setTabCompleter(matches);
        }
    }

    @Override
    public void onDisable() {
        if (matches != null) matches.shutdown();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("whistle.give")) {
            player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(createWhistle());
        leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.sendMessage(Component.text("You received the Whistle.", NamedTextColor.YELLOW));
        return true;
    }

    private ItemStack createWhistle() {
        ItemStack item = new ItemStack(Material.GOAT_HORN);
        ItemMeta meta = item.getItemMeta();

        // No instrument is set on purpose: a goat horn without one makes no sound.
        meta.displayName(Component.text("Whistle", NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.setEnchantmentGlintOverride(true); // "enchanted" shimmer, no real enchantments
        meta.getPersistentDataContainer().set(whistleKey, PersistentDataType.BYTE, (byte) 1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isWhistle(ItemStack item) {
        return item != null
                && item.getType() == Material.GOAT_HORN
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(whistleKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || !isWhistle(event.getItem())) return;

        Player player = event.getPlayer();

        // Stop the goat horn's own sound from playing (block interactions still work).
        event.setUseItemInHand(Event.Result.DENY);

        // Goat horns have a 7 second cooldown; mirror it so the title can't be spammed.
        long now = System.currentTimeMillis();
        Long last = lastBlown.get(player.getUniqueId());
        if (last != null && now - last < 7000) return;
        lastBlown.put(player.getUniqueId(), now);

        // Equivalent of:
        //   /title @a title {color:"yellow",text:"Whistle Blown!"}
        //   /title @a subtitle "by <user>"
        Title title = Title.title(
                Component.text("Whistle Blown!", NamedTextColor.YELLOW),
                Component.text("by " + player.getName()));
        getServer().showTitle(title);

        // Level-up sound for everyone, played at each player's own location.
        getServer().playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.PLAYER, 1.0f, 1.0f));
    }
}
