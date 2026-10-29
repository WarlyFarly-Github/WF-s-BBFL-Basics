package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Random;

/** What happens when a minigame ends: the winner gets a title and fireworks, everyone hears the level up sound. */
final class Celebration implements Listener {

    private static final int FIREWORKS = 5;
    private static final long FIREWORK_GAP_TICKS = 6L;

    /** A few colourful, differently shaped bursts. */
    private static final List<FireworkEffect> EFFECTS = List.of(
            FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.RED, Color.ORANGE).withFade(Color.YELLOW).trail(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                    .withColor(Color.LIME, Color.GREEN).withFade(Color.WHITE).flicker(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.CREEPER)
                    .withColor(Color.AQUA, Color.WHITE).withFade(Color.BLUE).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.BURST)
                    .withColor(Color.PURPLE, Color.FUCHSIA).withFade(Color.WHITE).trail(true).flicker(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                    .withColor(Color.YELLOW, Color.ORANGE).withFade(Color.RED).trail(true).build(),
            FireworkEffect.builder().with(FireworkEffect.Type.BALL)
                    .withColor(Color.BLUE, Color.AQUA).withFade(Color.PURPLE).flicker(true).build());

    private final JavaPlugin plugin;
    private final NamespacedKey fireworkKey;
    private final Random random = new Random();

    Celebration(JavaPlugin plugin) {
        this.plugin = plugin;
        this.fireworkKey = new NamespacedKey(plugin, "celebration_firework");
    }

    /**
     * Call when a minigame is over. Everyone given plays the level up sound; the winner (may be null if there is no
     * winner, such as a draw) gets a "You won!" title and fireworks around them.
     */
    void finish(Collection<Player> everyone, Player winner) {
        for (Player p : everyone) {
            if (p.isOnline()) p.playSound(p.getLocation(), "entity.player.levelup", 1.0f, 1.0f);
        }
        if (winner == null || !winner.isOnline()) return;

        winner.showTitle(Title.title(Component.text("You won!", NamedTextColor.GOLD, TextDecoration.BOLD), Component.empty(),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(750))));

        for (int i = 0; i < FIREWORKS; i++) {
            FireworkEffect effect = EFFECTS.get(i % EFFECTS.size());
            Bukkit.getScheduler().runTaskLater(plugin, () -> launch(winner, effect), i * FIREWORK_GAP_TICKS);
        }
    }

    /** One firework bursting a few blocks from the player, at a random spot around them. */
    private void launch(Player winner, FireworkEffect effect) {
        if (!winner.isOnline()) return;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = 2.5 + random.nextDouble() * 2.0;
        Location spot = winner.getLocation().add(Math.cos(angle) * distance, 1.0 + random.nextDouble() * 2.0,
                Math.sin(angle) * distance);

        Firework firework = winner.getWorld().spawn(spot, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(effect);
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            fw.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
        firework.detonate();
    }

    /** These fireworks are only for show: they never hurt anyone. */
    @EventHandler
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(fireworkKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
