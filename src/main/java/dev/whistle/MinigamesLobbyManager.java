package dev.whistle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The minigames lobby: /lobby minigames takes you to the lobby an operator set with /setlobby minigames (up to 10
 * players), where a football is always there to kick around. When 2 players are waiting, a vote starts: three random
 * challenges (any except Duels, which is only played through /challenge) are put in chat and the hotbar items 1, 2 and
 * 3 vote for them. The winner starts with everyone who is
 * waiting. After a game you are back in the lobby, ready for the next vote.
 */
final class MinigamesLobbyManager implements CommandExecutor, TabCompleter, Listener {

    static final int MAX_PLAYERS = 10;
    private static final int VOTE_SECONDS = 30;
    private static final int RETRY_SECONDS = 10;         // wait this long before voting again after a failed start
    private static final int[] VOTE_SLOTS = {3, 4, 5};   // the items 1, 2 and 3
    private static final int LEAVE_SLOT = 7;            // the 9th slot belongs to the navigator

    /** One player in the lobby and what to give back to them when they leave it. */
    private static final class Member {
        private final Location origin;
        private final GameMode savedMode;
        private final ItemStack savedLeave;
        private final ItemStack[] savedVote = new ItemStack[3];
        private boolean hasVoteItems;
        private int vote = -1;

        private Member(Location origin, GameMode savedMode, ItemStack savedLeave) {
            this.origin = origin;
            this.savedMode = savedMode;
            this.savedLeave = savedLeave;
        }
    }

    /** The vote in progress. */
    private static final class Vote {
        private final List<ChallengeManager.Challenge> options;
        private int secondsLeft = VOTE_SECONDS;
        private final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

        private Vote(List<ChallengeManager.Challenge> options) {
            this.options = options;
        }
    }

    private final JavaPlugin plugin;
    private final MatchManager matches;
    private final ChallengeManager challenges;
    private final LobbyManager lobbies;
    private final NamespacedKey itemKey;
    private final Random random = new Random();
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final LobbyBall ball;
    private Vote vote;
    private int cooldown; // seconds before another vote may start

    MinigamesLobbyManager(JavaPlugin plugin, MatchManager matches, ChallengeManager challenges, LobbyManager lobbies) {
        this.plugin = plugin;
        this.matches = matches;
        this.challenges = challenges;
        this.lobbies = lobbies;
        this.itemKey = new NamespacedKey(plugin, "minigames_lobby_item");
        this.ball = new LobbyBall(plugin, matches, () -> lobbies.get(LobbyManager.MINIGAMES), members::size);
        matches.addLobbyBall(ball);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    boolean isInLobby(UUID id) {
        return members.containsKey(id);
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(members.keySet())) leave(id, true);
    }

