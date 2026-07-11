package dev.lans.routinebags.network;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.server.ServerSortService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = RoutineBags.MOD_ID)
public final class RoutineBagsNetwork {
    public static final String PROTOCOL = "1";

    public static final Identifier HELLO_ID = id("hello");
    public static final Identifier SORT_REQUEST_ID = id("sort_request");
    public static final Identifier SORT_RESULT_ID = id("sort_result");
    public static final Identifier STORE_REQUEST_ID = id("store_request");
    public static final Identifier STORE_RESULT_ID = id("store_result");

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
        registrar.playToClient(HelloPayload.TYPE, HelloPayload.STREAM_CODEC, (payload, context) -> {
            dispatchToClient("handleHello", HelloPayload.class, payload);
        });
        registrar.playToServer(SortRequestPayload.TYPE, SortRequestPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerSortService.handleSortRequest(player, payload);
            }
        });
        registrar.playToClient(SortResultPayload.TYPE, SortResultPayload.STREAM_CODEC, (payload, context) -> {
            dispatchToClient("handleSortResult", SortResultPayload.class, payload);
        });
        registrar.playToServer(StoreRequestPayload.TYPE, StoreRequestPayload.STREAM_CODEC, (payload, context) -> {
            // Dedicated NeoForge servers do not implement server-side smart store yet; Paper RoutineBagkkit handles this channel.
        });
        registrar.playToClient(StoreResultPayload.TYPE, StoreResultPayload.STREAM_CODEC, (payload, context) -> {
            dispatchToClient("handleStoreResult", StoreResultPayload.class, payload);
        });
    }

    public static void sendHello(ServerPlayer player) {
        if (NetworkRegistry.hasChannel(player.connection, HELLO_ID)) {
            player.connection.send(new HelloPayload("routinebags-mod", true, false));
        }
    }

    public static void sendSortResult(ServerPlayer player, SortResultPayload payload) {
        if (NetworkRegistry.hasChannel(player.connection, SORT_RESULT_ID)) {
            player.connection.send(payload);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RoutineBags.MOD_ID, path);
    }

    private static <T> void dispatchToClient(String method, Class<T> payloadType, T payload) {
        try {
            Class<?> bridge = Class.forName("dev.lans.routinebags.client.ServerBridge");
            bridge.getMethod(method, payloadType).invoke(null, payload);
        } catch (ReflectiveOperationException ignored) {
            // Dedicated servers still register clientbound payload metadata; there is no client bridge there.
        }
    }

    public record HelloPayload(String provider, boolean serverSort, boolean serverStore) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HelloPayload> TYPE = new CustomPacketPayload.Type<>(HELLO_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                HelloPayload::provider,
                ByteBufCodecs.BOOL,
                HelloPayload::serverSort,
                ByteBufCodecs.BOOL,
                HelloPayload::serverStore,
                HelloPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SortRequestPayload(int sortModeOrdinal) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SortRequestPayload> TYPE = new CustomPacketPayload.Type<>(SORT_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SortRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                SortRequestPayload::sortModeOrdinal,
                SortRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SortResultPayload(boolean success, int moves, String messageKey) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SortResultPayload> TYPE = new CustomPacketPayload.Type<>(SORT_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SortResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL,
                SortResultPayload::success,
                ByteBufCodecs.VAR_INT,
                SortResultPayload::moves,
                ByteBufCodecs.STRING_UTF8,
                SortResultPayload::messageKey,
                SortResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StoreRequestPayload(int menuSlot) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StoreRequestPayload> TYPE = new CustomPacketPayload.Type<>(STORE_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                StoreRequestPayload::menuSlot,
                StoreRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StoreResultPayload(boolean success, int moved, String messageKey) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StoreResultPayload> TYPE = new CustomPacketPayload.Type<>(STORE_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL,
                StoreResultPayload::success,
                ByteBufCodecs.VAR_INT,
                StoreResultPayload::moved,
                ByteBufCodecs.STRING_UTF8,
                StoreResultPayload::messageKey,
                StoreResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RoutineBagsNetwork() {}
}
