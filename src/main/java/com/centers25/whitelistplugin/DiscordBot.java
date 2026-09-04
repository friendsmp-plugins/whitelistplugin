package com.centers25.whitelistplugin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.attribute.IPostContainer;
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

final class DiscordBot extends ListenerAdapter {
    private final WhitelistPlugin plugin;
    private final ExecutorService pool;
    private final MicrosoftXboxAuthService auth;
    private final GamertagLookupService lookup;
    private final OpenRouterParsingService parser;
    private final CraftlandsWhitelistService craftlands;
    private final Referrals referrals;
    private final AtomicBoolean authBusy = new AtomicBoolean();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private JDA jda;
    private String guildId;

    DiscordBot(WhitelistPlugin plugin, ExecutorService pool, MicrosoftXboxAuthService auth,
               GamertagLookupService lookup, OpenRouterParsingService parser,
               CraftlandsWhitelistService craftlands, Referrals referrals) {
        this.plugin = plugin;
        this.pool = pool;
        this.auth = auth;
        this.lookup = lookup;
        this.parser = parser;
        this.craftlands = craftlands;
        this.referrals = referrals;
    }

    void start(String token, String guildId) {
        this.guildId = guildId;
        jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(this)
                .build();
    }

    void stop() {
        if (jda == null) return;
        jda.shutdown();
        try {
            if (!jda.awaitShutdown(Duration.ofSeconds(5))) {
                jda.shutdownNow();
                jda.awaitShutdown(Duration.ofSeconds(5));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            jda.shutdownNow();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || !event.getGuild().getId().equals(guildId)) return;
        if (event.getName().equals("auth")) defer(event, hook -> runAuth(event, hook));
        if (event.getName().equals("whitelist")) defer(event, hook -> runWhitelist(event, hook));
        if (event.getName().equals("manualwhitelist")) defer(event, hook -> runManual(event, hook));
        if (event.getName().equals("autowhitelist")) defer(event, hook -> runAutoConfig(event, hook));
        if (event.getName().equals("whitelistreload")) defer(event, hook -> runReload(event, hook));
    }

    @Override
    public void onReady(ReadyEvent event) {
        Guild guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            plugin.getLogger().severe("The Discord bot cannot access the configured guild.");
            return;
        }
        register(guild);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (event.getGuild().getId().equals(guildId)) register(event.getGuild());
    }

    private void register(Guild guild) {
        OptionData role = new OptionData(OptionType.ROLE, "role", "Save the role granted after whitelisting");
        OptionData log = new OptionData(OptionType.CHANNEL, "log_channel", "Save the channel where log threads are created");
        OptionData allowed = new OptionData(OptionType.CHANNEL, "allowed_channel", "Add or remove a channel or forum for /whitelist");
        OptionData channelEnabled = new OptionData(OptionType.BOOLEAN, "channel_enabled", "Add or remove the allowed channel");
        OptionData enabled = new OptionData(OptionType.BOOLEAN, "enabled", "Enable or disable automatic whitelisting", true);
        OptionData forum = new OptionData(OptionType.CHANNEL, "forum", "Forum whose new posts are reviewed");
        OptionData applicant = new OptionData(OptionType.USER, "discord", "Discord member being whitelisted", true);
        OptionData username = new OptionData(OptionType.STRING, "username", "Minecraft username or gamertag", true);
        OptionData platform = new OptionData(OptionType.STRING, "platform", "Minecraft platform", true)
                .addChoice("Regular/Premium Java", OpenRouterParsingService.Platform.REGULAR_JAVA.name())
                .addChoice("Cracked Java", OpenRouterParsingService.Platform.CRACKED_JAVA.name())
                .addChoice("Bedrock", OpenRouterParsingService.Platform.BEDROCK.name());
        OptionData referrer = new OptionData(OptionType.USER, "referrer", "Optional Discord referrer");
        OptionData referrerName = new OptionData(OptionType.STRING, "referrer_username", "Optional referrer Minecraft username");
        guild.updateCommands().addCommands(
                Commands.slash("auth", "Authenticate the Bedrock lookup account"),
                Commands.slash("whitelist", "Scan this channel or thread and whitelist the applicant")
                        .addOptions(role, log, allowed, channelEnabled),
                Commands.slash("manualwhitelist", "Whitelist an applicant with manually supplied details")
                        .addOptions(applicant, username, platform, referrer, referrerName),
                Commands.slash("autowhitelist", "Configure automatic forum whitelisting").addOptions(enabled, forum),
                Commands.slash("whitelistreload", "Reload whitelistplugin configuration and Discord bot")
        ).queue(commands -> plugin.getLogger().info("Registered 5 commands in the configured guild."),
                error -> plugin.getLogger().severe("Could not register Discord commands: " + ErrorMessages.safe(error)));
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        if (!(event.getChannel() instanceof ThreadChannel thread) || !plugin.autoEnabled()) return;
        if (!thread.getGuild().getId().equals(guildId)) return;
        if (!thread.getParentChannel().getId().equals(plugin.autoForumId())) return;
        if (plugin.autoForumId().equals(plugin.logChannelId())) {
            plugin.getLogger().warning("Autowhitelist forum cannot also be the log forum.");
            return;
        }
        pool.execute(() -> runAuto(thread));
    }

