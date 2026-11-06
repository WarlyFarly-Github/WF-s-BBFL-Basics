package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One football match: two teams of up to 5 (10 players in total), a score and a bossbar. */
final class Match {

    static final int MAX_PER_TEAM = 5;

    enum Team {
        RED("Red", NamedTextColor.RED),
        BLUE("Blue", NamedTextColor.BLUE);

        final String label;
        final NamedTextColor color;

        Team(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }
    }

    enum Phase { FIRST_HALF, SECOND_HALF, COUNTDOWN }

    enum CountdownType { START, HALF_TIME, GOAL, GOLDEN_GOAL }

    final UUID host; // null for quickmatches, which have no host
    boolean quick;   // quickmatch: one period, no half time
    boolean roulette; // Roulette: a quickmatch where everyone gets a random power every 30 seconds
    boolean duel;    // a duel: a 5 minute 1v1 quickmatch with no red and blue teams (players are shown by name)
    private String redName = "";  // duels: the player on the Red side
    private String blueName = ""; // duels: the player on the Blue side
    int playSeconds = 5 * 60; // length of each half (league) or of the whole match (quick)
    static final int DEFAULT_HALF_TIME_SECONDS = 60;
    int halfTimeSeconds = DEFAULT_HALF_TIME_SECONDS; // length of the half-time break (league matches), set in the timings menu
    int redScore;
    int blueScore;
    int playTicks;      // ticks of live play so far (not counting freezes or pauses): the match clock, for the leaderboard
    boolean fullTime;   // the match was played to a result (full time or a golden goal), so its winner gets a win
    boolean started;
    /** Referees: in the match (they see its bars and blow the whistle) but on neither team. */
    final Set<UUID> referees = new LinkedHashSet<>();
    static final int MAX_REFEREES = 3;
    static final int MAX_SUBS_PER_TEAM = 3;
    /** A spot left empty when a player disconnected mid-match, to be filled by a substitute (where they stood). */
    record Vacancy(Team team, String name, org.bukkit.Location spot, UUID sentOffId) { } // sentOffId: a red card, else null
    /** Players sent off with a red card: in the match, up in the stands, and not substitutes (so they can't be subbed back on). */
    final Map<UUID, Team> sentOff = new java.util.LinkedHashMap<>();
    /** Sent-off players who come back at half time (red card in the first half with nobody to sub on). */
    final Set<UUID> returnAtHalfTime = new LinkedHashSet<>();
    final Map<UUID, Integer> yellows = new HashMap<>(); // yellow cards shown to each player; two make a red
    String pauseReason; // why play pauses once the current countdown ends (a red card or a disconnect)
    final java.util.List<Vacancy> vacancies = new java.util.ArrayList<>();
    /** Where referees and substitutes were when they joined, so they go back there from the stands. */
    final Map<UUID, org.bukkit.Location> sidelineOrigins = new HashMap<>();
    boolean pauseAfterCountdown; // someone disconnected during a countdown: pause as soon as it ends

    Vacancy vacancyFor(Team team) {
        for (Vacancy vacancy : vacancies) {
            if (vacancy.team() == team) return vacancy;
        }
        return null;
    }
    /** Substitutes: in the match and watching from the sidelines until a referee subs them on for a player. */
    private final Set<UUID> redSubs = new LinkedHashSet<>();
    private final Set<UUID> blueSubs = new LinkedHashSet<>();
    boolean paused;                        // a referee blew the whistle: clock, ball and players are held still
    org.bukkit.util.Vector pausedVelocity; // the ball's velocity when play was paused, given back on resume
    boolean serverInvited; // the whole Discord server was invited to this match, so Discord hears when it is over
    boolean bypass; // created with /match create bypass: may start without players on both teams
    SulfurCube ball;
    Stadium stadium; // optional: gives the match a centre point and goal areas
    UUID lastKicker; // last match member to hit the ball
    org.bukkit.util.Vector lastVelocity; // the ball's velocity when it was last inside the bounds, for bouncing off the wall
    Location lastInBounds; // where the ball last was inside the stadium bounds (null until it has been seen there)

    // Match timer
    Phase phase = Phase.FIRST_HALF;          // COUNTDOWN means everything is frozen
    Phase resumePhase = Phase.FIRST_HALF;    // the half to carry on with after a countdown
    CountdownType countdownType = CountdownType.START;
    boolean goldenGoal;                      // level at full time: a 5 minute period where the next goal wins
    int secondsLeft;                         // play time left in the current half (paused during countdowns)
    int countdownLeft;                       // seconds left in the current freeze
    BukkitTask timerTask;                    // ticks the half timer
    BukkitTask countdownTask;                // ticks the freeze countdown
    final BossBar timerBar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    // Where everyone (and the ball) were when the match started, used to reset after the first half.
    final Map<UUID, Location> startPositions = new HashMap<>();

