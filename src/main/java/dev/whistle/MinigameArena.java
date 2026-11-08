package dev.whistle;

import org.bukkit.Location;

import java.util.function.Predicate;

/**
 * Where a running minigame is played, as far as a spectator is concerned: the spot spectators are sent to, and a test
 * for whether a location is still inside the arena (spectators can't leave it).
 */
record MinigameArena(Location spot, Predicate<Location> contains) { }
