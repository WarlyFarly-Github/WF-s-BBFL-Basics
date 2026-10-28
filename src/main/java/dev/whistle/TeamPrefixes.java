package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name tag prefixes shown in front of players' names during a match, set by operators with /setprefix and saved
 * in config.yml. Colour codes with & work, and so do \\uXXXX escapes, for resource pack glyphs that are hard to type.
 * A space is always put between the prefix and the username. The prefix is not coloured by team; only the username is.
 */
final class TeamPrefixes implements CommandExecutor, TabCompleter {

    /** The name itself is coloured by team; the prefix is not, so these are only for the name. */
    private static final String RED_NAME = "&c";
    private static final String BLUE_NAME = "&9";
    private static final List<String> TEAMS = List.of("red", "blue");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private final JavaPlugin plugin;

    TeamPrefixes(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private String raw(boolean red) {
        return plugin.getConfig().getString("prefixes." + (red ? "red" : "blue"), "");
    }

    /**
     * For TAB: the prefix (left uncoloured, so resource pack glyphs keep their own look), a space, then the team colour
     * code, which colours the username that follows.
     */
    String tag(boolean red) {
        String prefix = raw(red);
        return (prefix.isBlank() ? "" : prefix + "&r ") + (red ? RED_NAME : BLUE_NAME);
    }

    /** The prefix and a space as a component, white unless it sets its own colour (for the scoreboard team fallback). */
    Component component(boolean red) {
        String prefix = raw(red);
        if (prefix.isBlank()) return Component.empty();
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix)
                .append(Component.text(" "))
                .colorIfAbsent(NamedTextColor.WHITE);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wfsbbfl.setprefix")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0 || !TEAMS.contains(args[0].toLowerCase())) {
            sender.sendMessage(Component.text("Usage: /setprefix <red|blue> <prefix|none>", NamedTextColor.RED));
            return true;
        }
        boolean red = args[0].equalsIgnoreCase("red");
        String team = red ? "red" : "blue";

        if (args.length == 1) {
            sender.sendMessage(Component.text("The " + team + " prefix is: " + raw(red), NamedTextColor.YELLOW)
                    .append(Component.text("  (shown as: ").color(NamedTextColor.GRAY))
                    .append(component(red))
                    .append(Component.text("Username", red ? NamedTextColor.RED : NamedTextColor.BLUE))
                    .append(Component.text(")", NamedTextColor.GRAY)));
            return true;
        }

        String input = String.join(" ", List.of(args).subList(1, args.length));
        String value = input.equalsIgnoreCase("none") ? "" : unescape(input);
        plugin.getConfig().set("prefixes." + team, value);
        plugin.saveConfig();
        sender.sendMessage(Component.text("The " + team + " prefix is now set. It shows as: ", NamedTextColor.GREEN)
                .append(component(red))
                .append(Component.text("Username", red ? NamedTextColor.RED : NamedTextColor.BLUE))
                .append(Component.text(" (applies to players when they next get their jersey, e.g. next match)",
                        NamedTextColor.GRAY)));
        return true;
    }

    /** Turns \\uE001 style escapes into the real character. */
    private static String unescape(String text) {
        Matcher m = UNICODE_ESCAPE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(out);
        return out.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return TEAMS.stream().filter(n -> n.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2) return List.of("none").stream().filter(n -> n.startsWith(args[1].toLowerCase())).toList();
        return List.of();
    }
}
