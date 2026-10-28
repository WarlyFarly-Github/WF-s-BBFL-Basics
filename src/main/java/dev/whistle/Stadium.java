package dev.whistle;

import dev.whistle.Match.Team;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A football stadium: a centre point for the ball and one goal area per team. */
final class Stadium {

    static final int GOAL_CORNERS = 4;
    static final double DEFAULT_GOAL_HEIGHT = 3.0;
    static final int MAX_BOUNDS_POINTS = 32;
    static final int POSITIONS_PER_TEAM = 5;

    final String name;

    /** Who owns the stadium. Null for stadiums from before owners existed: those can be taken with /stadium claim. */
    UUID owner;

    /** Players the owner invited to edit it too (uuid -> name when invited). */
    final Map<UUID, String> editors = new LinkedHashMap<>();

    /**
     * Confirmed stadiums are locked: no more edits, and no goal particles. New stadiums start unconfirmed
     * (editing) until /stadium confirm; /stadium edit unlocks them again.
     */
    boolean confirmed;

    /** The block shown for this stadium in the stadium chest menu; null means the default player head. */
    Material block;

    /**
     * The stadium bounds: a polygon of block positions, in order around the edge (at least 3 points to count as a
     * shape). Edited with /stadium bounds.
     */
    final List<Location> bounds = new ArrayList<>();

    /** Where the ball is placed during the 5 second freezes. */
    Location center;

    /**
     * Goal areas, keyed by the team that DEFENDS them. Each is a polygon of 4 corner blocks (pos1..pos4,
     * in order around the edge). When the ball enters the goal a team defends, the other team scores.
     */
    final Map<Team, Location[]> goals = new EnumMap<>(Team.class);

    /**
     * Where each team's players stand during a freeze (setpos1..setpos5). Players are shuffled between these each
     * freeze. Keyed by team.
     */
    final Map<Team, Location[]> positions = new EnumMap<>(Team.class);

    /** How many blocks tall each goal is (half blocks allowed), measured up from the lowest corner block. */
    final Map<Team, Double> goalHeights = new EnumMap<>(Team.class);

    Stadium(String name) {
        this.name = name;
        for (Team team : Team.values()) {
            goals.put(team, new Location[GOAL_CORNERS]);
            positions.put(team, new Location[POSITIONS_PER_TEAM]);
            goalHeights.put(team, DEFAULT_GOAL_HEIGHT);
        }
    }

    /** Everything a stadium needs before it can be used in a match; empty means it is finished. */
    List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (!hasCenter()) missing.add("centre point");
        for (Team team : Team.values()) {
            if (!goalComplete(team)) missing.add(team.label + " goal (all 4 corners)");
        }
        for (Team team : Team.values()) {
            if (!positionsComplete(team)) {
                missing.add(team.label + " player positions (" + positionsSet(team) + "/" + POSITIONS_PER_TEAM + ")");
            }
        }
        if (!hasBounds()) missing.add("bounds (at least 3 points)");
        return missing;
    }

    boolean isComplete() {
        return missingParts().isEmpty();
    }

    boolean hasCenter() {
        return center != null && center.getWorld() != null;
    }

    /** Height in blocks; can be a half block (for example 2.5 for a goal topped with a slab). */
    double goalHeight(Team team) {
        return goalHeights.get(team);
    }

    /** 3.0 -> "3", 2.5 -> "2.5" */
    static String formatHeight(double height) {
        return height == Math.floor(height) ? String.valueOf((int) height) : String.valueOf(height);
    }

    int cornersSet(Team team) {
        int count = 0;
        for (Location corner : goals.get(team)) {
            if (corner != null) count++;
        }
        return count;
    }

    int positionsSet(Team team) {
        int count = 0;
        for (Location spot : positions.get(team)) {
            if (spot != null && spot.getWorld() != null) count++;
        }
        return count;
    }

    boolean positionsComplete(Team team) {
        return positionsSet(team) == POSITIONS_PER_TEAM;
    }

    boolean goalComplete(Team team) {
        Location[] corners = goals.get(team);
        World world = corners[0] == null ? null : corners[0].getWorld();
        if (world == null) return false;
        for (Location corner : corners) {
            if (corner == null || !world.equals(corner.getWorld())) return false;
        }
        return true;
    }

    /** True once the bounds polygon has at least 3 points, all in one world: only then do matches use it. */
    boolean hasBounds() {
        if (bounds.size() < 3) return false;
        World world = bounds.get(0).getWorld();
        if (world == null) return false;
        for (Location point : bounds) {
            if (!world.equals(point.getWorld())) return false;
        }
        return true;
    }

    /** Whether the point is inside the bounds polygon (height is ignored). */
    boolean boundsContain(Location point) {
        if (!hasBounds() || !bounds.get(0).getWorld().equals(point.getWorld())) return false;

        int n = bounds.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = bounds.get(i).getBlockX() + 0.5;
            zs[i] = bounds.get(i).getBlockZ() + 0.5;
        }
        double px = point.getX();
        double pz = point.getZ();

        // Like the goals: within half a block of the line counts as inside.
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (distanceToSegment(px, pz, xs[i], zs[i], xs[j], zs[j]) <= 0.5) return true;
        }

        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            boolean crosses = (zs[i] > pz) != (zs[j] > pz);
            if (crosses && px < (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Whether the point is inside the goal area defended by the given team. */
    boolean goalContains(Team team, Location point) {
        if (!goalComplete(team)) return false;
        Location[] c = goals.get(team);
        if (!c[0].getWorld().equals(point.getWorld())) return false;

        // Height: from the lowest corner block upwards.
        int baseY = c[0].getBlockY();
        for (Location corner : c) baseY = Math.min(baseY, corner.getBlockY());
        if (point.getY() < baseY || point.getY() >= baseY + goalHeight(team)) return false;

        // The polygon runs through the middle of each corner block.
        double[] xs = new double[GOAL_CORNERS];
        double[] zs = new double[GOAL_CORNERS];
        for (int i = 0; i < GOAL_CORNERS; i++) {
            xs[i] = c[i].getBlockX() + 0.5;
            zs[i] = c[i].getBlockZ() + 0.5;
        }
        double px = point.getX();
        double pz = point.getZ();

        // Within half a block of an edge counts as inside, so the border blocks you stood in are included.
        for (int i = 0; i < GOAL_CORNERS; i++) {
            int j = (i + 1) % GOAL_CORNERS;
            if (distanceToSegment(px, pz, xs[i], zs[i], xs[j], zs[j]) <= 0.5) return true;
        }

        // Standard "ray casting" point-in-polygon test.
        boolean inside = false;
        for (int i = 0, j = GOAL_CORNERS - 1; i < GOAL_CORNERS; j = i++) {
            boolean crosses = (zs[i] > pz) != (zs[j] > pz);
            if (crosses && px < (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        double nearestX = ax + t * dx;
        double nearestZ = az + t * dz;
        return Math.hypot(px - nearestX, pz - nearestZ);
    }
}
