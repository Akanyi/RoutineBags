package dev.lans.routinebags.client;

import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.interact.InvOps;
import dev.lans.routinebags.network.ItemIdentity;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.HelloPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.SortResultPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.StoreResultV3Payload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeResultPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.TakeTarget;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ServerBridge {
    private static final int STORE_RETRY_DELAY_TICKS = 20;
    private static final int STORE_MAX_RETRIES = 2;
    private static final int REQUEST_TIMEOUT_TICKS = 100;
    private static final int TAKE_REQUEST_TIMEOUT_TICKS = 200;
    private static boolean available;
    private static String provider = "client";
    private static boolean serverSort;
    private static boolean serverStore;
    private static ClientPacketListener connection;
    private static boolean helloReceived;
    private static SortResultPayload lastSortResult;
    private static StoreResultV3Payload lastStoreResultV3;
    private static final Map<Integer, TakeResultPayload> takeResults = new HashMap<>();
    private static final Set<Integer> ignoredTakeRequests = new HashSet<>();
    private static int nextRequestId = 1;
    private static StoreRequest pendingStore;
    private static int activeTakeRequest = -1;
    private static int activeTakeTicks;

    public static void refresh() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener != connection) {
            connection = listener;
            helloReceived = false;
            provider = "client";
            serverSort = false;
            serverStore = false;
            clearTransientState();
        }
        boolean sortChannel = listener != null
                && NetworkRegistry.hasChannel(listener, RoutineBagsNetwork.SORT_REQUEST_ID);
        boolean storeChannel = listener != null
                && NetworkRegistry.hasChannel(listener, RoutineBagsNetwork.STORE_REQUEST_V3_ID);
        boolean takeChannel = listener != null
                && NetworkRegistry.hasChannel(listener, RoutineBagsNetwork.TAKE_REQUEST_ID);
        if (listener == null) {
            available = false;
            provider = "client";
            serverSort = false;
            serverStore = false;
        } else if (!helloReceived) {
            available = sortChannel || storeChannel || takeChannel;
            provider = "routinebags-compatible";
            serverSort = sortChannel;
            serverStore = storeChannel;
        } else {
            serverSort &= sortChannel;
            serverStore &= storeChannel;
            available = serverSort || serverStore || takeChannel;
        }
    }

    public static boolean canSortOnServer() {
        refresh();
        return available && serverSort;
    }

    public static boolean canStoreOnServer() {
        refresh();
        return available && serverStore && connection != null
                && NetworkRegistry.hasChannel(connection, RoutineBagsNetwork.STORE_REQUEST_V3_ID);
    }

    public static boolean canTakeOnServer() {
        refresh();
        return connection != null && NetworkRegistry.hasChannel(connection, RoutineBagsNetwork.TAKE_REQUEST_ID);
    }

    public static void requestSort(SortMode mode) {
        lastSortResult = null;
        if (canSortOnServer()) {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener != null) {
                listener.send(new RoutineBagsNetwork.SortRequestPayload(mode.ordinal()));
            }
        }
    }

    public static boolean requestStore(int menuSlot) {
        if (!canStoreOnServer() || pendingStore != null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        ItemStack source = InvOps.stackAt(menuSlot);
        byte[] expectedHash = ItemIdentity.hash(source, minecraft.level.registryAccess());
        if (source.isEmpty() || expectedHash.length != ItemIdentity.HASH_SIZE) {
            return false;
        }
        int requestId = nextRequestId();
        pendingStore = new StoreRequest(requestId, minecraft.player.containerMenu.containerId,
                menuSlot, source.getCount(), expectedHash);
        lastStoreResultV3 = null;
        if (!sendStore(pendingStore)) {
            pendingStore = null;
            return false;
        }
        return true;
    }

    public static int requestTake(int destination, List<TakeTarget> targets) {
        if (!canTakeOnServer() || activeTakeRequest >= 0
                || targets.isEmpty() || targets.size() > TakeRequestPayload.MAX_TARGETS) {
            return -1;
        }
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null) {
            return -1;
        }
        int requestId = nextRequestId();
        int containerId = Minecraft.getInstance().player.containerMenu.containerId;
        takeResults.remove(requestId);
        activeTakeRequest = requestId;
        activeTakeTicks = TAKE_REQUEST_TIMEOUT_TICKS;
        listener.send(new TakeRequestPayload(requestId, containerId, destination, targets));
        return requestId;
    }

    public static boolean hasTakeRequestInFlight() {
        refresh();
        return activeTakeRequest >= 0;
    }

    public static boolean hasOperationInFlight() {
        return hasTakeRequestInFlight() || pendingStore != null;
    }

    public static void tick() {
        refresh();
        if (activeTakeRequest >= 0 && --activeTakeTicks <= 0) {
            activeTakeRequest = -1;
            activeTakeTicks = 0;
        }
        if (pendingStore == null) {
            return;
        }
        pendingStore.ticksSinceSend++;
        if (pendingStore.waitingRetry) {
            if (pendingStore.ticksSinceSend >= STORE_RETRY_DELAY_TICKS) {
                pendingStore.waitingRetry = false;
                pendingStore.ticksSinceSend = 0;
                if (!sendStore(pendingStore)) {
                    finishStore(new StoreResultV3Payload(pendingStore.requestId, false, 0,
                            "gui.routinebags.status.server_store_failed"));
                }
            }
        } else if (pendingStore.ticksSinceSend >= REQUEST_TIMEOUT_TICKS) {
            retryOrFinishStore(new StoreResultV3Payload(pendingStore.requestId, false, 0,
                    "gui.routinebags.status.server_store_failed"));
        }
    }

    public static void handleHello(HelloPayload payload) {
        connection = Minecraft.getInstance().getConnection();
        helloReceived = true;
        available = payload.serverSort() || payload.serverStore();
        serverSort = payload.serverSort();
        serverStore = payload.serverStore();
        provider = payload.provider();
    }

    public static void handleSortResult(SortResultPayload payload) {
        lastSortResult = payload;
    }

    public static void handleStoreResultV3(StoreResultV3Payload payload) {
        if (pendingStore == null || payload.requestId() != pendingStore.requestId) {
            return;
        }
        if (payload.success()) {
            finishStore(payload);
        } else {
            retryOrFinishStore(payload);
        }
    }

    public static void handleTakeResult(TakeResultPayload payload) {
        if (ignoredTakeRequests.remove(payload.requestId())) {
            releaseTakeRequest(payload.requestId());
            return;
        }
        if (takeResults.size() >= 32) {
            takeResults.clear();
        }
        takeResults.put(payload.requestId(), payload);
    }

    public static Component modeLabel() {
        refresh();
        return Component.translatable(available
                ? "gui.routinebags.mode.server"
                : "gui.routinebags.mode.client");
    }

    public static Component providerTooltip() {
        refresh();
        if (available) {
            return Component.translatable("gui.routinebags.mode.server_detail", provider);
        }
        return Component.translatable("gui.routinebags.mode.client_detail");
    }

    public static SortResultPayload takeSortResult() {
        SortResultPayload result = lastSortResult;
        lastSortResult = null;
        return result;
    }

    public static StoreResultV3Payload takeStoreResultV3() {
        StoreResultV3Payload result = lastStoreResultV3;
        lastStoreResultV3 = null;
        return result;
    }

    public static TakeResultPayload takeTakeResult(int requestId) {
        TakeResultPayload result = takeResults.remove(requestId);
        if (result != null) {
            releaseTakeRequest(requestId);
        }
        return result;
    }

    public static void cancelStoreRequest() {
        pendingStore = null;
        lastStoreResultV3 = null;
    }

    public static void cancelTakeRequest(int requestId) {
        if (requestId < 0) {
            return;
        }
        if (takeResults.remove(requestId) != null) {
            releaseTakeRequest(requestId);
        } else {
            ignoredTakeRequests.add(requestId);
        }
        if (ignoredTakeRequests.size() > 64) {
            ignoredTakeRequests.clear();
        }
    }

    private static void releaseTakeRequest(int requestId) {
        if (requestId == activeTakeRequest) {
            activeTakeRequest = -1;
            activeTakeTicks = 0;
        }
    }

    private static boolean sendStore(StoreRequest request) {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener == null || !NetworkRegistry.hasChannel(listener, RoutineBagsNetwork.STORE_REQUEST_V3_ID)) {
            return false;
        }
        listener.send(new RoutineBagsNetwork.StoreRequestV3Payload(request.requestId, request.containerId,
                request.menuSlot, request.amount, request.expectedHash));
        return true;
    }

    private static void retryOrFinishStore(StoreResultV3Payload failure) {
        if (pendingStore.retries < STORE_MAX_RETRIES) {
            pendingStore.retries++;
            pendingStore.waitingRetry = true;
            pendingStore.ticksSinceSend = 0;
        } else {
            finishStore(failure);
        }
    }

    private static void finishStore(StoreResultV3Payload result) {
        lastStoreResultV3 = result;
        pendingStore = null;
    }

    private static int nextRequestId() {
        int id = nextRequestId++;
        if (nextRequestId <= 0) {
            nextRequestId = 1;
        }
        return id;
    }

    private static void clearTransientState() {
        lastSortResult = null;
        lastStoreResultV3 = null;
        takeResults.clear();
        ignoredTakeRequests.clear();
        pendingStore = null;
        activeTakeRequest = -1;
        activeTakeTicks = 0;
    }

    private static final class StoreRequest {
        final int requestId;
        final int containerId;
        final int menuSlot;
        final int amount;
        final byte[] expectedHash;
        int retries;
        int ticksSinceSend;
        boolean waitingRetry;

        StoreRequest(int requestId, int containerId, int menuSlot, int amount, byte[] expectedHash) {
            this.requestId = requestId;
            this.containerId = containerId;
            this.menuSlot = menuSlot;
            this.amount = amount;
            this.expectedHash = expectedHash.clone();
        }
    }

    private ServerBridge() {}
}
