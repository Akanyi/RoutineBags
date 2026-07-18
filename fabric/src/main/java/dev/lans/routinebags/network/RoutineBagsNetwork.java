package dev.lans.routinebags.network;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.client.ServerBridge;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class RoutineBagsNetwork {
    public static final Identifier HELLO_ID = id("hello");
    public static final Identifier SORT_REQUEST_ID = id("sort_request");
    public static final Identifier SORT_RESULT_ID = id("sort_result");
    public static final Identifier STORE_REQUEST_V3_ID = id("store_request_v3");
    public static final Identifier STORE_RESULT_V3_ID = id("store_result_v3");
    public static final Identifier TAKE_REQUEST_ID = id("take_request_v3");
    public static final Identifier TAKE_RESULT_ID = id("take_result_v3");

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SortRequestPayload.TYPE, SortRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SortResultPayload.TYPE, SortResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StoreRequestV3Payload.TYPE, StoreRequestV3Payload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StoreResultV3Payload.TYPE, StoreResultV3Payload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TakeRequestPayload.TYPE, TakeRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TakeResultPayload.TYPE, TakeResultPayload.STREAM_CODEC);

        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> ServerBridge.handleHello(payload));
        ClientPlayNetworking.registerGlobalReceiver(SortResultPayload.TYPE, (payload, context) -> ServerBridge.handleSortResult(payload));
        ClientPlayNetworking.registerGlobalReceiver(StoreResultV3Payload.TYPE, (payload, context) -> ServerBridge.handleStoreResultV3(payload));
        ClientPlayNetworking.registerGlobalReceiver(TakeResultPayload.TYPE, (payload, context) -> ServerBridge.handleTakeResult(payload));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(RoutineBags.MOD_ID, path);
    }

    public record HelloPayload(String provider, boolean serverSort, boolean serverStore) implements CustomPacketPayload {
        public static final Type<HelloPayload> TYPE = new Type<>(HELLO_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, HelloPayload::provider,
                ByteBufCodecs.BOOL, HelloPayload::serverSort,
                ByteBufCodecs.BOOL, HelloPayload::serverStore,
                HelloPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SortRequestPayload(int sortModeOrdinal) implements CustomPacketPayload {
        public static final Type<SortRequestPayload> TYPE = new Type<>(SORT_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SortRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SortRequestPayload::sortModeOrdinal, SortRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SortResultPayload(boolean success, int moves, String messageKey) implements CustomPacketPayload {
        public static final Type<SortResultPayload> TYPE = new Type<>(SORT_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SortResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, SortResultPayload::success,
                ByteBufCodecs.VAR_INT, SortResultPayload::moves,
                ByteBufCodecs.STRING_UTF8, SortResultPayload::messageKey,
                SortResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StoreRequestV3Payload(int requestId, int containerId, int menuSlot, int amount,
            byte[] expectedHash) implements CustomPacketPayload {
        public static final Type<StoreRequestV3Payload> TYPE = new Type<>(STORE_REQUEST_V3_ID);
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
        public static final Type<StoreResultV3Payload> TYPE = new Type<>(STORE_RESULT_V3_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreResultV3Payload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StoreResultV3Payload::requestId,
                ByteBufCodecs.BOOL, StoreResultV3Payload::success,
                ByteBufCodecs.VAR_INT, StoreResultV3Payload::moved,
                ByteBufCodecs.STRING_UTF8, StoreResultV3Payload::messageKey,
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
        public static final Type<TakeRequestPayload> TYPE = new Type<>(TAKE_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TakeRequestPayload::requestId,
                ByteBufCodecs.VAR_INT, TakeRequestPayload::containerId,
                ByteBufCodecs.VAR_INT, TakeRequestPayload::destination,
                TakeTarget.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_TARGETS)), TakeRequestPayload::targets,
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
        public static final Type<TakeResultPayload> TYPE = new Type<>(TAKE_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, TakeResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TakeResultPayload::requestId,
                ByteBufCodecs.BOOL, TakeResultPayload::success,
                ByteBufCodecs.VAR_INT, TakeResultPayload::moved,
                ByteBufCodecs.STRING_UTF8, TakeResultPayload::messageKey,
                TakeResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RoutineBagsNetwork() {}
}
