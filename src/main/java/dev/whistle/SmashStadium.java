package dev.whistle;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** A Smash arena: bounds, and a target wall between two points with a height. Made by operators with /smashstadium. */
final class SmashStadium {

    static final int MAX_BOUNDS_POINTS = 32;
    /**
     * The wall trigger is a line just in front of the wall's blocks (not inside them): half a block (the block's
     * face) plus the ball's half width, so the ball's edge meets the block face as its centre reaches the line, plus
     * a tiny gap so the block and the trigger never overlap.
     */
    static final double BLOCK_HALF_WIDTH = 0.5;
    static final double TRIGGER_GAP = 0.05;
    /** The ball's half width, used to draw the trigger line while editing (in a game the real width is measured). */
    static final double DEFAULT_BALL_HALF_WIDTH = 0.5;

    static double triggerDistance(double ballHalfWidth) {
        return BLOCK_HALF_WIDTH + ballHalfWidth + TRIGGER_GAP;
    }

    final String name;
    final List<Location> bounds = new ArrayList<>();
    Location wall1;
    Location wall2;
    double wallHeight; // 0 until set
    /** Confirmed arenas are locked (no edits, no particles). New ones start unconfirmed, being edited. */
    boolean confirmed;
    /** Which side of the wall is the front, where the trigger is: 1 or -1 along {@link #wallNormal()}. */
    int frontSide = 1;

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

    /** The unit direction (horizontal, {x, z}) pointing out from the wall's front. */
    double[] frontNormal() {
        double[] normal = wallNormal();
        return new double[] {normal[0] * frontSide, normal[1] * frontSide};
    }

    /** How far in front of the wall line the point is (negative if it is behind it). */
    double frontDistance(Location point) {
        return signedWallDistance(point) * frontSide;
    }

    /** Chooses the front as the side facing the given horizontal direction. Returns false if it faces neither. */
    boolean setFrontToward(double dx, double dz) {
        double[] normal = wallNormal();
        double dot = normal[0] * dx + normal[1] * dz;
        if (Math.abs(dot) < 1.0E-6) return false;
        frontSide = dot > 0 ? 1 : -1;
        return true;
    }

    /** A compass name for the direction the front faces. */
    String frontCompass() {
        double[] n = frontNormal();
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        double angle = Math.toDegrees(Math.atan2(n[1], n[0])); // 0 = +x (east), 90 = +z (south)
        int index = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return names[index];
    }

    /** Whether the point is along the wall (within its length, half a block past the ends) and within its height. */
    boolean alongWall(Location point) {
        if (!hasWallPoints() || !wall1.getWorld().equals(point.getWorld())) return false;
        double baseY = Math.min(wall1.getBlockY(), wall2.getBlockY());
        if (point.getY() < baseY - 1 || point.getY() > baseY + wallHeight + 1) return false;

        double x1 = wall1.getBlockX() + 0.5;
        double z1 = wall1.getBlockZ() + 0.5;
        double dx = wall2.getBlockX() + 0.5 - x1;
        double dz = wall2.getBlockZ() + 0.5 - z1;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((point.getX() - x1) * dx + (point.getZ() - z1) * dz) / lengthSquared;
        double margin = lengthSquared == 0 ? 1 : 0.5 / Math.sqrt(lengthSquared);
        return t >= -margin && t <= 1 + margin;
    }

    /**
     * Whether the ball is in the wall's trigger zone: along the wall, within the wall's height, and between a little
     * behind the wall and the trigger line in front of it.
     */
    boolean inTriggerZone(Location ball, double trigger) {
        if (!alongWall(ball)) return false;
        double front = frontDistance(ball);
        return front <= trigger && front >= -trigger;
    }

    /** Which side of the wall line the point is on, as a distance: positive on one side, negative on the other. */
    double signedWallDistance(Location point) {
        double x1 = wall1.getBlockX() + 0.5;
        double z1 = wall1.getBlockZ() + 0.5;
        double dx = wall2.getBlockX() + 0.5 - x1;
        double dz = wall2.getBlockZ() + 0.5 - z1;
        double length = Math.hypot(dx, dz);
        if (length == 0) return 0;
        return ((point.getX() - x1) * dz - (point.getZ() - z1) * dx) / length;
    }

    /** The wall's unit normal (horizontal) pointing to the positive side, as {x, z}. */
    double[] wallNormal() {
        double dx = wall2.getBlockX() - wall1.getBlockX();
        double dz = wall2.getBlockZ() - wall1.getBlockZ();
        double length = Math.hypot(dx, dz);
        return length == 0 ? new double[] {0, 1} : new double[] {dz / length, -dx / length};
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
