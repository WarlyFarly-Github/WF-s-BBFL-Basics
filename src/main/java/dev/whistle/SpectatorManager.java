package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /match watch: spectating a running match, either from the stands (a normal walk-around mode, at the stadium's
 * stands point if one is set) or in a restricted spectator mode (spectator game mode, teleported to the centre and
 * unable to leave the stadium bounds). Players can switch between the two with /match watch stands|pitch.
 */
final class SpectatorManager implements Listener {

    enum Mode {
        STANDS("stands"), PITCH("pitch");

        final String label;

        Mode(String label) {
            this.label = label;
        }

        static Mode parse(String text) {
            for (Mode mode : values()) {
                if (mode.label.equalsIgnoreCase(text)) return mode;
            }
            return null;
        }
    }

    private static final class Watcher {
        private final Match match;
        private final GameMode savedMode; // from before they started watching
        private final Location origin;   // where they were before they started watching
        private Mode mode;

        private Watcher(Match match, GameMode savedMode, Location origin, Mode mode) {
            this.match = match;
            this.savedMode = savedMode;
            this.origin = origin;
            this.mode = mode;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final Map<UUID, Watcher> watchers = new HashMap<>();

    SpectatorManager(JavaPlugin plugin, MatchManager matches) {
        this.plugin = plugin;
        this.matches = matches;
    }

    boolean isWatching(UUID id) {
        return watchers.containsKey(id);
    }

    // ------------------------------------------------------------------ /match watch

    /** /match watch [stadium] [stands|pitch]  (args[0] is "watch"). */
    void watch(Player player, String[] args) {
        UUID id = player.getUniqueId();
        if (matches.isInMatch(id)) {
            error(player, "You are playing in a match, so you can't watch one.");
            return;
        }

        Mode mode = null;
        String stadiumName = null;
        for (int i = 1; i < args.length; i++) {
            Mode parsed = Mode.parse(args[i]);
            if (parsed != null) mode = parsed;
            else stadiumName = args[i];
        }
        if (args.length > 3) {
            error(player, "Usage: /match watch [stadium] [stands|pitch]");
            return;
        }

        Watcher current = watchers.get(id);

        // Only a mode given while already watching: switch between the stands and the pitch.
        if (current != null && stadiumName == null) {
            if (mode == null) {
                player.sendMessage(Component.text("You are watching the match on " + current.match.stadium.name
                        + " from the " + current.mode.label + ". Switch with /match watch stands|pitch, "
                        + "stop with /match leave.", NamedTextColor.YELLOW));
            } else if (mode == current.mode) {
                error(player, "You are already watching from the " + mode.label + ".");
            } else {
                switchMode(player, current, mode);
            }
            return;
        }

        Match match = findMatch(player, stadiumName);
        if (match == null) return;

        if (current != null && current.match == match) { // named the match they're already watching
            if (mode != null && mode != current.mode) switchMode(player, current, mode);
            else error(player, "You are already watching that match.");
            return;
        }
        if (current != null) stop(player, false); // moving to a different match

        Mode chosen = mode == null ? Mode.STANDS : mode;
        Watcher watcher = new Watcher(match, player.getGameMode(), player.getLocation().clone(), chosen);
        watchers.put(id, watcher);
        match.showBar(player);
        apply(player, watcher, false);
        player.sendMessage(Component.text("You are now watching the match on " + match.stadium.name + " from the "
                + chosen.label + ". Switch with /match watch " + (chosen == Mode.STANDS ? "pitch" : "stands")
                + ", stop with /match leave.", NamedTextColor.GREEN));
    }

    private Match findMatch(Player player, String stadiumName) {
        List<Match> running = matches.runningMatches();
        if (running.isEmpty()) {
            error(player, "There are no matches running right now.");
            return null;
        }
        if (stadiumName != null) {
            for (Match match : running) {
                if (match.stadium.name.equalsIgnoreCase(stadiumName)) return match;
            }
            error(player, "There is no running match on " + stadiumName + ".");
            return null;
        }
        if (running.size() == 1) return running.get(0);
        error(player, "Several matches are running (" + String.join(", ", stadiumNames(running))
                + "). Use /match watch <stadium>.");
        return null;
    }

    private List<String> stadiumNames(List<Match> running) {
        return running.stream().map(m -> m.stadium.name).toList();
    }

    private void switchMode(Player player, Watcher watcher, Mode mode) {
        Mode before = watcher.mode;
        watcher.mode = mode;
        apply(player, watcher, before == Mode.PITCH);
        player.sendMessage(Component.text("Now watching from the " + mode.label + ".", NamedTextColor.GREEN));
    }

    /** Puts the player into the watcher's mode. */
    private void apply(Player player, Watcher watcher, boolean wasOnPitch) {
        Stadium stadium = watcher.match.stadium;
        if (watcher.mode == Mode.PITCH) {
            player.setGameMode(GameMode.SPECTATOR);
            if (stadium.hasCenter()) player.teleport(stadium.center);
            return;
        }

        player.setGameMode(GameMode.ADVENTURE); // walk around, but no breaking or placing blocks
        if (stadium.hasStands()) {
            player.teleport(stadium.stands);
        } else if (wasOnPitch) {
            player.teleport(watcher.origin); // no stands set: back to where they were
            player.sendMessage(Component.text(stadium.name + " has no stands point set, so you were sent back to where "
                    + "you started watching.", NamedTextColor.GRAY));
        }
    }

    // ------------------------------------------------------------------ stopping

    /** Stops watching: back to the old game mode and place. Returns false if the player wasn't watching. */
    boolean stop(Player player, boolean announce) {
        Watcher watcher = watchers.remove(player.getUniqueId());
        if (watcher == null) return false;
        watcher.match.hideBar(player);
        player.setGameMode(watcher.savedMode);
        player.teleport(watcher.origin);
        if (announce) player.sendMessage(Component.text("You stopped watching the match.", NamedTextColor.YELLOW));
        return true;
    }

    /** The match is over: everyone watching it goes back. */
    void matchEnded(Match match) {
        for (UUID id : new ArrayList<>(watchers.keySet())) {
            Watcher watcher = watchers.get(id);
            if (watcher == null || watcher.match != match) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null && stop(player, false)) {
                player.sendMessage(Component.text("The match you were watching has ended.", NamedTextColor.YELLOW,
                        TextDecoration.BOLD));
            } else {
                watchers.remove(id);
            }
        }
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(watchers.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) stop(player, false);
        }
        watchers.clear();
    }

