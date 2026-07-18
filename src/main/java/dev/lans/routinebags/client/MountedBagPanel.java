package dev.lans.routinebags.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import dev.lans.routinebags.ClientConfig;
import dev.lans.routinebags.SortMode;
import dev.lans.routinebags.bag.BagKind;
import dev.lans.routinebags.bag.BagScanner;
import dev.lans.routinebags.bag.BagView;
import dev.lans.routinebags.interact.CursorOps;
import dev.lans.routinebags.interact.InvOps;
import dev.lans.routinebags.interact.StepRunner;
import dev.lans.routinebags.merge.AggregatedIndex;
import dev.lans.routinebags.merge.AggregatedIndex.Entry;
import dev.lans.routinebags.merge.ItemKey;
import dev.lans.routinebags.sort.SortController;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 容器界面旁的轻量 RTbags 终端。它不替换原 GUI，只拦截自己面板内的输入。
 */
public final class MountedBagPanel implements GuiEventListener, Renderable, NarratableEntry {
    private static final int PAD = 6;
    private static final int CELL = 18;
    private static final int GRID_COLS = 6;
    private static final int GRID_W = GRID_COLS * CELL;
    private static final int BTN_H = 14;
    private static final int W = PAD + GRID_W + PAD;
    private static final int PANEL_GAP = 4;
    private static final int TAB_W = 24;
    private static final int TAB_H = 18;
    private static final int MIN_GRID_ROWS = 3;
    private static final int MAX_GRID_ROWS = 8;
    private static final int HEADER_H = 18;
    private static final int SECTION_HEADER_H = 11;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    private final Minecraft minecraft = Minecraft.getInstance();
    private final AbstractContainerMenu mountedMenu;
    private final StepRunner runner = new StepRunner();
    private final SortController sorter = new SortController();
    private final List<Entry> visible = new ArrayList<>();
    private static boolean openState = ClientConfig.MOUNTED_PANEL_OPEN_BY_DEFAULT.get();

    private List<BagView> bags = List.of();
    private List<Entry> entries = List.of();
    private int scrollRow;
    private int bagFilter = -1;
    private SortMode sortMode = ClientConfig.SORT_MODE.get();
    private @Nullable Component status;
    private boolean sorterWasActive;
    private boolean waitingServerSort;
    private int pendingTakeRequest = -1;
    private int pendingTakeTicks;
    private boolean focused;
    private boolean open = openState;
    private boolean mouseCaptured;
    private boolean suppressed;
    private boolean layoutAvailable;

    private int x;
    private int y;
    private int h;
    private Rect tabRect = new Rect(0, 0, 0, 0);
    private int gridRows;
    private int gridH;
    private Rect gridRect = new Rect(0, 0, 0, 0);
    private Rect sortBtn = new Rect(0, 0, 0, 0);
    private Rect cancelBtn = new Rect(0, 0, 0, 0);
    private Rect modeBtn = new Rect(0, 0, 0, 0);
    private Rect openBtn = new Rect(0, 0, 0, 0);
    private Rect closeBtn = new Rect(0, 0, 0, 0);

    public MountedBagPanel(AbstractContainerMenu mountedMenu) {
        this.mountedMenu = mountedMenu;
    }

