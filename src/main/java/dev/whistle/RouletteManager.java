package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Roulette: a normal 5 minute quickmatch, but every 30 seconds of match time everyone playing is given a random power
 * (replacing the last one). Some help (speed, a knockback stick...) and some hurt (slowness, no jumping...).
 */
final class RouletteManager implements Listener {

    static final int MAX_PLAYERS = 10;
    static final int MINUTES = 5;
    private static final int ROLL_SECONDS = 30;
    private static final double STICK_PUSH = 1.7;
    private static final long STICK_COOLDOWN_MILLIS = 400;

    /** The powers. Good ones are green, bad ones red. */
    enum Power {
        SPEED("Speed Boost", true, "You run much faster."),
        SUPER_JUMP("Super Jump", true, "You jump much higher."),
        KNOCKBACK_STICK("Knockback Stick", true, "Hit a player with the stick to launch them away."),
        POWER_KICK("Power Kick", true, "Your kicks are 60% stronger."),
        SLOWNESS("Slowness", false, "You move slowly."),
        NO_JUMP("Glue Boots", false, "You can't jump."),
        WEAK_KICK("Weak Kick", false, "Your kicks are only half as strong.");

        final String label;
        final boolean good;
        final String description;

        Power(String label, boolean good, String description) {
            this.label = label;
            this.good = good;
            this.description = description;
        }

