package dev.lans.routinebags.client;

import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.HelloPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.SortResultPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.StoreResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

public final class ServerBridge {
    private static boolean available;
    private static String provider = "client";
    private static boolean serverSort;
    private static boolean serverStore;
    private static ClientPacketListener connection;
    private static boolean helloReceived;
    private static SortResultPayload lastSortResult;
    private static StoreResultPayload lastStoreResult;

    public static void refresh() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        if (listener != connection) {
            connection = listener;
            helloReceived = false;
            provider = "client";
            serverSort = false;
            serverStore = false;
        }
        boolean channelAvailable = listener != null
                && ClientPlayNetworking.canSend(RoutineBagsNetwork.SortRequestPayload.TYPE);
        if (!channelAvailable) {
            available = false;
            provider = "client";
            serverSort = false;
            serverStore = false;
        } else if (!helloReceived) {
            available = true;
            provider = "routinebags-compatible";
            serverSort = true;
        } else {
            available = serverSort || serverStore;
        }
    }

    public static boolean canSortOnServer() {
        refresh();
        return available && serverSort;
    }

    public static boolean canStoreOnServer() {
        refresh();
        return available && serverStore && Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(RoutineBagsNetwork.StoreRequestPayload.TYPE);
    }

    public static void requestSort(SortMode mode) {
        lastSortResult = null;
        if (canSortOnServer()) {
            ClientPlayNetworking.send(new RoutineBagsNetwork.SortRequestPayload(mode.ordinal()));
        }
    }

    public static boolean requestStore(int menuSlot) {
        lastStoreResult = null;
        if (!canStoreOnServer()) {
            return false;
        }
        ClientPlayNetworking.send(new RoutineBagsNetwork.StoreRequestPayload(menuSlot));
        return true;
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

    public static void handleStoreResult(StoreResultPayload payload) {
        lastStoreResult = payload;
    }

    public static Component modeLabel() {
        refresh();
        return Component.translatable(available && serverSort
                ? "gui.routinebags.mode.server"
                : "gui.routinebags.mode.client");
    }

    public static Component providerTooltip() {
        refresh();
        if (available && (serverSort || serverStore)) {
            return Component.translatable("gui.routinebags.mode.server_detail", provider);
        }
        return Component.translatable("gui.routinebags.mode.client_detail");
    }

    public static SortResultPayload takeSortResult() {
        SortResultPayload result = lastSortResult;
        lastSortResult = null;
        return result;
    }

    public static StoreResultPayload takeStoreResult() {
        StoreResultPayload result = lastStoreResult;
        lastStoreResult = null;
        return result;
    }

    private ServerBridge() {}
}
