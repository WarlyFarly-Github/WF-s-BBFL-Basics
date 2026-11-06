package dev.whistle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bedrock (Geyser/Floodgate) helpers. Bedrock players can't click chat components, so invites
 * show them the command to type instead of the [Accept] / [Decline] buttons.
 */
final class BedrockSupport {
    private static Object geyserApi;
    private static Method isBedrockPlayer;
    private static boolean geyserLookedUp;

    private BedrockSupport() {}

    /** True when the player joined through Geyser. Floodgate UUIDs start with zeros; the Geyser API is used as a backup. */
    static boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();
        if (id.getMostSignificantBits() == 0L) return true;
        if (!geyserLookedUp) {
            geyserLookedUp = true;
            try {
                Class<?> api = Class.forName("org.geysermc.geyser.api.GeyserApi");
                geyserApi = api.getMethod("api").invoke(null);
                isBedrockPlayer = api.getMethod("isBedrockPlayer", UUID.class);
            } catch (Throwable ignored) {
                geyserApi = null;
            }
        }
        if (geyserApi == null) return false;
        try {
            return (boolean) isBedrockPlayer.invoke(geyserApi, id);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The end of an invite message. Java players get the clickable [Accept] [Decline] buttons;
     * Bedrock players get the commands to type, e.g. "To respond, use /match accept or /match decline!".
     */
    static Component respondButtons(Player viewer, String command) {
        if (isBedrock(viewer)) {
            return Component.text("To respond, use " + command + " accept or " + command + " decline!",
                    NamedTextColor.YELLOW);
        }
        return Component.textOfChildren(
                Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(command + " accept")),
                Component.text(" "),
                Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand(command + " decline")));
    }
}
