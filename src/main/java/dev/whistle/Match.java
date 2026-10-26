package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One football match: two teams of up to 4, a score and a bossbar. */
final class Match {

    static final int MAX_PER_TEAM = 4;

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

    enum Phase { FIRST_HALF, COUNTDOWN, SECOND_HALF }

    final UUID host;
    int redScore;
    int blueScore;
    boolean started;
    SulfurCube ball;
    UUID lastKicker; // last match member to hit the ball

    // Match timer
    Phase phase = Phase.FIRST_HALF;
    int secondsLeft;
    BukkitTask timerTask;
    final BossBar timerBar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    // Where everyone (and the ball) were when the match started, used to reset after the first half.
    final Map<UUID, Location> startPositions = new HashMap<>();
    Location ballStart;

    private final Set<UUID> red = new LinkedHashSet<>();
    private final Set<UUID> blue = new LinkedHashSet<>();
    private final BossBar bar = BossBar.bossBar(
            Component.empty(), 1.0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    Match(UUID host) {
        this.host = host;
        this.red.add(host); // the host always starts on Red
        updateBar();
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

    void broadcast(Component message) {
        onlineMembers().forEach(p -> p.sendMessage(message));
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

    private void updateBar() {
        bar.name(Component.text("RED ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(redScore + " - " + blueScore, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" BLUE", NamedTextColor.BLUE, TextDecoration.BOLD)));
    }
}
