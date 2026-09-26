package com.centers25.whitelistplugin;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Referrals implements Listener {
    private static final Pattern NAME = Pattern.compile("^[.-]?[A-Za-z0-9_.-]{1,32}$");
    private static final String ITEM = "minecraft:compass[minecraft:lodestone_tracker={target:{pos:[I;-68,67,-45],dimension:\"minecraft:overworld\"}},minecraft:custom_name={text:\"Friend Ticket\",color:\"gold\",italic:false},minecraft:lore=[{text:\"Redeem at spawn\",color:\"gray\",italic:false}],minecraft:custom_data={friend_ticket:1b}]";
    private final WhitelistPlugin plugin;
    private final Path file;
    private final Gson gson = new Gson();
    private State state;
    private ItemStack ticket;

    Referrals(WhitelistPlugin plugin) {
        this(plugin, plugin.getDataFolder().toPath().resolve("referrals.json"));
    }

    Referrals(WhitelistPlugin plugin, Path file) {
        this.plugin = plugin;
        this.file = file;
        this.state = load();
    }

    synchronized boolean reward(String referrerId, String referrerName, String applicantName) throws IOException {
        if (!valid(referrerName) || !valid(applicantName)) throw new IllegalArgumentException("Invalid reward username.");
        String day = LocalDate.now().toString();
        Map<String, Integer> counts = day.equals(state.day()) ? new HashMap<>(state.counts()) : new HashMap<>();
        if (counts.getOrDefault(referrerId, 0) >= 3) return false;
        counts.merge(referrerId, 1, Integer::sum);
        Map<String, Reward> pending = new HashMap<>(state.pending());
        add(pending, referrerName, 16);
        add(pending, applicantName, 8);
        State next = new State(day, counts, pending);
        save(next);
        state = next;
        schedule(referrerName);
        schedule(applicantName);
        return true;
    }

    void start() {
        if (plugin == null) return;
        ticket = Bukkit.getItemFactory().createItemStack(ITEM);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::deliver));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliver(event.getPlayer());
    }

    static boolean valid(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    synchronized int pending(String name) {
        Reward reward = state.pending().get(key(name));
        return reward == null ? 0 : reward.amount();
    }

    synchronized int count(String referrerId) {
        return LocalDate.now().toString().equals(state.day()) ? state.counts().getOrDefault(referrerId, 0) : 0;
    }

    private void schedule(String name) {
        if (plugin == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getName().equalsIgnoreCase(name))
                .findFirst().ifPresent(this::deliver));
    }

    private void deliver(Player player) {
        int amount;
        synchronized (this) {
            Reward reward = state.pending().get(key(player.getName()));
            amount = reward == null ? 0 : reward.amount();
        }
        if (amount == 0) return;
        int delivered;
        try {
            delivered = add(player, amount);
        } catch (IllegalArgumentException error) {
            plugin.log().error("Could not create friend tickets: " + ErrorMessages.safe(error), error);
            return;
        }
        if (delivered == 0) return;
        synchronized (this) {
            Map<String, Reward> pending = new HashMap<>(state.pending());
            Reward current = pending.get(key(player.getName()));
            if (current == null) return;
            int left = current.amount() - delivered;
            if (left > 0) pending.put(key(player.getName()), new Reward(current.name(), left));
            else pending.remove(key(player.getName()));
            State next = new State(state.day(), new HashMap<>(state.counts()), pending);
            try {
                save(next);
                state = next;
            } catch (IOException error) {
                plugin.log().error("Could not save friend ticket delivery: " + ErrorMessages.safe(error), error);
            }
        }
    }

    private int add(Player player, int amount) {
        int left = amount;
        while (left > 0) {
            ItemStack stack = ticket.clone();
            int size = Math.min(stack.getMaxStackSize(), left);
            stack.setAmount(size);
            int rejected = player.getInventory().addItem(stack).values().stream().mapToInt(ItemStack::getAmount).sum();
            left -= size - rejected;
            if (rejected > 0) break;
        }
        return amount - left;
    }

    private State load() {
        if (!Files.exists(file)) return empty();
        try {
            State loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (loaded == null) return empty();
            return new State(loaded.day() == null ? "" : loaded.day(),
                    loaded.counts() == null ? new HashMap<>() : new HashMap<>(loaded.counts()),
                    loaded.pending() == null ? new HashMap<>() : new HashMap<>(loaded.pending()));
        } catch (Exception error) {
            if (plugin != null) plugin.log().warn("Could not load referrals: " + ErrorMessages.safe(error), error);
            return empty();
        }
    }

    private void save(State value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, gson.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void add(Map<String, Reward> pending, String name, int amount) {
        pending.compute(key(name), (ignored, current) -> new Reward(name, amount + (current == null ? 0 : current.amount())));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static State empty() {
        return new State("", new HashMap<>(), new HashMap<>());
    }

    private record State(String day, Map<String, Integer> counts, Map<String, Reward> pending) { }

    private record Reward(String name, int amount) { }
}