    public void layout(int screenW, int screenH, int containerLeft, int containerTop, int containerW, int containerH) {
        int gridTop = PAD + HEADER_H + SECTION_HEADER_H;
        int fixedH = gridTop + 4 + BTN_H + 3 + 11 + 4 + 9 + PAD;
        this.gridRows = Math.clamp((screenH - fixedH - 8) / CELL, MIN_GRID_ROWS, MAX_GRID_ROWS);
        this.gridH = this.gridRows * CELL;
        this.h = fixedH + this.gridH;
        int leftX = containerLeft - W - PANEL_GAP;
        int rightX = containerLeft + containerW + PANEL_GAP;
        boolean fitsLeft = leftX >= 4;
        boolean fitsRight = rightX + W <= screenW - 4;
        this.layoutAvailable = fitsLeft || fitsRight;
        if (!this.layoutAvailable) {
            this.mouseCaptured = false;
            setFocused(false);
            return;
        }
        this.x = fitsLeft ? leftX : rightX;
        this.y = Mth.clamp(containerTop, 4, Math.max(4, screenH - this.h - 4));
        this.tabRect = new Rect(tabX(screenW, containerLeft), Mth.clamp(this.y + 8, 4, Math.max(4, screenH - TAB_H - 4)), TAB_W, TAB_H);
        this.closeBtn = new Rect(this.x + W - PAD - 12, this.y + 3, 12, 12);
        int innerX = this.x + PAD;
        this.gridRect = new Rect(innerX, this.y + gridTop, GRID_W, this.gridH);
        int btnY = this.gridRect.y + this.gridH + 4;
        this.sortBtn = new Rect(innerX, btnY, 34, BTN_H);
        this.cancelBtn = new Rect(innerX + 38, btnY, 34, BTN_H);
        this.modeBtn = new Rect(innerX + 76, btnY, GRID_W - 76, BTN_H);
        this.openBtn = new Rect(innerX, btnY + BTN_H + 3, GRID_W, 11);
        refresh();
    }

    private int tabX(int screenW, int containerLeft) {
        int x = containerLeft - TAB_W - 2;
        return Mth.clamp(x, 4, Math.max(4, screenW - TAB_W - 4));
    }

    public static int panelWidth() {
        return W;
    }

    public static int panelGap() {
        return PANEL_GAP;
    }

    public void tick() {
        if (this.minecraft.player == null || this.minecraft.player.containerMenu != this.mountedMenu) {
            cleanup();
            return;
        }
        refresh();
        this.runner.tick(ClientConfig.OPS_PER_TICK.get(), ClientConfig.STEP_DELAY_TICKS.get());
        this.sorter.tick(this.runner, this.sortMode);

        var serverSortResult = ServerBridge.takeSortResult();
        if (serverSortResult != null) {
            this.waitingServerSort = false;
            this.status = Component.translatable(serverSortResult.messageKey(), serverSortResult.moves());
        }
        if (this.pendingTakeRequest >= 0) {
            var serverTakeResult = ServerBridge.takeTakeResult(this.pendingTakeRequest);
            if (serverTakeResult != null) {
                this.pendingTakeRequest = -1;
                this.pendingTakeTicks = 0;
                this.status = Component.translatable(serverTakeResult.messageKey(), serverTakeResult.moved());
                refresh();
            } else if (--this.pendingTakeTicks <= 0) {
                ServerBridge.cancelTakeRequest(this.pendingTakeRequest);
                this.pendingTakeRequest = -1;
                this.status = Component.translatable("gui.routinebags.status.server_take_failed");
            }
        }

        Component abortMsg = this.runner.takeAbortMessage();
        if (abortMsg != null) {
            this.status = abortMsg;
            this.sorter.cancel();
            this.waitingServerSort = false;
        } else if (this.sorter.isActive()) {
            this.status = this.sorter.status();
        } else if (this.waitingServerSort) {
            this.status = Component.translatable("gui.routinebags.status.server_sorting");
        } else if (this.sorterWasActive) {
            this.status = this.sorter.status();
        }
        this.sorterWasActive = this.sorter.isActive();
    }

    public void cleanup() {
        this.runner.clear();
        this.sorter.cancel();
        this.waitingServerSort = false;
        ServerBridge.cancelTakeRequest(this.pendingTakeRequest);
        this.pendingTakeRequest = -1;
        this.pendingTakeTicks = 0;
    }

    public boolean hasActiveOperation() {
        return this.runner.busy() || this.sorter.isActive() || this.waitingServerSort
                || this.pendingTakeRequest >= 0;
    }

