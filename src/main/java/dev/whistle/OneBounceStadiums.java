package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /obstadium (operators only): makes and edits One-Bounce arenas, saved in onebouncestadiums.yml. */
final class OneBounceStadiums implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "list", "info", "setballpos", "setradius", "bounds", "edit", "confirm");
    private static final List<String> BOUNDS_ACTIONS = List.of("addpoint", "removepoint", "setpoint", "clear");
    private static final double VIEW_DISTANCE = 64;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, OneBounceStadium> stadiums = new LinkedHashMap<>();
    private final Map<UUID, String> lastEdited = new HashMap<>();

    OneBounceStadiums(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "onebouncestadiums.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::showParticles, 20L, 10L);
    }

    OneBounceStadium get(String name) {
        return name == null ? null : stadiums.get(name.toLowerCase());
    }

    List<OneBounceStadium> all() {
        return new ArrayList<>(stadiums.values());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("wfsbbfl.obstadium")) {
            error(player, "Only operators can use /obstadium.");
            return true;
        }
        if (args.length == 0 || !SUBCOMMANDS.contains(args[0].toLowerCase())) {
            error(player, "Usage: /obstadium <create|delete|list|info|setballpos|setradius|bounds|edit|confirm> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "delete" -> delete(player, args);
            case "list" -> list(player);
            case "info" -> info(player, args);
            case "setballpos" -> setBallPos(player, args);
            case "setradius" -> setRadius(player, args);
            case "bounds" -> bounds(player, args);
            case "edit" -> edit(player, args);
            case "confirm" -> confirm(player, args);
            default -> { }
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length != 2) {
            error(player, "Usage: /obstadium create <name>");
            return;
        }
        if (!args[1].matches("[A-Za-z0-9_-]{1,24}")) {
            error(player, "Names can use letters, numbers, - and _ (up to 24 characters).");
            return;
        }
        if (get(args[1]) != null) {
            error(player, "A One-Bounce stadium called " + args[1] + " already exists.");
            return;
        }
        OneBounceStadium stadium = new OneBounceStadium(args[1]);
        stadiums.put(args[1].toLowerCase(), stadium);
        startEditing(player, stadium);
        save();
        player.sendMessage(Component.text("Created One-Bounce stadium " + stadium.name + ". Stand in the middle and use "
                + "/obstadium setballpos " + stadium.name + ", then /obstadium setradius " + stadium.name
                + " <blocks>. Optionally add /obstadium bounds " + stadium.name + " addpoint to shape the edge of play. Use /obstadium confirm "
                + stadium.name + " when you are done.", NamedTextColor.GREEN));
    }

    private void delete(Player player, String[] args) {
        OneBounceStadium stadium = named(player, args, "delete");
        if (stadium == null) return;
        stadiums.remove(stadium.name.toLowerCase());
        lastEdited.values().removeIf(name -> name.equalsIgnoreCase(stadium.name));
        save();
        player.sendMessage(Component.text("Deleted One-Bounce stadium " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    private void list(Player player) {
        if (stadiums.isEmpty()) {
            player.sendMessage(Component.text("There are no One-Bounce stadiums yet. Use /obstadium create <name>.",
                    NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("One-Bounce stadiums:", NamedTextColor.GOLD));
        for (OneBounceStadium stadium : stadiums.values()) {
            player.sendMessage(Component.text(" - " + stadium.name + (stadium.isComplete() ? "" : " (unfinished)"),
                    stadium.isComplete() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
    }

    private void info(Player player, String[] args) {
        OneBounceStadium stadium = named(player, args, "info");
        if (stadium == null) return;
        player.sendMessage(Component.text("One-Bounce stadium " + stadium.name, NamedTextColor.GOLD));
        line(player, "Ball position", stadium.ballPos == null ? null : describe(stadium.ballPos));
        line(player, "Radius", stadium.radius > 0 ? stadium.radius + " blocks" : null);
        player.sendMessage(Component.text(" - Bounds: ", NamedTextColor.GRAY).append(stadium.bounds.isEmpty()
                ? Component.text("none (the circle is the edge of play)", NamedTextColor.GREEN)
                : Component.text(stadium.bounds.size() + " points" + (stadium.hasBounds() ? " (edge of play)" : " (needs at least 3)"),
                        stadium.hasBounds() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        List<String> missing = stadium.missingParts();
        line(player, "Status", stadium.confirmed ? "confirmed (locked)" : "editing (particles shown)");
        line(player, "Ready to play", missing.isEmpty() ? "yes" : null);
        if (!missing.isEmpty()) {
            player.sendMessage(Component.text("Still needed: " + String.join(", ", missing), NamedTextColor.RED));
        }
    }

    /** /obstadium setballpos <stadium>: the block you stand on is the middle of the arena, where the ball starts. */
    private void setBallPos(Player player, String[] args) {
        if (args.length != 2) {
            error(player, "Usage: /obstadium setballpos <stadium>");
            return;
        }
        OneBounceStadium stadium = named(player, args, "setballpos");
        if (stadium == null || !requireEditable(player, stadium)) return;
        stadium.ballPos = player.getLocation().getBlock().getLocation();
        save();
        player.sendMessage(Component.text("Ball position of " + stadium.name + " set to " + describe(stadium.ballPos)
                + ". Players are placed in a ring around it and the ball is served from there.", NamedTextColor.GREEN));
    }

    private void setRadius(Player player, String[] args) {
        if (args.length != 3) {
            error(player, "Usage: /obstadium setradius <stadium> <blocks>");
            return;
        }
        OneBounceStadium stadium = named(player, args, "setradius");
        if (stadium == null || !requireEditable(player, stadium)) return;
        double radius;
        try {
            radius = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            radius = -1;
        }
        if (!(radius >= OneBounceStadium.MIN_RADIUS && radius <= OneBounceStadium.MAX_RADIUS)) {
            error(player, "The radius must be a number of blocks between " + (int) OneBounceStadium.MIN_RADIUS + " and "
                    + (int) OneBounceStadium.MAX_RADIUS + ".");
            return;
        }
        stadium.radius = radius;
        save();
        player.sendMessage(Component.text("The radius of " + stadium.name + " is now " + radius
                + " blocks. A ball that goes past it is out of play (unless the stadium has /obstadium bounds, which then set the edge).", NamedTextColor.GREEN));
    }

    /**
     * /obstadium bounds <stadium> <addpoint | removepoint [number] | setpoint <number> | clear>: an optional polygon that
     * is the edge of play instead of the circle. Stand where each point should go.
     */
    private void bounds(Player player, String[] args) {
        String usage = "Usage: /obstadium bounds <stadium> <addpoint | removepoint [number] | setpoint <number> | clear>";
        if (args.length < 3) {
            error(player, usage);
            return;
        }
        OneBounceStadium stadium = named(player, args, "bounds");
        if (stadium == null || !requireEditable(player, stadium)) return;

        Location here = player.getLocation().getBlock().getLocation();
        List<Location> points = stadium.bounds;
        switch (args[2].toLowerCase()) {
            case "addpoint" -> {
                if (points.size() >= OneBounceStadium.MAX_BOUNDS_POINTS) {
                    error(player, "At most " + OneBounceStadium.MAX_BOUNDS_POINTS + " bounds points.");
                    return;
                }
                if (!points.isEmpty() && !points.get(0).getWorld().equals(here.getWorld())) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.add(here);
                save();
                player.sendMessage(Component.text("Added bounds point " + points.size() + " to " + stadium.name + " at "
                        + describe(here) + (points.size() < 3 ? " (needs at least 3)." : "."), NamedTextColor.GREEN));
                warnIfBallOutside(player, stadium);
            }
            case "removepoint" -> {
                if (points.isEmpty()) {
                    error(player, stadium.name + " has no bounds points to remove.");
                    return;
                }
                int index = points.size() - 1; // the last point unless a number is given
                if (args.length > 3) {
                    index = parsePoint(player, args[3], points.size());
                    if (index < 0) return;
                }
                points.remove(index);
                save();
                player.sendMessage(Component.text("Removed bounds point " + (index + 1) + " from " + stadium.name + ". "
                        + points.size() + " left" + (points.isEmpty() ? ", so the circle is the edge of play again"
                        : points.size() < 3 ? " (needs at least 3, or /obstadium bounds " + stadium.name + " clear)"
                        : " (the points after it moved down one number)") + ".", NamedTextColor.YELLOW));
                warnIfBallOutside(player, stadium);
            }
            case "setpoint" -> {
                if (args.length < 4) {
                    error(player, "Usage: /obstadium bounds <stadium> setpoint <number>  (stand where the point should go)");
                    return;
                }
                int index = parsePoint(player, args[3], points.size());
                if (index < 0) return;
                if (points.size() > 1 && !points.get(index == 0 ? 1 : 0).getWorld().equals(here.getWorld())) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.set(index, here);
                save();
                player.sendMessage(Component.text("Moved bounds point " + (index + 1) + " of " + stadium.name + " to "
                        + describe(here) + ".", NamedTextColor.GREEN));
                warnIfBallOutside(player, stadium);
            }
            case "clear" -> {
                points.clear();
                save();
                player.sendMessage(Component.text("Cleared the bounds of " + stadium.name
                        + ". The circle is the edge of play again.", NamedTextColor.YELLOW));
            }
            default -> error(player, usage);
        }
    }

    /** Turns "3" into the list index 2, or explains the problem and returns -1. */
    private int parsePoint(Player player, String text, int size) {
        int number;
        try {
            number = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            number = -1;
        }
        if (number < 1 || number > size) {
            error(player, size == 0 ? "There are no bounds points yet." : "The point must be between 1 and " + size + ".");
            return -1;
        }
        return number - 1;
    }

    /** The ball is served from the middle, so the middle has to be inside the bounds. */
    private void warnIfBallOutside(Player player, OneBounceStadium stadium) {
        if (stadium.hasBounds() && stadium.ballPos != null && stadium.ballPos.getWorld() != null
                && stadium.bounds.get(0).getWorld().equals(stadium.ballPos.getWorld())
                && !stadium.boundsContain(stadium.centre())) {
            player.sendMessage(Component.text("Warning: the ball position of " + stadium.name
                    + " is outside these bounds. Move the points or use /obstadium setballpos.", NamedTextColor.GOLD));
        }
    }

    /** Refuses (and explains) if the arena is confirmed; otherwise remembers it as the one being worked on. */
    private boolean requireEditable(Player player, OneBounceStadium stadium) {
        if (stadium.confirmed) {
            error(player, stadium.name + " is confirmed and locked. Use /obstadium edit " + stadium.name + " to change it.");
            return false;
        }
        startEditing(player, stadium);
        return true;
    }

    /** Makes this the arena the player is editing; the one they were editing before is locked. */
    private void startEditing(Player player, OneBounceStadium stadium) {
        String previousName = lastEdited.put(player.getUniqueId(), stadium.name.toLowerCase());
        if (previousName != null && !previousName.equalsIgnoreCase(stadium.name)) {
            OneBounceStadium previous = stadiums.get(previousName);
            if (previous != null && !previous.confirmed) {
                previous.confirmed = true;
                save();
            }
        }
    }

    /** /obstadium confirm [name]: locks the arena and hides its particles. It must be finished. */
    private void confirm(Player player, String[] args) {
        OneBounceStadium stadium = namedOrLast(player, args, "confirm");
        if (stadium == null) return;
        if (stadium.confirmed) {
            error(player, stadium.name + " is already confirmed.");
            return;
        }
        List<String> missing = stadium.missingParts();
        if (!missing.isEmpty()) {
            error(player, stadium.name + " isn't finished yet. Still missing: " + String.join(", ", missing) + ".");
            return;
        }
        stadium.confirmed = true;
        save();
        player.sendMessage(Component.text("Confirmed " + stadium.name + ". The particles are gone and it is locked. "
                + "Use /obstadium edit " + stadium.name + " to change it again.", NamedTextColor.GREEN));
    }

    /** /obstadium edit [name]: unlocks the arena and brings the particles back. */
    private void edit(Player player, String[] args) {
        OneBounceStadium stadium = namedOrLast(player, args, "edit");
        if (stadium == null) return;
        if (!stadium.confirmed && stadium.name.equalsIgnoreCase(lastEdited.get(player.getUniqueId()))) {
            error(player, "You are already editing " + stadium.name + ".");
            return;
        }
        startEditing(player, stadium); // locks whatever this player was editing before
        stadium.confirmed = false;
        save();
        player.sendMessage(Component.text("Editing " + stadium.name + ". The particles are back; use /obstadium confirm "
                + "when you are done.", NamedTextColor.GREEN));
    }

    /** The named arena, or the one this player last worked on if no name is given. */
    private OneBounceStadium namedOrLast(Player player, String[] args, String sub) {
        if (args.length >= 2) {
            OneBounceStadium stadium = get(args[1]);
            if (stadium == null) error(player, "No One-Bounce stadium called " + args[1] + ".");
            return stadium;
        }
        String last = lastEdited.get(player.getUniqueId());
        OneBounceStadium stadium = last == null ? null : stadiums.get(last);
        if (stadium == null) error(player, "Name a stadium: /obstadium " + sub + " <stadium>");
        return stadium;
    }

    private OneBounceStadium named(Player player, String[] args, String sub) {
        if (args.length < 2) {
            error(player, "Usage: /obstadium " + sub + " <stadium> ...");
            return null;
        }
        OneBounceStadium stadium = get(args[1]);
        if (stadium == null) error(player, "No One-Bounce stadium called " + args[1] + ".");
        return stadium;
    }

    private void line(Player player, String label, String value) {
        player.sendMessage(Component.text(" - " + label + ": ", NamedTextColor.GRAY)
                .append(value == null ? Component.text("not set", NamedTextColor.RED)
                        : Component.text(value, NamedTextColor.GREEN)));
    }

    private String describe(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("wfsbbfl.obstadium")) return List.of();
        String prefix = args[args.length - 1].toLowerCase();
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(SUBCOMMANDS);
        else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            stadiums.values().forEach(s -> options.add(s.name));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bounds")) {
            options.addAll(BOUNDS_ACTIONS);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("bounds")
                && (args[2].equalsIgnoreCase("removepoint") || args[2].equalsIgnoreCase("setpoint"))) {
            OneBounceStadium target = get(args[1]);
            if (target != null) for (int i = 1; i <= target.bounds.size(); i++) options.add(String.valueOf(i));
        }
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ particles (only for the op editing it)

    private void showParticles() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String name = lastEdited.get(viewer.getUniqueId());
            if (name == null || !viewer.hasPermission("wfsbbfl.obstadium")) continue;
            OneBounceStadium stadium = stadiums.get(name);
            if (stadium == null || stadium.confirmed) continue;
            if (stadium.ballPos == null || !stadium.ballPos.getWorld().equals(viewer.getWorld())) continue;

            Location c = stadium.centre();
            Particle.DustOptions pink = new Particle.DustOptions(Color.FUCHSIA, 1.2f);
            // A pillar marking the ball position.
            line(viewer, pink, c.getX(), c.getY(), c.getZ(), c.getX(), c.getY() + 3, c.getZ());

            // The bounds, if it has any: an upright marker at each point and lines between them in order.
            Particle.DustOptions aqua = new Particle.DustOptions(Color.AQUA, 1.2f);
            int n = stadium.bounds.size();
            for (int i = 0; i < n; i++) {
                Location a = stadium.bounds.get(i);
                if (!a.getWorld().equals(viewer.getWorld())) break;
                double ax = a.getBlockX() + 0.5;
                double az = a.getBlockZ() + 0.5;
                double y = a.getBlockY() + 0.1;
                line(viewer, aqua, ax, y, az, ax, y + 2, az);
                if (n > 1) {
                    Location b = stadium.bounds.get((i + 1) % n);
                    line(viewer, aqua, ax, y, az, b.getBlockX() + 0.5, b.getBlockY() + 0.1, b.getBlockZ() + 0.5);
                }
            }

            // The circle stays visible while editing; it is the edge of play only when there are no bounds.
            if (stadium.radius > 0) {
                Particle.DustOptions white = new Particle.DustOptions(Color.WHITE, 1.2f);
                int steps = (int) Math.max(24, Math.min(160, stadium.radius * 4));
                Location eye = viewer.getLocation();
                for (int i = 0; i < steps; i++) {
                    double angle = 2 * Math.PI * i / steps;
                    double x = c.getX() + Math.cos(angle) * stadium.radius;
                    double z = c.getZ() + Math.sin(angle) * stadium.radius;
                    double dx = x - eye.getX();
                    double dz = z - eye.getZ();
                    if (dx * dx + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) continue;
                    viewer.spawnParticle(Particle.DUST, x, c.getY() + 0.1, z, 1, 0, 0, 0, 0, white);
                    viewer.spawnParticle(Particle.DUST, x, c.getY() + 1.1, z, 1, 0, 0, 0, 0, white);
                }
            }
        }
    }

    private void line(Player viewer, Particle.DustOptions dust, double x1, double y1, double z1,
                      double x2, double y2, double z2) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = (int) Math.max(1, Math.min(80, Math.ceil(length / 0.5)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            viewer.spawnParticle(Particle.DUST, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0, 0, 0, 0, dust);
        }
    }

    // ------------------------------------------------------------------ saving

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (OneBounceStadium stadium : stadiums.values()) {
            String path = "stadiums." + stadium.name.toLowerCase();
            yaml.set(path + ".name", stadium.name);
            if (stadium.ballPos != null) yaml.set(path + ".ballPos", stadium.ballPos);
            yaml.set(path + ".radius", stadium.radius);
            for (int i = 0; i < stadium.bounds.size(); i++) yaml.set(path + ".bounds." + (i + 1), stadium.bounds.get(i));
            yaml.set(path + ".confirmed", stadium.confirmed);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save onebouncestadiums.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("stadiums");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path = "stadiums." + key;
            String name = yaml.getString(path + ".name", key);
            OneBounceStadium stadium = new OneBounceStadium(name);
            stadium.ballPos = yaml.getLocation(path + ".ballPos");
            stadium.radius = yaml.getDouble(path + ".radius", 0);
            for (int i = 1; i <= OneBounceStadium.MAX_BOUNDS_POINTS; i++) {
                Location point = yaml.getLocation(path + ".bounds." + i);
                if (point == null) break;
                stadium.bounds.add(point);
            }
            stadium.confirmed = yaml.getBoolean(path + ".confirmed", true);
            stadiums.put(name.toLowerCase(), stadium);
        }
    }
}