    /** Each player's game mode from before the match, put back when they leave it. */
    final Map<UUID, GameMode> savedGameModes = new HashMap<>();

    /** Which of the team's stadium positions (0-2) each player was put on last freeze, so nobody repeats one. */
    final Map<UUID, Integer> lastPosition = new HashMap<>();
    Location ballStart;

    // Each player's own chestplate, put back when they leave or the match ends.
    final Map<UUID, ItemStack> savedChestplates = new HashMap<>();

    private final Set<UUID> red = new LinkedHashSet<>();
    private final Set<UUID> blue = new LinkedHashSet<>();
    private final BossBar bar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    Match(UUID host) {
        this.host = host;
        this.red.add(host); // the host always starts on Red
        updateBar();
    }

    /** A quickmatch: nobody hosts it, and players are added to teams when it starts. */
    Match() {
        this.host = null;
        this.quick = true;
        updateBar();
    }

    /** Turns this into a duel between the two named players. */
    void setDuel(String redPlayer, String bluePlayer) {
        duel = true;
        redName = redPlayer;
        blueName = bluePlayer;
        updateBar();
    }

    /** The player's name on this side of a duel. */
    String duelName(Team team) {
        return team == Team.RED ? redName : blueName;
    }

    Set<UUID> subs(Team team) {
        return team == Team.RED ? redSubs : blueSubs;
    }

    /** The team this player is a substitute for, or null if they are not a substitute. */
    Team subTeamOf(UUID id) {
        if (redSubs.contains(id)) return Team.RED;
        return blueSubs.contains(id) ? Team.BLUE : null;
    }

    /** The team whose bench this player is on: a substitute, or someone sent off (a referee can bring them back on). */
    Team benchTeamOf(UUID id) {
        Team sub = subTeamOf(id);
        return sub != null ? sub : sentOff.get(id);
    }

    /** On the sidelines: a referee or a substitute. They are in the match but not playing. */
    boolean isSideline(UUID id) {
        return referees.contains(id) || redSubs.contains(id) || blueSubs.contains(id) || sentOff.containsKey(id);
    }

    Set<UUID> teamSet(Team team) {
        return team == Team.RED ? red : blue;
    }

    int size(Team team) {
        return teamSet(team).size();
    }

    boolean isFull(Team team) {
        return size(team) >= MAX_PER_TEAM;
    }

    boolean contains(UUID id) {
        return red.contains(id) || blue.contains(id);
    }

    void add(UUID id, Team team) {
        teamSet(team).add(id);
    }

    void remove(UUID id) {
        red.remove(id);
        blue.remove(id);
        referees.remove(id);
        redSubs.remove(id);
        blueSubs.remove(id);
        sentOff.remove(id);
        returnAtHalfTime.remove(id);
        yellows.remove(id);
    }

    Team teamOf(UUID id) {
        return red.contains(id) ? Team.RED : Team.BLUE;
    }

    List<Player> onlineMembers() {
        List<Player> players = new ArrayList<>();
        for (UUID id : red) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        for (UUID id : blue) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        return players;
    }

    /** The players on the two teams plus the referees: everyone who takes part in the match. */
    List<Player> onlineAudience() {
        List<Player> players = onlineMembers();
        for (UUID id : referees) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        for (UUID id : redSubs) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        for (UUID id : blueSubs) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        for (UUID id : sentOff.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) players.add(p);
        }
        return players;
    }

    void broadcast(Component message) {
        onlineAudience().forEach(p -> p.sendMessage(message));
    }

    void showBar(Player player) {
        player.showBossBar(bar);
        player.showBossBar(timerBar);
    }

    void hideBar(Player player) {
        player.hideBossBar(bar);
        player.hideBossBar(timerBar);
    }

    void addGoal(Team team) {
        if (team == Team.RED) redScore++;
        else blueScore++;
        updateBar();
    }

    void removeGoal(Team team) {
        if (team == Team.RED) redScore = Math.max(0, redScore - 1);
        else blueScore = Math.max(0, blueScore - 1);
        updateBar();
    }

    private void updateBar() {
        if (duel) {
            bar.name(Component.text(redName + " ", NamedTextColor.WHITE, TextDecoration.BOLD)
                    .append(Component.text(redScore + " - " + blueScore, NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" " + blueName, NamedTextColor.WHITE, TextDecoration.BOLD)));
            return;
        }
        bar.name(Component.text("RED ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(redScore + " - " + blueScore, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" BLUE", NamedTextColor.BLUE, TextDecoration.BOLD)));
    }
}
