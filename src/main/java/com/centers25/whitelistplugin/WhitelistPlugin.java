package com.centers25.whitelistplugin;

import com.centers25.core.discord.DiscordService;
import com.centers25.core.backup.PluginBackups;
import com.centers25.core.logging.PluginLogger;
import com.centers25.core.logging.PluginLogs;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WhitelistPlugin extends JavaPlugin {
    private ExecutorService pool;
    private DiscordBot bot;
    private DiscordService discord;
    private MicrosoftXboxAuthService auth;
    private GamertagLookupService lookup;
    private OpenRouterParsingService parser;
    private UltraServersWhitelistService ultraServers;
    private Referrals referrals;
    private Set<String> authUsers;
    private Set<String> whitelistUsers;
    private PluginLogger log;

    @Override
    public void onEnable() {
        log = PluginLogs.get(this);
        saveDefaultConfig();
        PluginBackups.get(this).register(this);
        if (!start()) Bukkit.getPluginManager().disablePlugin(this);
    }

    private synchronized boolean start() {
        discord = getServer().getServicesManager().load(DiscordService.class);
        if (discord == null) {
            log.error("plugincore Discord service is unavailable.");
            return false;
        }
        authUsers = new HashSet<>(getConfig().getStringList("auth-allowed-discord-user-ids"));
        whitelistUsers = new HashSet<>(getConfig().getStringList("whitelist-allowed-discord-user-ids"));
        Duration http = Duration.ofSeconds(Math.max(5, getConfig().getLong("http-timeout-seconds", 30)));
        Duration login = Duration.ofSeconds(Math.max(60, getConfig().getLong("device-login-timeout-seconds", 900)));
        Duration ai = Duration.ofSeconds(Math.max(5, getConfig().getLong("openrouter-timeout-seconds", 45)));
        Duration ultraServersTimeout = Duration.ofSeconds(Math.max(5, getConfig().getLong("ultraservers-timeout-seconds", 15)));
        String key = System.getenv("OPENROUTER_API_KEY");
        if (key == null || key.isBlank()) key = getConfig().getString("openrouter-api-key", "");
        String ultraServersKey = System.getenv("ULTRASERVERS_API_KEY");
        if (ultraServersKey == null || ultraServersKey.isBlank()) ultraServersKey = getConfig().getString("ultraservers-api-key", "");
        Path tokens = getDataFolder().toPath().resolve("auth").resolve("tokens.json");

        pool = Executors.newVirtualThreadPerTaskExecutor();
        auth = new MicrosoftXboxAuthService(getConfig().getString("microsoft-client-id", "00000000441cc96b"), tokens, http, login);
        lookup = new GamertagLookupService(auth, http);
        parser = new OpenRouterParsingService(key, getConfig().getString("openrouter-model", "openai/gpt-5.6-luna:nitro"), ai);
        ultraServers = new UltraServersWhitelistService(
                getConfig().getString("ultraservers-api-base", "https://panel.ultraservers.com"),
                getConfig().getString("ultraservers-server-id", ""), ultraServersKey, ultraServersTimeout);
        referrals = new Referrals(this);
        getServer().getPluginManager().registerEvents(referrals, this);
        referrals.start();
        bot = new DiscordBot(this, pool, auth, lookup, parser, ultraServers, referrals);
        discord.register(this, bot, bot.commands());
        log.info("Registered Discord commands with plugincore.");
        log.debug("Whitelist services started; automatic review=" + autoEnabled()
                + ", allowed channel count=" + whitelistChannels().size() + ".");
        return true;
    }

    @Override
    public void onDisable() {
        stop();
        PluginBackups.get(this).unregister(this);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equals("whitelistpluginreload")) return false;
        if (!sender.hasPermission("whitelistplugin.reload")) {
            sender.sendMessage("You do not have permission to reload whitelistplugin.");
            return true;
        }
        try {
            restart();
            sender.sendMessage("whitelistplugin reloaded.");
        } catch (RuntimeException error) {
            sender.sendMessage("whitelistplugin reload failed: " + ErrorMessages.safe(error));
        }
        return true;
    }

    synchronized void restart() {
        log.debug("Restarting whitelist services.");
        stop();
        reloadConfig();
        if (!start()) {
            Bukkit.getPluginManager().disablePlugin(this);
            throw new IllegalStateException("plugincore Discord service is unavailable.");
        }
    }

    private synchronized void stop() {
        if (log != null) log.debug("Stopping whitelist services.");
        if (discord != null) discord.unregister(this);
        if (pool != null) pool.shutdownNow();
        if (referrals != null) HandlerList.unregisterAll(referrals);
        pool = null;
        bot = null;
        discord = null;
        referrals = null;
    }

    Set<String> authUsers() {
        return Set.copyOf(authUsers);
    }

    Set<String> whitelistUsers() {
        return Set.copyOf(whitelistUsers);
    }

    DiscordService discord() {
        return discord;
    }

    PluginLogger log() {
        return log;
    }

    String roleId() {
        return getConfig().getString("whitelist-role-id", "");
    }

    String logChannelId() {
        return getConfig().getString("whitelist-log-channel-id", "");
    }

    Set<String> whitelistChannels() {
        return Set.copyOf(getConfig().getStringList("whitelist-channel-ids"));
    }

    boolean autoEnabled() {
        return getConfig().getBoolean("autowhitelist-enabled", false);
    }

    String autoForumId() {
        return getConfig().getString("autowhitelist-forum-id", "");
    }

    int scanLimit() {
        return Math.clamp(getConfig().getInt("scan-message-limit", 50), 1, 100);
    }

    int maxMessage() {
        return Math.clamp(getConfig().getInt("scan-max-message-chars", 1500), 100, 4000);
    }

    int maxInput() {
        return Math.clamp(getConfig().getInt("scan-max-input-chars", 8000), 1000, 30000);
    }

    synchronized void targets(String roleId, String channelId) {
        if (roleId != null) getConfig().set("whitelist-role-id", roleId);
        if (channelId != null) getConfig().set("whitelist-log-channel-id", channelId);
        saveConfig();
    }

    synchronized void whitelistChannel(String channelId, boolean enabled) {
        Set<String> channels = new HashSet<>(getConfig().getStringList("whitelist-channel-ids"));
        if (enabled) channels.add(channelId);
        else channels.remove(channelId);
        getConfig().set("whitelist-channel-ids", channels.stream().sorted().toList());
        saveConfig();
    }

    synchronized void auto(boolean enabled, String forumId) {
        getConfig().set("autowhitelist-enabled", enabled);
        if (forumId != null) getConfig().set("autowhitelist-forum-id", forumId);
        saveConfig();
    }

    static String safe(String input) {
        if (input == null) return "unknown";
        return input.replace("`", "'").replace("@", "＠");
    }
}
