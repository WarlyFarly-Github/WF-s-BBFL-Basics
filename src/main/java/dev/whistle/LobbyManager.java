package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Stores named lobby locations in config.yml and lets operators set them with /setlobby. */
final class LobbyManager implements CommandExecutor, TabCompleter {

    static final String QUICKMATCH = "quickmatch";
    private static final List<String> NAMES = List.of(QUICKMATCH);

    private final JavaPlugin plugin;

    LobbyManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The saved lobby location, or null if it hasn't been set (or its world isn't loaded). */
    Location get(String name) {
        Location location = plugin.getConfig().getLocation("lobbies." + name);
        return location == null ? null : location.clone();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("wfsbbfl.setlobby")) {
            player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1 || !NAMES.contains(args[0].toLowerCase())) {
            player.sendMessage(Component.text("Usage: /setlobby quickmatch", NamedTextColor.RED));
            return true;
        }
        String name = args[0].toLowerCase();
        plugin.getConfig().set("lobbies." + name, player.getLocation());
        plugin.saveConfig();
        player.sendMessage(Component.text("The " + name + " lobby is now where you are standing.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return NAMES.stream().filter(n -> n.startsWith(args[0].toLowerCase())).toList();
        return List.of();
    }
}