    // ------------------------------------------------------------------ the command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use lobbies.", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "minigames" -> join(player);
            case "leave" -> {
                if (!members.containsKey(player.getUniqueId())) error(player, "You aren't in the minigames lobby.");
                else if (matches.isInMatch(player.getUniqueId())) error(player, "Finish your game first (/match leave).");
                else leave(player.getUniqueId(), true);
            }
            default -> error(player, "Usage: /lobby minigames   (and /lobby leave to go back)");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("minigames", "leave").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }

    private void join(Player player) {
        UUID id = player.getUniqueId();
        if (members.containsKey(id)) {
            error(player, "You are already in the minigames lobby. Use /lobby leave or the red concrete to go back.");
            return;
        }
        Location lobby = lobbies.get(LobbyManager.MINIGAMES);
        if (lobby == null) {
            error(player, "The minigames lobby hasn't been set up yet.");
            return;
        }
        if (members.size() >= MAX_PLAYERS) {
            error(player, "The minigames lobby is full (" + MAX_PLAYERS + "/" + MAX_PLAYERS + ").");
            return;
        }
        if (matches.isInMatch(id) || matches.isWatching(id)) {
            error(player, "Leave your match first (or stop watching).");
            return;
        }

        ItemStack savedLeave = player.getInventory().getItem(LEAVE_SLOT);
        members.put(id, new Member(player.getLocation().clone(), player.getGameMode(),
                savedLeave == null ? null : savedLeave.clone()));
        player.teleport(lobby);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().setItem(LEAVE_SLOT, leaveItem());

        Component joined = Component.text(player.getName() + " joined the minigames lobby (" + members.size() + "/" + MAX_PLAYERS + ").",
                NamedTextColor.GREEN);
        for (UUID other : members.keySet()) {
            Player p = Bukkit.getPlayer(other);
            if (p != null) p.sendMessage(joined);
        }
        player.sendMessage(Component.text("Kick the football around while you wait. With 2 players a vote for the next game starts.",
                NamedTextColor.GRAY));
        ball.refresh(); // a football as soon as someone is here
        if (vote != null) showVote(player); // joined during a vote: same message and items as everyone else
    }

    /** Takes a player out of the lobby, back where they came from. */
    private void leave(UUID id, boolean home) {
        Member member = members.remove(id);
        Player player = Bukkit.getPlayer(id);
        if (member == null || player == null) return;
        removeVoteItems(player, member);
        if (vote != null) player.hideBossBar(vote.bar);
        player.getInventory().setItem(LEAVE_SLOT, member.savedLeave);
        player.setGameMode(member.savedMode);
        if (home) player.teleport(member.origin);
        if (home) player.sendMessage(Component.text("You left the minigames lobby.", NamedTextColor.YELLOW));
        ball.refresh();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (members.containsKey(id)) leave(id, true); // otherwise they'd log back in standing in the lobby
    }

    // ------------------------------------------------------------------ the vote

    /** Everyone in the lobby who is not in a game right now. */
    private List<Player> waiting() {
        List<Player> list = new ArrayList<>();
        for (UUID id : members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && !matches.isInMatch(id)) list.add(p);
        }
        return list;
    }

    private void tick() {
        ball.refresh();
        if (cooldown > 0) cooldown--;
        List<Player> waiting = waiting();

        if (vote == null) {
            if (waiting.size() >= 2 && cooldown == 0) startVote(waiting);
            return;
        }
        if (waiting.size() < 2) {
            cancelVote("The vote was cancelled: not enough players are waiting.");
            return;
        }
        vote.secondsLeft--;
        boolean everyoneVoted = waiting.stream().allMatch(p -> members.get(p.getUniqueId()).vote >= 0);
        if (vote.secondsLeft <= 0 || everyoneVoted) {
            finishVote(waiting);
            return;
        }
        updateBar(waiting);
    }

    /** Three random challenges for the people waiting. Duels is only ever played through /challenge, never from here. */
    private void startVote(List<Player> waiting) {
        List<ChallengeManager.Challenge> eligible = new ArrayList<>();
        for (ChallengeManager.Challenge game : ChallengeManager.Challenge.values()) {
            if (game == ChallengeManager.Challenge.DUELS) continue;
            eligible.add(game);
        }
        Collections.shuffle(eligible, random);
        vote = new Vote(new ArrayList<>(eligible.subList(0, Math.min(3, eligible.size()))));
        for (Member member : members.values()) member.vote = -1;
        for (Player p : waiting) showVote(p);
        updateBar(waiting);
    }

    /** The vote message in chat, the bar, and the hotbar items 1, 2 and 3. */
    private void showVote(Player player) {
        Member member = members.get(player.getUniqueId());
        if (member == null || vote == null) return;
        Component message = Component.text("Please vote for one of the following.", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.newline()).append(Component.newline());
        for (int i = 0; i < vote.options.size(); i++) {
            message = message.append(Component.text((i + 1) + ". ", NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(vote.options.get(i).label, NamedTextColor.WHITE)).append(Component.newline());
        }
        message = message.append(Component.newline())
                .append(Component.text("Use the items in your hotbar to vote.", NamedTextColor.GRAY));
        player.sendMessage(message);

        if (!member.hasVoteItems) {
            for (int i = 0; i < VOTE_SLOTS.length; i++) {
                ItemStack old = player.getInventory().getItem(VOTE_SLOTS[i]);
                member.savedVote[i] = old == null ? null : old.clone();
            }
            member.hasVoteItems = true;
        }
        for (int i = 0; i < vote.options.size(); i++) {
            player.getInventory().setItem(VOTE_SLOTS[i], voteItem(i, vote.options.get(i)));
        }
        player.showBossBar(vote.bar);
    }

    private void updateBar(List<Player> waiting) {
        int[] counts = new int[3];
        for (Player p : waiting) {
            int v = members.get(p.getUniqueId()).vote;
            if (v >= 0 && v < 3) counts[v]++;
        }
        Component name = Component.text("Vote - ", NamedTextColor.GOLD, TextDecoration.BOLD);
        for (int i = 0; i < vote.options.size(); i++) {
            name = name.append(Component.text((i + 1) + ": " + counts[i] + "  ", NamedTextColor.WHITE, TextDecoration.BOLD));
        }
        name = name.append(Component.text("(" + Math.max(0, vote.secondsLeft) + "s)", NamedTextColor.GRAY));
        vote.bar.name(name);
        vote.bar.progress(Math.max(0f, Math.min(1f, vote.secondsLeft / (float) VOTE_SECONDS)));
    }

    private void removeVoteItems(Player player, Member member) {
        if (!member.hasVoteItems) return;
        for (int i = 0; i < VOTE_SLOTS.length; i++) player.getInventory().setItem(VOTE_SLOTS[i], member.savedVote[i]);
        member.hasVoteItems = false;
    }

    private void endVote() {
        for (UUID id : members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            Member member = members.get(id);
            member.vote = -1;
            if (p == null) continue;
            removeVoteItems(p, member);
            p.hideBossBar(vote.bar);
        }
        vote = null;
    }

    private void cancelVote(String message) {
        List<Player> everyone = new ArrayList<>();
        for (UUID id : members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) everyone.add(p);
        }
        endVote();
        everyone.forEach(p -> p.sendMessage(Component.text(message, NamedTextColor.YELLOW)));
    }

    /** Counts the votes (ties are broken at random) and starts the winner with everyone who is waiting. */
    private void finishVote(List<Player> waiting) {
        List<ChallengeManager.Challenge> options = vote.options;
        int[] counts = new int[options.size()];
        for (Player p : waiting) {
            int v = members.get(p.getUniqueId()).vote;
            if (v >= 0 && v < counts.length) counts[v]++;
        }
        endVote();

        // Best first, ties shuffled. Take the first one that can start with this many players.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) order.add(i);
        Collections.shuffle(order, random);
        order.sort((a, b) -> Integer.compare(counts[b], counts[a]));

        for (int index : order) {
            ChallengeManager.Challenge game = options.get(index);
            List<Player> players = new ArrayList<>(waiting.subList(0, Math.min(waiting.size(), ChallengeManager.maxPlayers(game))));
            String problem = challenges.launch(game, players);
            if (problem == null) {
                Component started = Component.text(game.label + " won the vote and is starting!", NamedTextColor.GREEN, TextDecoration.BOLD);
                players.forEach(p -> p.sendMessage(started));
                return;
            }
            waiting.forEach(p -> p.sendMessage(Component.text(game.label + " couldn't start: " + problem, NamedTextColor.RED)));
        }
        cooldown = RETRY_SECONDS;
        waiting.forEach(p -> p.sendMessage(Component.text("Nothing could be started. Another vote will start shortly.",
                NamedTextColor.YELLOW)));
    }

    // ------------------------------------------------------------------ the hotbar items

    private ItemStack voteItem(int index, ChallengeManager.Challenge game) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(String.valueOf(index + 1), NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click to vote for " + game.label, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "vote:" + index);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack leaveItem() {
        ItemStack item = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Leave Lobby", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click to go back to where you were.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, "leave");
        item.setItemMeta(meta);
        return item;
    }

    private String itemAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String action = itemAction(event.getItem());
        if (action == null) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Member member = members.get(id);
        if (member == null) {
            player.getInventory().setItem(event.getHand(), null); // a left-over item from a lobby that is long gone
            return;
        }
        if (action.equals("leave")) {
            if (matches.isInMatch(id)) error(player, "Finish your game first (/match leave).");
            else leave(id, true);
            return;
        }
        if (!action.startsWith("vote:") || vote == null || matches.isInMatch(id)) return;
        int index = Integer.parseInt(action.substring("vote:".length()));
        if (index < 0 || index >= vote.options.size()) return;
        member.vote = index;
        player.sendMessage(Component.text("You voted for " + vote.options.get(index).label + ".", NamedTextColor.GREEN));
        player.playSound(player.getLocation(), "block.note_block.pling", 1.0f, 1.5f);
        updateBar(waiting());
    }

    /** The lobby items stay in the hotbar while you are in the lobby. */
    @EventHandler
    public void onItemMove(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !members.containsKey(player.getUniqueId())) return;
        if (itemAction(event.getCurrentItem()) != null || itemAction(event.getCursor()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (itemAction(event.getItemDrop().getItemStack()) != null) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (itemAction(event.getMainHandItem()) != null || itemAction(event.getOffHandItem()) != null) event.setCancelled(true);
    }

    /** Nothing in the lobby hurts or starves you. */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && members.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && members.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    private void error(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
