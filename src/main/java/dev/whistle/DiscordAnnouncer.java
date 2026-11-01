package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Invites the whole Discord server to a match: /match invite server (or the button in the invite menu) posts a message
 * to one Discord channel through a channel webhook, and when that match ends or is cancelled a follow-up is posted.
 * Everything is configured under "server-invite" in this plugin's config.yml. This is independent of BBFL-Utils: it
 * needs no bot token and does not touch that plugin.
 */
final class DiscordAnnouncer {

    private static final String ROOT = "server-invite.";

    private final JavaPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private long lastInvite; // millis, for the cooldown

    DiscordAnnouncer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Posts the server invite to Discord and tells the operator how it went. If the sender is hosting a match, that
     * match is remembered as announced, so Discord is told when it ends or is cancelled.
     */
    void inviteServer(Player sender, Match match) {
        FileConfiguration config = plugin.getConfig();
        String webhook = config.getString(ROOT + "webhook-url", "").trim();
        if (webhook.isEmpty()) {
            error(sender, "The Discord invite isn't set up yet. Put a channel webhook URL in config.yml under "
                    + "server-invite.webhook-url and restart the server.");
            return;
        }
        if (!webhook.startsWith("https://discord.com/api/webhooks/")
                && !webhook.startsWith("https://discordapp.com/api/webhooks/")) {
            error(sender, "server-invite.webhook-url in config.yml is not a Discord webhook URL.");
            return;
        }

        long cooldown = Math.max(0, config.getLong(ROOT + "cooldown-seconds", 60)) * 1000L;
        long wait = lastInvite + cooldown - System.currentTimeMillis();
        if (wait > 0) {
            error(sender, "The server was invited recently. Wait " + ((wait + 999) / 1000) + " more seconds.");
            return;
        }
        lastInvite = System.currentTimeMillis();
        if (match != null) match.serverInvited = true;

        String roleId = config.getString(ROOT + "mention-role-id", "").trim();
        boolean mention = roleId.matches("\\d{5,25}");
        String content = config.getString(ROOT + "message",
                        "{role}**{player}** is hosting a football match! Join **{ip}** to play or watch.")
                .replace("{role}", mention ? "<@&" + roleId + "> " : "")
                .replace("{player}", escapeMarkdown(sender.getName()))
                .replace("{ip}", config.getString(ROOT + "server-address", "bbfl.my.pebble.host"));
        if (content.length() > 2000) content = content.substring(0, 2000);

        // Only the configured role may be pinged: a player name can never trigger @everyone or other mentions.
        String allowed = mention ? "{\"roles\":[\"" + roleId + "\"]}" : "{\"parse\":[]}";
        String body = "{\"content\":\"" + jsonEscape(content) + "\",\"allowed_mentions\":" + allowed + "}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        UUID senderId = sender.getUniqueId();
        // Asynchronous, so a slow Discord never freezes the server. The URL is a secret and is never logged.
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            boolean ok = failure == null && response.statusCode() / 100 == 2;
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not send the server invite to Discord: "
                        + failure.getClass().getSimpleName());
            } else if (!ok) {
                plugin.getLogger().warning("Discord rejected the server invite (HTTP " + response.statusCode() + ").");
            }
            if (!ok) { // a failed post doesn't use up the cooldown, and nothing was announced
                lastInvite = 0;
                if (match != null) match.serverInvited = false;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(senderId);
                if (player == null) return;
                if (ok) player.sendMessage(Component.text("The whole server was invited on Discord.", NamedTextColor.GREEN));
                else error(player, "Discord didn't accept the invite. Check the webhook URL in config.yml (see the console).");
            });
        });
    }

    /**
     * Tells the Discord channel how an announced match finished. The key is "match-ended-message" or
     * "match-cancelled-message"; the values fill the {placeholders} in that message.
     */
    void matchUpdate(String key, Map<String, String> values) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean(ROOT + "announce-match-end", true)) return;
        String webhook = config.getString(ROOT + "webhook-url", "").trim();
        if (!webhook.startsWith("https://discord.com/api/webhooks/")
                && !webhook.startsWith("https://discordapp.com/api/webhooks/")) return;
        String content = config.getString(ROOT + key, "");
        if (content.isBlank()) return;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}", escapeMarkdown(entry.getValue()));
        }
        content = content.replace("{role}", "")
                .replace("{ip}", config.getString(ROOT + "server-address", "bbfl.my.pebble.host"));
        if (content.length() > 2000) content = content.substring(0, 2000);

        String body = "{\"content\":\"" + jsonEscape(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not send the match update to Discord: "
                        + failure.getClass().getSimpleName());
            } else if (response.statusCode() / 100 != 2) {
                plugin.getLogger().warning("Discord rejected the match update (HTTP " + response.statusCode() + ").");
            }
        });
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    /** Stops player names from being formatted as Discord markdown. */
    private static String escapeMarkdown(String text) {
        return text.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    private static String jsonEscape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
