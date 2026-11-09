package dev.whistle;

import dev.whistle.Match.Team;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * The Builder's Guide: an interactive tutorial, in its own superflat world, that walks a player through setting up a
 * stadium with the real /stadium commands. Nothing is saved: while a player is in the guide, /stadium is answered by a
 * private, in-memory practice stadium instead of the real StadiumManager (which only hands the command over here).
 *
 * The guide is told with chat messages, the action bar, particles on the blocks to use and, when DecentHolograms is
 * installed, holograms that only that player can see. Finishing it gives the player the LuckPerms builder role.
 */
final class BuildersGuide implements CommandExecutor, TabCompleter, Listener {

    private static final int STEPS = 8;
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    /** How far in front of the player (blocks) and how far above their eyes the instruction board floats. */
    private static final double BOARD_DISTANCE = 3.5;
    private static final double BOARD_HEIGHT = 0.9;

    /** Superflat, plains, no features. Structures are switched off separately as well. */
    private static final String FLAT_SETTINGS = "{\"biome\":\"minecraft:plains\",\"features\":false,\"lakes\":false,"
            + "\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"minecraft:dirt\",\"height\":2},"
            + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"structure_overrides\":[]}";

    // The practice pitch. Each pair is {x, z}; the marker blocks sit flush in the grass. Red defends the west end.
    private static final int SPAWN_X = 0;
    private static final int SPAWN_Z = -15;
    private static final int[][] BOUNDS = {{-20, -10}, {20, -10}, {20, 10}, {-20, 10}};
    private static final int[][] RED_SPOTS = {{-12, -6}, {-12, 6}, {-8, -3}, {-8, 3}, {-5, 0}};
    private static final int[][] BLUE_SPOTS = {{12, -6}, {12, 6}, {8, -3}, {8, 3}, {5, 0}};
    private static final int[][] CENTRE = {{0, 0}};
    private static final int[][] RED_GOAL = {{-20, -3}, {-20, 3}, {-18, 3}, {-18, -3}};
    private static final int[][] BLUE_GOAL = {{20, -3}, {20, 3}, {18, 3}, {18, -3}};

    /** Commands that would put the player in a game (and so move them out of the guide) are refused meanwhile. */
    private static final Set<String> BLOCKED = Set.of("match", "quickmatch", "votematch", "lobby", "training", "challenge");

    private record Step(String command, String suggest, String chat, List<String> board) { }

    private static final List<Step> STEP_LIST = List.of(
            new Step("/next", "/next",
                    "First you need to create a world to build your stadium. You can do so with /bw create_world, "
                            + "however that is not needed for this tutorial. Please do /next to continue.",
                    List.of("&fFirst you need to create a world", "&fto build your stadium. You can do",
                            "&fso with &e/bw create_world&f, but that", "&fis not needed for this tutorial.",
                            "&7Please do &e/next &7to continue.")),
            new Step("/stadium create <name>", "/stadium create ",
                    "Next, you need to create a stadium. Please do the command /stadium create <name> to continue "
                            + "(any name will do). There are other stadium types, but they work very similarly to this.",
                    List.of("&fNext, you need to create a stadium.", "&fPlease do &e/stadium create <name>",
                            "&7There are other stadium types, but", "&7they work very similarly to this.")),
            new Step("/stadium bounds addpoint", "/stadium bounds addpoint",
                    "Next, create the bounds of your stadium. There are red concrete blocks in the ground. Stand on "
                            + "each one and run /stadium bounds addpoint. Do that at all the red concrete to continue.",
                    List.of("&fNow the bounds of your stadium.", "&fStand on each &cred concrete &fblock",
                            "&fand run &e/stadium bounds addpoint", "&7Do it at all the red concrete.")),
            new Step("/stadium setpos<1-5> <red|blue>", "/stadium setpos1 ",
                    "Next, set the positions for the players using /stadium setpos<1-5> <red|blue>. There are red and "
                            + "cyan concrete powder blocks in the ground where these need to be: red powder for the red "
                            + "team, cyan powder for the blue team. Do that on every one to continue.",
                    List.of("&fSet the player positions with", "&e/stadium setpos<1-5> <red|blue>",
                            "&fStand on the &cred &fand &bcyan &fconcrete", "&fpowder: red for red, cyan for blue.")),
            new Step("/stadium setcentre", "/stadium setcentre",
                    "Set the position for the ball using /stadium setcentre. Stand on the yellow concrete powder to do this.",
                    List.of("&fSet the position of the ball.", "&fStand on the &eyellow concrete powder",
                            "&fand run &e/stadium setcentre")),
            new Step("/stadium setgoal <red|blue> <pos1-4>", "/stadium setgoal ",
                    "Set the goal positions using /stadium setgoal <red|blue> <pos1-pos4>. The corners of each goal are "
                            + "marked with red and blue wool, each with its number. Again, it autocompletes.",
                    List.of("&fSet the goal corners with", "&e/stadium setgoal <red|blue> <pos1-4>",
                            "&fStand on the &cred &fand &9blue wool &fcorners.", "&7Again, it autocompletes.")),
            new Step("/stadium goalheight <red|blue> 4", "/stadium goalheight ",
                    "Set the goal height using /stadium goalheight <red|blue> <number>. For this tutorial, please use 4.",
                    List.of("&fSet how high each goal is with", "&e/stadium goalheight <red|blue> <number>",
                            "&fFor this tutorial, please use &a4&f.")),
            new Step("/stadium confirm", "/stadium confirm",
                    "Finally, do /stadium confirm to finish your stadium! You can edit any stadium you own with "
                            + "/stadium edit in the future.",
                    List.of("&fFinally, do &e/stadium confirm", "&fto finish your stadium!",
                            "&7You can edit any stadium you own", "&7with &e/stadium edit &7in the future.")));

