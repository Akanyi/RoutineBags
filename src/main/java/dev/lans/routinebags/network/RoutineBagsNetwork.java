package dev.lans.routinebags.network;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.server.ServerStoreService;
import dev.lans.routinebags.server.ServerTakeService;
import dev.lans.routinebags.server.ServerSortService;
import java.util.Arrays;
import java.util.List;
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
    public static final Identifier STORE_REQUEST_V3_ID = id("store_request_v3");
    public static final Identifier STORE_RESULT_V3_ID = id("store_result_v3");
    public static final Identifier TAKE_REQUEST_ID = id("take_request_v3");
    public static final Identifier TAKE_RESULT_ID = id("take_result_v3");

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
        registrar.playToServer(StoreRequestV3Payload.TYPE, StoreRequestV3Payload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerStoreService.handleStoreRequest(player, payload);
            }
        });
        registrar.playToClient(StoreResultV3Payload.TYPE, StoreResultV3Payload.STREAM_CODEC, (payload, context) -> {
            dispatchToClient("handleStoreResultV3", StoreResultV3Payload.class, payload);
        });
        registrar.playToServer(TakeRequestPayload.TYPE, TakeRequestPayload.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                ServerTakeService.handleTakeRequest(player, payload);
            }
        });
        registrar.playToClient(TakeResultPayload.TYPE, TakeResultPayload.STREAM_CODEC, (payload, context) -> {
            dispatchToClient("handleTakeResult", TakeResultPayload.class, payload);
        });
    }

    public static void sendHello(ServerPlayer player) {
        if (NetworkRegistry.hasChannel(player.connection, HELLO_ID)) {
            player.connection.send(new HelloPayload("routinebags-mod", true, true));
        }
    }

    public static void sendSortResult(ServerPlayer player, SortResultPayload payload) {
        if (NetworkRegistry.hasChannel(player.connection, SORT_RESULT_ID)) {
            player.connection.send(payload);
        }
    }

    public static void sendStoreResultV3(ServerPlayer player, StoreResultV3Payload payload) {
        if (NetworkRegistry.hasChannel(player.connection, STORE_RESULT_V3_ID)) {
            player.connection.send(payload);
        }
    }

    public static void sendTakeResult(ServerPlayer player, TakeResultPayload payload) {
        if (NetworkRegistry.hasChannel(player.connection, TAKE_RESULT_ID)) {
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
                ByteBufCodecs.stringUtf8(1_048_576),
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

    public record StoreRequestV3Payload(int requestId, int containerId, int menuSlot, int amount,
            byte[] expectedHash) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StoreRequestV3Payload> TYPE = new CustomPacketPayload.Type<>(STORE_REQUEST_V3_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreRequestV3Payload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public StoreRequestV3Payload decode(RegistryFriendlyByteBuf buffer) {
                int requestId = buffer.readVarInt();
                int containerId = buffer.readVarInt();
                int menuSlot = buffer.readVarInt();
                int amount = buffer.readVarInt();
                byte[] expectedHash = new byte[ItemIdentity.HASH_SIZE];
                buffer.readBytes(expectedHash);
                return new StoreRequestV3Payload(requestId, containerId, menuSlot, amount, expectedHash);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, StoreRequestV3Payload payload) {
                buffer.writeVarInt(payload.requestId);
                buffer.writeVarInt(payload.containerId);
                buffer.writeVarInt(payload.menuSlot);
                buffer.writeVarInt(payload.amount);
                buffer.writeBytes(payload.expectedHash);
            }
        };

        public StoreRequestV3Payload {
            if (expectedHash.length != ItemIdentity.HASH_SIZE) {
                throw new IllegalArgumentException("Invalid item identity hash");
            }
            expectedHash = expectedHash.clone();
        }

        @Override
        public byte[] expectedHash() {
            return expectedHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof StoreRequestV3Payload payload
                    && requestId == payload.requestId
                    && containerId == payload.containerId
                    && menuSlot == payload.menuSlot
                    && amount == payload.amount
                    && Arrays.equals(expectedHash, payload.expectedHash);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(requestId);
            result = 31 * result + Integer.hashCode(containerId);
            result = 31 * result + Integer.hashCode(menuSlot);
            result = 31 * result + Integer.hashCode(amount);
            return 31 * result + Arrays.hashCode(expectedHash);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StoreResultV3Payload(int requestId, boolean success, int moved, String messageKey) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<StoreResultV3Payload> TYPE = new CustomPacketPayload.Type<>(STORE_RESULT_V3_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreResultV3Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                StoreResultV3Payload::requestId,
                ByteBufCodecs.BOOL,
                StoreResultV3Payload::success,
                ByteBufCodecs.VAR_INT,
                StoreResultV3Payload::moved,
                ByteBufCodecs.STRING_UTF8,
                StoreResultV3Payload::messageKey,
                StoreResultV3Payload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TakeTarget(int bagMenuSlot, int entryIndex, int amount, byte[] expectedHash) {
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeTarget> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public TakeTarget decode(RegistryFriendlyByteBuf buffer) {
                int bagMenuSlot = buffer.readVarInt();
                int entryIndex = buffer.readVarInt();
                int amount = buffer.readVarInt();
                byte[] expectedHash = new byte[ItemIdentity.HASH_SIZE];
                buffer.readBytes(expectedHash);
                return new TakeTarget(bagMenuSlot, entryIndex, amount, expectedHash);
            }

            @Override
            public void encode(RegistryFriendlyByteBuf buffer, TakeTarget target) {
                buffer.writeVarInt(target.bagMenuSlot);
                buffer.writeVarInt(target.entryIndex);
                buffer.writeVarInt(target.amount);
                buffer.writeBytes(target.expectedHash);
            }
        };

        public TakeTarget {
            if (expectedHash.length != ItemIdentity.HASH_SIZE) {
                throw new IllegalArgumentException("Invalid item identity hash");
            }
            expectedHash = expectedHash.clone();
        }

        @Override
        public byte[] expectedHash() {
            return expectedHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof TakeTarget target
                    && bagMenuSlot == target.bagMenuSlot
                    && entryIndex == target.entryIndex
                    && amount == target.amount
                    && Arrays.equals(expectedHash, target.expectedHash);
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(bagMenuSlot);
            result = 31 * result + Integer.hashCode(entryIndex);
            result = 31 * result + Integer.hashCode(amount);
            return 31 * result + Arrays.hashCode(expectedHash);
        }
    }

    public record TakeRequestPayload(int requestId, int containerId, int destination, List<TakeTarget> targets)
            implements CustomPacketPayload {
        public static final int DESTINATION_CURSOR = 0;
        public static final int DESTINATION_INVENTORY = 1;
        public static final int MAX_TARGETS = 128;
        public static final CustomPacketPayload.Type<TakeRequestPayload> TYPE = new CustomPacketPayload.Type<>(TAKE_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                TakeRequestPayload::requestId,
                ByteBufCodecs.VAR_INT,
                TakeRequestPayload::containerId,
                ByteBufCodecs.VAR_INT,
                TakeRequestPayload::destination,
                TakeTarget.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TARGETS)),
                TakeRequestPayload::targets,
                TakeRequestPayload::new);

        public TakeRequestPayload {
            targets = List.copyOf(targets);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TakeResultPayload(int requestId, boolean success, int moved, String messageKey) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<TakeResultPayload> TYPE = new CustomPacketPayload.Type<>(TAKE_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                TakeResultPayload::requestId,
                ByteBufCodecs.BOOL,
                TakeResultPayload::success,
                ByteBufCodecs.VAR_INT,
                TakeResultPayload::moved,
                ByteBufCodecs.STRING_UTF8,
                TakeResultPayload::messageKey,
                TakeResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RoutineBagsNetwork() {}
}
