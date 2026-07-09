package dev.lans.routinebags.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
    private static final int TAB_W = 24;
    private static final int TAB_H = 18;
    private static final int MIN_GRID_ROWS = 3;
    private static final int MAX_GRID_ROWS = 8;

    private static final int COL_PANEL = 0xF0060D16;
    private static final int COL_PANEL_INNER = 0xE90B1522;
    private static final int COL_PANEL_HEADER = 0xF20D2032;
    private static final int COL_BORDER = 0xFF244A63;
    private static final int COL_BORDER_BRIGHT = 0xFF3E8CB0;
    private static final int COL_CELL = 0xFF101A26;
    private static final int COL_CELL_INSET = 0xFF07101A;
    private static final int COL_CELL_HOVER = 0x663FAFD6;
    private static final int COL_TEXT_DIM = 0xFF8BA8B8;
    private static final int COL_TEXT_AE = 0xFF77D8F0;
    private static final int COL_BTN = 0xFF12283A;
    private static final int COL_BTN_HOVER = 0xFF1B3F57;
    private static final int COL_BAR_BG = 0xFF071018;
    private static final int COL_BAR_GRID = 0x663E8CB0;

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
    private String query = "";
    private int scrollRow;
    private int bagFilter = -1;
    private SortMode sortMode = ClientConfig.SORT_MODE.get();
    private @Nullable Component status;
    private boolean sorterWasActive;
    private boolean waitingServerSort;
    private boolean focused;
    private boolean open = openState;

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
    private Rect searchClearBtn = new Rect(0, 0, 0, 0);
    private EditBox searchBox;

    public MountedBagPanel(AbstractContainerMenu mountedMenu) {
        this.mountedMenu = mountedMenu;
        this.searchBox = new EditBox(this.minecraft.font, 0, 0, 10, 12, Component.translatable("gui.routinebags.search_hint"));
        this.searchBox.setHint(Component.translatable("gui.routinebags.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setMaxLength(60);
        this.searchBox.setResponder(s -> {
            this.query = s.toLowerCase(Locale.ROOT).trim();
            this.scrollRow = 0;
            refresh();
        });
    }

    public void layout(int screenW, int screenH, int containerLeft, int containerTop, int containerW, int containerH) {
        this.gridRows = Math.clamp((screenH - 88) / CELL, MIN_GRID_ROWS, MAX_GRID_ROWS);
        this.gridH = this.gridRows * CELL;
        this.h = PAD + 16 + 16 + this.gridH + 4 + BTN_H + 16 + PAD;
        int rightX = containerLeft + containerW + 8;
        int leftX = containerLeft - W - 8;
        if (rightX + W <= screenW - 4) {
            this.x = rightX;
        } else if (leftX >= 4) {
            this.x = leftX;
        } else {
            this.x = Math.max(4, screenW - W - 4);
        }
        this.y = Mth.clamp(containerTop, 4, Math.max(4, screenH - this.h - 4));
        this.tabRect = new Rect(tabX(screenW, containerLeft, containerW), Mth.clamp(this.y + 8, 4, Math.max(4, screenH - TAB_H - 4)), TAB_W, TAB_H);
        int innerX = this.x + PAD;
        this.searchBox.setX(innerX);
        this.searchBox.setY(this.y + PAD + 16);
        this.searchBox.setSize(GRID_W - 14, 12);
        this.searchClearBtn = new Rect(innerX + GRID_W - 12, this.y + PAD + 16, 12, 12);
        this.gridRect = new Rect(innerX, this.y + PAD + 32, GRID_W, this.gridH);
        int btnY = this.gridRect.y + this.gridH + 4;
        this.sortBtn = new Rect(innerX, btnY, 34, BTN_H);
        this.cancelBtn = new Rect(innerX + 38, btnY, 34, BTN_H);
        this.modeBtn = new Rect(innerX + 76, btnY, GRID_W - 76, BTN_H);
        this.openBtn = new Rect(innerX, btnY + BTN_H + 3, GRID_W, 11);
        refresh();
    }

    private int tabX(int screenW, int containerLeft, int containerW) {
        int rightX = containerLeft + containerW + 2;
        int leftX = containerLeft - TAB_W - 2;
        if (rightX + TAB_W <= screenW - 4) {
            return rightX;
        }
        if (leftX >= 4) {
            return leftX;
        }
        return Math.max(4, screenW - TAB_W - 4);
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
            if (!e.matchesQuery(this.query)) {
                continue;
            }
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
        drawTab(g, mouseX, mouseY);
        if (!this.open || this.h <= 0) {
            return;
        }
        g.fill(this.x, this.y, this.x + W, this.y + this.h, COL_PANEL);
        g.fill(this.x + 1, this.y + 1, this.x + W - 1, this.y + 17, COL_PANEL_HEADER);
        g.outline(this.x, this.y, W, this.h, COL_BORDER_BRIGHT);
        g.outline(this.x + 2, this.y + 2, W - 4, this.h - 4, COL_BORDER);
        g.text(this.minecraft.font, Component.translatable("gui.routinebags.mount.title"), this.x + PAD, this.y + 5, COL_TEXT_AE);

        this.searchBox.extractRenderState(g, mouseX, mouseY, partialTick);
        drawSearchClear(g, mouseX, mouseY);
        drawGrid(g, mouseX, mouseY);
        drawButtons(g, mouseX, mouseY);
        drawStatus(g);
    }

    private void drawTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean hover = this.tabRect.contains(mouseX, mouseY);
        int fill = this.open ? COL_BTN_HOVER : COL_BTN;
        g.fill(this.tabRect.x, this.tabRect.y, this.tabRect.x + this.tabRect.w, this.tabRect.y + this.tabRect.h,
                hover ? COL_BTN_HOVER : fill);
        g.outline(this.tabRect.x, this.tabRect.y, this.tabRect.w, this.tabRect.h, hover || this.open ? COL_BORDER_BRIGHT : COL_BORDER);
        g.centeredText(this.minecraft.font, Component.literal(this.open ? "<" : "RT"),
                this.tabRect.x + this.tabRect.w / 2, this.tabRect.y + 5, this.open ? COL_TEXT_DIM : COL_TEXT_AE);
        if (!this.statusBadgeText().isEmpty()) {
            g.fill(this.tabRect.x + this.tabRect.w - 5, this.tabRect.y + 2, this.tabRect.x + this.tabRect.w - 2, this.tabRect.y + 5, 0xFFE0C060);
        }
        if (hover) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable(this.open
                    ? "gui.routinebags.mount.tooltip_collapse"
                    : "gui.routinebags.mount.tooltip_expand")), mouseX, mouseY);
        }
    }

    private String statusBadgeText() {
        return (this.runner.busy() || this.sorter.isActive() || this.waitingServerSort) ? "!" : "";
    }

    private void drawSearchClear(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (this.query.isEmpty()) {
            return;
        }
        boolean hover = this.searchClearBtn.contains(mouseX, mouseY);
        g.fill(this.searchClearBtn.x, this.searchClearBtn.y, this.searchClearBtn.x + this.searchClearBtn.w,
                this.searchClearBtn.y + this.searchClearBtn.h, hover ? COL_BTN_HOVER : COL_BTN);
        g.centeredText(this.minecraft.font, Component.literal("x"), this.searchClearBtn.x + 6, this.searchClearBtn.y + 2, 0xFFE0E0E0);
        if (hover) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable("gui.routinebags.clear_search")), mouseX, mouseY);
        }
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawSection(g, this.gridRect.x - 3, this.gridRect.y - 11, GRID_W + 6, this.gridH + 14,
                Component.translatable("gui.routinebags.section.grid"));
        for (int row = 0; row < this.gridRows; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                drawCell(g, this.gridRect.x + col * CELL, this.gridRect.y + row * CELL);
            }
        }
        if (!InvOps.hasReachablePlayerInventory()) {
            g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.mount.unavailable"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, COL_TEXT_DIM);
            return;
        }
        if (this.bags.isEmpty()) {
            g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.no_bags"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, COL_TEXT_DIM);
            return;
        }
        if (this.visible.isEmpty()) {
            Component empty = this.query.isEmpty()
                    ? Component.translatable("gui.routinebags.no_items")
                    : Component.translatable("gui.routinebags.no_search_results", this.query);
            g.centeredText(this.minecraft.font, empty, this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, COL_TEXT_DIM);
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
                g.fill(x + CELL - 5, y + 1, x + CELL - 2, y + 4, 0xFFCC5555);
            }
            if (mouseX >= x && mouseX < x + CELL - 1 && mouseY >= y && mouseY < y + CELL - 1) {
                g.fill(x, y, x + CELL - 1, y + CELL - 1, COL_CELL_HOVER);
                g.outline(x, y, CELL - 1, CELL - 1, COL_BORDER_BRIGHT);
                hovered = entry;
            }
        }
        int totalRows = Math.max(1, (this.visible.size() + GRID_COLS - 1) / GRID_COLS);
        if (totalRows > this.gridRows) {
            int trackX = this.gridRect.x + GRID_W + 1;
            g.fill(trackX, this.gridRect.y, trackX + 3, this.gridRect.y + this.gridH, COL_BAR_BG);
            int thumbH = Math.max(8, this.gridH * this.gridRows / totalRows);
            int thumbY = this.gridRect.y + (this.gridH - thumbH) * this.scrollRow / Math.max(1, totalRows - this.gridRows);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, COL_BORDER);
        }
        if (hovered != null) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, entryTooltip(hovered), mouseX, mouseY, hovered.display);
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
                canOperate && !this.sorter.isActive() && !this.waitingServerSort);
        drawButton(g, this.cancelBtn, Component.translatable("gui.routinebags.cancel"), mouseX, mouseY,
                this.sorter.isActive() || this.runner.busy() || this.waitingServerSort);
        drawButton(g, this.modeBtn, compactModeLabel(), mouseX, mouseY, true);
        boolean openHover = this.openBtn.contains(mouseX, mouseY);
        g.fill(this.openBtn.x, this.openBtn.y, this.openBtn.x + this.openBtn.w, this.openBtn.y + this.openBtn.h, openHover ? COL_BTN_HOVER : COL_BTN);
        g.outline(this.openBtn.x, this.openBtn.y, this.openBtn.w, this.openBtn.h, openHover ? COL_BORDER_BRIGHT : COL_BORDER);
        g.centeredText(this.minecraft.font, Component.translatable("gui.routinebags.mount.open_full"),
                this.openBtn.x + this.openBtn.w / 2, this.openBtn.y + 2, COL_TEXT_DIM);
        if (this.sortBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.minecraft.font, List.of(Component.translatable(ServerBridge.canSortOnServer()
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
        g.fill(r.x, r.y, r.x + r.w, r.y + r.h, hover ? COL_BTN_HOVER : COL_BTN);
        g.outline(r.x, r.y, r.w, r.h, hover ? COL_BORDER_BRIGHT : COL_BORDER);
        g.centeredText(this.minecraft.font, label, r.x + r.w / 2, r.y + (r.h - 8) / 2, enabled ? COL_TEXT_AE : 0xFF607080);
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int y = this.openBtn.y + this.openBtn.h + 4;
        Component text = this.status;
        if (text == null) {
            text = Component.translatable("gui.routinebags.mount.summary", this.visible.size(), this.bags.size());
        }
        g.text(this.minecraft.font, text, this.x + PAD, y, this.status == null ? COL_TEXT_DIM : 0xFFE0C060);
    }

    private void drawSection(GuiGraphicsExtractor g, int x, int y, int w, int h, Component title) {
        g.fill(x, y, x + w, y + h, COL_PANEL_INNER);
        g.outline(x, y, w, h, COL_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, COL_PANEL_HEADER);
        g.text(this.minecraft.font, title, x + 4, y + 1, COL_TEXT_AE);
    }

    private void drawCell(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + CELL - 1, y + CELL - 1, COL_CELL);
        g.fill(x + 1, y + 1, x + CELL - 2, y + CELL - 2, COL_CELL_INSET);
        g.outline(x, y, CELL - 1, CELL - 1, COL_BAR_GRID);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (this.tabRect.contains(mx, my)) {
            toggleOpen();
            return true;
        }
        if (!this.open) {
            return false;
        }
        if (!isMouseOver(mx, my)) {
            this.searchBox.setFocused(false);
            return false;
        }
        this.focused = true;
        boolean rightClick = event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        if (this.searchClearBtn.contains(mx, my) && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (this.searchBox.isMouseOver(mx, my) && rightClick && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (this.searchBox.mouseClicked(event, doubleClick)) {
            return true;
        }
        this.searchBox.setFocused(false);
        if (this.openBtn.contains(mx, my)) {
            if (this.minecraft.screen != null) {
                this.minecraft.screen.onClose();
            }
            this.minecraft.setScreen(new UnifiedBagScreen());
            return true;
        }
        if (this.sortBtn.contains(mx, my)) {
            startSort();
            return true;
        }
        if (this.cancelBtn.contains(mx, my)) {
            this.sorter.cancel();
            this.runner.clear();
            this.waitingServerSort = false;
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

    private void startSort() {
        if (!InvOps.carried().isEmpty()) {
            this.status = Component.translatable("gui.routinebags.status.cursor_busy");
            return;
        }
        if (this.sorter.isActive() || this.runner.busy() || this.waitingServerSort) {
            return;
        }
        if (ServerBridge.canSortOnServer()) {
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
            if (bestFitBag(carried) == null) {
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

    private Component bagsFullMessage(ItemStack stack) {
        int maxFree = 0;
        for (BagView bag : this.bags) {
            if (bag.kind == BagKind.BUNDLE && bag.mutable) {
                Fraction freeFrac = Fraction.ONE.subtract(bag.weightUsed);
                maxFree = Math.max(maxFree, Mth.mulAndTruncate(freeFrac, BagView.DISPLAY_UNITS));
            }
        }
        return Component.translatable("gui.routinebags.status.bags_full_detail", unitsPerItem(stack), maxFree);
    }

    private static int unitsPerItem(ItemStack stack) {
        return Math.max(1, Mth.ceil(BagView.unitWeight(stack).floatValue() * BagView.DISPLAY_UNITS));
    }

    private boolean busyBlocked() {
        if (this.runner.busy() || this.sorter.isActive() || this.waitingServerSort) {
            this.status = Component.translatable("gui.routinebags.status.busy");
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (!this.open) {
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
        if (Keybinds.OPEN_UNIFIED.get().matches(event)) {
            toggleOpen();
            return true;
        }
        if (!this.open) {
            return false;
        }
        if (!this.focused && !this.searchBox.isFocused()) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_SLASH && !this.searchBox.isFocused()) {
            this.searchBox.setFocused(true);
            return true;
        }
        return this.searchBox.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!this.open) {
            return false;
        }
        return this.searchBox.charTyped(event);
    }

    public void toggleOpen() {
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

    private void clearSearch() {
        this.query = "";
        this.scrollRow = 0;
        this.searchBox.setValue("");
        refresh();
    }

    private boolean shiftDown() {
        return InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (this.tabRect.contains(mouseX, mouseY)) {
            return true;
        }
        return this.open && mouseX >= this.x && mouseX < this.x + W && mouseY >= this.y && mouseY < this.y + this.h;
    }

    @Override
    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            this.searchBox.setFocused(false);
        }
    }

    @Override
    public boolean isFocused() {
        return this.focused || this.searchBox.isFocused();
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
