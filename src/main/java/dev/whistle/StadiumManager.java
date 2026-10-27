package dev.whistle;

import dev.whistle.Match.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
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
import java.util.logging.Level;
import java.util.regex.Pattern;

/** Handles /stadium and saves stadiums to plugins/WFs_BBFL_Basics/stadiums.yml. */
final class StadiumManager implements CommandExecutor, TabCompleter {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "list", "info", "setcentre", "setgoal", "goalheight", "block", "confirm", "edit");

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Stadium> stadiums = new LinkedHashMap<>();

    /** The stadium each builder last worked on, so /stadium confirm and /stadium edit work without a name. */
    private final Map<UUID, String> lastEdited = new HashMap<>();

    private static final double PARTICLE_VIEW_DISTANCE = 40.0;
    private static final double PARTICLE_SPACING = 0.5;
    private static final int MAX_POINTS_PER_EDGE = 200;

    StadiumManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stadiums.yml");
        load();
        // Outline the goals of unconfirmed stadiums for builders; confirmed stadiums show nothing.
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::showGoalParticles, 20L, 10L);
    }

    Stadium get(String name) {
        return stadiums.get(name.toLowerCase());
    }

    List<String> names() {
        return stadiums.values().stream().map(s -> s.name).toList();
    }

    // ------------------------------------------------------------------ commands

    /** The "builder" LuckPerms role grants the group.builder permission node. Operators are allowed too. */
    private boolean canBuild(Player player) {
        return player.hasPermission("group.builder") || player.hasPermission("wfsbbfl.stadium");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (!canBuild(player)) {
            error(player, "You need the builder role to use /stadium.");
            return true;
        }
        if (args.length == 0) {
            error(player, "Usage: /stadium <create|delete|list|info|setcentre|setgoal|goalheight|block|confirm|edit>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "delete" -> delete(player, args);
            case "list" -> list(player);
            case "info" -> info(player, args);
            case "setcentre" -> setCenter(player, args);
            case "setgoal" -> setGoal(player, args);
            case "goalheight" -> setGoalHeight(player, args);
            case "block" -> setBlock(player, args);
            case "confirm" -> confirm(player, args);
            case "edit" -> edit(player, args);
            default -> error(player, "Unknown subcommand. Use /stadium <create|delete|list|info|setcentre|setgoal|goalheight|block|confirm|edit>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !canBuild(player)) return List.of();

        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2 && List.of("delete", "info", "setcentre", "setgoal", "goalheight", "block", "confirm", "edit").contains(sub)) {
            return filter(names(), args[1]);
        }
        if (args.length == 3 && (sub.equals("setgoal") || sub.equals("goalheight"))) {
            return filter(List.of("red", "blue"), args[2]);
        }
        if (args.length == 3 && sub.equals("block")) {
            return filter(List.of("reset"), args[2]);
        }
        if (args.length == 4 && sub.equals("setgoal")) {
            return filter(List.of("pos1", "pos2", "pos3", "pos4"), args[3]);
        }
        if (args.length == 4 && sub.equals("goalheight")) {
            return filter(List.of("2.5", "3", "3.5", "4", "5"), args[3]);
        }
        return List.of();
    }

    private void create(Player player, String[] args) {
        if (args.length < 2) {
            error(player, "Usage: /stadium create <name>");
            return;
        }
        String name = args[1];
        if (!VALID_NAME.matcher(name).matches()) {
            error(player, "Names can only use letters, numbers, - and _ (max 32 characters).");
            return;
        }
        if (get(name) != null) {
            error(player, "A stadium called " + name + " already exists.");
            return;
        }
        Stadium created = new Stadium(name);
        stadiums.put(name.toLowerCase(), created);
        startEditing(player, created);
        save();
        player.sendMessage(Component.text("Created stadium " + name + ". Next: ", NamedTextColor.GREEN)
                .append(Component.text("/stadium setcentre " + name + "  and  /stadium setgoal " + name
                        + " <red|blue> <pos1|pos2|pos3|pos4>", NamedTextColor.YELLOW))
                .append(Component.text(", then /stadium confirm when you are done.", NamedTextColor.GREEN)));
    }

    private void delete(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "delete");
        if (stadium == null) return;
        stadiums.remove(stadium.name.toLowerCase());
        save();
        player.sendMessage(Component.text("Deleted stadium " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    private void list(Player player) {
        if (stadiums.isEmpty()) {
            player.sendMessage(Component.text("There are no stadiums yet. Use /stadium create <name>.", NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text("Stadiums: ", NamedTextColor.GOLD)
                .append(Component.text(String.join(", ", names()), NamedTextColor.WHITE)));
    }

    private void info(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "info");
        if (stadium == null) return;

        player.sendMessage(Component.text("Stadium " + stadium.name, NamedTextColor.GOLD));
        player.sendMessage(statusLine("Status", stadium.confirmed ? "confirmed (locked)" : "editing (goal particles shown)"));
        player.sendMessage(statusLine("Menu block", stadium.block == null ? "player head (default)" : stadium.block.name()));
        player.sendMessage(statusLine("Centre point", stadium.hasCenter() ? describe(stadium.center) : null));
        for (Team team : Team.values()) {
            String value = null;
            if (stadium.goalComplete(team)) {
                value = "4 corners set, " + Stadium.formatHeight(stadium.goalHeight(team)) + " blocks high";
            } else if (stadium.cornersSet(team) > 0) {
                value = "incomplete (" + stadium.cornersSet(team) + "/4 corners, all in the same world)";
            }
            player.sendMessage(statusLine(team.label + " goal (defended by " + team.label + ")", value));
        }
    }

    private void setCenter(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "setcentre");
        if (stadium == null || !requireEditable(player, stadium)) return;

        stadium.center = player.getLocation().clone();
        save();
        player.sendMessage(Component.text("Centre point of " + stadium.name + " set to "
                + describe(stadium.center) + ". The ball is placed here during freezes.", NamedTextColor.GREEN));
    }

    /** /stadium block <name> uses the block in your main hand as the stadium's icon in the chest menu. */
    private void setBlock(Player player, String[] args) {
        if (args.length < 2) {
            error(player, "Usage: /stadium block <name> [reset]  (hold the block you want in your main hand)");
            return;
        }
        Stadium stadium = requireStadium(player, args, "block");
        if (stadium == null || !requireEditable(player, stadium)) return;

        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            stadium.block = null;
            save();
            player.sendMessage(Component.text("Menu block of " + stadium.name + " reset to the player head.",
                    NamedTextColor.GREEN));
            return;
        }

        Material held = player.getInventory().getItemInMainHand().getType();
        if (held.isAir() || !held.isBlock() || !held.isItem()) {
            error(player, "Hold the block you want to use in your main hand first.");
            return;
        }
        stadium.block = held;
        save();
        player.sendMessage(Component.text("Menu block of " + stadium.name + " set to " + held.name() + ".",
                NamedTextColor.GREEN));
    }

    private void setGoal(Player player, String[] args) {
        if (args.length < 4) {
            error(player, "Usage: /stadium setgoal <name> <red|blue> <pos1|pos2|pos3|pos4>");
            return;
        }
        Stadium stadium = get(args[1]);
        if (stadium == null) {
            error(player, "No stadium called " + args[1] + ".");
            return;
        }
        if (!requireEditable(player, stadium)) return;
        Team team = parseTeam(args[2]);
        if (team == null) {
            error(player, "The goal must be red or blue.");
            return;
        }
        int index = -1;
        for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
            if (args[3].equalsIgnoreCase("pos" + (i + 1))) index = i;
        }
        if (index < 0) {
            error(player, "The corner must be pos1, pos2, pos3 or pos4.");
            return;
        }

        // The block you're standing in. Go around the edge of the goal in order: pos1 -> pos2 -> pos3 -> pos4.
        stadium.goals.get(team)[index] = player.getLocation().getBlock().getLocation();
        save();

        Component message = Component.text("Set corner " + (index + 1) + " of the " + team.label + " goal in "
                + stadium.name + " (" + stadium.cornersSet(team) + "/4).", NamedTextColor.GREEN);
        if (stadium.goalComplete(team)) {
            message = message.append(Component.text(" Goal area complete, " + Stadium.formatHeight(stadium.goalHeight(team))
                    + " blocks high. Goals here score for the other team.", NamedTextColor.GREEN));
        }
        player.sendMessage(message);
    }

    private void setGoalHeight(Player player, String[] args) {
        if (args.length < 4) {
            error(player, "Usage: /stadium goalheight <name> <red|blue> <blocks>  (half blocks allowed, e.g. 2.5)");
            return;
        }
        Stadium stadium = get(args[1]);
        if (stadium == null) {
            error(player, "No stadium called " + args[1] + ".");
            return;
        }
        if (!requireEditable(player, stadium)) return;
        Team team = parseTeam(args[2]);
        if (team == null) {
            error(player, "The goal must be red or blue.");
            return;
        }
        double height;
        try {
            height = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            error(player, "The height must be a number, like 3 or 2.5.");
            return;
        }
        if (Double.isNaN(height) || height < 0.5 || height > 64) {
            error(player, "The height must be between 0.5 and 64 blocks.");
            return;
        }
        height = Math.round(height * 2) / 2.0; // snap to whole or half blocks (slabs)

        stadium.goalHeights.put(team, height);
        save();
        player.sendMessage(Component.text("The " + team.label + " goal in " + stadium.name + " is now " + Stadium.formatHeight(height)
                + " blocks high, measured up from its lowest corner block.", NamedTextColor.GREEN));
    }

    /** Refuses (and explains) if the stadium is confirmed; otherwise remembers it as the one being worked on. */
    private boolean requireEditable(Player player, Stadium stadium) {
        if (stadium.confirmed) {
            error(player, stadium.name + " is confirmed and locked. Use /stadium edit " + stadium.name + " to change it.");
            return false;
        }
        startEditing(player, stadium);
        return true;
    }

    /**
     * Makes this stadium the one the player is editing. A player edits one stadium at a time, so the one they were
     * editing before is locked (as if confirmed) unless another builder is still working on it.
     */
    private void startEditing(Player player, Stadium target) {
        String targetKey = target.name.toLowerCase();
        String previousKey = lastEdited.put(player.getUniqueId(), targetKey);
        if (previousKey == null || previousKey.equals(targetKey)) return;

        Stadium previous = stadiums.get(previousKey);
        if (previous == null || previous.confirmed) return;
        if (lastEdited.containsValue(previousKey)) return; // someone else is still editing it

        previous.confirmed = true;
        save();
        Component message = Component.text("Stopped editing " + previous.name + " and locked it. Use /stadium edit "
                + previous.name + " to carry on with it.", NamedTextColor.YELLOW);
        List<String> missing = missingParts(previous);
        if (!missing.isEmpty()) {
            message = message.append(Component.text(" It is still missing: " + String.join(", ", missing) + ".",
                    NamedTextColor.RED));
        }
        player.sendMessage(message);
    }

    private List<String> missingParts(Stadium stadium) {
        List<String> missing = new ArrayList<>();
        if (!stadium.hasCenter()) missing.add("centre point");
        for (Team team : Team.values()) {
            if (!stadium.goalComplete(team)) missing.add(team.label + " goal (all 4 corners)");
        }
        return missing;
    }

    /** The stadium named in args[1], or else the one this player last worked on. */
    private Stadium namedOrLast(Player player, String[] args, String sub) {
        Stadium stadium = null;
        if (args.length >= 2) {
            stadium = get(args[1]);
            if (stadium == null) {
                error(player, "No stadium called " + args[1] + ".");
                return null;
            }
        } else {
            String last = lastEdited.get(player.getUniqueId());
            if (last != null) stadium = stadiums.get(last);
            if (stadium == null) {
                error(player, "Usage: /stadium " + sub + " <name>");
                return null;
            }
        }
        return stadium;
    }

    /** /stadium confirm [name]: locks the stadium and hides its goal particles. */
    private void confirm(Player player, String[] args) {
        Stadium stadium = namedOrLast(player, args, "confirm");
        if (stadium == null) return;
        if (stadium.confirmed) {
            error(player, stadium.name + " is already confirmed.");
            return;
        }

        List<String> missing = missingParts(stadium);
        if (!missing.isEmpty()) {
            error(player, stadium.name + " isn't finished yet. Still missing: " + String.join(", ", missing) + ".");
            return;
        }

        stadium.confirmed = true;
        save();
        player.sendMessage(Component.text("Confirmed " + stadium.name + ". The particles are gone and it is locked. Use /stadium edit "
                + stadium.name + " to change it again.", NamedTextColor.GREEN));
    }

    /** /stadium edit [name]: unlocks the stadium and brings the goal particles back. */
    private void edit(Player player, String[] args) {
        Stadium stadium = namedOrLast(player, args, "edit");
        if (stadium == null) return;
        if (!stadium.confirmed) {
            error(player, stadium.name + " is already being edited.");
            return;
        }
        startEditing(player, stadium); // locks whatever this player was editing before
        stadium.confirmed = false;
        save();
        player.sendMessage(Component.text("Editing " + stadium.name + ". The goal particles are back; use /stadium confirm when you are done.",
                NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ particles

    private void showGoalParticles() {
        List<Stadium> editing = stadiums.values().stream().filter(s -> !s.confirmed).toList();
        if (editing.isEmpty()) return;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!canBuild(viewer)) continue;
            for (Stadium stadium : editing) {
                for (Team team : Team.values()) drawGoal(viewer, stadium, team);
                drawCenter(viewer, stadium);
            }
        }
    }

    /** Outlines the goal box (floor, roof and the four upright edges) in the team's colour, for corners set so far. */
    private void drawGoal(Player viewer, Stadium stadium, Team team) {
        Location[] corners = stadium.goals.get(team);
        World world = viewer.getWorld();

        boolean[] set = new boolean[Stadium.GOAL_CORNERS];
        boolean near = false;
        int baseY = Integer.MAX_VALUE;
        for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
            Location c = corners[i];
            if (c == null || c.getWorld() == null || !c.getWorld().equals(world)) continue;
            set[i] = true;
            baseY = Math.min(baseY, c.getBlockY());
            if (c.distanceSquared(viewer.getLocation()) <= PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE) near = true;
        }
        if (!near) return;

        double bottom = baseY;
        double top = baseY + stadium.goalHeight(team);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(team.color.value()), 1.2f);

        for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
            if (!set[i]) continue;
            double x = corners[i].getBlockX() + 0.5;
            double z = corners[i].getBlockZ() + 0.5;
            drawLine(viewer, dust, x, bottom, z, x, top, z); // upright edge

            int next = (i + 1) % Stadium.GOAL_CORNERS;
            if (!set[next]) continue;
            double nx = corners[next].getBlockX() + 0.5;
            double nz = corners[next].getBlockZ() + 0.5;
            drawLine(viewer, dust, x, bottom, z, nx, bottom, nz); // floor edge
            drawLine(viewer, dust, x, top, z, nx, top, nz);       // roof edge
        }
    }

    /** Marks the centre point (where the ball is placed) with a yellow ring on the ground and a beam above it. */
    private void drawCenter(Player viewer, Stadium stadium) {
        if (!stadium.hasCenter() || !stadium.center.getWorld().equals(viewer.getWorld())) return;
        Location c = stadium.center;
        if (c.distanceSquared(viewer.getLocation()) > PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE) return;

        Particle.DustOptions dust = new Particle.DustOptions(Color.YELLOW, 1.2f);
        double y = c.getY() + 0.1;
        int points = 16;
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            viewer.spawnParticle(Particle.DUST, c.getX() + Math.cos(angle), y, c.getZ() + Math.sin(angle),
                    1, 0, 0, 0, 0, dust);
        }
        drawLine(viewer, dust, c.getX(), c.getY(), c.getZ(), c.getX(), c.getY() + 3, c.getZ());
    }

    private void drawLine(Player viewer, Particle.DustOptions dust,
                          double x1, double y1, double z1, double x2, double y2, double z2) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = (int) Math.max(1, Math.min(MAX_POINTS_PER_EDGE, Math.ceil(length / PARTICLE_SPACING)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            viewer.spawnParticle(Particle.DUST, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t,
                    1, 0, 0, 0, 0, dust);
        }
    }

    private Team parseTeam(String text) {
        if (text.equalsIgnoreCase("red")) return Team.RED;
        if (text.equalsIgnoreCase("blue")) return Team.BLUE;
        return null;
    }

    private Stadium requireStadium(Player player, String[] args, String sub) {
        if (args.length < 2) {
            error(player, "Usage: /stadium " + sub + " <name>");
            return null;
        }
        Stadium stadium = get(args[1]);
        if (stadium == null) {
            error(player, "No stadium called " + args[1] + ".");
        }
        return stadium;
    }

    private Component statusLine(String label, String value) {
        Component line = Component.text(" - " + label + ": ", NamedTextColor.GRAY);
        return value == null
                ? line.append(Component.text("not set", NamedTextColor.RED))
                : line.append(Component.text(value, NamedTextColor.GREEN));
    }

    private String describe(Location l) {
        return String.format("%.1f, %.1f, %.1f in %s", l.getX(), l.getY(), l.getZ(), l.getWorld().getName());
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ saving

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Stadium stadium : stadiums.values()) {
            String path = "stadiums." + stadium.name.toLowerCase();
            yaml.set(path + ".name", stadium.name);
            yaml.set(path + ".confirmed", stadium.confirmed);
            if (stadium.block != null) yaml.set(path + ".block", stadium.block.name());
            writeLocation(yaml, path + ".center", stadium.center);
            for (Team team : Team.values()) {
                Location[] corners = stadium.goals.get(team);
                String goalPath = path + ".goals." + team.name().toLowerCase();
                for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
                    writeLocation(yaml, goalPath + ".pos" + (i + 1), corners[i]);
                }
                yaml.set(goalPath + ".height", stadium.goalHeight(team));
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save stadiums.yml", e);
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("stadiums");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "stadiums." + key;
            Stadium stadium = new Stadium(yaml.getString(path + ".name", key));
            // Stadiums saved before confirming existed count as finished, so they don't suddenly show particles.
            stadium.confirmed = yaml.getBoolean(path + ".confirmed", true);
            String blockName = yaml.getString(path + ".block");
            if (blockName != null) {
                Material block = Material.matchMaterial(blockName);
                if (block != null && block.isBlock() && block.isItem()) stadium.block = block;
            }
            stadium.center = readLocation(yaml, path + ".center");
            for (Team team : Team.values()) {
                String goalPath = path + ".goals." + team.name().toLowerCase();
                Location[] corners = stadium.goals.get(team);
                for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
                    corners[i] = readLocation(yaml, goalPath + ".pos" + (i + 1));
                }
                stadium.goalHeights.put(team, yaml.getDouble(goalPath + ".height", Stadium.DEFAULT_GOAL_HEIGHT));
            }
            stadiums.put(key.toLowerCase(), stadium);
        }
        plugin.getLogger().info("Loaded " + stadiums.size() + " stadium(s).");
    }

    private void writeLocation(YamlConfiguration yaml, String path, Location l) {
        if (l == null || l.getWorld() == null) return;
        yaml.set(path + ".world", l.getWorld().getName());
        yaml.set(path + ".x", l.getX());
        yaml.set(path + ".y", l.getY());
        yaml.set(path + ".z", l.getZ());
        yaml.set(path + ".yaw", l.getYaw());
        yaml.set(path + ".pitch", l.getPitch());
    }

    private Location readLocation(YamlConfiguration yaml, String path) {
        String worldName = yaml.getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World '" + worldName + "' isn't loaded; skipping " + path);
            return null;
        }
        return new Location(world,
                yaml.getDouble(path + ".x"), yaml.getDouble(path + ".y"), yaml.getDouble(path + ".z"),
                (float) yaml.getDouble(path + ".yaw"), (float) yaml.getDouble(path + ".pitch"));
    }
}