    /** Everything one player has done in the guide so far. */
    private static final class Session {
        final Location origin;
        final GameMode mode;
        final boolean allowFlight;
        int step = 1;
        Stadium stadium; // the practice stadium: never put in StadiumManager, so never saved
        final boolean[] bounds = new boolean[BOUNDS.length];
        final List<Integer> boundsOrder = new ArrayList<>();
        final int[][] spot = new int[2][5]; // [team][setpos number - 1] -> which marker block, or -1
        boolean centre;
        final boolean[][] goal = new boolean[2][4];
        final boolean[] height = new boolean[2];
        Location boardAt;

        Session(Location origin, GameMode mode, boolean allowFlight) {
            this.origin = origin;
            this.mode = mode;
            this.allowFlight = allowFlight;
            Arrays.fill(spot[0], -1);
            Arrays.fill(spot[1], -1);
        }
    }

    private final JavaPlugin plugin;
    private final StadiumManager stadiums;
    private final MatchManager matches;
    private MinigamesLobbyManager minigamesLobby;
    private GuideHolograms holograms;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private World world;
    private int floorY;
    private int tickCount;

    BuildersGuide(JavaPlugin plugin, StadiumManager stadiums, MatchManager matches) {
        this.plugin = plugin;
        this.stadiums = stadiums;
        this.matches = matches;
        if (Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            try {
                holograms = new GuideHolograms(plugin.getLogger());
            } catch (Throwable problem) {
                plugin.getLogger().warning("DecentHolograms could not be used (" + problem + "); the Builder's Guide will run without holograms.");
            }
        } else {
            plugin.getLogger().warning("DecentHolograms isn't installed: the Builder's Guide will run without floating text.");
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    void setMinigamesLobby(MinigamesLobbyManager minigamesLobby) {
        this.minigamesLobby = minigamesLobby;
    }

    boolean isActive(UUID id) {
        return sessions.containsKey(id);
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) end(player, true, false);
        }
        sessions.clear();
    }

    // ------------------------------------------------------------------ starting and leaving