    List<String> completions(String[] args) {
        String prefix = args[args.length - 1].toLowerCase();
        List<String> options = new ArrayList<>();
        if (args.length == 2) matches.runningMatches().forEach(m -> options.add(m.stadium.name));
        if (args.length == 2 || (args.length == 3 && Mode.parse(args[1]) == null)) {
            options.add(Mode.STANDS.label);
            options.add(Mode.PITCH.label);
        }
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ keeping pitch watchers inside the bounds

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Watcher watcher = watchers.get(event.getPlayer().getUniqueId());
        if (watcher == null) return;
        Stadium stadium = watcher.match.stadium;
        if (!stadium.hasBounds()) return;

        boolean toInside = stadium.boundsContain(event.getTo());
        boolean fromInside = stadium.boundsContain(event.getFrom());

        if (watcher.mode == Mode.PITCH) {
            // Spectator mode: stay inside the bounds.
            if (toInside) return;
            if (fromInside) {
                Location back = event.getFrom().clone();
                back.setYaw(event.getTo().getYaw());
                back.setPitch(event.getTo().getPitch());
                event.setTo(back);
            } else if (stadium.hasCenter()) {
                event.setTo(stadium.center); // somehow already outside: back to the centre
            }
        } else if (toInside && !fromInside) {
            // Stands: stay out of the bounds. (Someone already inside, such as after switching over, can walk out.)
            Location back = event.getFrom().clone();
            back.setYaw(event.getTo().getYaw());
            back.setPitch(event.getTo().getPitch());
            event.setTo(back);
        }
    }

    /** Teleports can't take a pitch watcher out of the bounds, or a stands watcher into them. */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Watcher watcher = watchers.get(event.getPlayer().getUniqueId());
        if (watcher == null) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return; // our own and other plugins' moves
        Stadium stadium = watcher.match.stadium;
        if (!stadium.hasBounds() || event.getTo() == null) return;

        boolean toInside = stadium.boundsContain(event.getTo());
        if (watcher.mode == Mode.PITCH ? !toInside : toInside) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer(), false); // otherwise they'd log back in as a spectator
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