    private void defer(SlashCommandInteractionEvent event, Task task) {
        event.deferReply(true).queue(hook -> pool.execute(() -> task.run(hook)));
    }

    private void runAuth(SlashCommandInteractionEvent event, InteractionHook hook) {
        if (!allowed(event, plugin.authUsers(), Permission.ADMINISTRATOR)) {
            edit(hook, "You are not allowed to authenticate the lookup account.");
            return;
        }
        if (!authBusy.compareAndSet(false, true)) {
            edit(hook, "An authentication is already in progress.");
            return;
        }
        try {
            MicrosoftXboxAuthService.DeviceCode code = auth.beginDeviceLogin();
            edit(hook, "**Code:** `" + WhitelistPlugin.safe(code.userCode()) + "`\n**Link:** https://microsoft.com/link?otc=" + code.userCode());
            auth.completeDeviceLogin(code);
            edit(hook, "Authentication complete.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            edit(hook, "Authentication cancelled.");
        } catch (Exception error) {
            fail(hook, "Authentication", error);
        } finally {
            authBusy.set(false);
        }
    }

    private void runWhitelist(SlashCommandInteractionEvent event, InteractionHook hook) {
        if (event.getGuild() == null || event.getMember() == null) {
            edit(hook, "This command only works in a server channel or thread.");
            return;
        }
        OptionMapping roleOption = event.getOption("role");
        OptionMapping logOption = event.getOption("log_channel");
        OptionMapping allowedOption = event.getOption("allowed_channel");
        OptionMapping enabledOption = event.getOption("channel_enabled");
        if (roleOption != null || logOption != null || allowedOption != null || enabledOption != null) {
            configure(event, hook, roleOption, logOption, allowedOption, enabledOption);
            return;
        }
        if (!allowed(event, plugin.whitelistUsers(), Permission.MANAGE_ROLES)) {
            edit(hook, "You are not allowed to run the whitelist command.");
            return;
        }
        if (!listed(plugin.whitelistChannels(), event.getChannel())) {
            edit(hook, "The whitelist command is not enabled in this channel.");
            return;
        }
        String channelId = event.getChannel().getId();
        if (!busy.add(channelId)) {
            edit(hook, "A whitelist scan is already running in this channel.");
            return;
        }
        try {
            edit(hook, process(event.getGuild(), event.getChannel(), message -> hook.editOriginal(message).complete()));
        } catch (Exception error) {
            fail(hook, "Whitelist", error);
        } finally {
            busy.remove(channelId);
        }
    }

    private void runManual(SlashCommandInteractionEvent event, InteractionHook hook) {
        if (event.getGuild() == null || event.getMember() == null) {
            edit(hook, "This command only works in a server.");
            return;
        }
        if (!allowed(event, plugin.whitelistUsers(), Permission.MANAGE_ROLES)) {
            edit(hook, "You are not allowed to run the manual whitelist command.");
            return;
        }
        OptionMapping referrerOption = event.getOption("referrer");
        OptionMapping referrerNameOption = event.getOption("referrer_username");
        if ((referrerOption == null) != (referrerNameOption == null)) {
            edit(hook, "Missing referral info");
            return;
        }
        User applicant = event.getOption("discord").getAsUser();
        String key = "manual:" + applicant.getId();
        if (!busy.add(key)) {
            edit(hook, "A whitelist request is already running for this user.");
            return;
        }
        try {
            User referrer = referrerOption == null ? null : referrerOption.getAsUser();
            OpenRouterParsingService.App app = new OpenRouterParsingService.App(
                    event.getOption("username").getAsString(),
                    OpenRouterParsingService.Platform.valueOf(event.getOption("platform").getAsString()),
                    applicant.getId(),
                    referrer == null ? "" : referrer.getName(),
                    referrer == null ? "" : referrer.getId(),
                    referrerNameOption == null ? "" : referrerNameOption.getAsString());
            edit(hook, apply(event.getGuild(), app, ignored -> { }));
        } catch (Exception error) {
            fail(hook, "Manual whitelist", error);
        } finally {
            busy.remove(key);
        }
    }

    private void runReload(SlashCommandInteractionEvent event, InteractionHook hook) {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            edit(hook, "Administrator permission is required.");
            return;
        }
        edit(hook, "Reloading whitelistplugin...");
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                plugin.restart();
            } catch (RuntimeException error) {
                plugin.getLogger().severe("Reload failed: " + ErrorMessages.safe(error));
            }
        });
    }

    private void runAutoConfig(SlashCommandInteractionEvent event, InteractionHook hook) {
        if (event.getGuild() == null || event.getMember() == null
                || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            edit(hook, "Administrator permission is required.");
            return;
        }
        boolean enabled = event.getOption("enabled").getAsBoolean();
        OptionMapping forumOption = event.getOption("forum");
        String forumId = forumOption == null ? plugin.autoForumId() : forumOption.getAsChannel().getId();
        ForumChannel forum = forumId.isBlank() ? null : event.getGuild().getForumChannelById(forumId);
        if (enabled && forum == null) {
            edit(hook, "Select a forum when enabling autowhitelist.");
            return;
        }
        if (enabled && forumId.equals(plugin.logChannelId())) {
            edit(hook, "The autowhitelist forum and log forum must be different.");
            return;
        }
        if (forumOption != null && forum == null) {
            edit(hook, "The selected channel must be a forum.");
            return;
        }
        plugin.auto(enabled, forumOption == null ? null : forumId);
        edit(hook, enabled ? "Autowhitelist enabled." : "Autowhitelist disabled.");
    }

    private void runAuto(ThreadChannel thread) {
        String id = thread.getId();
        if (!busy.add(id)) return;
        try {
            Thread.sleep(1500);
            String result = process(thread.getGuild(), thread, ignored -> { });
            plugin.getLogger().info("Autowhitelist " + thread.getName() + ": " + result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            plugin.getLogger().warning("Autowhitelist failed for " + thread.getName() + ": " + ErrorMessages.safe(error));
        } finally {
            busy.remove(id);
        }
    }

    private void configure(SlashCommandInteractionEvent event, InteractionHook hook,
                           OptionMapping roleOption, OptionMapping logOption,
                           OptionMapping allowedOption, OptionMapping enabledOption) {
        if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
            edit(hook, "Administrator permission is required to change whitelist configuration.");
            return;
        }
        if (enabledOption != null && allowedOption == null) {
            edit(hook, "Select an allowed channel when using channel_enabled.");
            return;
        }
        String roleId = roleOption == null ? null : roleOption.getAsRole().getId();
        String channelId = null;
        if (logOption != null) {
            GuildChannel channel = logOption.getAsChannel();
            if (!(channel instanceof StandardGuildMessageChannel) && !(channel instanceof IPostContainer)) {
                edit(hook, "The log channel must be a forum, media, text, or announcement channel.");
                return;
            }
            if (plugin.autoEnabled() && channel.getId().equals(plugin.autoForumId())) {
                edit(hook, "The log forum and autowhitelist forum must be different.");
                return;
            }
            channelId = channel.getId();
        }
        if (allowedOption != null) {
            GuildChannel channel = allowedOption.getAsChannel();
            if (!(channel instanceof MessageChannel) && !(channel instanceof IPostContainer)) {
                edit(hook, "The allowed channel must be a forum, media, text, announcement, or thread channel.");
                return;
            }
            plugin.whitelistChannel(channel.getId(), enabledOption == null || enabledOption.getAsBoolean());
        }
        plugin.targets(roleId, channelId);
        edit(hook, "Whitelist configuration saved.");
    }

    private String process(Guild guild, MessageChannel channel, Progress progress) throws Exception {
        progress.show("[1/4] Parsing channel");
        List<Message> messages = channel.getHistory().retrievePast(plugin.scanLimit()).complete();
        ChatScan.Scan scan = ChatScan.build(items(messages), plugin.maxMessage(), plugin.maxInput());
        if (scan.empty()) return "Missing important info";

        progress.show("[2/4] AI is handling this...");
        OpenRouterParsingService.App app;
        try {
            app = parser.parse(scan.transcript());
        } catch (HttpTimeoutException error) {
            progress.show("[2/4] AI timed out, retrying...");
            app = parser.parse(scan.transcript());
        }
        if (!app.complete(scan.users())) return "Missing important info";
        if (app.incompleteReferral(scan.users())) return "Missing referral info";

        return apply(guild, app, progress);
    }

    private String apply(Guild guild, OpenRouterParsingService.App app, Progress progress) throws Exception {
        Role role = guild.getRoleById(plugin.roleId());
        GuildChannel rawLog = guild.getGuildChannelById(plugin.logChannelId());
        if (role == null || !(rawLog instanceof IThreadContainer)) {
            return "Configure a valid role and log channel with `/whitelist role:... log_channel:...` first.";
        }
        if (!guild.getSelfMember().canInteract(role)) {
            return "The bot role must be above the configured whitelist role.";
        }

        progress.show("[3/4] Whitelisting this user...");
        Member member = guild.retrieveMemberById(app.userId()).complete();
        Checked checked = check(app);
        if (!checked.valid()) return "False";
        Referral referral = referral(guild, member, role, app, checked.name());
        craftlands.add(checked.name());
        if (referral != null) referrals.reward(referral.userId(), referral.name(), checked.name());
        if (!member.getRoles().contains(role)) guild.addRoleToMember(member, role).complete();

        progress.show("[4/4] Logging user");
        log(member, rawLog, app, checked.name());
        dm(member);
        return "True - `" + WhitelistPlugin.safe(checked.name()) + "`";
    }

    static boolean listed(Set<String> channels, MessageChannel channel) {
        String parent = channel instanceof ThreadChannel thread ? thread.getParentChannel().getId() : "";
        return listed(channels, channel.getId(), parent);
    }

    static boolean listed(Set<String> channels, String channel, String parent) {
        return channels.contains(channel) || !parent.isBlank() && channels.contains(parent);
    }

    private Checked check(OpenRouterParsingService.App app) throws Exception {
        if (app.cracked()) {
            String name = app.name().trim();
            if (name.startsWith("-")) name = name.substring(1);
            if (!GamertagLookupService.validCracked(name)) return new Checked(false, "");
            return new Checked(true, GamertagLookupService.crackedName(name));
        }
        GamertagLookupService.LookupResult result = lookup.lookup(app.name(), app.edition());
        return new Checked(result.exists(), result.exists() ? result.name() : "");
    }

    private Referral referral(Guild guild, Member applicant, Role role, OpenRouterParsingService.App app,
                              String applicantName) throws Exception {
        if (!app.referral() || applicant.getRoles().contains(role)) return null;
        Member referrer = referrer(guild, app);
        if (referrer == null || referrer.getId().equals(applicant.getId())) return null;
        if (!referrer.getRoles().contains(role)) return null;
        Checked checked = checkReferral(app.referralName());
        if (!checked.valid() || checked.name().equalsIgnoreCase(applicantName)) return null;
        return new Referral(referrer.getId(), checked.name());
    }

    private Member referrer(Guild guild, OpenRouterParsingService.App app) {
        String id = app.referralUserId();
        String raw = app.referralDiscord().trim();
        if (id.isBlank()) {
            java.util.regex.Matcher mention = java.util.regex.Pattern.compile("^<@!?(\\d{17,20})>$").matcher(raw);
            if (mention.matches()) id = mention.group(1);
            else if (raw.matches("\\d{17,20}")) id = raw;
        }
        if (!id.isBlank()) {
            try {
                return guild.retrieveMemberById(id).complete();
            } catch (ErrorResponseException ignored) {
                return null;
            }
        }
        String query = raw.startsWith("@") ? raw.substring(1) : raw;
        List<Member> matches = guild.retrieveMembersByPrefix(query, 20).get().stream()
                .filter(member -> matches(member, query))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean matches(Member member, String name) {
        if (member.getUser().getName().equalsIgnoreCase(name) || member.getEffectiveName().equalsIgnoreCase(name)) return true;
        String global = member.getUser().getGlobalName();
        return global != null && global.equalsIgnoreCase(name);
    }

    private Checked checkReferral(String raw) throws Exception {
        String name = raw.trim();
        if (name.startsWith("-")) {
            String base = name.substring(1);
            return GamertagLookupService.validCracked(base)
                    ? new Checked(true, GamertagLookupService.crackedName(base)) : new Checked(false, "");
        }
        GamertagLookupService.LookupResult result = lookup.lookup(name, name.startsWith(".") ? "bedrock" : "java");
        return new Checked(result.exists(), result.exists() ? result.name() : "");
    }

    private void log(Member member, GuildChannel log, OpenRouterParsingService.App app, String name) {
        String entry = "In-Game Name/Gamertag: " + WhitelistPlugin.safe(name)
                + "\n\nPlatform: " + app.platform().label()
                + "\n\nDiscord: <@" + member.getId() + ">";
        if (log instanceof IPostContainer forum) {
            forum.createForumPost("Whitelist", MessageCreateData.fromContent(entry)).complete();
        } else {
            ThreadChannel thread = ((IThreadContainer) log).createThreadChannel("Whitelist").complete();
            thread.sendMessage(entry).complete();
        }
    }

    private void dm(Member member) {
        MessageEmbed embed = new EmbedBuilder()
                .setDescription("You have been whitelisted.")
                .setColor(0x90EE90)
                .build();
        member.getUser().openPrivateChannel()
                .flatMap(channel -> channel.sendMessageEmbeds(embed))
                .queue(ignored -> { }, error -> plugin.getLogger().warning("Could not DM the whitelisted user."));
    }

    private static List<ChatScan.Item> items(List<Message> messages) {
        List<ChatScan.Item> items = new ArrayList<>();
        for (Message message : messages) {
            User author = message.getAuthor();
            String name = message.getMember() == null ? author.getName() : message.getMember().getEffectiveName();
            Message reply = message.getReferencedMessage();
            User replyUser = reply == null || reply.getAuthor().isBot() ? null : reply.getAuthor();
            Map<String, String> mentions = message.getMentions().getUsers().stream()
                    .filter(user -> !user.isBot())
                    .collect(Collectors.toMap(User::getId, User::getName, (left, right) -> left, LinkedHashMap::new));
            items.add(new ChatScan.Item(author.getId(), name, author.isBot(), mentions,
                    replyUser == null ? "" : replyUser.getId(),
                    replyUser == null ? "" : replyUser.getName(), text(message)));
        }
        return items;
    }

    private static String text(Message message) {
        StringBuilder out = new StringBuilder(message.getContentRaw());
        for (MessageEmbed embed : message.getEmbeds()) {
            add(out, embed.getTitle());
            add(out, embed.getDescription());
            for (MessageEmbed.Field field : embed.getFields()) {
                add(out, field.getName());
                add(out, field.getValue());
            }
        }
        return out.toString().trim();
    }

    private static void add(StringBuilder out, String value) {
        if (value == null || value.isBlank()) return;
        if (!out.isEmpty()) out.append('\n');
        out.append(value);
    }

    private static boolean allowed(SlashCommandInteractionEvent event, Set<String> users, Permission permission) {
        if (users.contains(event.getUser().getId())) return true;
        return users.isEmpty() && event.getMember() != null
                && (event.getMember().hasPermission(Permission.ADMINISTRATOR) || event.getMember().hasPermission(permission));
    }

    private void fail(InteractionHook hook, String action, Exception error) {
        plugin.getLogger().warning(action + " failed: " + ErrorMessages.safe(error));
        edit(hook, action + " failed: " + ErrorMessages.safe(error));
    }

    private static void edit(InteractionHook hook, String message) {
        hook.editOriginal(message).complete();
    }

    private record Checked(boolean valid, String name) { }

    private record Referral(String userId, String name) { }

    @FunctionalInterface
    private interface Task {
        void run(InteractionHook hook);
    }

    @FunctionalInterface
    private interface Progress {
        void show(String message);
    }
}
