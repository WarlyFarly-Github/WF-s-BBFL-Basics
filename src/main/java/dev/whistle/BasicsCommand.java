package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** /bbflbasics reload: re-reads config.yml without restarting the server. */
final class BasicsCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    BasicsCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wfsbbfl.reload")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(Component.text("Usage: /" + label + " reload", NamedTextColor.RED));
            return true;
        }

        // Check the file first: Bukkit's reload silently swaps in an empty config when the YAML is broken, which
        // would lose the lobbies and settings until the next restart (or overwrite them on the next /setlobby).
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (file.exists()) {
            try {
                new YamlConfiguration().load(file);
            } catch (IOException | InvalidConfigurationException e) {
                sender.sendMessage(Component.text("config.yml has an error, so nothing was reloaded: "
                        + firstLine(e.getMessage()), NamedTextColor.RED));
                plugin.getLogger().warning("config.yml could not be reloaded: " + e.getMessage());
                return true;
            }
        }

        plugin.reloadConfig();
        sender.sendMessage(Component.text("WF's BBFL Basics config.yml reloaded.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("wfsbbfl.reload") && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }

    private static String firstLine(String text) {
        if (text == null) return "unknown error";
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
