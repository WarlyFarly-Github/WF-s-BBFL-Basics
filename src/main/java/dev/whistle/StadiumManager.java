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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
final class StadiumManager implements CommandExecutor, TabCompleter, Listener {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> SUBCOMMANDS =
            List.of("create", "delete", "list", "info", "setcentre", "setstands", "setgoal", "goalheight", "block", "confirm", "edit", "invite", "uninvite", "claim", "bounds", "setpos1", "setpos2", "setpos3", "setpos4", "setpos5");

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Stadium> stadiums = new LinkedHashMap<>();

    /** The stadium each builder last worked on, so /stadium confirm and /stadium edit work without a name. */
    private final Map<UUID, String> lastEdited = new HashMap<>();

    private static final List<String> BOUNDS_ACTIONS = List.of("addpoint", "removepoint", "setpoint");

    private static final double PARTICLE_VIEW_DISTANCE = 40.0;
    private static final double PARTICLE_SPACING = 0.5;
    private static final int MAX_POINTS_PER_EDGE = 300;

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
            error(player, "Usage: /stadium <create|delete|list|info|setcentre|setgoal|goalheight|block|confirm|edit|invite|uninvite|claim|bounds|setpos1|setpos2|setpos3|setpos4|setpos5>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player, args);
            case "delete" -> delete(player, args);
            case "list" -> list(player);
            case "info" -> info(player, args);
            case "setcentre" -> setCenter(player, args);
            case "setstands" -> setStands(player, args);
            case "setgoal" -> setGoal(player, args);
            case "goalheight" -> setGoalHeight(player, args);
            case "block" -> setBlock(player, args);
            case "confirm" -> confirm(player, args);
            case "edit" -> edit(player, args);
            case "invite" -> invite(player, args);
            case "uninvite" -> uninvite(player, args);
            case "claim" -> claim(player, args);
            case "bounds" -> bounds(player, args);
            case "setpos1" -> setPosition(player, args, 0);
            case "setpos2" -> setPosition(player, args, 1);
            case "setpos3" -> setPosition(player, args, 2);
            case "setpos4" -> setPosition(player, args, 3);
            case "setpos5" -> setPosition(player, args, 4);
            default -> error(player, "Unknown subcommand. Use /stadium <create|delete|list|info|setcentre|setgoal|goalheight|block|confirm|edit|invite|uninvite|claim|bounds|setpos1|setpos2|setpos3|setpos4|setpos5>");
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
        if (sub.matches("setpos[1-5]")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(names());
                options.addAll(List.of("red", "blue"));
                return filter(options, args[1]);
            }
            if (args.length == 3 && get(args[1]) != null) return filter(List.of("red", "blue"), args[2]);
            return List.of();
        }
        if (sub.equals("bounds")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(names());
                options.addAll(BOUNDS_ACTIONS);
                return filter(options, args[1]);
            }
            Stadium named = get(args[1]);
            if (args.length == 3 && named != null) return filter(BOUNDS_ACTIONS, args[2]);
            // point numbers for removepoint / setpoint
            Stadium target = named;
            String action = named != null ? args[2].toLowerCase() : args[1].toLowerCase();
            boolean numberSlot = named != null ? args.length == 4 : args.length == 3;
            if (target == null) {
                String last = lastEdited.get(player.getUniqueId());
                target = last == null ? null : stadiums.get(last);
            }
            if (numberSlot && target != null && (action.equals("removepoint") || action.equals("setpoint"))) {
                List<String> numbers = new ArrayList<>();
                for (int i = 1; i <= target.bounds.size(); i++) numbers.add(String.valueOf(i));
                return filter(numbers, args[args.length - 1]);
            }
            return List.of();
        }
        if (args.length == 2 && sub.equals("claim")) {
            return filter(stadiums.values().stream().filter(s -> s.owner == null).map(s -> s.name).toList(), args[1]);
        }
        if (args.length == 2 && List.of("delete", "info", "setcentre", "setstands", "setgoal", "goalheight", "block", "confirm", "edit",
                "invite", "uninvite").contains(sub)) {
            return filter(names(), args[1]);
        }
        if (args.length == 3 && sub.equals("invite")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 3 && sub.equals("uninvite")) {
            Stadium stadium = get(args[1]);
            return stadium == null ? List.of() : filter(new ArrayList<>(stadium.editors.values()), args[2]);
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
        created.owner = player.getUniqueId();
        stadiums.put(name.toLowerCase(), created);
        startEditing(player, created);
        save();
        player.sendMessage(Component.text("Created stadium " + name + ". Next: ", NamedTextColor.GREEN)
                .append(Component.text("/stadium setcentre " + name + "  and  /stadium setgoal " + name
                        + " <red|blue> <pos1|pos2|pos3|pos4>", NamedTextColor.YELLOW))
                .append(Component.text(", plus /stadium bounds and /stadium setpos1 to setpos5 <red|blue>. Finish with /stadium confirm.",
                        NamedTextColor.GREEN)));
    }

    private void delete(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "delete");
        if (stadium == null || !requireOwner(player, stadium)) return;
        stadiums.remove(stadium.name.toLowerCase());
        save();
        player.sendMessage(Component.text("Deleted stadium " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    private void list(Player player) {
        if (stadiums.isEmpty()) {
            player.sendMessage(Component.text("There are no stadiums yet. Use /stadium create <name>.", NamedTextColor.YELLOW));
            return;
        }
        List<String> labels = stadiums.values().stream()
                .map(s -> s.owner == null ? s.name + " (unclaimed)" : s.name).toList();
        player.sendMessage(Component.text("Stadiums: ", NamedTextColor.GOLD)
                .append(Component.text(String.join(", ", labels), NamedTextColor.WHITE)));
    }

    private void info(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "info");
        if (stadium == null) return;

        player.sendMessage(Component.text("Stadium " + stadium.name, NamedTextColor.GOLD));
        player.sendMessage(statusLine("Owner", stadium.owner == null ? "unclaimed (use /stadium claim " + stadium.name + ")" : ownerName(stadium)));
        if (!stadium.editors.isEmpty()) {
            player.sendMessage(statusLine("Invited editors", String.join(", ", stadium.editors.values())));
        }
        player.sendMessage(statusLine("Status", stadium.confirmed ? "confirmed (locked)" : "editing (particles shown)"));
        player.sendMessage(statusLine("Menu block", stadium.block == null ? "player head (default)" : stadium.block.name()));
        int points = stadium.bounds.size();
        player.sendMessage(statusLine("Bounds", points == 0 ? null
                : points + " point" + (points == 1 ? "" : "s") + (points < 3 ? " (needs at least 3 to make a shape)" : "")));
        List<String> notReady = stadium.missingParts();
        player.sendMessage(statusLine("Ready for matches", notReady.isEmpty() ? "yes" : null));
        for (Team team : Team.values()) {
            int set = stadium.positionsSet(team);
            player.sendMessage(statusLine(team.label + " player positions",
                    set == 0 ? null : set + "/" + Stadium.POSITIONS_PER_TEAM + (set < Stadium.POSITIONS_PER_TEAM ? " (needs all " + Stadium.POSITIONS_PER_TEAM + ")" : " set")));
        }
        player.sendMessage(statusLine("Centre point", stadium.hasCenter() ? describe(stadium.center) : null));
        player.sendMessage(statusLine("Stands point", stadium.hasStands() ? describe(stadium.stands) : null));
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

    /** /stadium setstands <name>: where spectators who /match watch from the stands are put. */
    private void setStands(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "setstands");
        if (stadium == null || !requireEditable(player, stadium)) return;

        stadium.stands = player.getLocation().clone();
        save();
        player.sendMessage(Component.text("Stands point of " + stadium.name + " set to " + describe(stadium.stands)
                + ". Spectators who watch from the stands are placed here.", NamedTextColor.GREEN));
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
        if (!requireAccess(player, stadium)) return false;
        if (stadium.confirmed) {
            error(player, stadium.name + " is confirmed and locked. Use /stadium edit " + stadium.name + " to change it.");
            return false;
        }
        startEditing(player, stadium);
        return true;
    }

    /** /stadium bounds [stadium] <addpoint|removepoint|setpoint> [number] edits the stadium bounds polygon. */
    private void bounds(Player player, String[] args) {
        String usage = "Usage: /stadium bounds [stadium] <addpoint | removepoint [number] | setpoint <number>>";

        Stadium stadium = null;
        int next = 1;
        if (args.length >= 2 && get(args[1]) != null) {
            stadium = get(args[1]);
            next = 2;
        } else if (args.length >= 2 && !BOUNDS_ACTIONS.contains(args[1].toLowerCase())) {
            error(player, "No stadium called " + args[1] + ".");
            return;
        } else {
            String last = lastEdited.get(player.getUniqueId());
            if (last != null) stadium = stadiums.get(last);
        }
        if (stadium == null || args.length <= next) {
            error(player, stadium == null ? "Name a stadium first: /stadium bounds <stadium> <addpoint|removepoint|setpoint>" : usage);
            return;
        }
        if (!requireEditable(player, stadium)) return;

        String action = args[next].toLowerCase();
        List<Location> points = stadium.bounds;
        Location here = player.getLocation().getBlock().getLocation();

        switch (action) {
            case "addpoint" -> {
                if (points.size() >= Stadium.MAX_BOUNDS_POINTS) {
                    error(player, "A stadium can have at most " + Stadium.MAX_BOUNDS_POINTS + " bounds points.");
                    return;
                }
                if (!points.isEmpty() && !sameWorld(points.get(0), here)) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.add(here);
                save();
                player.sendMessage(Component.text("Added bounds point " + points.size() + " to " + stadium.name + " at "
                        + describeBlock(here) + boundsHint(points.size()), NamedTextColor.GREEN));
            }
            case "removepoint" -> {
                if (points.isEmpty()) {
                    error(player, stadium.name + " has no bounds points to remove.");
                    return;
                }
                int index = points.size() - 1; // the last point unless a number is given
                if (args.length > next + 1) {
                    index = parsePoint(player, args[next + 1], points.size());
                    if (index < 0) return;
                }
                points.remove(index);
                save();
                player.sendMessage(Component.text("Removed bounds point " + (index + 1) + " from " + stadium.name
                        + ". " + points.size() + " left" + (points.isEmpty() ? "" : " (the points after it moved down one number)")
                        + ".", NamedTextColor.YELLOW));
            }
            case "setpoint" -> {
                if (args.length <= next + 1) {
                    error(player, "Usage: /stadium bounds [stadium] setpoint <number>  (stand where the point should go)");
                    return;
                }
                int index = parsePoint(player, args[next + 1], points.size());
                if (index < 0) return;
                if (points.size() > 1 && !sameWorld(points.get(index == 0 ? 1 : 0), here)) {
                    error(player, "Bounds points must all be in the same world.");
                    return;
                }
                points.set(index, here);
                save();
                player.sendMessage(Component.text("Moved bounds point " + (index + 1) + " of " + stadium.name + " to "
                        + describeBlock(here) + ".", NamedTextColor.GREEN));
            }
            default -> error(player, usage);
        }
    }

    /** /stadium setpos1 to setpos5 [stadium] <red|blue>: where one of the team's players stands during freezes. */
    private void setPosition(Player player, String[] args, int index) {
        String sub = "setpos" + (index + 1);
        String usage = "Usage: /stadium " + sub + " [stadium] <red|blue>  (stand where the player should be placed)";

        Stadium stadium = null;
        int next = 1;
        if (args.length >= 2 && get(args[1]) != null) {
            stadium = get(args[1]);
            next = 2;
        } else if (args.length >= 2 && parseTeam(args[1]) == null) {
            error(player, "No stadium called " + args[1] + ".");
            return;
        } else {
            String last = lastEdited.get(player.getUniqueId());
            if (last != null) stadium = stadiums.get(last);
        }
        if (stadium == null || args.length <= next) {
            error(player, stadium == null ? "Name a stadium first: /stadium " + sub + " <stadium> <red|blue>" : usage);
            return;
        }
        Team team = parseTeam(args[next]);
        if (team == null) {
            error(player, usage);
            return;
        }
        if (!requireEditable(player, stadium)) return;

        stadium.positions.get(team)[index] = player.getLocation().clone();
        save();
        player.sendMessage(Component.text("Set player position " + (index + 1) + " for the " + team.label + " team in "
                + stadium.name + " (" + stadium.positionsSet(team) + "/" + Stadium.POSITIONS_PER_TEAM + ").", NamedTextColor.GREEN));
    }

    /** Converts "3" into index 2, or explains the problem and returns -1. */
    private int parsePoint(Player player, String text, int size) {
        int number;
        try {
            number = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            error(player, "The point must be a number, like 1.");
            return -1;
        }
        if (number < 1 || number > size) {
            error(player, size == 0 ? "There are no bounds points yet." : "The point must be between 1 and " + size + ".");
            return -1;
        }
        return number - 1;
    }

    private boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    private String describeBlock(Location l) {
        return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private String boundsHint(int points) {
        return points < 3 ? " (add at least " + (3 - points) + " more to make a shape)." : ".";
    }

    private boolean isAdmin(Player player) {
        return player.hasPermission("wfsbbfl.stadium.admin");
    }

    private boolean canEditStadium(Player player, Stadium stadium) {
        return isAdmin(player) || player.getUniqueId().equals(stadium.owner) || stadium.editors.containsKey(player.getUniqueId());
    }

    private String ownerName(Stadium stadium) {
        if (stadium.owner == null) return "nobody";
        String name = Bukkit.getOfflinePlayer(stadium.owner).getName();
        return name != null ? name : stadium.owner.toString();
    }

    /** The owner, an invited editor or an admin may change a stadium. Unclaimed ones must be claimed first. */
    private boolean requireAccess(Player player, Stadium stadium) {
        if (stadium.owner == null && !isAdmin(player)) {
            error(player, stadium.name + " has no owner yet. Use /stadium claim " + stadium.name + " to take it.");
            return false;
        }
        if (!canEditStadium(player, stadium)) {
            error(player, stadium.name + " belongs to " + ownerName(stadium) + ". Ask them to /stadium invite you.");
            return false;
        }
        return true;
    }

    /** Only the owner (or an admin) may delete a stadium or manage who can edit it. */
    private boolean requireOwner(Player player, Stadium stadium) {
        if (stadium.owner == null && !isAdmin(player)) {
            error(player, stadium.name + " has no owner yet. Use /stadium claim " + stadium.name + " to take it.");
            return false;
        }
        if (!isAdmin(player) && !player.getUniqueId().equals(stadium.owner)) {
            error(player, "Only the owner of " + stadium.name + " (" + ownerName(stadium) + ") can do that.");
            return false;
        }
        return true;
    }

    /** /stadium invite <stadium> <player>: lets another builder edit the stadium too. */
    private void invite(Player player, String[] args) {
        if (args.length < 3) {
            error(player, "Usage: /stadium invite <stadium> <player>");
            return;
        }
        Stadium stadium = requireStadium(player, args, "invite");
        if (stadium == null || !requireOwner(player, stadium)) return;

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            error(player, args[2] + " needs to be online to be invited.");
            return;
        }
        if (target.getUniqueId().equals(stadium.owner)) {
            error(player, target.getName() + " already owns " + stadium.name + ".");
            return;
        }
        if (!canBuild(target)) {
            error(player, target.getName() + " needs the builder role before they can use /stadium.");
            return;
        }
        if (stadium.editors.containsKey(target.getUniqueId())) {
            error(player, target.getName() + " is already invited to " + stadium.name + ".");
            return;
        }

        stadium.editors.put(target.getUniqueId(), target.getName());
        save();
        player.sendMessage(Component.text("Invited " + target.getName() + " to edit " + stadium.name + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text(player.getName() + " invited you to edit the stadium " + stadium.name
                + ". Use /stadium edit " + stadium.name + " to start.", NamedTextColor.GREEN));
    }

    /** /stadium uninvite <stadium> <player>: takes someone's editing access away again. */
    private void uninvite(Player player, String[] args) {
        if (args.length < 3) {
            error(player, "Usage: /stadium uninvite <stadium> <player>");
            return;
        }
        Stadium stadium = requireStadium(player, args, "uninvite");
        if (stadium == null || !requireOwner(player, stadium)) return;

        UUID found = null;
        for (Map.Entry<UUID, String> entry : stadium.editors.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(args[2])) found = entry.getKey();
        }
        if (found == null) {
            error(player, args[2] + " isn't invited to " + stadium.name + ".");
            return;
        }
        String name = stadium.editors.remove(found);
        save();
        player.sendMessage(Component.text(name + " can no longer edit " + stadium.name + ".", NamedTextColor.YELLOW));
    }

    /** /stadium claim <stadium>: takes ownership of a stadium that was made before owners existed. */
    private void claim(Player player, String[] args) {
        Stadium stadium = requireStadium(player, args, "claim");
        if (stadium == null) return;
        if (stadium.owner != null) {
            error(player, stadium.name + " is already owned by " + ownerName(stadium) + ".");
            return;
        }
        stadium.owner = player.getUniqueId();
        save();
        player.sendMessage(Component.text("You now own " + stadium.name + ". Use /stadium edit " + stadium.name
                + " to change it, and /stadium invite to let others help.", NamedTextColor.GREEN));
        List<String> missing = stadium.missingParts();
        if (!missing.isEmpty()) {
            player.sendMessage(Component.text("It still needs: " + String.join(", ", missing)
                    + " before it can be used in matches.", NamedTextColor.YELLOW));
        }
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
        return stadium.missingParts();
    }

    /** Owners are reminded on every join until each of their stadiums has everything it needs. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> remindOwner(player), 60L); // after the join spam
    }

    private void remindOwner(Player player) {
        if (!player.isOnline()) return;
        for (Stadium stadium : stadiums.values()) {
            if (!player.getUniqueId().equals(stadium.owner)) continue;
            List<String> missing = stadium.missingParts();
            if (missing.isEmpty()) continue;
            player.sendMessage(Component.text("Your stadium ", NamedTextColor.YELLOW)
                    .append(Component.text(stadium.name, NamedTextColor.GOLD))
                    .append(Component.text(" can't be used in matches yet. Still needed: ", NamedTextColor.YELLOW))
                    .append(Component.text(String.join(", ", missing), NamedTextColor.RED))
                    .append(Component.text(". Use /stadium edit " + stadium.name + " to finish it.", NamedTextColor.YELLOW)));
        }
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
        if (stadium == null || !requireAccess(player, stadium)) return;
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
        if (stadium == null || !requireAccess(player, stadium)) return;
        if (!stadium.confirmed && stadium.name.toLowerCase().equals(lastEdited.get(player.getUniqueId()))) {
            error(player, "You are already editing " + stadium.name + ".");
            return;
        }
        startEditing(player, stadium); // locks whatever this player was editing before
        stadium.confirmed = false;
        save();
        player.sendMessage(Component.text("Editing " + stadium.name + ". The goal particles are back; use /stadium confirm when you are done.",
                NamedTextColor.GREEN));
    }

    // ------------------------------------------------------------------ particles

    /** Each builder only sees the particles of the stadium they are editing themselves. */
    private void showGoalParticles() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            String key = lastEdited.get(viewer.getUniqueId());
            if (key == null) continue;
            Stadium stadium = stadiums.get(key);
            if (stadium == null || stadium.confirmed) continue;
            if (!canBuild(viewer) || !canEditStadium(viewer, stadium)) continue;

            for (Team team : Team.values()) drawGoal(viewer, stadium, team);
            drawCenter(viewer, stadium);
            drawBounds(viewer, stadium);
            drawPositions(viewer, stadium);
            drawStands(viewer, stadium);
        }
    }

    /** Marks the stands point (where stand spectators are placed) with a green ring and a beam above it. */
    private void drawStands(Player viewer, Stadium stadium) {
        if (!stadium.hasStands() || !sameWorld(stadium.stands, viewer.getLocation())) return;
        Location s = stadium.stands;
        if (s.distanceSquared(viewer.getLocation()) > PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE) return;

        Particle.DustOptions dust = new Particle.DustOptions(Color.LIME, 1.2f);
        int points = 12;
        for (int i = 0; i < points; i++) {
            double angle = 2 * Math.PI * i / points;
            viewer.spawnParticle(Particle.DUST, s.getX() + Math.cos(angle) * 0.7, s.getY() + 0.1,
                    s.getZ() + Math.sin(angle) * 0.7, 1, 0, 0, 0, 0, dust);
        }
        drawLine(viewer, dust, s.getX(), s.getY(), s.getZ(), s.getX(), s.getY() + 3, s.getZ());
    }

    /** Marks each player position with a ring and a short beam in the team's colour. */
    private void drawPositions(Player viewer, Stadium stadium) {
        for (Team team : Team.values()) {
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(team.color.value()), 1.0f);
            for (Location spot : stadium.positions.get(team)) {
                if (spot == null || !sameWorld(spot, viewer.getLocation())) continue;
                if (spot.distanceSquared(viewer.getLocation()) > PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE) continue;

                int points = 10;
                for (int i = 0; i < points; i++) {
                    double angle = 2 * Math.PI * i / points;
                    viewer.spawnParticle(Particle.DUST, spot.getX() + Math.cos(angle) * 0.5, spot.getY() + 0.1,
                            spot.getZ() + Math.sin(angle) * 0.5, 1, 0, 0, 0, 0, dust);
                }
                drawLine(viewer, dust, spot.getX(), spot.getY(), spot.getZ(), spot.getX(), spot.getY() + 1.8, spot.getZ());
            }
        }
    }

    /** Outlines the bounds polygon in white: an upright marker at each point and lines between them in order. */
    private void drawBounds(Player viewer, Stadium stadium) {
        List<Location> points = stadium.bounds;
        if (points.isEmpty() || !sameWorld(points.get(0), viewer.getLocation())) return;

        Particle.DustOptions dust = new Particle.DustOptions(Color.WHITE, 1.2f);
        int count = points.size();
        for (int i = 0; i < count; i++) {
            Location a = points.get(i);
            double ax = a.getBlockX() + 0.5;
            double ay = a.getBlockY() + 0.1;
            double az = a.getBlockZ() + 0.5;
            drawLine(viewer, dust, 1.0, ax, ay, az, ax, ay + 2, az); // marker so single points show up

            // Lines run 1 -> 2 -> 3 ...; the shape only closes (last -> first) once there are 3 or more points.
            boolean hasNext = i + 1 < count || count >= 3;
            if (!hasNext) continue;
            Location b = points.get((i + 1) % count);
            drawLine(viewer, dust, 1.0, ax, ay, az, b.getBlockX() + 0.5, b.getBlockY() + 0.1, b.getBlockZ() + 0.5);
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
        drawLine(viewer, dust, PARTICLE_SPACING, x1, y1, z1, x2, y2, z2);
    }

    /** Dust points along a line, skipping the ones too far from the viewer to be seen anyway. */
    private void drawLine(Player viewer, Particle.DustOptions dust, double spacing,
                          double x1, double y1, double z1, double x2, double y2, double z2) {
        double length = Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1) + (z2 - z1) * (z2 - z1));
        int steps = (int) Math.max(1, Math.min(MAX_POINTS_PER_EDGE, Math.ceil(length / spacing)));
        Location eye = viewer.getLocation();
        double limit = PARTICLE_VIEW_DISTANCE * PARTICLE_VIEW_DISTANCE;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + (x2 - x1) * t;
            double y = y1 + (y2 - y1) * t;
            double z = z1 + (z2 - z1) * t;
            double dx = x - eye.getX();
            double dy = y - eye.getY();
            double dz = z - eye.getZ();
            if (dx * dx + dy * dy + dz * dz > limit) continue;
            viewer.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust);
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
            if (stadium.owner != null) yaml.set(path + ".owner", stadium.owner.toString());
            for (Map.Entry<UUID, String> editor : stadium.editors.entrySet()) {
                yaml.set(path + ".editors." + editor.getKey(), editor.getValue());
            }
            if (stadium.block != null) yaml.set(path + ".block", stadium.block.name());
            writeLocation(yaml, path + ".center", stadium.center);
            writeLocation(yaml, path + ".stands", stadium.stands);
            for (int i = 0; i < stadium.bounds.size(); i++) {
                writeLocation(yaml, path + ".bounds." + (i + 1), stadium.bounds.get(i));
            }
            for (Team team : Team.values()) {
                Location[] spots = stadium.positions.get(team);
                for (int i = 0; i < Stadium.POSITIONS_PER_TEAM; i++) {
                    writeLocation(yaml, path + ".positions." + team.name().toLowerCase() + "." + (i + 1), spots[i]);
                }
            }
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
            String ownerText = yaml.getString(path + ".owner");
            if (ownerText != null) {
                try {
                    stadium.owner = UUID.fromString(ownerText);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Bad owner id for stadium " + key + "; it can be claimed again.");
                }
            }
            ConfigurationSection editorSection = yaml.getConfigurationSection(path + ".editors");
            if (editorSection != null) {
                for (String id : editorSection.getKeys(false)) {
                    try {
                        stadium.editors.put(UUID.fromString(id), editorSection.getString(id, id));
                    } catch (IllegalArgumentException ignored) {
                        // skip a corrupt entry
                    }
                }
            }
            String blockName = yaml.getString(path + ".block");
            if (blockName != null) {
                Material block = Material.matchMaterial(blockName);
                if (block != null && block.isBlock() && block.isItem()) stadium.block = block;
            }
            stadium.center = readLocation(yaml, path + ".center");
            stadium.stands = readLocation(yaml, path + ".stands");
            for (Team team : Team.values()) {
                Location[] spots = stadium.positions.get(team);
                for (int i = 0; i < Stadium.POSITIONS_PER_TEAM; i++) {
                    spots[i] = readLocation(yaml, path + ".positions." + team.name().toLowerCase() + "." + (i + 1));
                }
            }
            for (int i = 1; i <= Stadium.MAX_BOUNDS_POINTS; i++) {
                Location point = readLocation(yaml, path + ".bounds." + i);
                if (point == null) break;
                stadium.bounds.add(point);
            }
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
