package dev.whistle;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * A One-Bounce arena: a circle with a middle point (where the ball is) and a radius. Made with /obstadium.
 * It can also have bounds (a polygon, /obstadium bounds); with those, the bounds are the edge of play instead of the circle.
 */
final class OneBounceStadium {

    static final double MIN_RADIUS = 6;
    static final double MAX_RADIUS = 100;
    static final int MAX_BOUNDS_POINTS = 32;

    final String name;
    Location ballPos;
    double radius; // 0 until set
    /** Optional polygon marking the edge of play. Empty means the circle is the edge. */
    final List<Location> bounds = new ArrayList<>();
    /** Confirmed arenas are locked (no edits, no particles). New ones start unconfirmed, being edited. */
    boolean confirmed;

    OneBounceStadium(String name) {
        this.name = name;
    }

    List<String> missingParts() {
        List<String> missing = new ArrayList<>();
        if (ballPos == null || ballPos.getWorld() == null) missing.add("ball position (/obstadium setballpos)");
        if (radius <= 0) missing.add("radius (/obstadium setradius)");
        if (!bounds.isEmpty() && bounds.size() < 3) {
            missing.add("bounds with at least 3 points (or /obstadium bounds <stadium> clear)");
        } else if (hasBounds() && ballPos != null && ballPos.getWorld() != null) {
            if (!bounds.get(0).getWorld().equals(ballPos.getWorld())) {
                missing.add("bounds in the same world as the ball position");
            } else if (!boundsContain(centre())) {
                missing.add("ball position inside the bounds");
            }
        }
        return missing;
    }

    boolean isComplete() {
        return missingParts().isEmpty();
    }

    /** The middle of the arena, on the block the ball position was set at. */
    Location centre() {
        return new Location(ballPos.getWorld(), ballPos.getBlockX() + 0.5, ballPos.getBlockY(), ballPos.getBlockZ() + 0.5);
    }

    boolean hasBounds() {
        return bounds.size() >= 3 && bounds.get(0).getWorld() != null;
    }

    /** Whether the point is inside the arena: inside the bounds if it has them, otherwise inside the circle. Height is ignored. */
    boolean contains(Location point) {
        if (!isComplete() || !ballPos.getWorld().equals(point.getWorld())) return false;
        if (hasBounds()) return boundsContain(point);
        Location c = centre();
        return Math.hypot(point.getX() - c.getX(), point.getZ() - c.getZ()) <= radius;
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
