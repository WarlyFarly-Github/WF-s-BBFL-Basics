package dev.whistle;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The only class that touches the DecentHolograms API, so the plugin still loads (and the Builder's Guide still works,
 * just without floating text) when DecentHolograms isn't installed. Every hologram is private to one player: it is
 * created with its default visibility off and then shown to that player only, and it is never saved to disk.
 */
final class GuideHolograms {

    private final Logger logger;
    private final Map<UUID, Set<String>> names = new HashMap<>();
    private boolean warned;

    GuideHolograms(Logger logger) {
        this.logger = logger;
    }

    private static String name(UUID id, String key) {
        return "bbflbg_" + id.toString().replace("-", "") + "_" + key;
    }

    /** Shows (or replaces) the hologram with this key for the viewer only. Lines use &-colour codes. */
    void place(Player viewer, String key, Location location, List<String> lines) {
        String name = name(viewer.getUniqueId(), key);
        try {
            if (DHAPI.getHologram(name) != null) DHAPI.removeHologram(name);
            Hologram hologram = DHAPI.createHologram(name, location, false, lines);
            hologram.setDefaultVisibleState(false);
            hologram.setShowPlayer(viewer);
            names.computeIfAbsent(viewer.getUniqueId(), id -> new HashSet<>()).add(name);
        } catch (Throwable problem) {
            if (!warned) {
                warned = true;
                logger.warning("Could not create a Builder's Guide hologram: " + problem);
            }
        }
    }

    /** Removes every hologram of this viewer whose key starts with the prefix. */
    void removePrefix(UUID viewer, String keyPrefix) {
        Set<String> mine = names.get(viewer);
        if (mine == null) return;
        String start = name(viewer, keyPrefix);
        mine.removeIf(name -> {
            if (!name.startsWith(start)) return false;
            try {
                DHAPI.removeHologram(name);
            } catch (Throwable ignored) {
                // already gone
            }
            return true;
        });
    }

    void removeAll(UUID viewer) {
        Set<String> mine = names.remove(viewer);
        if (mine == null) return;
        for (String name : mine) {
            try {
                DHAPI.removeHologram(name);
            } catch (Throwable ignored) {
                // already gone
            }
        }
    }
}
