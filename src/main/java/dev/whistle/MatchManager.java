package dev.whistle;

import dev.whistle.Match.Team;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SulfurCube;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Handles /match, the invite GUI, invites and the football itself. */
final class MatchManager implements CommandExecutor, TabCompleter, Listener {

    private static final long INVITE_TIMEOUT_MS = 60_000;
    private static final List<String> SUBCOMMANDS =
            List.of("create", "invite", "start", "goal", "end", "leave", "accept", "decline");

    private record Invite(UUID host, Team team, long time) {}

    /** Marks our chest GUI so clicks can be recognised. */
    private static final class InviteGui implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey ballKey;
    private final NamespacedKey targetKey;
    private final Map<UUID, Match> matchByPlayer = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    MatchManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.ballKey = new NamespacedKey(plugin, "football");
        this.targetKey = new NamespacedKey(plugin, "invite_target");
    }

    /** Removes every ball on shutdown so none are left behind. */
    void shutdown() {
        new ArrayList<>(matchByPlayer.values()).forEach(m -> {
            if (m.ball != null) m.ball.remove();
        });
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            error(player, "Usage: /match <create|invite|start|goal|end|leave|accept|decline>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(player);
            case "invite" -> {
                Match match = hostedMatch(player);
                if (match != null) openInviteGui(player);
            }
            case "start" -> start(player);
            case "goal" -> goal(player, args);
            case "end" -> {
                Match match = hostedMatch(player);
                if (match != null) endMatch(match, "The match was ended by the host.");
            }
            case "leave" -> leave(player);
            case "accept" -> accept(player);
            case "decline" -> decline(player);
            default -> error(player, "Unknown subcommand. Use /match <create|invite|start|goal|end|leave|accept|decline>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("goal")) {
            return List.of("red", "blue");
        }
        return List.of();
    }

    private void create(Player host) {
        if (matchByPlayer.containsKey(host.getUniqueId())) {
            error(host, "You are already in a match! Use /match leave first.");
            return;
        }
        Match match = new Match(host.getUniqueId());
        matchByPlayer.put(host.getUniqueId(), match);
        host.sendMessage(Component.text("Match created! You are on the ", NamedTextColor.GREEN)
                .append(Component.text("Red", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" team. Use /match start to kick off.", NamedTextColor.GREEN)));
        openInviteGui(host);
    }

    private void start(Player host) {
        Match match = hostedMatch(host);
        if (match == null) return;

        boolean firstStart = !match.started;
        match.started = true;
        spawnBall(match, host.getLocation());

        if (firstStart) {
            for (Player p : match.onlineMembers()) match.showBar(p);
            match.broadcast(Component.text("The match has started!", NamedTextColor.GREEN, TextDecoration.BOLD));
        } else {
            host.sendMessage(Component.text("The ball was moved to your location.", NamedTextColor.YELLOW));
        }
    }

    private void goal(Player host, String[] args) {
        Match match = hostedMatch(host);
        if (match == null) return;
        if (!match.started) {
            error(host, "The match hasn't started yet.");
            return;
        }
        if (args.length < 2 || !(args[1].equalsIgnoreCase("red") || args[1].equalsIgnoreCase("blue"))) {
            error(host, "Usage: /match goal <red|blue>");
            return;
        }
        Team team = args[1].equalsIgnoreCase("red") ? Team.RED : Team.BLUE;
        match.addGoal(team);
        match.broadcast(Component.text("GOAL for the ", NamedTextColor.YELLOW)
                .append(Component.text(team.label + " team", team.color, TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.YELLOW)));
    }

    private void leave(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You are not in a match.");
            return;
        }
        if (match.host.equals(player.getUniqueId())) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
            return;
        }
        removeMember(match, player);
        player.sendMessage(Component.text("You left the match.", NamedTextColor.YELLOW));
        match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
    }

    private void accept(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || System.currentTimeMillis() - invite.time() > INVITE_TIMEOUT_MS) {
            error(player, "You have no pending invite.");
            return;
        }
        Match match = matchByPlayer.get(invite.host());
        if (match == null || !match.host.equals(invite.host())) {
            error(player, "That match no longer exists.");
            return;
        }
        if (matchByPlayer.containsKey(player.getUniqueId())) {
            error(player, "You are already in a match!");
            return;
        }
        if (match.isFull(invite.team())) {
            error(player, "The " + invite.team().label + " team is full!");
            return;
        }

        match.add(player.getUniqueId(), invite.team());
        matchByPlayer.put(player.getUniqueId(), match);
        if (match.started) match.showBar(player);

        match.broadcast(Component.text(player.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" joined the ", NamedTextColor.GRAY))
                .append(Component.text(invite.team().label + " team", invite.team().color))
                .append(Component.text(" (" + match.size(invite.team()) + "/" + Match.MAX_PER_TEAM + ")", NamedTextColor.GRAY)));
    }

    private void decline(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null) {
            error(player, "You have no pending invite.");
            return;
        }
        player.sendMessage(Component.text("Invite declined.", NamedTextColor.YELLOW));
        Player host = Bukkit.getPlayer(invite.host());
        if (host != null) {
            host.sendMessage(Component.text(player.getName() + " declined your invite.", NamedTextColor.YELLOW));
        }
    }

    /** Returns the match this player hosts, or tells them why not. */
    private Match hostedMatch(Player player) {
        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) {
            error(player, "You don't have a match. Use /match create first.");
            return null;
        }
        if (!match.host.equals(player.getUniqueId())) {
            error(player, "Only the match host can do that.");
            return null;
        }
        return match;
    }

    // ------------------------------------------------------------------ invite GUI

    private void openInviteGui(Player host) {
        List<Player> others = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(host.getUniqueId())) others.add(p);
        }
        if (others.isEmpty()) {
            error(host, "There are no other players online!");
            return;
        }
        if (others.size() > 54) others = others.subList(0, 54);

        int rows = (others.size() - 1) / 9 + 1;
        InviteGui holder = new InviteGui();
        Inventory inventory = Bukkit.createInventory(holder, rows * 9,
                Component.text("Invite players", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;

        for (Player other : others) {
            inventory.addItem(createHead(other));
        }
        host.openInventory(inventory);
    }

    private ItemStack createHead(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(target.getPlayerProfile());
        meta.displayName(Component.text(target.getName(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left-click: invite to ", NamedTextColor.GRAY)
                        .append(Component.text("Red", NamedTextColor.RED))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: invite to ", NamedTextColor.GRAY)
                        .append(Component.text("Blue", NamedTextColor.BLUE))
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING,
                target.getUniqueId().toString());
        head.setItemMeta(meta);
        return head;
    }

    @EventHandler
    public void onInviteClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof InviteGui)) return;
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player host)) return;

        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        String id = item.getItemMeta().getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (id == null) return;

        Player target = Bukkit.getPlayer(UUID.fromString(id));
        if (target == null) {
            error(host, "That player is no longer online.");
            return;
        }
        invite(host, target, event.isRightClick() ? Team.BLUE : Team.RED);
    }

    @EventHandler
    public void onInviteDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InviteGui) {
            event.setCancelled(true);
        }
    }

    private void invite(Player host, Player target, Team team) {
        Match match = matchByPlayer.get(host.getUniqueId());
        if (match == null || !match.host.equals(host.getUniqueId())) return;

        if (matchByPlayer.containsKey(target.getUniqueId())) {
            error(host, target.getName() + " is already in a match.");
            return;
        }
        if (match.isFull(team)) {
            error(host, "The " + team.label + " team is full (" + Match.MAX_PER_TEAM + "/" + Match.MAX_PER_TEAM + ").");
            return;
        }

        invites.put(target.getUniqueId(), new Invite(host.getUniqueId(), team, System.currentTimeMillis()));

        target.sendMessage(Component.text(host.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" invited you to the ", NamedTextColor.GRAY))
                .append(Component.text(team.label + " team", team.color))
                .append(Component.text(" in a football match. ", NamedTextColor.GRAY))
                .append(Component.text("[Accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/match accept")))
                .append(Component.text(" "))
                .append(Component.text("[Decline]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/match decline"))));

        host.sendMessage(Component.text("Invited " + target.getName() + " to the ", NamedTextColor.GREEN)
                .append(Component.text(team.label + " team", team.color))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    // ------------------------------------------------------------------ the ball

    private void spawnBall(Match match, Location location) {
        if (match.ball != null) match.ball.remove();

        match.ball = location.getWorld().spawn(location, SulfurCube.class, cube -> {
            cube.setSize(2);
            cube.setAdult();
            cube.setAgeLock(true);
            cube.customName(Component.text("Football", NamedTextColor.YELLOW, TextDecoration.BOLD));
            cube.setCustomNameVisible(true);
            cube.setGlowing(true);
            cube.setAware(false);              // no AI: it only moves when kicked
            cube.setPersistent(true);
            cube.setRemoveWhenFarAway(false);
            cube.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private boolean isBall(Entity entity) {
        return entity instanceof SulfurCube
                && entity.getPersistentDataContainer().has(ballKey, PersistentDataType.BYTE);
    }

    /** The ball takes no damage; hitting it kicks it in the direction you're facing. */
    @EventHandler
    public void onBallDamage(EntityDamageEvent event) {
        if (!isBall(event.getEntity())) return;
        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player kicker) {
            Vector direction = kicker.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() < 1.0E-4) {
                direction = new Vector(0, 0, 1);
            }
            double power = kicker.isSprinting() ? 1.5 : 1.0;
            Vector velocity = direction.normalize().multiply(power).setY(0.35);
            event.getEntity().setVelocity(velocity);
        }
    }

    /** Stops players bucketing, shearing or feeding the ball. */
    @EventHandler
    public void onBallInteract(PlayerInteractEntityEvent event) {
        if (isBall(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void onBallExplode(ExplosionPrimeEvent event) {
        if (isBall(event.getEntity())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        invites.remove(player.getUniqueId());

        Match match = matchByPlayer.get(player.getUniqueId());
        if (match == null) return;
        if (match.host.equals(player.getUniqueId())) {
            endMatch(match, player.getName() + " (the host) left, so the match was ended.");
        } else {
            removeMember(match, player);
            match.broadcast(Component.text(player.getName() + " left the match.", NamedTextColor.YELLOW));
        }
    }

    private void removeMember(Match match, Player player) {
        match.remove(player.getUniqueId());
        matchByPlayer.remove(player.getUniqueId());
        match.hideBar(player);
    }

    private void endMatch(Match match, String reason) {
        if (match.ball != null) match.ball.remove();
        Component message = Component.text(reason, NamedTextColor.YELLOW);

        for (Player p : match.onlineMembers()) {
            match.hideBar(p);
            p.sendMessage(message);
        }
        for (UUID id : new ArrayList<>(matchByPlayer.keySet())) {
            if (matchByPlayer.get(id) == match) matchByPlayer.remove(id);
        }
        invites.values().removeIf(invite -> invite.host().equals(match.host));
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