    void start(Player player) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            error(player, "You are already in the Builder's Guide. Use /tutorial leave to stop it.");
            return;
        }
        if (matches.isInMatch(id) || (minigamesLobby != null && minigamesLobby.isInLobby(id))) {
            error(player, "Finish or leave your game first, then start the Builder's Guide.");
            return;
        }
        World practice = ensureWorld();
        if (practice == null) {
            error(player, "The tutorial world could not be created. Please tell a staff member.");
            return;
        }

        Session session = new Session(player.getLocation().clone(), player.getGameMode(), player.getAllowFlight());
        sessions.put(id, session);
        player.teleport(new Location(practice, SPAWN_X + 0.5, floorY + 1, SPAWN_Z + 0.5, 0f, 0f));
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true); // so the pitch can be looked at from above

        player.sendMessage(Component.text("Welcome to the Builder's Guide! ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("Nothing you do here is saved. Use /tutorial leave to stop at any time.", NamedTextColor.GRAY)));
        // A tick later, so the board is placed where the player really is after the teleport.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessions.get(id) == session && player.isOnline()) showStep(player, session);
        });
    }

    void leave(Player player) {
        if (!sessions.containsKey(player.getUniqueId())) {
            error(player, "You aren't in a tutorial.");
            return;
        }
        end(player, true, true);
    }

    /** Ends the guide: removes the holograms, gives back the game mode and (if home) the old location. */
    private void end(Player player, boolean home, boolean message) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        if (holograms != null) holograms.removeAll(player.getUniqueId());

        player.setGameMode(session.mode);
        if (session.mode != GameMode.CREATIVE && session.mode != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(session.allowFlight);
        }
        if (home) player.teleport(session.origin);
        if (message) player.sendMessage(Component.text("You left the Builder's Guide.", NamedTextColor.YELLOW));
    }

    // ------------------------------------------------------------------ the practice world

    private World ensureWorld() {
        String name = plugin.getConfig().getString("builders-guide.world", "builders_guide");
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            world = existing;
        } else {
            try {
                world = new WorldCreator(name).type(WorldType.FLAT).generateStructures(false)
                        .generatorSettings(FLAT_SETTINGS).createWorld();
            } catch (RuntimeException problem) {
                plugin.getLogger().warning("Flat world settings were refused (" + problem + "); using the default superflat.");
                try {
                    world = new WorldCreator(name).type(WorldType.FLAT).generateStructures(false).createWorld();
                } catch (RuntimeException second) {
                    plugin.getLogger().severe("Could not create the Builder's Guide world: " + second);
                    return null;
                }
            }
        }
        if (world == null) return null;

        world.setDifficulty(Difficulty.PEACEFUL);
        world.setPVP(false);
        world.setStorm(false);
        world.setTime(6000);
        floorY = world.getHighestBlockYAt(SPAWN_X, SPAWN_Z); // the grass block of the flat world
        world.setSpawnLocation(SPAWN_X, floorY + 1, SPAWN_Z);
        buildStations();
        return world;
    }

    /** Puts the marker blocks in the ground, once (they are saved with the world). */
    private void buildStations() {
        if (world.getBlockAt(CENTRE[0][0], floorY, CENTRE[0][1]).getType() == Material.YELLOW_CONCRETE_POWDER) return;
        for (int cx = -2; cx <= 1; cx++) {
            for (int cz = -1; cz <= 0; cz++) world.getChunkAt(cx, cz); // load (and generate) the pitch's chunks
        }
        fill(BOUNDS, Material.RED_CONCRETE);
        fill(RED_SPOTS, Material.RED_CONCRETE_POWDER);
        fill(BLUE_SPOTS, Material.CYAN_CONCRETE_POWDER);
        fill(CENTRE, Material.YELLOW_CONCRETE_POWDER);
        fill(RED_GOAL, Material.RED_WOOL);
        fill(BLUE_GOAL, Material.BLUE_WOOL);
    }

    private void fill(int[][] stations, Material material) {
        for (int[] station : stations) world.getBlockAt(station[0], floorY, station[1]).setType(material, false);
    }

    private boolean inPracticeWorld(World other) {
        return world != null && world.equals(other);
    }

    // ------------------------------------------------------------------ commands: /tutorial and /next

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (command.getName().equalsIgnoreCase("next")) {
            next(player);
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "builders-guide", "buildersguide", "builder" -> start(player);
            case "leave", "exit", "stop" -> leave(player);
            default -> error(player, "Usage: /tutorial <builders-guide|leave>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("next") || args.length != 1) return List.of();
        return filter(List.of("builders-guide", "leave"), args[0]);
    }

    private void next(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            error(player, "You aren't in a tutorial. Open the navigator and pick Tutorials.");
            return;
        }
        if (session.step == 1) {
            advance(player, session);
        } else if (session.step > STEPS) {
            error(player, "The guide is finished. Use /tutorial leave to go back.");
        } else {
            error(player, "Nothing to skip here. Follow the guide: " + STEP_LIST.get(session.step - 1).command());
        }
    }

    // ------------------------------------------------------------------ /stadium while in the guide

    /** Called by StadiumManager first. True means the guide answered the command; false lets the real one run. */
    boolean handleStadium(Player player, String[] args) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        if (sub.equals("list")) return false; // the real list is harmless

        switch (session.step) {
            case 2 -> {
                if (sub.equals("create")) create(player, session, args);
                else notYet(player, session);
            }
            case 3 -> {
                if (sub.equals("bounds")) bounds(player, session, args);
                else notYet(player, session);
            }
            case 4 -> {
                if (sub.matches("setpos[1-5]")) setPosition(player, session, args, sub.charAt(6) - '1');
                else notYet(player, session);
            }
            case 5 -> {
                if (sub.equals("setcentre")) setCentre(player, session);
                else notYet(player, session);
            }
            case 6 -> {
                if (sub.equals("setgoal")) setGoal(player, session, args);
                else notYet(player, session);
            }
            case 7 -> {
                if (sub.equals("goalheight")) goalHeight(player, session, args);
                else notYet(player, session);
            }
            case 8 -> {
                if (sub.equals("confirm")) confirm(player, session);
                else notYet(player, session);
            }
            default -> notYet(player, session);
        }
        return true;
    }

    /** Tab completion for /stadium in the guide (null when the player isn't in it). */
    List<String> tabComplete(Player player, String[] args) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return null;
        String last = args.length == 0 ? "" : args[args.length - 1];

        if (args.length <= 1) {
            List<String> subs = new ArrayList<>();
            switch (session.step) {
                case 2 -> subs.add("create");
                case 3 -> subs.add("bounds");
                case 4 -> {
                    for (int i = 1; i <= 5; i++) subs.add("setpos" + i);
                }
                case 5 -> subs.add("setcentre");
                case 6 -> subs.add("setgoal");
                case 7 -> subs.add("goalheight");
                case 8 -> subs.add("confirm");
                default -> { }
            }
            subs.add("list");
            return filter(subs, last);
        }

        String sub = args[0].toLowerCase();
        List<String> options = new ArrayList<>();
        switch (session.step) {
            case 2 -> {
                if (sub.equals("create") && args.length == 2) options.add("MyStadium");
            }
            case 3 -> {
                if (sub.equals("bounds") && args.length == 2) options.add("addpoint");
            }
            case 4 -> {
                if (sub.matches("setpos[1-5]") && args.length == 2) options.addAll(List.of("red", "blue"));
            }
            case 6 -> {
                if (sub.equals("setgoal")) {
                    if (args.length == 2) options.addAll(List.of("red", "blue"));
                    if (args.length == 3) options.addAll(List.of("pos1", "pos2", "pos3", "pos4"));
                }
            }
            case 7 -> {
                if (sub.equals("goalheight")) {
                    if (args.length == 2) options.addAll(List.of("red", "blue"));
                    if (args.length == 3) options.add("4");
                }
            }
            default -> { }
        }
        return filter(options, last);
    }

    private void notYet(Player player, Session session) {
        if (session.step > STEPS) {
            error(player, "The guide is finished. Use /tutorial leave to go back.");
            return;
        }
        error(player, "Not yet! Follow the guide: " + STEP_LIST.get(session.step - 1).command());
    }

    // step 2
    private void create(Player player, Session session, String[] args) {
        String name = args.length >= 2 ? args[1] : "MyStadium";
        if (!VALID_NAME.matcher(name).matches()) {
            error(player, "Names can only use letters, numbers, - and _ (max 32 characters).");
            return;
        }
        session.stadium = new Stadium(name);
        player.sendMessage(Component.text("Created stadium " + name + ". (Builder's Guide: nothing is saved.)", NamedTextColor.GREEN));
        advance(player, session);
    }

    // step 3
    private void bounds(Player player, Session session, String[] args) {
        if (args.length < 2) {
            error(player, "Usage: /stadium bounds addpoint");
            return;
        }
        String name = session.stadium.name;
        switch (args[1].toLowerCase()) {
            case "addpoint" -> {
                int index = stationAt(player, BOUNDS);
                if (index < 0) {
                    error(player, "Stand on one of the red concrete blocks first." + hint(player, BOUNDS, i -> !session.bounds[i]));
                    return;
                }
                if (session.bounds[index]) {
                    error(player, "You already added a point here. Go to another red concrete block.");
                    return;
                }
                session.bounds[index] = true;
                session.boundsOrder.add(index);
                Location point = feetBlock(BOUNDS[index]);
                session.stadium.bounds.add(point);
                int count = session.boundsOrder.size();
                player.sendMessage(Component.text("Added bounds point " + count + " to " + name + " at " + point.getBlockX() + ", "
                        + point.getBlockY() + ", " + point.getBlockZ()
                        + (count < 3 ? " (add at least " + (3 - count) + " more to make a shape)." : ".")
                        + (count == BOUNDS.length ? "" : " (" + count + "/" + BOUNDS.length + ")"), NamedTextColor.GREEN));
                if (count == BOUNDS.length) advance(player, session);
                else refreshMarkers(player, session);
            }
            case "removepoint" -> {
                if (session.boundsOrder.isEmpty()) {
                    error(player, name + " has no bounds points to remove.");
                    return;
                }
                int index = session.boundsOrder.remove(session.boundsOrder.size() - 1);
                session.bounds[index] = false;
                session.stadium.bounds.remove(session.stadium.bounds.size() - 1);
                player.sendMessage(Component.text("Removed the last bounds point. " + session.boundsOrder.size() + " left.", NamedTextColor.YELLOW));
                refreshMarkers(player, session);
            }
            default -> error(player, "Usage: /stadium bounds addpoint");
        }
    }

    // step 4
    private void setPosition(Player player, Session session, String[] args, int slot) {
        String sub = "setpos" + (slot + 1);
        Team team = args.length >= 2 ? parseTeam(args[1]) : null;
        if (team == null) {
            error(player, "Usage: /stadium " + sub + " <red|blue>  (stand on a red or cyan concrete powder block)");
            return;
        }
        int[][] mine = spots(team);
        int index = stationAt(player, mine);
        int[] used = session.spot[team.ordinal()];
        if (index < 0) {
            Team other = team == Team.RED ? Team.BLUE : Team.RED;
            if (stationAt(player, spots(other)) >= 0) {
                error(player, "That is a " + other.label.toLowerCase() + " team spot. Use /stadium " + sub + " " + other.label.toLowerCase()
                        + " here, or walk to a " + powder(team) + " concrete powder block for the " + team.label.toLowerCase() + " team.");
            } else {
                error(player, "Stand on a " + powder(team) + " concrete powder block first."
                        + hint(player, mine, i -> !contains(used, i)));
            }
            return;
        }
        for (int i = 0; i < used.length; i++) {
            if (i != slot && used[i] == index) {
                error(player, "That spot is already used for setpos" + (i + 1) + ". Go to a different " + powder(team) + " block.");
                return;
            }
        }
        used[slot] = index;
        session.stadium.positions.get(team)[slot] = player.getLocation().clone();
        player.sendMessage(Component.text("Set player position " + (slot + 1) + " for the " + team.label + " team in " + session.stadium.name
                + " (" + session.stadium.positionsSet(team) + "/" + Stadium.POSITIONS_PER_TEAM + ").", NamedTextColor.GREEN));
        if (session.stadium.positionsComplete(Team.RED) && session.stadium.positionsComplete(Team.BLUE)) advance(player, session);
        else refreshMarkers(player, session);
    }

    // step 5
    private void setCentre(Player player, Session session) {
        if (stationAt(player, CENTRE) < 0) {
            error(player, "Stand on the yellow concrete powder first." + hint(player, CENTRE, i -> true));
            return;
        }
        session.stadium.center = player.getLocation().clone();
        session.centre = true;
        player.sendMessage(Component.text("Centre point of " + session.stadium.name
                + " set. The ball is placed here during freezes.", NamedTextColor.GREEN));
        advance(player, session);
    }

    // step 6
    private void setGoal(Player player, Session session, String[] args) {
        if (args.length < 3) {
            error(player, "Usage: /stadium setgoal <red|blue> <pos1|pos2|pos3|pos4>");
            return;
        }
        Team team = parseTeam(args[1]);
        if (team == null) {
            error(player, "The goal must be red or blue.");
            return;
        }
        int corner = -1;
        for (int i = 0; i < Stadium.GOAL_CORNERS; i++) {
            if (args[2].equalsIgnoreCase("pos" + (i + 1))) corner = i;
        }
        if (corner < 0) {
            error(player, "The corner must be pos1, pos2, pos3 or pos4.");
            return;
        }
        int[][] mine = goals(team);
        int index = stationAt(player, mine);
        if (index < 0) {
            Team other = team == Team.RED ? Team.BLUE : Team.RED;
            if (stationAt(player, goals(other)) >= 0) {
                error(player, "That corner belongs to the " + other.label.toLowerCase() + " goal. Use /stadium setgoal "
                        + other.label.toLowerCase() + " pos<number> here.");
            } else {
                error(player, "Stand on a " + team.label.toLowerCase() + " wool corner first."
                        + hint(player, mine, i -> !session.goal[team.ordinal()][i]));
            }
            return;
        }
        if (index != corner) {
            error(player, "This is corner pos" + (index + 1) + " of the " + team.label.toLowerCase()
                    + " goal. Use /stadium setgoal " + team.label.toLowerCase() + " pos" + (index + 1) + " here.");
            return;
        }
        session.stadium.goals.get(team)[corner] = feetBlock(mine[index]);
        session.goal[team.ordinal()][corner] = true;
        player.sendMessage(Component.text("Set corner " + (corner + 1) + " of the " + team.label + " goal in " + session.stadium.name
                + " (" + session.stadium.cornersSet(team) + "/4).", NamedTextColor.GREEN));
        if (session.stadium.goalComplete(Team.RED) && session.stadium.goalComplete(Team.BLUE)) advance(player, session);
        else refreshMarkers(player, session);
    }

    // step 7
    private void goalHeight(Player player, Session session, String[] args) {
        if (args.length < 3) {
            error(player, "Usage: /stadium goalheight <red|blue> <blocks>");
            return;
        }
        Team team = parseTeam(args[1]);
        if (team == null) {
            error(player, "The goal must be red or blue.");
            return;
        }
        double height;
        try {
            height = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            error(player, "The height must be a number, like 4.");
            return;
        }
        if (height != 4.0) {
            error(player, "For this tutorial please use 4. (In a real stadium any number from 0.5 to 64 works, half blocks included.)");
            return;
        }
        session.stadium.goalHeights.put(team, 4.0);
        session.height[team.ordinal()] = true;
        player.sendMessage(Component.text("The " + team.label + " goal in " + session.stadium.name
                + " is now 4 blocks high, measured up from its lowest corner block.", NamedTextColor.GREEN));
        if (session.height[0] && session.height[1]) advance(player, session);
        else refreshMarkers(player, session);
    }

    // step 8
    private void confirm(Player player, Session session) {
        session.stadium.confirmed = true; // hides the practice particles, like the real thing
        player.sendMessage(Component.text("Confirmed " + session.stadium.name + ". The particles are gone and it is locked. Use /stadium edit "
                + session.stadium.name + " to change it again.", NamedTextColor.GREEN));
        finish(player, session);
    }

    // ------------------------------------------------------------------ moving through the steps

    private void advance(Player player, Session session) {
        session.step++;
        showStep(player, session);
    }

    private void showStep(Player player, Session session) {
        if (session.step > STEPS) return;
        Step step = STEP_LIST.get(session.step - 1);
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Builder's Guide - step " + session.step + "/" + STEPS, NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text(step.chat(), NamedTextColor.WHITE));
        Component command = Component.text("-> " + step.command(), NamedTextColor.YELLOW);
        if (!BedrockSupport.isBedrock(player)) { // Bedrock can't click chat; they type it
            command = command.clickEvent(ClickEvent.suggestCommand(step.suggest()))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to put this in your chat box", NamedTextColor.GRAY)));
        }
        player.sendMessage(command);
        player.playSound(Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.PLAYER, 1.0f, 1.2f));
        placeBoard(player, session);
        refreshMarkers(player, session);
    }

    private void finish(Player player, Session session) {
        session.step = STEPS + 1;
        if (holograms != null) {
            holograms.removePrefix(player.getUniqueId(), "board");
            holograms.removePrefix(player.getUniqueId(), "m");
        }
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Builder's Guide complete!", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("That was only a demo: nothing was saved. For a real stadium you also need /stadium setstands "
                + "(where spectators who watch from the stands are placed) before it can be confirmed.", NamedTextColor.GRAY));
        player.playSound(Sound.sound(Key.key("entity.player.levelup"), Sound.Source.PLAYER, 1.0f, 1.0f));

        grantBuilder(player);

        int seconds = Math.max(3, plugin.getConfig().getInt("builders-guide.return-delay-seconds", 10));
        player.sendMessage(Component.text("You'll be taken back in " + seconds + " seconds, or use /tutorial leave now.", NamedTextColor.YELLOW));
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && sessions.get(id) == session) end(online, true, false);
        }, seconds * 20L);
    }

    // ------------------------------------------------------------------ the builder rank (LuckPerms)

    /**
     * Gives the LuckPerms builder role. The console command uses the player's UUID, not their name, so it works for
     * Bedrock (Floodgate/Geyser) players too, whose names carry a prefix and can contain spaces.
     */
    private void grantBuilder(Player player) {
        String group = plugin.getConfig().getString("builders-guide.luckperms-group", "builder");
        if (player.hasPermission("group." + group)) {
            player.sendMessage(Component.text("You already have the " + group + " role, so you can use /stadium for real.", NamedTextColor.GREEN));
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            plugin.getLogger().warning("Builder's Guide finished by " + player.getName() + " but LuckPerms isn't installed, so no role was given.");
            player.sendMessage(Component.text("Well done! LuckPerms isn't available, so please ask a staff member for the builder role.", NamedTextColor.YELLOW));
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getUniqueId() + " parent add " + group);
        verifyBuilder(player.getUniqueId(), group, 0);
    }

    /** LuckPerms runs its commands off the main thread, so check a little later that the role arrived. */
    private void verifyBuilder(UUID id, String group, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            if (player.hasPermission("group." + group)) {
                player.sendMessage(Component.text("You are now a Builder! You can use /stadium to build your own stadiums.", NamedTextColor.GOLD, TextDecoration.BOLD));
                player.showTitle(Title.title(Component.text("Builder!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("You can now use /stadium", NamedTextColor.GRAY)));
            } else if (attempt < 2) {
                verifyBuilder(id, group, attempt + 1);
            } else {
                player.sendMessage(Component.text("Well done! I couldn't confirm your builder role, so please ask a staff member.", NamedTextColor.YELLOW));
                plugin.getLogger().warning("The builder role (" + group + ") did not show up for " + player.getName() + " after the Builder's Guide.");
            }
        }, attempt == 0 ? 40L : 60L);
    }

    // ------------------------------------------------------------------ holograms, particles, action bar

    private void placeBoard(Player player, Session session) {
        if (holograms == null || session.step > STEPS) return;
        Step step = STEP_LIST.get(session.step - 1);
        List<String> lines = new ArrayList<>();
        lines.add("&6&lBUILDER'S GUIDE &7- Step " + session.step + "/" + STEPS);
        lines.addAll(step.board());

        Location eye = player.getEyeLocation();
        Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0E-4) ahead = new Vector(0, 0, 1);
        ahead.normalize().multiply(BOARD_DISTANCE);
        Location at = eye.clone().add(ahead);
        at.setY(eye.getY() + BOARD_HEIGHT);
        session.boardAt = at;
        holograms.place(player, "board", at, lines);
    }

    /** Labels above the blocks still to be used in the current step (the finished ones lose theirs). */
    private void refreshMarkers(Player player, Session session) {
        if (holograms == null) return;
        UUID id = player.getUniqueId();
        holograms.removePrefix(id, "m");
        switch (session.step) {
            case 3 -> {
                for (int i = 0; i < BOUNDS.length; i++) {
                    if (!session.bounds[i]) marker(player, "mb" + i, BOUNDS[i], 1.6,
                            "&c&lBounds point " + (i + 1), "&7/stadium bounds addpoint");
                }
            }
            case 4 -> {
                for (int i = 0; i < RED_SPOTS.length; i++) {
                    if (!contains(session.spot[0], i)) marker(player, "mr" + i, RED_SPOTS[i], 1.6,
                            "&c&lRed player spot", "&7/stadium setpos<1-5> red");
                }
                for (int i = 0; i < BLUE_SPOTS.length; i++) {
                    if (!contains(session.spot[1], i)) marker(player, "mu" + i, BLUE_SPOTS[i], 1.6,
                            "&b&lBlue player spot", "&7/stadium setpos<1-5> blue");
                }
            }
            case 5 -> marker(player, "mc", CENTRE[0], 1.6, "&e&lBall position", "&7/stadium setcentre");
            case 6 -> {
                for (int i = 0; i < RED_GOAL.length; i++) {
                    if (!session.goal[0][i]) marker(player, "mgr" + i, RED_GOAL[i], 1.6,
                            "&c&lRed goal pos" + (i + 1), "&7/stadium setgoal red pos" + (i + 1));
                }
                for (int i = 0; i < BLUE_GOAL.length; i++) {
                    if (!session.goal[1][i]) marker(player, "mgb" + i, BLUE_GOAL[i], 1.6,
                            "&9&lBlue goal pos" + (i + 1), "&7/stadium setgoal blue pos" + (i + 1));
                }
            }
            case 7 -> {
                if (!session.height[0]) marker(player, "mhr", new int[]{-19, 0}, 4.6, "&c&lRed goal height", "&7/stadium goalheight red 4");
                if (!session.height[1]) marker(player, "mhb", new int[]{19, 0}, 4.6, "&9&lBlue goal height", "&7/stadium goalheight blue 4");
            }
            case 8 -> marker(player, "mf", CENTRE[0], 2.6, "&a&lAll set!", "&7/stadium confirm");
            default -> { }
        }
    }

    private void marker(Player player, String key, int[] station, double lift, String... lines) {
        Location at = new Location(world, station[0] + 0.5, floorY + lift, station[1] + 0.5);
        holograms.place(player, key, at, List.of(lines));
    }

    private void tick() {
        tickCount++;
        if (tickCount % 10 == 0 && world != null && !world.getPlayers().isEmpty()) {
            world.setTime(6000); // always daytime in the practice world
            world.setStorm(false);
        }
        if (sessions.isEmpty()) return;

        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (player == null || !player.isOnline() || !inPracticeWorld(player.getWorld())) continue;

            if (session.stadium != null) stadiums.drawPreview(player, session.stadium); // the same particles a builder sees
            highlight(player, session);

            if (tickCount % 2 == 0) {
                Component bar = session.step > STEPS
                        ? Component.text("Builder's Guide complete! Use /tutorial leave to go back.", NamedTextColor.GREEN)
                        : Component.text("Builder's Guide " + session.step + "/" + STEPS + ": " + STEP_LIST.get(session.step - 1).command()
                                + progress(session), NamedTextColor.YELLOW);
                player.sendActionBar(bar);
            }
            // Walked away from the board? Bring it back to where they are.
            if (tickCount % 4 == 0 && holograms != null && session.step <= STEPS && session.boardAt != null
                    && player.getLocation().distanceSquared(session.boardAt) > 12 * 12) {
                placeBoard(player, session);
            }
        }
    }

    private String progress(Session session) {
        return switch (session.step) {
            case 3 -> " (" + session.boundsOrder.size() + "/" + BOUNDS.length + ")";
            case 4 -> " (" + (count(session.spot[0]) + count(session.spot[1])) + "/10)";
            case 6 -> " (" + (count(session.goal[0]) + count(session.goal[1])) + "/8)";
            case 7 -> " (" + ((session.height[0] ? 1 : 0) + (session.height[1] ? 1 : 0)) + "/2)";
            default -> "";
        };
    }

    /** A beam of sparkles over each block the current step still needs, so they are easy to find. */
    private void highlight(Player player, Session session) {
        switch (session.step) {
            case 3 -> beams(player, BOUNDS, i -> !session.bounds[i]);
            case 4 -> {
                beams(player, RED_SPOTS, i -> !contains(session.spot[0], i));
                beams(player, BLUE_SPOTS, i -> !contains(session.spot[1], i));
            }
            case 5 -> beams(player, CENTRE, i -> !session.centre);
            case 6 -> {
                beams(player, RED_GOAL, i -> !session.goal[0][i]);
                beams(player, BLUE_GOAL, i -> !session.goal[1][i]);
            }
            default -> { }
        }
    }

    private void beams(Player player, int[][] stations, IntPredicate pending) {
        Location here = player.getLocation();
        for (int i = 0; i < stations.length; i++) {
            if (!pending.test(i)) continue;
            double x = stations[i][0] + 0.5;
            double z = stations[i][1] + 0.5;
            double dx = x - here.getX();
            double dz = z - here.getZ();
            if (dx * dx + dz * dz > 48 * 48) continue;
            for (int k = 0; k < 4; k++) {
                player.spawnParticle(Particle.END_ROD, x, floorY + 1.2 + k * 0.5, z, 1, 0, 0, 0, 0);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Which marker block the player is standing on (x and z match, feet at or just above it), or -1. */
    private int stationAt(Player player, int[][] stations) {
        Location at = player.getLocation();
        if (!inPracticeWorld(at.getWorld())) return -1;
        if (at.getY() < floorY || at.getY() >= floorY + 3) return -1;
        for (int i = 0; i < stations.length; i++) {
            if (at.getBlockX() == stations[i][0] && at.getBlockZ() == stations[i][1]) return i;
        }
        return -1;
    }

    /** " The nearest one is about 7 blocks away." for the closest block that is still to be used. */
    private String hint(Player player, int[][] stations, IntPredicate pending) {
        Location at = player.getLocation();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < stations.length; i++) {
            if (!pending.test(i)) continue;
            best = Math.min(best, Math.hypot(stations[i][0] + 0.5 - at.getX(), stations[i][1] + 0.5 - at.getZ()));
        }
        if (best == Double.MAX_VALUE) return "";
        return " The nearest one is about " + Math.round(best) + " block" + (Math.round(best) == 1 ? "" : "s") + " away.";
    }

    /** The block at a marker's feet level, like the block /stadium bounds and /stadium setgoal record. */
    private Location feetBlock(int[] station) {
        return new Location(world, station[0], floorY + 1, station[1]);
    }

    private static int[][] spots(Team team) {
        return team == Team.RED ? RED_SPOTS : BLUE_SPOTS;
    }

    private static int[][] goals(Team team) {
        return team == Team.RED ? RED_GOAL : BLUE_GOAL;
    }

    private static String powder(Team team) {
        return team == Team.RED ? "red" : "cyan";
    }

    private static Team parseTeam(String text) {
        if (text.equalsIgnoreCase("red")) return Team.RED;
        if (text.equalsIgnoreCase("blue")) return Team.BLUE;
        return null;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) return true;
        }
        return false;
    }

    private static int count(int[] values) {
        int total = 0;
        for (int value : values) {
            if (value >= 0) total++;
        }
        return total;
    }

    private static int count(boolean[] values) {
        int total = 0;
        for (boolean value : values) {
            if (value) total++;
        }
        return total;
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer(), true, false); // otherwise they'd log back in standing in the practice world
    }

    /** Left the practice world some other way (a warp, say): the guide is over, but they stay where they went. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!sessions.containsKey(player.getUniqueId()) || inPracticeWorld(player.getWorld())) return;
        end(player, false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!sessions.containsKey(player.getUniqueId()) || event.getMessage().length() < 2) return;
        String root = event.getMessage().substring(1).split(" ")[0].toLowerCase();
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        if (BLOCKED.contains(root)) {
            event.setCancelled(true);
            error(player, "Finish the Builder's Guide first, or leave it with /tutorial leave.");
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (inPracticeWorld(event.getLocation().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (inPracticeWorld(event.getEntity().getWorld())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && inPracticeWorld(event.getEntity().getWorld())) event.setCancelled(true);
    }
}
