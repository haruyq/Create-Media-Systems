package org.haruyq.createmediasystems;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import io.netty.buffer.ByteBuf;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The server config, synced to clients (they stream from the API it points them at) but for its secrets, and
 * {@code /cms config ...}, with which operators read, change and reload it without restarting.
 */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.ConfigValue<String> API_URL = BUILDER
            .comment("Base URL of the audio API (endpoints in API.md). Clients stream from it; the server asks it for track lengths.")
            .define("apiUrl", "http://127.0.0.1:8000", o -> o instanceof String base && ApiHttp.isBase(base));
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_HOSTS = BUILDER
            .comment("Hosts (and their subdomains) a disc, announcement or image viewer may link to.")
            .defineListAllowEmpty("allowedHosts",
                    List.of("youtube.com", "youtu.be", "discordapp.com", "discordapp.net", "tver.jp"),
                    () -> "", o -> o instanceof String);
    /** Built once every feature has added its settings (see register). */
    private static ModConfigSpec spec;
    private static final List<IntSetting> INT_SETTINGS = new ArrayList<>();
    /** The keys of the settings players never receive (see {@link #secret}). */
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    /** The file's name, which NeoForge also sends players by (see ConfigSyncMixin). */
    public static final String FILE = "cms-server.toml";

    private ServerConfig() {
    }

    /** A whole-number setting of this file, which {@code /cms config <key> <argument>} shows and sets. */
    public static ModConfigSpec.IntValue intSetting(String key, String argument, String comment, int defaultValue,
            int min, int max) {
        ModConfigSpec.IntValue value = BUILDER.comment(comment).defineInRange(key, defaultValue, min, max);
        INT_SETTINGS.add(new IntSetting(key, argument, value, min, max));
        return value;
    }

    /** A text setting players never receive: emptied in what they get of this file (see {@link #withoutSecrets}). */
    public static ModConfigSpec.ConfigValue<String> secret(String key, String comment) {
        SECRETS.add(key);
        return BUILDER.comment(comment).define(key, "", o -> o instanceof String);
    }

    /** After every feature has added its settings. */
    static void register(IEventBus modBus, ModContainer container) {
        spec = BUILDER.build();
        container.registerConfig(ModConfig.Type.SERVER, spec, FILE);
        modBus.addListener(ServerConfig::onReload);
        NeoForge.EVENT_BUS.addListener(ServerConfig::registerCommand);
    }

    static void registerPayloads(PayloadRegistrar registrar) {
        registrar.playToClient(Changed.TYPE, Changed.CODEC, (payload, context) -> {
            // The integrated server's client already shares its config.
            if (ServerLifecycleHooks.getCurrentServer() == null) {
                apply(TomlFormat.instance().createParser().parse(payload.toml()));
            }
        });
    }

    private static void registerCommand(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> config = Commands.literal("config")
                .then(Commands.literal("reload").executes(c -> reload(c.getSource())))
                .then(Commands.literal("apiUrl")
                        .executes(c -> show(c, "apiUrl", API_URL.get()))
                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                .executes(ServerConfig::setApiUrl)))
                .then(Commands.literal("allowedHosts")
                        .executes(c -> show(c, "allowedHosts", String.join(", ", ALLOWED_HOSTS.get())))
                        .then(Commands.literal("add")
                                .then(Commands.argument("host", StringArgumentType.word())
                                        .executes(c -> editHosts(c, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("host", StringArgumentType.word())
                                        .suggests((c, builder) -> SharedSuggestionProvider.suggest(
                                                ALLOWED_HOSTS.get().stream().map(String::valueOf), builder))
                                        .executes(c -> editHosts(c, false)))));
        for (IntSetting setting : INT_SETTINGS) {
            config.then(Commands.literal(setting.key())
                    .executes(c -> show(c, setting.key(), setting.value().get()))
                    .then(Commands.argument(setting.argument(), IntegerArgumentType.integer(setting.min(), setting.max()))
                            .executes(c -> set(c, setting.value(), setting.key(),
                                    IntegerArgumentType.getInteger(c, setting.argument())))));
        }
        event.getDispatcher().register(Commands.literal(CreateMediaSystems.COMMAND)
                .requires(source -> source.hasPermission(2)).then(config));
    }

    /** A change made by editing the file reaches the clients that stream with it, as one by command does (see saved). */
    private static void onReload(ModConfigEvent.Reloading event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (event.getConfig().getSpec() == spec && server != null) {
            server.execute(ServerConfig::sync);
        }
    }

    private static int reload(CommandSourceStack source) {
        CommentedConfig fresh;
        try {
            fresh = TomlFormat.instance().createParser().parse(file().getFullPath(), FileNotFoundAction.THROW_ERROR);
        } catch (RuntimeException e) {
            source.sendFailure(Component.translatable("create_media_systems.command.config.unreadable", e.getMessage()));
            return 0;
        }
        if (!apply(fresh)) {
            source.sendFailure(Component.translatable("create_media_systems.command.config.invalid"));
            return 0;
        }
        sync();
        source.sendSuccess(() -> Component.translatable("create_media_systems.command.config.reloaded"), true);
        return 1;
    }

    private static int setApiUrl(CommandContext<CommandSourceStack> context) {
        String url = StringArgumentType.getString(context, "url").trim();
        if (!ApiHttp.isBase(url)) {
            context.getSource().sendFailure(Component.translatable("create_media_systems.command.config.api_url.invalid", url));
            return 0;
        }
        return set(context, API_URL, "apiUrl", url);
    }

    private static int editHosts(CommandContext<CommandSourceStack> context, boolean add) {
        String host = StringArgumentType.getString(context, "host").toLowerCase(Locale.ROOT);
        List<String> hosts = new ArrayList<>(ALLOWED_HOSTS.get());
        boolean changed = add ? !hosts.contains(host) && hosts.add(host) : hosts.remove(host);
        String result = (add ? "added" : "removed") + (changed ? "" : ".unchanged");
        if (!changed) {
            context.getSource().sendFailure(Component.translatable("create_media_systems.command.config.host." + result, host));
            return 0;
        }
        ALLOWED_HOSTS.set(hosts);
        saved();
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.host." + result, host), true);
        return 1;
    }

    private static <T> int set(CommandContext<CommandSourceStack> context, ModConfigSpec.ConfigValue<T> value,
            String key, T newValue) {
        value.set(newValue);
        saved();
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.set", key, String.valueOf(newValue)), true);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context, String key, Object value) {
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.value", key, String.valueOf(value)), false);
        return 1;
    }

    /** Swaps in a whole config only if every value is valid, so a typo keeps the working settings. */
    private static boolean apply(CommentedConfig fresh) {
        if (!spec.isCorrect(fresh)) {
            return false;
        }
        file().getLoadedConfig().config().putAll(fresh);
        spec.afterReload();
        return true;
    }

    /** A command changed a value: written to the file, and sent to the clients now rather than when the file is seen to change. */
    private static void saved() {
        spec.save();
        sync();
    }

    private static void sync() {
        PacketDistributor.sendToAllPlayers(new Changed(
                withoutSecrets(TomlFormat.instance().createWriter().writeToString(file().getLoadedConfig().config()))));
    }

    /**
     * {@code toml} (this file's) as players may have it: every {@link #secret} emptied, not removed, so it stays a
     * valid config for them. Nothing at all if it can't be read, as then the secrets can't be found to empty.
     */
    public static String withoutSecrets(String toml) {
        CommentedConfig config;
        try {
            config = TomlFormat.instance().createParser().parse(toml);
        } catch (RuntimeException unreadable) {
            return "";
        }
        for (String secret : SECRETS) {
            if (config.contains(secret)) {
                config.set(secret, "");
            }
        }
        return TomlFormat.instance().createWriter().writeToString(config);
    }

    public static byte[] withoutSecrets(byte[] toml) {
        return withoutSecrets(new String(toml, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    static ModConfig file() {
        return ModConfigs.getModConfigs(CreateMediaSystems.MOD_ID).stream()
                .filter(c -> c.getType() == ModConfig.Type.SERVER).findFirst().orElseThrow();
    }

    private record IntSetting(String key, String argument, ModConfigSpec.IntValue value, int min, int max) {
    }

    /** Server → client: the config's TOML after it changed while players were on. */
    record Changed(String toml) implements CustomPacketPayload {
        static final Type<Changed> TYPE = new Type<>(CreateMediaSystems.id("server_config"));
        static final StreamCodec<ByteBuf, Changed> CODEC =
                ByteBufCodecs.stringUtf8(1 << 20).map(Changed::new, Changed::toml);

        @Override
        public Type<Changed> type() {
            return TYPE;
        }
    }
}
