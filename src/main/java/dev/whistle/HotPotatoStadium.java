package dev.whistle;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * A Hot Potato arena, made with /hpstadium: a polygon (the bounds) that the ball stays inside, and up to five starting
 * positions for each team. The floor is built by hand (ice, normally); nothing here depends on it.
 */
final class HotPotatoStadium {

    static final int MIN_POINTS = 3;
    static final int MAX_BOUNDS_POINTS = 32;
    static final int MAX_POSITIONS = 5; // starting positions per team: also the most players a team can have here

    final String name;
    final List<Location> bounds = new ArrayList<>();
    /** Starting positions, position 1 first. A slot is null until it has been set. */
    final Location[] redPositions = new Location[MAX_POSITIONS];
    final Location[] bluePositions = new Location[MAX_POSITIONS];
    /** Confirmed arenas are locked (no edits, no particles). New ones start unconfirmed, being edited. */
    boolean confirmed;

    HotPotatoStadium(String name) {
        this.name = name;
    }

    Location[] slots(Match.Team team) {
        return team == Match.Team.RED ? redPositions : bluePositions;
    }

    /** The positions that are set for this team, in order: its players are placed on them one by one. */
    List<Location> positions(Match.Team team) {
        List<Location> set = new ArrayList<>();
        for (Location position : slots(team)) {
            if (position != null && position.getWorld() != null) set.add(position);
        }
        return set;
    }

    /** How many players this team can have here: one per position that is set. */
    int capacity(Match.Team team) {
        return positions(team).size();
    }

    boolean hasBounds() {
        return bounds.size() >= MIN_POINTS && bounds.get(0).getWorld() != null;
    }

    List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (bounds.size() < MIN_POINTS) {
            missing.add("bounds with at least " + MIN_POINTS + " points (/hpstadium bounds <stadium> addpoint)");
        } else if (!hasBounds()) {
            missing.add("bounds in a loaded world");
        } else {
            boolean sameWorld = true;
            for (Location point : bounds) {
                if (point.getWorld() == null || !point.getWorld().equals(bounds.get(0).getWorld())) sameWorld = false;
            }
            if (!sameWorld) missing.add("bounds points that are all in the same world");
        }
        for (Match.Team team : Match.Team.values()) {
            if (capacity(team) == 0) {
                missing.add(team.label.toLowerCase() + " positions (/hpstadium setpos <stadium> " + team.label.toLowerCase() + " <1-"
                        + MAX_POSITIONS + ">)");
            }
        }
        if (missing.isEmpty()) {
            for (Match.Team team : Match.Team.values()) {
                for (Location position : positions(team)) {
                    if (!position.getWorld().equals(bounds.get(0).getWorld())) {
                        missing.add("all " + team.label.toLowerCase() + " positions in the same world as the bounds");
                        break;
                    }
                    if (!boundsContain(centreOf(position))) {
                        missing.add("all " + team.label.toLowerCase() + " positions inside the bounds");
                        break;
                    }
                }
            }
        }
        return missing;
    }

    boolean isComplete() {
        return missingParts().isEmpty();
    }

    /** The middle of the block, so a position is judged by the block it was set on. */
    static Location centreOf(Location block) {
        return new Location(block.getWorld(), block.getBlockX() + 0.5, block.getBlockY(), block.getBlockZ() + 0.5);
    }

    // ------------------------------------------------------------------ the bounds polygon

    int minX() {
        int min = Integer.MAX_VALUE;
        for (Location p : bounds) min = Math.min(min, p.getBlockX());
        return min;
    }

    int maxX() {
        int max = Integer.MIN_VALUE;
        for (Location p : bounds) max = Math.max(max, p.getBlockX());
        return max;
    }

    int minZ() {
        int min = Integer.MAX_VALUE;
        for (Location p : bounds) min = Math.min(min, p.getBlockZ());
        return min;
    }

    int maxZ() {
        int max = Integer.MIN_VALUE;
        for (Location p : bounds) max = Math.max(max, p.getBlockZ());
        return max;
    }

    int boxWidth() {
        return bounds.isEmpty() ? 0 : maxX() - minX() + 1;
    }

    int boxDepth() {
        return bounds.isEmpty() ? 0 : maxZ() - minZ() + 1;
    }

    /** The average of the bounds points: roughly the middle of the arena. */
    Location middle() {
        double x = 0;
        double z = 0;
        double y = 0;
        for (Location p : bounds) {
            x += p.getBlockX() + 0.5;
            z += p.getBlockZ() + 0.5;
            y += p.getBlockY();
        }
        int n = Math.max(1, bounds.size());
        return new Location(bounds.get(0).getWorld(), x / n, y / n, z / n);
    }

    /** Whether the point is inside the bounds polygon (half a block over the edge still counts). Height is ignored. */
    boolean boundsContain(Location point) {
        if (!hasBounds() || point.getWorld() == null || !bounds.get(0).getWorld().equals(point.getWorld())) return false;
        return contains(point.getX(), point.getZ());
    }

    /** As above, for plain coordinates (the world is assumed to be the arena's). */
    boolean contains(double px, double pz) {
        int n = bounds.size();
        if (n < MIN_POINTS) return false;
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = bounds.get(i).getBlockX() + 0.5;
            zs[i] = bounds.get(i).getBlockZ() + 0.5;
        }
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (distanceToSegment(px, pz, xs[i], zs[i], xs[j], zs[j]) <= 0.5) return true;
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            boolean crosses = (zs[i] > pz) != (zs[j] > pz);
            if (crosses && px < (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside;
        }
        return inside;
    }

    /** Where the nearest bounds edge is, and which way is back inside (the normal points from the ball to the edge). */
    record BoundsHit(double x, double z, double nx, double nz, double distance) { }

    /** The nearest bounds edge to the point, or null if there are no bounds or the point is exactly on the edge. */
    BoundsHit nearestBoundsEdge(Location point) {
        if (!hasBounds()) return null;
        int n = bounds.size();
        double px = point.getX();
        double pz = point.getZ();

        double bestDistance = Double.MAX_VALUE;
        double bestX = 0;
        double bestZ = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ax = bounds.get(i).getBlockX() + 0.5;
            double az = bounds.get(i).getBlockZ() + 0.5;
            double bx = bounds.get(j).getBlockX() + 0.5;
            double bz = bounds.get(j).getBlockZ() + 0.5;
            double dx = bx - ax;
            double dz = bz - az;
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
            t = Math.max(0, Math.min(1, t));
            double qx = ax + t * dx;
            double qz = az + t * dz;
            double distance = Math.hypot(px - qx, pz - qz);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestX = qx;
                bestZ = qz;
            }
        }
        if (bestDistance < 1.0E-6) return null;
        return new BoundsHit(bestX, bestZ, (bestX - px) / bestDistance, (bestZ - pz) / bestDistance, bestDistance);
    }

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
