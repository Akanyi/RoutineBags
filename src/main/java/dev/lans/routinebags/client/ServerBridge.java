package dev.lans.routinebags.client;

import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.network.RoutineBagsNetwork;
import dev.lans.routinebags.network.RoutineBagsNetwork.HelloPayload;
import dev.lans.routinebags.network.RoutineBagsNetwork.SortResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ServerBridge {
    private static boolean available;
    private static String provider = "client";
    private static boolean serverSort;
    private static SortResultPayload lastSortResult;

    public static void refresh() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        available = listener != null && NetworkRegistry.hasChannel(listener, RoutineBagsNetwork.SORT_REQUEST_ID);
        if (!available) {
            provider = "client";
            serverSort = false;
        } else if (!serverSort) {
            provider = "routinebags-compatible";
            serverSort = true;
        }
    }

    public static boolean canSortOnServer() {
        refresh();
        return available && serverSort;
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

    public static void handleHello(HelloPayload payload) {
        available = payload.serverSort();
        serverSort = payload.serverSort();
        provider = payload.provider();
    }

    public static void handleSortResult(SortResultPayload payload) {
        lastSortResult = payload;
    }

    public static Component modeLabel() {
        refresh();
        return Component.translatable(available && serverSort
                ? "gui.routinebags.mode.server"
                : "gui.routinebags.mode.client");
    }

    public static Component providerTooltip() {
        refresh();
        if (available && serverSort) {
            return Component.translatable("gui.routinebags.mode.server_detail", provider);
        }
        return Component.translatable("gui.routinebags.mode.client_detail");
    }

    public static SortResultPayload takeSortResult() {
        SortResultPayload result = lastSortResult;
        lastSortResult = null;
        return result;
    }

    private ServerBridge() {}
}