    public boolean isMountedTo(AbstractContainerMenu menu) {
        return this.mountedMenu == menu;
    }

    private void refresh() {
        if (this.minecraft.player == null) {
            return;
        }
        this.bags = BagScanner.scan(this.minecraft.player, ClientConfig.SHOW_READ_ONLY.get()).stream()
                .filter(b -> InvOps.canReachSlot(b.menuSlot))
                .toList();
        if (this.bagFilter >= this.bags.size()) {
            this.bagFilter = -1;
        }
        this.entries = AggregatedIndex.build(this.bags, this.sortMode);
        this.visible.clear();
        for (Entry e : this.entries) {
            if (this.bagFilter >= 0 && e.sources.stream().noneMatch(s -> s.bagOrdinal() == this.bagFilter)) {
                continue;
            }
            this.visible.add(e);
        }
        int maxRow = Math.max(0, (this.visible.size() + GRID_COLS - 1) / GRID_COLS - this.gridRows);
        this.scrollRow = Math.clamp(this.scrollRow, 0, maxRow);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (this.suppressed || !this.layoutAvailable) {
            return;
        }
        if (!this.open) {
            drawTab(g, mouseX, mouseY);
        }
        if (!this.open || this.h <= 0) {
            return;
        }
        VanillaUi.panel(g, this.x, this.y, W, this.h);
        g.text(this.minecraft.font, Component.translatable("gui.routinebags.mount.title"),
                this.x + PAD, this.y + 5, VanillaUi.TEXT);
        drawCloseButton(g, mouseX, mouseY);

        drawGrid(g, mouseX, mouseY);
        drawButtons(g, mouseX, mouseY);
        drawStatus(g);
    }

