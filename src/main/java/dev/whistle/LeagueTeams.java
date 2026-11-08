package dev.whistle;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the league teams made by the Skript league script (league.sk). Skript keeps its variables in memory, so
 * they are read straight from there (through Skript's own Variables class, found by reflection so this plugin
 * needs no build dependency on Skript) and are always up to date: renames and colour changes show immediately.
 *
 * The script's variables used here:
 *   {league::owner::*}              owner's Discord ID -> team key (one entry per team)
 *   {league::team::KEY::display}    the team's name as typed
 *   {league::team::KEY::colour}     hex colour like #ff0000 (not set = #ffffff)
 *   {league::alias::OLDKEY}         the new key after a team was renamed
 */
final class LeagueTeams {

    /** A team from the league script. rgb is the 0xRRGGBB value of its hex colour. */
    record LeagueTeam(String key, String name, int rgb) { }

    private static final int DEFAULT_RGB = 0xFFFFFF; // the script's default for a team without a colour

    private final JavaPlugin plugin;
    private Method getVariable;
    private boolean lookedUp;
    private boolean warned;

    LeagueTeams(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** True when Skript is running and its variables can be read. */
    boolean available() {
        if (!lookedUp) {
            lookedUp = true;
            if (Bukkit.getPluginManager().getPlugin("Skript") != null) {
                try {
                    Class<?> variables = Class.forName("ch.njol.skript.variables.Variables");
                    getVariable = variables.getMethod("getVariable", String.class, Event.class, boolean.class);
                } catch (ReflectiveOperationException | LinkageError e) {
                    plugin.getLogger().warning("Skript is installed but its variables couldn't be read ("
                            + e.getClass().getSimpleName() + "), so league teams can't be used in matches.");
                }
            }
        }
        return getVariable != null;
    }

    private Object read(String name) {
        if (!available()) return null;
        try {
            return getVariable.invoke(null, name, null, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Couldn't read the Skript variable " + name + ": " + e);
            }
            return null;
        }
    }

    private String readString(String name) {
        Object value = read(name);
        return value instanceof String s ? s : null;
    }

    /** Every league team, in no particular order. Empty if Skript or the league script isn't there. */
    List<LeagueTeam> all() {
        List<LeagueTeam> teams = new ArrayList<>();
        if (!(read("league::owner::*") instanceof Map<?, ?> owners)) return teams;
        for (Object value : owners.values()) {
            if (!(value instanceof String key)) continue;
            LeagueTeam team = byKey(key);
            if (team != null) teams.add(team);
        }
        return teams;
    }

    private LeagueTeam byKey(String key) {
        String name = readString("league::team::" + key + "::display");
        if (name == null) return null;
        return new LeagueTeam(key, name, parseHex(readString("league::team::" + key + "::colour")));
    }

    /** Finds a team by the name players type (any capitals), following renames. Null if there is no such team. */
    LeagueTeam find(String typed) {
        String wanted = typed.trim().toLowerCase();
        for (int i = 0; i < 10; i++) { // a renamed team's old name points at its new one
            for (LeagueTeam team : all()) {
                if (team.name().equalsIgnoreCase(typed.trim()) || team.key().equals(wanted)) return team;
            }
            String renamed = readString("league::alias::" + wanted);
            if (renamed == null) return null;
            wanted = renamed;
        }
        return null;
    }

    List<String> names() {
        return all().stream().map(LeagueTeam::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** "#rrggbb" (or without the #) as 0xRRGGBB; white when it is missing or not a hex colour. */
    private static int parseHex(String hex) {
        if (hex == null) return DEFAULT_RGB;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return DEFAULT_RGB;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return DEFAULT_RGB;
        }
    }

    static TextColor textColor(int rgb) {
        return TextColor.color(rgb);
    }

    static String hex(int rgb) {
        return String.format("%06x", rgb & 0xFFFFFF);
    }
}