        NamedTextColor color() {
            return good ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final NamespacedKey stickKey;
    private final Random random = new Random();
    private final Map<UUID, Power> active = new HashMap<>();
    private final Map<UUID, Long> lastStick = new HashMap<>();
    /** Every running Roulette match, and the match-clock second (seconds played) at which the next power is given. */
    private final Map<Match, Integer> nextRoll = new HashMap<>();
    private final java.util.Set<Match> inOvertime = new java.util.HashSet<>(); // golden goal: its clock starts again from 0

    RouletteManager(JavaPlugin plugin, MatchManager matches) {
        this.plugin = plugin;
        this.matches = matches;
        this.stickKey = new NamespacedKey(plugin, "roulette_stick");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** A Roulette match has just started. The first power comes after 30 seconds of play. */
    void register(Match match) {
        nextRoll.put(match, ROLL_SECONDS);
    }

    Power powerOf(Player player) {
        return active.get(player.getUniqueId());
    }

    /** How hard this player's kicks are compared to normal: 1.0 unless a power changes it. */
    double kickMultiplier(Player player) {
        Power power = active.get(player.getUniqueId());
        if (power == Power.POWER_KICK) return 1.6;
        if (power == Power.WEAK_KICK) return 0.5;
        return 1.0;
    }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        for (Iterator<Map.Entry<Match, Integer>> it = nextRoll.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Match, Integer> entry = it.next();
            Match match = entry.getKey();
            if (!matches.isLive(match)) {
                inOvertime.remove(match);
                it.remove(); // the match is over (everyone's powers were already taken off as they left it)
                continue;
            }
            if (!match.started || match.paused) continue;

            if (match.goldenGoal && inOvertime.add(match)) entry.setValue(ROLL_SECONDS); // powers carry on every 30s
            // Match-clock seconds, so a goal freeze or a pause doesn't use up a power's time.
            int played = match.goldenGoal ? 300 - Math.max(0, match.secondsLeft)
                    : match.playSeconds - Math.max(0, match.secondsLeft);
            if (played >= entry.getValue() && match.secondsLeft > 0) {
                roll(match);
                entry.setValue(entry.getValue() + ROLL_SECONDS);
            }
            showStatus(match, entry.getValue() - played);
        }
    }

    /** The power in use and how long until the next one, just above the hotbar. */
    private void showStatus(Match match, int secondsToNext) {
        for (Player p : match.onlineMembers()) {
            Power power = active.get(p.getUniqueId());
            Component bar = power == null
                    ? Component.text("Roulette: next power in " + secondsToNext + "s", NamedTextColor.GRAY)
                    : Component.text(power.label, power.color(), TextDecoration.BOLD)
                            .append(Component.text("  -  next power in " + secondsToNext + "s", NamedTextColor.GRAY));
            p.sendActionBar(bar);
        }
    }

    /** Everyone playing gets a new random power (never the same one twice in a row). */
    private void roll(Match match) {
        for (Player p : match.onlineMembers()) {
            Power previous = active.get(p.getUniqueId());
            Power next;
            do {
                next = Power.values()[random.nextInt(Power.values().length)];
            } while (next == previous && Power.values().length > 1);
            give(p, next);
        }
    }

    // ------------------------------------------------------------------ powers

    private void give(Player player, Power power) {
        clearPowers(player);
        active.put(player.getUniqueId(), power);
        int ticks = (ROLL_SECONDS + 5) * 20; // a little over 30s: the next roll replaces it anyway
        switch (power) {
            case SPEED -> player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, false, true));
            case SUPER_JUMP -> player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 2, false, false, true));
            case SLOWNESS -> player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1, false, false, true));
            case NO_JUMP -> {
                AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
                if (jump != null) jump.setBaseValue(0.0);
            }
            case KNOCKBACK_STICK -> {
                for (ItemStack leftover : player.getInventory().addItem(createStick()).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
            default -> { } // the kick powers are applied when the player kicks the ball
        }

        player.showTitle(Title.title(Component.text("ROULETTE!", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(power.label + (power.good ? "" : " ..."), power.color(), TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(400))));
        player.sendMessage(Component.text("Your power: ", NamedTextColor.GOLD)
                .append(Component.text(power.label, power.color(), TextDecoration.BOLD))
                .append(Component.text(" - " + power.description, NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), power.good ? "block.note_block.chime" : "block.note_block.bass", 1.0f,
                power.good ? 1.4f : 0.7f);
    }

    /** Takes whatever power the player has off them again. Called as a power is replaced and when they leave a match. */
    void clearPowers(Player player) {
        Power power = active.remove(player.getUniqueId());
        if (power == null) return;
        switch (power) {
            case SPEED -> player.removePotionEffect(PotionEffectType.SPEED);
            case SUPER_JUMP -> player.removePotionEffect(PotionEffectType.JUMP_BOOST);
            case SLOWNESS -> player.removePotionEffect(PotionEffectType.SLOWNESS);
            case NO_JUMP -> resetJump(player);
            case KNOCKBACK_STICK -> removeSticks(player);
            default -> { }
        }
    }

    void shutdown() {
        for (UUID id : List.copyOf(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) clearPowers(p);
        }
        active.clear();
        nextRoll.clear();
        inOvertime.clear();
    }

    private void resetJump(Player player) {
        AttributeInstance jump = player.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null && jump.getBaseValue() != jump.getDefaultValue()) jump.setBaseValue(jump.getDefaultValue());
    }

    /** If someone left the server (or it crashed) while they couldn't jump, they get their jump back when they return. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!active.containsKey(event.getPlayer().getUniqueId())) resetJump(event.getPlayer());
    }

    // ------------------------------------------------------------------ the knockback stick

    private ItemStack createStick() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Knockback Stick", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Hit a player to launch them away.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.addEnchant(Enchantment.KNOCKBACK, 2, true); // the glint; the push itself is done in onStickHit
        meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isStick(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(stickKey, PersistentDataType.BYTE);
    }

    private void removeSticks(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isStick(contents[slot])) player.getInventory().setItem(slot, null);
        }
    }

    /** Hitting another player in the match with the stick launches them (no damage). */
    @EventHandler
    public void onStickHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) return;
        if (active.get(attacker.getUniqueId()) != Power.KNOCKBACK_STICK) return;
        if (!isStick(attacker.getInventory().getItemInMainHand())) return;
        if (!matches.inSameMatch(attacker, victim)) return;

        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - lastStick.getOrDefault(attacker.getUniqueId(), 0L) < STICK_COOLDOWN_MILLIS) return;
        lastStick.put(attacker.getUniqueId(), now);

        Vector push = victim.getLocation().toVector().subtract(attacker.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 1.0E-4) push = attacker.getLocation().getDirection().setY(0);
        if (push.lengthSquared() < 1.0E-4) push = new Vector(0, 0, 1);
        victim.setVelocity(push.normalize().multiply(STICK_PUSH).setY(0.5));
        victim.getWorld().playSound(victim.getLocation(), "entity.player.attack.knockback", 1.0f, 1.0f);
    }
}
