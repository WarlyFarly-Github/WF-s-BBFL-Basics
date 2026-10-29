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

/** /smashstadium (operators only): makes and edits Smash arenas, saved in smashstadiums.yml. */
final class SmashStadiums implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "list", "info", "edit", "confirm", "bounds", "wallpoint1", "wallpoint2", "wallheight", "wallfront");
    private static final List<String> BOUNDS_ACTIONS = List.of("addpoint", "removepoint", "clear");
    private static final double VIEW_DISTANCE = 48;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, SmashStadium> stadiums = new LinkedHashMap<>();
    private final Map<UUID, String> lastEdited = new HashMap<>();

    SmashStadiums(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "smashstadiums.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::showParticles, 20L, 10L);
    }

    SmashStadium get(String name) {
        return name == null ? null : stadiums.get(name.toLowerCase());
    }

    List<SmashStadium> all() {
        return new ArrayList<>(stadiums.values());
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission("wfsbbfl.smashstadium")) {
            error(player, "Only operators can use /smashstadium.");
            return true;
        }
        if (args.length == 0 || !SUBCOMMANDS.contains(args[0].toLowerCase())) {
            error(player, "Usage: /smashstadium <create|delete|list|info|edit|confirm|bounds|wallpoint1|wallpoint2|wallheight|wallfront> ...");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "delete" -> delete(player, args);
            case "list" -> list(player);
            case "info" -> info(player, args);
            case "edit" -> edit(player, args);
            case "confirm" -> confirm(player, args);
            case "bounds" -> bounds(player, args);
            case "wallpoint1", "wallpoint2" -> wallPoint(player, args);
            case "wallheight" -> wallHeight(player, args);
            case "wallfront" -> wallFront(player, args);
            default -> { }
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length != 2) {
            error(player, "Usage: /smashstadium create <name>");
            return;
        }
        if (!args[1].matches("[A-Za-z0-9_-]{1,24}")) {
            error(player, "Names can use letters, numbers, - and _ (up to 24 characters).");
            return;
        }
        if (get(args[1]) != null) {
            error(player, "A smash stadium called " + args[1] + " already exists.");
            return;
        }
        SmashStadium stadium = new SmashStadium(args[1]);
        stadiums.put(args[1].toLowerCase(), stadium);
        startEditing(player, stadium);
        save();
        player.sendMessage(Component.text("Created smash stadium " + stadium.name + ". It needs bounds (/smashstadium bounds "
                + stadium.name + " addpoint), wallpoint1, wallpoint2 and wallheight. Use /smashstadium confirm "
                + stadium.name + " when you are done.", NamedTextColor.GREEN));
    }

    private void delete(Player player, String[] args) {
        SmashStadium stadium = named(player, args, "delete");
        if (stadium == null) return;
        stadiums.remove(stadium.name.toLowerCase());
        lastEdited.values().removeIf(name -> name.equalsIgnoreCase(stadium.name));
        save();
        player.sendMessage(Component.text("Deleted smash stadium " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    private void list(Player player) {
        if (stadiums.isEmpty()) {
            player.sendMessage(Component.text("There are no smash stadiums yet. Use /smashstadium create <name>.",
                    NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Smash stadiums:", NamedTextColor.GOLD));
        for (SmashStadium stadium : stadiums.values()) {
            player.sendMessage(Component.text(" - " + stadium.name + (stadium.isComplete() ? "" : " (unfinished)"),
                    stadium.isComplete() ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
    }

    private void info(Player player, String[] args) {
        SmashStadium stadium = named(player, args, "info");
        if (stadium == null) return;
        player.sendMessage(Component.text("Smash stadium " + stadium.name, NamedTextColor.GOLD));
        line(player, "Bounds", stadium.hasBounds() ? stadium.bounds.size() + " points" : null);
        line(player, "Wall point 1", stadium.wall1 == null ? null : describe(stadium.wall1));
        line(player, "Wall point 2", stadium.wall2 == null ? null : describe(stadium.wall2));
        line(player, "Wall height", stadium.wallHeight > 0 ? stadium.wallHeight + " blocks" : null);
        if (stadium.hasWallPoints()) line(player, "Wall front faces", stadium.frontCompass());
        List<String> missing = stadium.missingParts();
        line(player, "Status", stadium.confirmed ? "confirmed (locked)" : "editing (particles shown)");
        line(player, "Ready to play", missing.isEmpty() ? "yes" : null);
        if (!missing.isEmpty()) {
            player.sendMessage(Component.text("Still needed: " + String.join(", ", missing), NamedTextColor.RED));
        }
    }

    private void bounds(Player player, String[] args) {
        String usage = "Usage: /smashstadium bounds <stadium> <addpoint | removepoint [number] | clear>";
        if (args.length < 3) {
            error(player, usage);
            return;
        }
        SmashStadium stadium = named(player, args, "bounds");
        if (stadium == null || !requireEditable(player, stadium)) return;

        Location here = player.getLocation().getBlock().getLocation();
        List<Location> points = stadium.bounds;
        switch (args[2].toLowerCase()) {
            case "addpoint" -> {
                if (points.size() >= SmashStadium.MAX_BOUNDS_POINTS) {
                    error(player, "At most " + SmashStadium.MAX_BOUNDS_POINTS + " bounds points.");
                    return;
                }
                if (!points.isEmpty() && !points.get(0).getWorld().equals(here.getWorld())) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.add(here);
                save();
                player.sendMessage(Component.text("Added bounds point " + points.size() + " at " + describe(here)
                        + (points.size() < 3 ? " (needs at least 3)." : "."), NamedTextColor.GREEN));
            }
            case "removepoint" -> {
                if (points.isEmpty()) {
                    error(player, "There are no bounds points to remove.");
                    return;
                }
                int index = points.size() - 1;
                if (args.length > 3) {
                    try {
                        index = Integer.parseInt(args[3]) - 1;
                    } catch (NumberFormatException e) {
                        index = -1;
                    }
                    if (index < 0 || index >= points.size()) {
                        error(player, "Choose a point number from 1 to " + points.size() + ".");
                        return;
                    }
                }
                points.remove(index);
                save();
                player.sendMessage(Component.text("Removed bounds point " + (index + 1) + ".", NamedTextColor.YELLOW));
            }
            case "clear" -> {
                points.clear();
                save();
                player.sendMessage(Component.text("Cleared the bounds of " + stadium.name + ".", NamedTextColor.YELLOW));
            }
            default -> error(player, usage);
        }
    }

    private void wallPoint(Player player, String[] args) {
        boolean first = args[0].equalsIgnoreCase("wallpoint1");
        SmashStadium stadium = named(player, args, args[0].toLowerCase());
        if (stadium == null || !requireEditable(player, stadium)) return;

        Location here = player.getLocation().getBlock().getLocation();
        if (first) stadium.wall1 = here;
        else stadium.wall2 = here;
        save();
        player.sendMessage(Component.text("Wall point " + (first ? 1 : 2) + " of " + stadium.name + " set to " + describe(here)
                + ". The wall runs between the two points, upwards from the lower one.", NamedTextColor.GREEN));
    }

    private void wallHeight(Player player, String[] args) {
        if (args.length != 3) {
            error(player, "Usage: /smashstadium wallheight <stadium> <blocks>");
            return;
        }
        SmashStadium stadium = named(player, args, "wallheight");
        if (stadium == null || !requireEditable(player, stadium)) return;
        double height;
        try {
            height = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            height = -1;
        }
        if (height <= 0 || height > 64) {
            error(player, "The height must be a number of blocks between 0 and 64.");
            return;
        }
        stadium.wallHeight = height;
        save();
        player.sendMessage(Component.text("The wall of " + stadium.name + " is now " + height + " blocks high.",
                NamedTextColor.GREEN));
    }

    /** Refuses (and explains) if the arena is confirmed; otherwise remembers it as the one being worked on. */
    private boolean requireEditable(Player player, SmashStadium stadium) {
        if (stadium.confirmed) {
            error(player, stadium.name + " is confirmed and locked. Use /smashstadium edit " + stadium.name
                    + " to change it.");
            return false;
        }
        startEditing(player, stadium);
        return true;
    }

    /** Makes this the arena the player is editing; the one they were editing before is locked. */
    private void startEditing(Player player, SmashStadium stadium) {
        String previousName = lastEdited.put(player.getUniqueId(), stadium.name.toLowerCase());
        if (previousName != null && !previousName.equalsIgnoreCase(stadium.name)) {
            SmashStadium previous = stadiums.get(previousName);
            if (previous != null && !previous.confirmed) {
                previous.confirmed = true;
                save();
            }
        }
    }

    /** /smashstadium confirm [name]: locks the arena and hides its particles. It must be finished. */
    private void confirm(Player player, String[] args) {
        SmashStadium stadium = namedOrLast(player, args, "confirm");
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
                + "Use /smashstadium edit " + stadium.name + " to change it again.", NamedTextColor.GREEN));
    }

    /** /smashstadium edit [name]: unlocks the arena and brings the particles back. */
    private void edit(Player player, String[] args) {
        SmashStadium stadium = namedOrLast(player, args, "edit");
        if (stadium == null) return;
        if (!stadium.confirmed && stadium.name.equalsIgnoreCase(lastEdited.get(player.getUniqueId()))) {
            error(player, "You are already editing " + stadium.name + ".");
            return;
        }
        startEditing(player, stadium); // locks whatever this player was editing before
        stadium.confirmed = false;
        save();
        player.sendMessage(Component.text("Editing " + stadium.name + ". The particles are back; use /smashstadium confirm "
                + "when you are done.", NamedTextColor.GREEN));
    }

    private static final Map<String, double[]> DIRECTIONS = Map.of(
            "north", new double[] {0, -1}, "south", new double[] {0, 1},
            "east", new double[] {1, 0}, "west", new double[] {-1, 0},
            "northeast", new double[] {1, -1}, "northwest", new double[] {-1, -1},
            "southeast", new double[] {1, 1}, "southwest", new double[] {-1, 1});

    /** /smashstadium wallfront <stadium> <direction|here>: which side of the wall the trigger is on. */
    private void wallFront(Player player, String[] args) {
        String usage = "Usage: /smashstadium wallfront <stadium> <north|northeast|east|southeast|south|southwest|west|northwest|here>";
        if (args.length != 3) {
            error(player, usage);
            return;
        }
        SmashStadium stadium = named(player, args, "wallfront");
        if (stadium == null || !requireEditable(player, stadium)) return;
        if (!stadium.hasWallPoints()) {
            error(player, "Set wallpoint1 and wallpoint2 first.");
            return;
        }

        String choice = args[2].toLowerCase().replace("-", "");
        boolean ok;
        if (choice.equals("here")) {
            // The side of the wall you are standing on.
            double side = stadium.signedWallDistance(player.getLocation());
            ok = Math.abs(side) > 1.0E-6;
            if (ok) stadium.frontSide = side > 0 ? 1 : -1;
        } else if (DIRECTIONS.containsKey(choice)) {
            double[] d = DIRECTIONS.get(choice);
            ok = stadium.setFrontToward(d[0], d[1]);
        } else {
            error(player, usage);
            return;
        }
        if (!ok) {
            error(player, "That direction runs along the wall, so it has no front. Try another direction or stand to one side and use here.");
            return;
        }
        save();
        player.sendMessage(Component.text("The front of the wall of " + stadium.name + " now faces " + stadium.frontCompass()
                + ". The ball bounces off a trigger line just in front of the wall's blocks (shown in aqua while editing).",
                NamedTextColor.GREEN));
    }

    /** The named arena, or the one this player last worked on if no name is given. */
    private SmashStadium namedOrLast(Player player, String[] args, String sub) {
        if (args.length >= 2) {
            SmashStadium stadium = get(args[1]);
            if (stadium == null) error(player, "No smash stadium called " + args[1] + ".");
            return stadium;
        }
        String last = lastEdited.get(player.getUniqueId());
        SmashStadium stadium = last == null ? null : stadiums.get(last);
        if (stadium == null) error(player, "Name a stadium: /smashstadium " + sub + " <stadium>");
        return stadium;
    }

    private SmashStadium named(Player player, String[] args, String sub) {
        if (args.length < 2) {
            error(player, "Usage: /smashstadium " + sub + " <stadium> ...");
            return null;
        }
        SmashStadium stadium = get(args[1]);
        if (stadium == null) error(player, "No smash stadium called " + args[1] + ".");
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
        if (!sender.hasPermission("wfsbbfl.smashstadium")) return List.of();
        String prefix = args[args.length - 1].toLowerCase();
        List<String> options = new ArrayList<>();
        if (args.length == 1) options.addAll(SUBCOMMANDS);
        else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            stadiums.values().forEach(s -> options.add(s.name));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bounds")) options.addAll(BOUNDS_ACTIONS);
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix)).toList();
    }

    // ------------------------------------------------------------------ particles (only for the op editing it)

    private void showParticles() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String name = lastEdited.get(viewer.getUniqueId());
            if (name == null || !viewer.hasPermission("wfsbbfl.smashstadium")) continue;
            SmashStadium stadium = stadiums.get(name);
            if (stadium == null || stadium.confirmed) continue;

            Particle.DustOptions white = new Particle.DustOptions(Color.WHITE, 1.2f);
            int n = stadium.bounds.size();
            for (int i = 0; i < n; i++) {
                Location a = stadium.bounds.get(i);
                if (!a.getWorld().equals(viewer.getWorld())) break;
                double ax = a.getBlockX() + 0.5;
                double az = a.getBlockZ() + 0.5;
                double y = a.getBlockY() + 0.1;
                line(viewer, white, ax, y, az, ax, y + 2, az);
                if (n > 1) {
                    Location b = stadium.bounds.get((i + 1) % n);
                    line(viewer, white, ax, y, az, b.getBlockX() + 0.5, b.getBlockY() + 0.1, b.getBlockZ() + 0.5);
                }
            }

            Particle.DustOptions pink = new Particle.DustOptions(Color.FUCHSIA, 1.2f);
            for (Location w : new Location[] {stadium.wall1, stadium.wall2}) {
                if (w == null || !w.getWorld().equals(viewer.getWorld())) continue;
                double top = w.getBlockY() + Math.max(stadium.wallHeight, 1);
                line(viewer, pink, w.getBlockX() + 0.5, w.getBlockY(), w.getBlockZ() + 0.5,
                        w.getBlockX() + 0.5, top, w.getBlockZ() + 0.5);
            }
            if (stadium.hasWallPoints() && stadium.wallHeight > 0 && stadium.wall1.getWorld().equals(viewer.getWorld())) {
                double base = Math.min(stadium.wall1.getBlockY(), stadium.wall2.getBlockY());
                double x1 = stadium.wall1.getBlockX() + 0.5;
                double z1 = stadium.wall1.getBlockZ() + 0.5;
                double x2 = stadium.wall2.getBlockX() + 0.5;
                double z2 = stadium.wall2.getBlockZ() + 0.5;
                line(viewer, pink, x1, base, z1, x2, base, z2);
                line(viewer, pink, x1, base + stadium.wallHeight, z1, x2, base + stadium.wallHeight, z2);

                // The trigger line in front of the wall, where the ball bounces.
                double[] front = stadium.frontNormal();
                double trigger = SmashStadium.triggerDistance(SmashStadium.DEFAULT_BALL_HALF_WIDTH);
                double ox = front[0] * trigger;
                double oz = front[1] * trigger;
                Particle.DustOptions aqua = new Particle.DustOptions(Color.AQUA, 1.2f);
                line(viewer, aqua, x1 + ox, base, z1 + oz, x2 + ox, base, z2 + oz);
                line(viewer, aqua, x1 + ox, base + stadium.wallHeight, z1 + oz, x2 + ox, base + stadium.wallHeight, z2 + oz);
                line(viewer, aqua, x1 + ox, base, z1 + oz, x1 + ox, base + stadium.wallHeight, z1 + oz);
                line(viewer, aqua, x2 + ox, base, z2 + oz, x2 + ox, base + stadium.wallHeight, z2 + oz);
            }
        }
    }

    private void line(Player viewer, Particle.DustOptions dust, double x1, double y1, double z1,
                      double x2, double y2, double z2) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = (int) Math.max(1, Math.min(80, Math.ceil(length / 0.75)));
        Location eye = viewer.getLocation();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + (x2 - x1) * t;
            double y = y1 + (y2 - y1) * t;
            double z = z1 + (z2 - z1) * t;
            double dx = x - eye.getX();
            double dy = y - eye.getY();
            double dz = z - eye.getZ();
            if (dx * dx + dy * dy + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) continue;
            viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
        }
    }

    // ------------------------------------------------------------------ saving

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (SmashStadium stadium : stadiums.values()) {
            String path = "stadiums." + stadium.name.toLowerCase();
            yaml.set(path + ".name", stadium.name);
            for (int i = 0; i < stadium.bounds.size(); i++) yaml.set(path + ".bounds." + (i + 1), stadium.bounds.get(i));
            if (stadium.wall1 != null) yaml.set(path + ".wall1", stadium.wall1);
            if (stadium.wall2 != null) yaml.set(path + ".wall2", stadium.wall2);
            yaml.set(path + ".wallHeight", stadium.wallHeight);
            yaml.set(path + ".confirmed", stadium.confirmed);
            yaml.set(path + ".frontSide", stadium.frontSide);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save smashstadiums.yml: " + e.getMessage());
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
            SmashStadium stadium = new SmashStadium(name);
            ConfigurationSection points = yaml.getConfigurationSection(path + ".bounds");
            if (points != null) {
                List<String> keys = new ArrayList<>(points.getKeys(false));
                keys.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
                for (String k : keys) {
                    Location l = yaml.getLocation(path + ".bounds." + k);
                    if (l != null) stadium.bounds.add(l);
                }
            }
            stadium.wall1 = yaml.getLocation(path + ".wall1");
            stadium.wall2 = yaml.getLocation(path + ".wall2");
            stadium.wallHeight = yaml.getDouble(path + ".wallHeight", 0);
            stadium.confirmed = yaml.getBoolean(path + ".confirmed", true);
            stadium.frontSide = yaml.getInt(path + ".frontSide", 1) < 0 ? -1 : 1;
            stadiums.put(name.toLowerCase(), stadium);
        }
    }
}
