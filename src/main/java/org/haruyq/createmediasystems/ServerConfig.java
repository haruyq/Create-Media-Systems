package org.haruyq.createmediasystems;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.haruyq.createmediasystems.disc.DiscUrl;
import org.haruyq.createmediasystems.stream.UrlPolicy;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * The server config, synced to clients (they stream from the API it points them at), and
 * {@code /create_media_systems config ...}, with which operators read, change and reload it without restarting.
 */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.ConfigValue<String> API_URL = BUILDER
            .comment("Base URL of the audio API (endpoints in API.md). Clients stream from it; the server asks it for track lengths.")
            .define("apiUrl", "http://127.0.0.1:8000");
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_HOSTS = BUILDER
            .comment("Hosts (and their subdomains) a disc, announcement or image viewer may link to.")
            .defineListAllowEmpty("allowedHosts",
                    List.of("youtube.com", "youtu.be", "discordapp.com", "discordapp.net", "tver.jp"),
                    () -> "", o -> o instanceof String);
    private static final int MAX_REMOTE_RANGE = 4096;
    public static final ModConfigSpec.IntValue REMOTE_RANGE = BUILDER
            .comment("Farthest (in blocks) a remote reaches the Blu-ray player it is paired with.")
            .defineInRange("remoteRange", 64, 1, MAX_REMOTE_RANGE);
    private static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {
    }

    static void register(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC, "cms-server.toml");
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

    /** Every stream a server hands to clients passes this check, whoever wrote the URL. */
    public static boolean isAllowed(String url) {
        return url != null && (DiscUrl.parse(url) != null
                || UrlPolicy.isAllowed(url, ALLOWED_HOSTS.get()));
    }

    private static void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cms").requires(source -> source.hasPermission(2))
                .then(Commands.literal("config")
                        .then(Commands.literal("reload").executes(c -> reload(c.getSource())))
                        .then(Commands.literal("apiUrl")
                                .executes(c -> show(c, "apiUrl", API_URL.get()))
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ServerConfig::setApiUrl)))
                        .then(Commands.literal("remoteRange")
                                .executes(c -> show(c, "remoteRange", REMOTE_RANGE.get()))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, MAX_REMOTE_RANGE))
                                        .executes(c -> set(c, REMOTE_RANGE, "remoteRange",
                                                IntegerArgumentType.getInteger(c, "blocks")))))
                        .then(Commands.literal("allowedHosts")
                                .executes(c -> show(c, "allowedHosts", String.join(", ", ALLOWED_HOSTS.get())))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("host", StringArgumentType.word())
                                                .executes(c -> editHosts(c, true))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("host", StringArgumentType.word())
                                                .suggests((c, builder) -> SharedSuggestionProvider.suggest(
                                                        ALLOWED_HOSTS.get().stream().map(String::valueOf), builder))
                                                .executes(c -> editHosts(c, false)))))));
    }

    /** Every change, by command or by editing the file, reaches the clients that stream with it. */
    private static void onReload(ModConfigEvent.Reloading event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (event.getConfig().getSpec() == SPEC && server != null) {
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
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) {
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
        SPEC.save();
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.host." + result, host), true);
        return 1;
    }

    private static <T> int set(CommandContext<CommandSourceStack> context, ModConfigSpec.ConfigValue<T> value,
            String key, T newValue) {
        value.set(newValue);
        SPEC.save();
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.set", key, String.valueOf(newValue)), true);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context, String key, Object value) {
        context.getSource().sendSuccess(() -> Component.translatable("create_media_systems.command.config.value", key, String.valueOf(value)), false);
        return 1;
    }

    /** Swaps in a whole config only if every value is valid, so a typo keeps the working settings. */
    private static boolean apply(CommentedConfig fresh) {
        if (!SPEC.isCorrect(fresh)) {
            return false;
        }
        file().getLoadedConfig().config().putAll(fresh);
        SPEC.afterReload();
        return true;
    }

    private static void sync() {
        PacketDistributor.sendToAllPlayers(new Changed(
                TomlFormat.instance().createWriter().writeToString(file().getLoadedConfig().config())));
    }

    static ModConfig file() {
        return ModConfigs.getModConfigs(CreateMediaSystems.MOD_ID).stream()
                .filter(c -> c.getType() == ModConfig.Type.SERVER).findFirst().orElseThrow();
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