    private void drawTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean hover = this.tabRect.contains(mouseX, mouseY);
        VanillaUi.button(g, this.tabRect.x, this.tabRect.y, this.tabRect.w, this.tabRect.h,
                hover || this.open, true);
        Component label = this.open ? Component.literal("<") : Component.translatable("gui.routinebags.mount.tab");
        g.centeredText(this.minecraft.font, label, this.tabRect.x + this.tabRect.w / 2,
                this.tabRect.y + 5, VanillaUi.buttonText(hover || this.open, true));
        if (!this.statusBadgeText().isEmpty()) {
            g.fill(this.tabRect.x + this.tabRect.w - 5, this.tabRect.y + 2,
                    this.tabRect.x + this.tabRect.w - 2, this.tabRect.y + 5, VanillaUi.STATUS);
        }
        if (hover) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable(this.open
                    ? "gui.routinebags.mount.tooltip_collapse"
                    : "gui.routinebags.mount.tooltip_expand")), mouseX, mouseY);
        }
    }

    private String statusBadgeText() {
        return (this.runner.busy() || this.sorter.isActive() || this.waitingServerSort || this.pendingTakeRequest >= 0) ? "!" : "";
    }

    private void drawCloseButton(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean hover = this.closeBtn.contains(mouseX, mouseY);
        VanillaUi.button(g, this.closeBtn.x, this.closeBtn.y, this.closeBtn.w, this.closeBtn.h, hover, true);
        g.centeredText(this.minecraft.font, Component.literal("x"), this.closeBtn.x + this.closeBtn.w / 2,
                this.closeBtn.y + 2, VanillaUi.buttonText(hover, true));
        if (hover) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable("gui.routinebags.mount.tooltip_collapse")), mouseX, mouseY);
        }
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawSection(g, this.gridRect.x - 3, this.gridRect.y - SECTION_HEADER_H, GRID_W + 6, this.gridH + SECTION_HEADER_H + 3,
                Component.translatable("gui.routinebags.section.grid"));
        for (int row = 0; row < this.gridRows; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                drawCell(g, this.gridRect.x + col * CELL, this.gridRect.y + row * CELL);
            }
        }
        if (!InvOps.hasReachablePlayerInventory()) {
            g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.mount.unavailable"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, VanillaUi.TEXT_DIM);
            return;
        }
        if (this.bags.isEmpty()) {
            g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.no_bags"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, VanillaUi.TEXT_DIM);
            return;
        }
        if (this.visible.isEmpty()) {
            g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.no_items"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, VanillaUi.TEXT_DIM);
        }
        Entry hovered = null;
        for (int i = 0; i < this.gridRows * GRID_COLS; i++) {
            int idx = (this.scrollRow + i / GRID_COLS) * GRID_COLS + i % GRID_COLS;
            if (idx >= this.visible.size()) {
                break;
            }
            Entry entry = this.visible.get(idx);
            int x = this.gridRect.x + (i % GRID_COLS) * CELL;
            int y = this.gridRect.y + (i / GRID_COLS) * CELL;
            g.item(entry.display, x + 1, y + 1);
            g.itemDecorations(this.minecraft.font, entry.display, x + 1, y + 1, AggregatedIndex.formatCount(entry.total));
            if (!entry.anyMutable) {
                g.fill(x + CELL - 5, y + 1, x + CELL - 2, y + 4, VanillaUi.DANGER);
            }
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                VanillaUi.slotHover(g, x, y, CELL);
                hovered = entry;
            }
        }
        int totalRows = Math.max(1, (this.visible.size() + GRID_COLS - 1) / GRID_COLS);
        if (totalRows > this.gridRows) {
            int trackX = this.gridRect.x + GRID_W + 1;
            int thumbH = Math.max(8, this.gridH * this.gridRows / totalRows);
            int thumbY = this.gridRect.y + (this.gridH - thumbH) * this.scrollRow / Math.max(1, totalRows - this.gridRows);
            VanillaUi.scrollbar(g, trackX, this.gridRect.y, this.gridH, thumbY, thumbH);
        }
        if (hovered != null) {
            g.setTooltipForNextFrame(this.minecraft.font, entryTooltip(hovered), hovered.display.getTooltipImage(),
                    mouseX, mouseY, hovered.display.get(DataComponents.TOOLTIP_STYLE));
        }
    }

    private List<Component> entryTooltip(Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(entry.display.getHoverName());
        lines.add(Component.translatable("gui.routinebags.total", entry.total).withStyle(ChatFormatting.GOLD));
        for (AggregatedIndex.Source src : entry.sources) {
            if (src.bagOrdinal() >= this.bags.size()) {
                continue;
            }
            BagView bag = this.bags.get(src.bagOrdinal());
            Component line = Component.literal("  #" + (src.bagOrdinal() + 1) + " ")
                    .append(bag.displayName().copy().withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" x" + src.count()).withStyle(ChatFormatting.WHITE));
            if (!src.mutable()) {
                line = line.copy().append(Component.translatable("gui.routinebags.readonly_tag").withStyle(ChatFormatting.RED));
            }
            lines.add(line);
        }
        if (entry.anyMutable) {
            lines.add(Component.translatable("gui.routinebags.mount.hint_extract").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.translatable("gui.routinebags.readonly").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean canOperate = InvOps.hasReachablePlayerInventory();
        drawButton(g, this.sortBtn, Component.translatable("gui.routinebags.sort"), mouseX, mouseY,
                canOperate && !this.sorter.isActive() && !this.runner.busy() && !this.waitingServerSort
                        && this.pendingTakeRequest < 0 && !RecipeBagSupport.isBusy()
                        && !ServerBridge.hasOperationInFlight());
        drawButton(g, this.cancelBtn, Component.translatable("gui.routinebags.cancel"), mouseX, mouseY,
                this.sorter.isActive() || this.runner.busy() || this.waitingServerSort || this.pendingTakeRequest >= 0);
        drawButton(g, this.modeBtn, compactModeLabel(), mouseX, mouseY, true);
        boolean openHover = this.openBtn.contains(mouseX, mouseY);
        boolean canOpenFull = !hasActiveOperation() && !RecipeBagSupport.isBusy()
                && !ServerBridge.hasOperationInFlight();
        VanillaUi.button(g, this.openBtn.x, this.openBtn.y, this.openBtn.w, this.openBtn.h,
                openHover && canOpenFull, canOpenFull);
        g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.mount.open_full"),
                this.openBtn.x + this.openBtn.w / 2, this.openBtn.y + 2,
                VanillaUi.buttonText(openHover && canOpenFull, canOpenFull));
        if (this.sortBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable(canUseServerSort()
                    ? "gui.routinebags.tooltip.sort_server"
                    : "gui.routinebags.tooltip.sort_client")), mouseX, mouseY);
        } else if (this.cancelBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable("gui.routinebags.tooltip.cancel")), mouseX, mouseY);
        } else if (this.modeBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable("gui.routinebags.tooltip.mode")), mouseX, mouseY);
        } else if (openHover) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable("gui.routinebags.mount.tooltip_full")), mouseX, mouseY);
        }
    }

    private Component compactModeLabel() {
        return switch (this.sortMode) {
            case BY_CREATIVE -> Component.literal("CRE");
            case BY_ID -> Component.literal("ID");
            case BY_NAME -> Component.literal("AZ");
            case BY_COUNT -> Component.literal("#");
        };
    }

    private void drawButton(GuiGraphicsExtractor g, Rect r, Component label, int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && r.contains(mouseX, mouseY);
        VanillaUi.button(g, r.x, r.y, r.w, r.h, hover, enabled);
        g.centeredText(this.minecraft.font, label, r.x + r.w / 2, r.y + (r.h - 8) / 2,
                VanillaUi.buttonText(hover, enabled));
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int y = this.openBtn.y + this.openBtn.h + 4;
        Component text = this.status;
        if (text == null) {
            text = Component.translatable("gui.routinebags.mount.summary", this.visible.size(), this.bags.size());
        }
        Component visibleText = text;
        if (this.minecraft.font.width(text) > GRID_W) {
            String suffix = "...";
            visibleText = Component.literal(this.minecraft.font.plainSubstrByWidth(text.getString(),
                    GRID_W - this.minecraft.font.width(suffix)) + suffix);
        }
        g.text(this.minecraft.font, visibleText, this.x + PAD, y,
                this.status == null ? VanillaUi.TEXT_DIM : VanillaUi.STATUS);
    }

    private void drawSection(GuiGraphicsExtractor g, int x, int y, int w, int h, Component title) {
        g.text(this.minecraft.font, title, x + 4, y + 1, VanillaUi.TEXT);
    }

    private void drawCell(GuiGraphicsExtractor g, int x, int y) {
        VanillaUi.slot(g, x, y, CELL);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.suppressed || !this.layoutAvailable) {
            return false;
        }
        double mx = event.x();
        double my = event.y();
        if (!this.open && this.tabRect.contains(mx, my)) {
            this.mouseCaptured = true;
            toggleOpen();
            return true;
        }
        if (!this.open) {
            return false;
        }
        if (!isMouseOver(mx, my)) {
            return false;
        }
        this.mouseCaptured = true;
        this.focused = true;
        boolean rightClick = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (this.closeBtn.contains(mx, my)) {
            toggleOpen();
            return true;
        }
        if (this.openBtn.contains(mx, my)) {
            if (busyBlocked()) {
                return true;
            }
            this.minecraft.setScreen(new UnifiedBagScreen(this.minecraft.screen));
            return true;
        }
        if (this.sortBtn.contains(mx, my)) {
            startSort();
            return true;
        }
        if (this.cancelBtn.contains(mx, my)) {
            this.sorter.cancel();
            this.runner.cancelSafely(Component.translatable("gui.routinebags.status.operation_cancelled"));
            this.waitingServerSort = false;
            ServerBridge.cancelTakeRequest(this.pendingTakeRequest);
            this.pendingTakeRequest = -1;
            this.pendingTakeTicks = 0;
            return true;
        }
        if (this.modeBtn.contains(mx, my)) {
            this.sortMode = this.sortMode.next();
            refresh();
            return true;
        }
        if (busyBlocked()) {
            return true;
        }
        if (this.gridRect.contains(mx, my)) {
            handleGridClick(mx, my, rightClick, event.hasShiftDown());
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return consumeMouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return consumeMouseDragged(event.x(), event.y(), event.button());
    }

    public boolean consumeMouseReleased(double mouseX, double mouseY, int button) {
        if (this.suppressed) {
            return false;
        }
        if (this.mouseCaptured || isMouseOver(mouseX, mouseY)) {
            this.mouseCaptured = false;
            return true;
        }
        return false;
    }

    public boolean consumeMouseDragged(double mouseX, double mouseY, int button) {
        return !this.suppressed && (this.mouseCaptured || isMouseOver(mouseX, mouseY));
    }

    private void startSort() {
        if (!InvOps.carried().isEmpty()) {
            this.status = Component.translatable("gui.routinebags.status.cursor_busy");
            return;
        }
        if (this.sorter.isActive() || this.runner.busy() || this.waitingServerSort || this.pendingTakeRequest >= 0
                || RecipeBagSupport.isBusy() || ServerBridge.hasOperationInFlight()) {
            return;
        }
        if (canUseServerSort()) {
            this.waitingServerSort = true;
            this.status = Component.translatable("gui.routinebags.status.server_sorting");
            ServerBridge.requestSort(this.sortMode);
            return;
        }
        this.sorter.start();
    }

    private void handleGridClick(double mx, double my, boolean rightClick, boolean shift) {
        if (!InvOps.carried().isEmpty()) {
            ItemStack carried = InvOps.carried();
            if (!BundleContents.canItemBeInBundle(carried)) {
                this.status = Component.translatable("gui.routinebags.cant_fit");
                return;
            }
            if (isBundleStack(carried)) {
                this.status = Component.translatable("gui.routinebags.status.bundle_cursor_store_blocked");
                return;
            }
            if (totalInsertable(carried) <= 0) {
                this.status = bagsFullMessage(carried);
                return;
            }
            this.status = null;
            if (rightClick) {
                CursorOps.storeOneFromCursor(this.runner);
            } else {
                CursorOps.storeAllFromCursor(this.runner);
            }
            return;
        }
        int col = (int) ((mx - this.gridRect.x) / CELL);
        int row = (int) ((my - this.gridRect.y) / CELL);
        int idx = (this.scrollRow + row) * GRID_COLS + col;
        if (idx < 0 || idx >= this.visible.size()) {
            return;
        }
        Entry entry = this.visible.get(idx);
        if (!entry.anyMutable) {
            this.status = Component.translatable("gui.routinebags.readonly");
            return;
        }
        this.status = null;
        if (ServerBridge.canTakeOnServer()) {
            int available = (int) Math.min(entry.total, entry.display.getMaxStackSize());
            int requested = rightClick ? Math.max(1, available / 2) : available;
            List<dev.lans.routinebags.network.RoutineBagsNetwork.TakeTarget> targets = TakePlanner.plan(entry.key,
                    requested);
            int destination = shift && !rightClick
                    ? dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload.DESTINATION_INVENTORY
                    : dev.lans.routinebags.network.RoutineBagsNetwork.TakeRequestPayload.DESTINATION_CURSOR;
            int requestId = ServerBridge.requestTake(destination, targets);
            if (requestId >= 0) {
                this.pendingTakeRequest = requestId;
                this.pendingTakeTicks = 100;
                this.status = Component.translatable("gui.routinebags.status.server_taking");
                return;
            }
        }
        if (shift && !rightClick) {
            CursorOps.pickupToInventory(this.runner, entry.key);
            return;
        }
        CursorOps.pickupToCursor(this.runner, entry.key, rightClick);
    }

    private @Nullable BagView bestFitBag(ItemStack stack) {
        BagView best = null;
        int bestFit = 0;
        for (BagView bag : this.bags) {
            int fit = bag.maxInsertable(stack);
            if (fit > bestFit) {
                bestFit = fit;
                best = bag;
            }
        }
        return best;
    }

    private int totalInsertable(ItemStack stack) {
        int total = 0;
        for (BagView bag : this.bags) {
            total += bag.maxInsertable(stack);
        }
        return total;
    }

    private Component bagsFullMessage(ItemStack stack) {
        int maxFree = 0;
        for (BagView bag : this.bags) {
            if (bag.kind == BagKind.BUNDLE && bag.mutable) {
                Fraction freeFrac = Fraction.ONE.subtract(bag.weightUsed);
                maxFree += Mth.mulAndTruncate(freeFrac, BagView.DISPLAY_UNITS);
            }
        }
        return Component.translatable("gui.routinebags.status.bags_full_detail", unitsPerItem(stack), maxFree);
    }

    private static int unitsPerItem(ItemStack stack) {
        return Math.max(1, Mth.ceil(BagView.unitWeight(stack).floatValue() * BagView.DISPLAY_UNITS));
    }

    private static boolean isBundleStack(ItemStack stack) {
        return stack.get(DataComponents.BUNDLE_CONTENTS) != null;
    }

    private boolean busyBlocked() {
        if (hasActiveOperation() || RecipeBagSupport.isBusy() || ServerBridge.hasOperationInFlight()) {
            this.status = Component.translatable("gui.routinebags.status.busy");
            return true;
        }
        return false;
    }

    private boolean canUseServerSort() {
        return this.sortMode != SortMode.BY_CREATIVE && ServerBridge.canSortOnServer();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!this.layoutAvailable || !this.open) {
            return false;
        }
        if (!this.gridRect.contains(x, y)) {
            return false;
        }
        int step = shiftDown() ? Math.max(1, this.gridRows - 1) : 1;
        this.scrollRow -= (int) Math.signum(scrollY) * step;
        refresh();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.layoutAvailable) {
            return false;
        }
        if (Keybinds.OPEN_UNIFIED.get().matches(event)) {
            toggleOpen();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        return false;
    }

    public void toggleOpen() {
        if (!this.layoutAvailable) {
            return;
        }
        this.open = !this.open;
        openState = this.open;
        if (!this.open) {
            setFocused(false);
        } else {
            refresh();
        }
    }

    public boolean matchesToggleKey(int keyCode, int scanCode, int modifiers) {
        return Keybinds.OPEN_UNIFIED.get().matches(new KeyEvent(keyCode, scanCode, modifiers));
    }

    public boolean isOpen() {
        return this.open;
    }

    public boolean isLayoutAvailable() {
        return this.layoutAvailable;
    }

    public void setSuppressed(boolean suppressed) {
        if (this.suppressed == suppressed) {
            return;
        }
        this.suppressed = suppressed;
        if (suppressed) {
            this.mouseCaptured = false;
            setFocused(false);
        }
    }

    private boolean shiftDown() {
        return InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (this.suppressed || !this.layoutAvailable) {
            return false;
        }
        if (!this.open && this.tabRect.contains(mouseX, mouseY)) {
            return true;
        }
        return this.open && mouseX >= this.x && mouseX < this.x + W && mouseY >= this.y && mouseY < this.y + this.h;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    @Override
    public boolean isFocused() {
        return this.focused;
    }

    @Override
    public NarrationPriority narrationPriority() {
        return isFocused() ? NarrationPriority.FOCUSED : NarrationPriority.NONE;
    }

    @Override
    public void updateNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.translatable("gui.routinebags.mount.title"));
    }
}
