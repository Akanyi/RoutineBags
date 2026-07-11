package dev.lans.routinebags.network;

import dev.lans.routinebags.RoutineBags;
import dev.lans.routinebags.client.ServerBridge;
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
    public static final Identifier STORE_REQUEST_ID = id("store_request");
    public static final Identifier STORE_RESULT_ID = id("store_result");

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(HelloPayload.TYPE, HelloPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SortRequestPayload.TYPE, SortRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SortResultPayload.TYPE, SortResultPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StoreRequestPayload.TYPE, StoreRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StoreResultPayload.TYPE, StoreResultPayload.STREAM_CODEC);

        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.TYPE, (payload, context) -> ServerBridge.handleHello(payload));
        ClientPlayNetworking.registerGlobalReceiver(SortResultPayload.TYPE, (payload, context) -> ServerBridge.handleSortResult(payload));
        ClientPlayNetworking.registerGlobalReceiver(StoreResultPayload.TYPE, (payload, context) -> ServerBridge.handleStoreResult(payload));
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

    public record StoreRequestPayload(int menuSlot) implements CustomPacketPayload {
        public static final Type<StoreRequestPayload> TYPE = new Type<>(STORE_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreRequestPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StoreRequestPayload::menuSlot, StoreRequestPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record StoreResultPayload(boolean success, int moved, String messageKey) implements CustomPacketPayload {
        public static final Type<StoreResultPayload> TYPE = new Type<>(STORE_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, StoreResultPayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, StoreResultPayload::success,
                ByteBufCodecs.VAR_INT, StoreResultPayload::moved,
                ByteBufCodecs.STRING_UTF8, StoreResultPayload::messageKey,
                StoreResultPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RoutineBagsNetwork() {}
}
