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

/** /hpstadium (operators only): makes and edits Hot Potato arenas, saved in hotpotatostadiums.yml. */
final class HotPotatoStadiums implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "list", "info", "setpos", "clearpos", "bounds", "edit", "confirm");
    private static final List<String> BOUNDS_ACTIONS = List.of("addpoint", "removepoint", "setpoint", "clear");
    private static final List<String> TEAMS = List.of("red", "blue");
    private static final double VIEW_DISTANCE = 64;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, HotPotatoStadium> stadiums = new LinkedHashMap<>();
    private final Map<UUID, String> lastEdited = new HashMap<>();

    HotPotatoStadiums(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "hotpotatostadiums.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::showParticles, 20L, 10L);
    }

    HotPotatoStadium get(String name) {
        return name == null ? null : stadiums.get(name.toLowerCase());
    }

    List<HotPotatoStadium> all() {
        return new ArrayList<>(stadiums.values());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("wfsbbfl.hpstadium")) {
            error(player, "Only operators can use /hpstadium.");
            return true;
        }
        if (args.length == 0 || !SUBCOMMANDS.contains(args[0].toLowerCase())) {
            error(player, "Usage: /hpstadium <create|delete|list|info|setpos|clearpos|bounds|edit|confirm> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "delete" -> delete(player, args);
            case "list" -> list(player);
            case "info" -> info(player, args);
            case "setpos" -> setPos(player, args);
            case "clearpos" -> clearPos(player, args);
            case "bounds" -> bounds(player, args);
            case "edit" -> edit(player, args);
            case "confirm" -> confirm(player, args);
            default -> { }
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length != 2) {
            error(player, "Usage: /hpstadium create <name>");
            return;
        }
        if (!args[1].matches("[A-Za-z0-9_-]{1,24}")) {
            error(player, "Names can use letters, numbers, - and _ (up to 24 characters).");
            return;
        }
        if (get(args[1]) != null) {
            error(player, "A Hot Potato stadium called " + args[1] + " already exists.");
            return;
        }
        HotPotatoStadium stadium = new HotPotatoStadium(args[1]);
        stadiums.put(args[1].toLowerCase(), stadium);
        startEditing(player, stadium);
        save();
        player.sendMessage(Component.text("Created Hot Potato stadium " + stadium.name + ". Stand on the floor at each corner "
                + "and use /hpstadium bounds " + stadium.name + " addpoint (at least 3 points, in order around the edge). "
                + "Then stand where each player starts and use /hpstadium setpos " + stadium.name + " <red|blue> <1-5> "
                + "(one position per player, up to 5 per team). Build the floor yourself: ice makes the ball slide. "
                + "Use /hpstadium confirm " + stadium.name + " when you are done.", NamedTextColor.GREEN));
    }

    private void delete(Player player, String[] args) {
        HotPotatoStadium stadium = named(player, args, "delete");
        if (stadium == null) return;
        stadiums.remove(stadium.name.toLowerCase());
        lastEdited.values().removeIf(name -> name.equalsIgnoreCase(stadium.name));
        save();
        player.sendMessage(Component.text("Deleted Hot Potato stadium " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    private void list(Player player) {
        if (stadiums.isEmpty()) {
            player.sendMessage(Component.text("There are no Hot Potato stadiums yet. Use /hpstadium create <name>.",
                    NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Hot Potato stadiums:", NamedTextColor.GOLD));
        for (HotPotatoStadium stadium : stadiums.values()) {
            player.sendMessage(Component.text(" - " + stadium.name + (stadium.isComplete() ? "" : " (unfinished)"),
                    stadium.isComplete() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
    }

    private void info(Player player, String[] args) {
        HotPotatoStadium stadium = named(player, args, "info");
        if (stadium == null) return;
        player.sendMessage(Component.text("Hot Potato stadium " + stadium.name, NamedTextColor.GOLD));
        for (Match.Team team : Match.Team.values()) {
            StringBuilder positions = new StringBuilder();
            Location[] slots = stadium.slots(team);
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == null) continue;
                if (positions.length() > 0) positions.append("; ");
                positions.append(i + 1).append(": ").append(describe(slots[i]));
            }
            line(player, team.label + " positions (" + stadium.capacity(team) + "/" + HotPotatoStadium.MAX_POSITIONS + ")",
                    positions.length() == 0 ? null : positions.toString());
        }
        player.sendMessage(Component.text(" - Bounds: ", NamedTextColor.GRAY).append(stadium.bounds.isEmpty()
                ? Component.text("not set", NamedTextColor.RED)
                : Component.text(stadium.bounds.size() + " points" + (stadium.hasBounds()
                                ? " (" + stadium.boxWidth() + " x " + stadium.boxDepth() + " blocks across)" : " (needs at least 3)"),
                        stadium.hasBounds() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        List<String> missing = stadium.missingParts();
        line(player, "Status", stadium.confirmed ? "confirmed (locked)" : "editing (particles shown)");
        line(player, "Ready to play", missing.isEmpty() ? "yes" : null);
        if (!missing.isEmpty()) {
            player.sendMessage(Component.text("Still needed: " + String.join(", ", missing), NamedTextColor.RED));
        }
    }

    /** /hpstadium setpos <stadium> <red|blue> <1-5>: the block you stand on is where that player starts. */
    private void setPos(Player player, String[] args) {
        String usage = "Usage: /hpstadium setpos <stadium> <red|blue> <1-" + HotPotatoStadium.MAX_POSITIONS + ">";
        if (args.length != 4 || !TEAMS.contains(args[2].toLowerCase())) {
            error(player, usage);
            return;
        }
        HotPotatoStadium stadium = named(player, args, "setpos");
        if (stadium == null || !requireEditable(player, stadium)) return;
        int number = positionNumber(player, args[3], usage);
        if (number < 0) return;
        Match.Team team = args[2].equalsIgnoreCase("red") ? Match.Team.RED : Match.Team.BLUE;
        Location spot = player.getLocation().getBlock().getLocation();
        stadium.slots(team)[number] = spot;
        save();
        player.sendMessage(Component.text(team.label + " position " + (number + 1) + " of " + stadium.name + " set to "
                + describe(spot) + ". The team has " + stadium.capacity(team) + " position(s) now: that is how many players it "
                + "can have here.", team.color));
        warnIfPositionsOutside(player, stadium);
    }

    /** /hpstadium clearpos <stadium> <red|blue> <1-5>: removes one starting position. */
    private void clearPos(Player player, String[] args) {
        String usage = "Usage: /hpstadium clearpos <stadium> <red|blue> <1-" + HotPotatoStadium.MAX_POSITIONS + ">";
        if (args.length != 4 || !TEAMS.contains(args[2].toLowerCase())) {
            error(player, usage);
            return;
        }
        HotPotatoStadium stadium = named(player, args, "clearpos");
        if (stadium == null || !requireEditable(player, stadium)) return;
        int number = positionNumber(player, args[3], usage);
        if (number < 0) return;
        Match.Team team = args[2].equalsIgnoreCase("red") ? Match.Team.RED : Match.Team.BLUE;
        if (stadium.slots(team)[number] == null) {
            error(player, "The " + team.label.toLowerCase() + " team has no position " + (number + 1) + ".");
            return;
        }
        stadium.slots(team)[number] = null;
        save();
        player.sendMessage(Component.text("Removed " + team.label.toLowerCase() + " position " + (number + 1) + " of "
                + stadium.name + ".", NamedTextColor.YELLOW));
    }

    /** A position number 1-5 as a 0-based index, or -1 (after explaining) if it isn't one. */
    private int positionNumber(Player player, String text, String usage) {
        try {
            int number = Integer.parseInt(text);
            if (number >= 1 && number <= HotPotatoStadium.MAX_POSITIONS) return number - 1;
        } catch (NumberFormatException ignored) {
            // falls through to the message
        }
        error(player, usage);
        return -1;
    }

    /**
     * /hpstadium bounds <stadium> <addpoint | removepoint [number] | setpoint <number> | clear>: the polygon that is the
     * arena. Stand on the floor where each point should go.
     */
    private void bounds(Player player, String[] args) {
        String usage = "Usage: /hpstadium bounds <stadium> <addpoint | removepoint [number] | setpoint <number> | clear>";
        if (args.length < 3) {
            error(player, usage);
            return;
        }
        HotPotatoStadium stadium = named(player, args, "bounds");
        if (stadium == null || !requireEditable(player, stadium)) return;

        Location here = player.getLocation().getBlock().getLocation();
        List<Location> points = stadium.bounds;
        switch (args[2].toLowerCase()) {
            case "addpoint" -> {
                if (points.size() >= HotPotatoStadium.MAX_BOUNDS_POINTS) {
                    error(player, "At most " + HotPotatoStadium.MAX_BOUNDS_POINTS + " bounds points.");
                    return;
                }
                if (!points.isEmpty() && !points.get(0).getWorld().equals(here.getWorld())) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.add(here);
                save();
                player.sendMessage(Component.text("Added bounds point " + points.size() + " to " + stadium.name + " at "
                        + describe(here) + (points.size() < HotPotatoStadium.MIN_POINTS ? " (needs at least 3)." : "."),
                        NamedTextColor.GREEN));
                warnIfPositionsOutside(player, stadium);
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
                        + points.size() + " left" + (points.size() < HotPotatoStadium.MIN_POINTS ? " (needs at least 3)"
                        : " (the points after it moved down one number)") + ".", NamedTextColor.YELLOW));
                warnIfPositionsOutside(player, stadium);
            }
            case "setpoint" -> {
                if (args.length < 4) {
                    error(player, "Usage: /hpstadium bounds <stadium> setpoint <number>  (stand where the point should go)");
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
                warnIfPositionsOutside(player, stadium);
            }
            case "clear" -> {
                points.clear();
                save();
                player.sendMessage(Component.text("Cleared the bounds of " + stadium.name + ".", NamedTextColor.YELLOW));
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

    /** Everyone starts inside the arena, so the positions have to be inside the bounds. */
    private void warnIfPositionsOutside(Player player, HotPotatoStadium stadium) {
        if (!stadium.hasBounds()) return;
        for (Match.Team team : Match.Team.values()) {
            for (Location position : stadium.positions(team)) {
                if (!stadium.boundsContain(HotPotatoStadium.centreOf(position))) {
                    player.sendMessage(Component.text("Warning: a " + team.label.toLowerCase() + " position of " + stadium.name
                            + " is outside these bounds. Move the points or use /hpstadium setpos.", NamedTextColor.GOLD));
                    break;
                }
            }
        }
    }

    /** Refuses (and explains) if the arena is confirmed; otherwise remembers it as the one being worked on. */
    private boolean requireEditable(Player player, HotPotatoStadium stadium) {
        if (stadium.confirmed) {
            error(player, stadium.name + " is confirmed and locked. Use /hpstadium edit " + stadium.name + " to change it.");
            return false;
        }
        startEditing(player, stadium);
        return true;
    }

    /** Makes this the arena the player is editing; the one they were editing before is locked. */
    private void startEditing(Player player, HotPotatoStadium stadium) {
        String previousName = lastEdited.put(player.getUniqueId(), stadium.name.toLowerCase());
        if (previousName != null && !previousName.equalsIgnoreCase(stadium.name)) {
            HotPotatoStadium previous = stadiums.get(previousName);
            if (previous != null && !previous.confirmed) {
                previous.confirmed = true;
                save();
            }
        }
    }

    /** /hpstadium confirm [name]: locks the arena and hides its particles. It must be finished. */
    private void confirm(Player player, String[] args) {
        HotPotatoStadium stadium = namedOrLast(player, args, "confirm");
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
                + "Use /hpstadium edit " + stadium.name + " to change it again.", NamedTextColor.GREEN));
    }

    /** /hpstadium edit [name]: unlocks the arena and brings the particles back. */
    private void edit(Player player, String[] args) {
        HotPotatoStadium stadium = namedOrLast(player, args, "edit");
        if (stadium == null) return;
        if (!stadium.confirmed && stadium.name.equalsIgnoreCase(lastEdited.get(player.getUniqueId()))) {
            error(player, "You are already editing " + stadium.name + ".");
            return;
        }
        startEditing(player, stadium); // locks whatever this player was editing before
        stadium.confirmed = false;
        save();
        player.sendMessage(Component.text("Editing " + stadium.name + ". The particles are back; use /hpstadium confirm "
                + "when you are done.", NamedTextColor.GREEN));
    }

    /** The named arena, or the one this player last worked on if no name is given. */
    private HotPotatoStadium namedOrLast(Player player, String[] args, String sub) {
        if (args.length >= 2) {
            HotPotatoStadium stadium = get(args[1]);
            if (stadium == null) error(player, "No Hot Potato stadium called " + args[1] + ".");
            return stadium;
        }
        String last = lastEdited.get(player.getUniqueId());
        HotPotatoStadium stadium = last == null ? null : stadiums.get(last);
        if (stadium == null) error(player, "Name a stadium: /hpstadium " + sub + " <stadium>");
        return stadium;
    }

    private HotPotatoStadium named(Player player, String[] args, String sub) {
        if (args.length < 2) {
            error(player, "Usage: /hpstadium " + sub + " <stadium> ...");
            return null;
        }
        HotPotatoStadium stadium = get(args[1]);
        if (stadium == null) error(player, "No Hot Potato stadium called " + args[1] + ".");
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
        if (!sender.hasPermission("wfsbbfl.hpstadium")) return List.of();
        String prefix = args[args.length - 1].toLowerCase();
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(SUBCOMMANDS);
        else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            stadiums.values().forEach(s -> options.add(s.name));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bounds")) {
            options.addAll(BOUNDS_ACTIONS);
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("setpos") || args[0].equalsIgnoreCase("clearpos"))) {
            options.addAll(TEAMS);
        } else if (args.length == 4 && (args[0].equalsIgnoreCase("setpos") || args[0].equalsIgnoreCase("clearpos"))) {
            for (int i = 1; i <= HotPotatoStadium.MAX_POSITIONS; i++) options.add(String.valueOf(i));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("bounds")
                && (args[2].equalsIgnoreCase("removepoint") || args[2].equalsIgnoreCase("setpoint"))) {
            HotPotatoStadium target = get(args[1]);
            if (target != null) for (int i = 1; i <= target.bounds.size(); i++) options.add(String.valueOf(i));
        }
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ particles (only for the op editing it)

    private void showParticles() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String name = lastEdited.get(viewer.getUniqueId());
            if (name == null || !viewer.hasPermission("wfsbbfl.hpstadium")) continue;
            HotPotatoStadium stadium = stadiums.get(name);
            if (stadium == null || stadium.confirmed) continue;

            // A pillar on each starting position, in the team's colour.
            for (Location position : stadium.redPositions) pillar(viewer, position, new Particle.DustOptions(Color.RED, 1.4f));
            for (Location position : stadium.bluePositions) pillar(viewer, position, new Particle.DustOptions(Color.BLUE, 1.4f));

            // The bounds: an upright marker at each point and lines between them in order (closing the shape).
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
        }
    }

    private void pillar(Player viewer, Location spawn, Particle.DustOptions dust) {
        if (spawn == null || spawn.getWorld() == null || !spawn.getWorld().equals(viewer.getWorld())) return;
        double x = spawn.getBlockX() + 0.5;
        double z = spawn.getBlockZ() + 0.5;
        double dx = x - viewer.getLocation().getX();
        double dz = z - viewer.getLocation().getZ();
        if (dx * dx + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) return;
        line(viewer, dust, x, spawn.getBlockY(), z, x, spawn.getBlockY() + 3, z);
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
        for (HotPotatoStadium stadium : stadiums.values()) {
            String path = "stadiums." + stadium.name.toLowerCase();
            yaml.set(path + ".name", stadium.name);
            for (int i = 0; i < HotPotatoStadium.MAX_POSITIONS; i++) {
                if (stadium.redPositions[i] != null) yaml.set(path + ".red." + (i + 1), stadium.redPositions[i]);
                if (stadium.bluePositions[i] != null) yaml.set(path + ".blue." + (i + 1), stadium.bluePositions[i]);
            }
            for (int i = 0; i < stadium.bounds.size(); i++) yaml.set(path + ".bounds." + (i + 1), stadium.bounds.get(i));
            yaml.set(path + ".confirmed", stadium.confirmed);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save hotpotatostadiums.yml: " + e.getMessage());
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
            HotPotatoStadium stadium = new HotPotatoStadium(name);
            for (int i = 0; i < HotPotatoStadium.MAX_POSITIONS; i++) {
                stadium.redPositions[i] = yaml.getLocation(path + ".red." + (i + 1));
                stadium.bluePositions[i] = yaml.getLocation(path + ".blue." + (i + 1));
            }
            for (int i = 1; i <= HotPotatoStadium.MAX_BOUNDS_POINTS; i++) {
                Location point = yaml.getLocation(path + ".bounds." + i);
                if (point == null) break;
                stadium.bounds.add(point);
            }
            stadium.confirmed = yaml.getBoolean(path + ".confirmed", true);
            stadiums.put(name.toLowerCase(), stadium);
        }
    }
}
