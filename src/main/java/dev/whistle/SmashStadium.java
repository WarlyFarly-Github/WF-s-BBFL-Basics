package dev.whistle;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** A Smash arena: bounds, and a target wall between two points with a height. Made by operators with /smashstadium. */
final class SmashStadium {

    static final int MAX_BOUNDS_POINTS = 32;
    /** How close the ball's centre must get to the wall line to count as hitting it. */
    private static final double WALL_CONTACT = 1.3;

    final String name;
    final List<Location> bounds = new ArrayList<>();
    Location wall1;
    Location wall2;
    double wallHeight; // 0 until set

    SmashStadium(String name) {
        this.name = name;
    }

    boolean hasBounds() {
        return bounds.size() >= 3 && bounds.get(0).getWorld() != null;
    }

    boolean hasWallPoints() {
        return wall1 != null && wall2 != null && wall1.getWorld() != null && wall1.getWorld().equals(wall2.getWorld());
    }

    /** Everything this arena needs before it can be played; empty means it is finished. */
    List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (!hasBounds()) missing.add("bounds (at least 3 points)");
        if (wall1 == null) missing.add("wall point 1");
        if (wall2 == null) missing.add("wall point 2");
        if (wall1 != null && wall2 != null && !hasWallPoints()) missing.add("wall points in the same world");
        if (wallHeight <= 0) missing.add("wall height");
        if (hasBounds() && hasWallPoints() && !bounds.get(0).getWorld().equals(wall1.getWorld())) {
            missing.add("wall in the same world as the bounds");
        }
        return missing;
    }

    boolean isComplete() {
        return missingParts().isEmpty();
    }

    /** The middle of the bounds, at the height of the wall's base: where the ball is served from. */
    Location centre() {
        double x = 0;
        double z = 0;
        for (Location point : bounds) {
            x += point.getBlockX() + 0.5;
            z += point.getBlockZ() + 0.5;
        }
        double y = Math.min(wall1.getBlockY(), wall2.getBlockY());
        return new Location(bounds.get(0).getWorld(), x / bounds.size(), y, z / bounds.size());
    }

    /** The middle of the wall, used to make players face it. */
    Location wallMiddle() {
        return new Location(wall1.getWorld(), (wall1.getBlockX() + wall2.getBlockX()) / 2.0 + 0.5,
                Math.min(wall1.getBlockY(), wall2.getBlockY()) + wallHeight / 2.0,
                (wall1.getBlockZ() + wall2.getBlockZ()) / 2.0 + 0.5);
    }

    boolean touchesWall(Location ball) {
        if (!hasWallPoints() || !wall1.getWorld().equals(ball.getWorld())) return false;
        double baseY = Math.min(wall1.getBlockY(), wall2.getBlockY());
        if (ball.getY() < baseY - 1 || ball.getY() > baseY + wallHeight + 1) return false;
        return distanceToSegment(ball.getX(), ball.getZ(), wall1.getBlockX() + 0.5, wall1.getBlockZ() + 0.5,
                wall2.getBlockX() + 0.5, wall2.getBlockZ() + 0.5) <= WALL_CONTACT;
    }

    /** Whether the point is inside the bounds polygon (half a block over the edge still counts). Height is ignored. */
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

    private static double distanceToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
