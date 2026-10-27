package dev.whistle;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Sets name tag colours through the TAB plugin's API. TAB controls scoreboard teams itself, so plain Bukkit teams
 * are overridden on servers that use it. The values set here are temporary (TAB drops them on quit or reload).
 *
 * TAB is reached by reflection so the plugin builds and runs without TAB, and without needing its API as a dependency.
 */
final class TabNameTags {

    private final JavaPlugin plugin;
    private boolean warned;

    TabNameTags(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** True if TAB is installed and enabled (it may still turn out to have its nametag feature switched off). */
    boolean isPresent() {
        Plugin tab = Bukkit.getPluginManager().getPlugin("TAB");
        return tab != null && tab.isEnabled();
    }

    /** Gives the player a name tag prefix such as "&c". Returns false if TAB couldn't be used. */
    boolean setPrefix(Player player, String prefix) {
        return apply(player.getUniqueId(), prefix);
    }

    /** Removes the temporary prefix so TAB goes back to the player's normal one. */
    void resetPrefix(Player player) {
        apply(player.getUniqueId(), null);
    }

    private boolean apply(UUID id, String prefix) {
        if (!isPresent()) return false;
        try {
            Class<?> apiClass = Class.forName("me.neznamy.tab.api.TabAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) return false;

            Object tabPlayer = apiClass.getMethod("getPlayer", UUID.class).invoke(api, id);
            if (tabPlayer == null) return false; // TAB hasn't finished loading this player

            Object nameTags = apiClass.getMethod("getNameTagManager").invoke(api);
            if (nameTags == null) {
                warnOnce("TAB's nametag feature is disabled (nametag section in TAB's config.yml), so team name tag colours can't be shown.");
                return false;
            }

            Class<?> tabPlayerClass = Class.forName("me.neznamy.tab.api.TabPlayer");
            Class<?> managerClass = Class.forName("me.neznamy.tab.api.nametag.NameTagManager");
            managerClass.getMethod("setPrefix", tabPlayerClass, String.class).invoke(nameTags, tabPlayer, prefix);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("Could not set name tag colours through TAB: " + e);
            plugin.getLogger().log(Level.FINE, "TAB hook failure", e);
            return false;
        }
    }

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning(message);
    }
}
