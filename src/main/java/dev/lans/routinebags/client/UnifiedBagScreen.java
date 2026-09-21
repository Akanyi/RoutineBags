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
import dev.lans.routinebags.merge.AggregatedIndex.Source;
import dev.lans.routinebags.merge.ItemKey;
import dev.lans.routinebags.sort.SortController;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 统一收纳视图：把身上所有袋子的内容聚合成一个虚拟大背包。
 * 这不是容器屏幕——服务器根本不知道它开着。所有可见状态直接读玩家背包菜单，
 * 所有修改都是合法点击。
 *
 * 交互约定与原版对齐：普通左右键 = 原版拿起/放下/分堆语义（直接转发点击包），
 * Shift+左键 = 本 mod 的智能操作（存入最合适的袋子）。聚合网格是虚拟区域，
 * 空光标点击 = 智能取出，光标有物品点击 = 存入最合适的袋子。
 */
public final class UnifiedBagScreen extends Screen {

    private static final int PAD = 8;
    private static final int CELL = 18;
    private static final int GRID_COLS = 9;
    private static final int GRID_W = GRID_COLS * CELL;
    private static final int SIDEBAR_W = 112;
    private static final int SIDEBAR_ROW_H = 20;
    private static final int BTN_H = 14;
    private static final int IMG_W = PAD + GRID_W + 6 + SIDEBAR_W + PAD;
    private static final int GRID_TOP = 35;
    private static final int INVENTORY_GAP = 17;
    private static final int MIN_GRID_ROWS = 2;
    private static final int MAX_GRID_ROWS = 10;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    private final StepRunner runner = new StepRunner();
    private final SortController sorter = new SortController();
    private final @Nullable Screen parent;

    private List<BagView> bags = List.of();
    private List<Entry> entries = List.of();
    private final List<Entry> visible = new ArrayList<>();

    private EditBox searchBox;
    private String query = "";
    private int scrollRow;
    private int bagScroll;
    private int bagFilter = -1;
    private SortMode sortMode = ClientConfig.SORT_MODE.get();
    private @Nullable Component status;
    private boolean sorterWasActive;
    private boolean waitingServerSort;
    private int pendingTakeRequest = -1;
    private int pendingTakeTicks;
    private boolean closeWhenRunnerStops;
    private boolean draggingGridScroll;
    private boolean draggingBagScroll;

    private int left;
    private int top;
    private int imgH;
    private int gridRows;
    private int gridH;
    private int sidebarVisibleRows;
    private Rect gridRect;
    private Rect sidebarRect;
    private Rect sortBtn;
    private Rect cancelBtn;
    private Rect modeBtn;
    private Rect searchClearBtn;
    private Rect invRect;
    private Rect hotbarRect;
    private Rect offhandRect;

    public UnifiedBagScreen() {
        this(null);
    }

    public UnifiedBagScreen(@Nullable Screen parent) {
        super(Component.translatable("gui.routinebags.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int invH = 3 * CELL + 4 + CELL;
        int footerH = 16;
        int fixedH = GRID_TOP + INVENTORY_GAP + invH + footerH + PAD;
        this.gridRows = Math.clamp((this.height - fixedH - 10) / CELL, MIN_GRID_ROWS, MAX_GRID_ROWS);
        this.gridH = this.gridRows * CELL;
        this.imgH = fixedH + this.gridH;
        this.left = (this.width - IMG_W) / 2;
        this.top = (this.height - this.imgH) / 2;

        int gridX = this.left + PAD;
        int gridY = this.top + GRID_TOP;
        this.gridRect = new Rect(gridX, gridY, GRID_W, this.gridH);
        this.sidebarRect = new Rect(gridX + GRID_W + 6, gridY, SIDEBAR_W, this.gridH);
        this.sidebarVisibleRows = Math.max(1, (this.gridH - 12) / SIDEBAR_ROW_H);

        int invY = gridY + this.gridH + INVENTORY_GAP;
        this.invRect = new Rect(gridX, invY, GRID_W, 3 * CELL);
        this.hotbarRect = new Rect(gridX, invY + 3 * CELL + 4, GRID_W, CELL);
        this.offhandRect = new Rect(gridX + GRID_W + 6, invY + 3 * CELL + 4, CELL, CELL);
        int controlsX = this.sidebarRect.x;
        this.sortBtn = new Rect(controlsX, invY, 54, BTN_H);
        this.cancelBtn = new Rect(controlsX + 58, invY, 54, BTN_H);
        this.modeBtn = new Rect(controlsX, invY + BTN_H + 4, SIDEBAR_W, BTN_H);

        this.searchBox = new EditBox(this.font, this.left + IMG_W - PAD - 108, this.top + 6, 94, 12,
                Component.translatable("gui.routinebags.search_hint"));
        this.searchClearBtn = new Rect(this.left + IMG_W - PAD - 12, this.top + 6, 12, 12);
        this.searchBox.setHint(Component.translatable("gui.routinebags.search_hint").withStyle(ChatFormatting.DARK_GRAY));
        this.searchBox.setMaxLength(60);
        this.searchBox.setValue(this.query);
        this.searchBox.setResponder(s -> {
            this.query = s.toLowerCase(Locale.ROOT).trim();
            this.scrollRow = 0;
        });
        this.addRenderableWidget(this.searchBox);

        refresh();
    }

    @Override
    public void tick() {
        refresh();
        this.runner.tick(ClientConfig.OPS_PER_TICK.get(), ClientConfig.STEP_DELAY_TICKS.get());
        this.sorter.tick(this.runner, this.sortMode);

        var serverSortResult = ServerBridge.takeSortResult();
        if (serverSortResult != null) {
            this.waitingServerSort = false;
            this.status = Component.translatable(serverSortResult.messageKey(), serverSortResult.moves());
        }
        var serverStoreResultV3 = ServerBridge.takeStoreResultV3();
        if (serverStoreResultV3 != null) {
            this.waitingServerSort = false;
            this.status = Component.translatable(serverStoreResultV3.messageKey(), serverStoreResultV3.moved());
            refresh();
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
            ServerBridge.cancelStoreRequest();
            ServerBridge.cancelTakeRequest(this.pendingTakeRequest);
            this.pendingTakeRequest = -1;
            this.pendingTakeTicks = 0;
        } else if (this.sorter.isActive()) {
            this.status = this.sorter.status();
        } else if (this.waitingServerSort) {
            if (this.status == null) {
                this.status = Component.translatable("gui.routinebags.status.server_sorting");
            }
        } else if (this.sorterWasActive) {
            this.status = this.sorter.status();
        }
        this.sorterWasActive = this.sorter.isActive();
        if (this.closeWhenRunnerStops && !this.runner.busy()) {
            this.closeWhenRunnerStops = false;
            closeImmediately();
        }
    }

    private void refresh() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        this.bags = BagScanner.scan(this.minecraft.player, ClientConfig.SHOW_READ_ONLY.get());
        if (this.bagFilter >= this.bags.size()) {
            this.bagFilter = -1;
        }
        this.bagScroll = Math.clamp(this.bagScroll, 0, Math.max(0, this.bags.size() - this.sidebarVisibleRows));
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

    // ---------------------------------------------------------------- render

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        VanillaUi.panel(g, this.left, this.top, IMG_W, this.imgH);
        VanillaUi.text(g, this.font, this.title, this.left + PAD, this.top + 7, VanillaUi.TEXT);

        g.nextStratum();
        super.extractRenderState(g, mouseX, mouseY, a);

        drawHeader(g, mouseX, mouseY);
        drawGrid(g, mouseX, mouseY);
        drawSidebar(g, mouseX, mouseY);
        drawButtons(g, mouseX, mouseY);
        drawPlayerInventory(g, mouseX, mouseY);
        drawStatus(g);
        drawModeBadge(g, mouseX, mouseY);
        drawCarried(g, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!this.query.isEmpty()) {
            boolean hover = this.searchClearBtn.contains(mouseX, mouseY);
            VanillaUi.button(g, this.searchClearBtn.x, this.searchClearBtn.y, this.searchClearBtn.w,
                    this.searchClearBtn.h, hover, true);
            VanillaUi.centeredText(g, this.font, Component.literal("×"), this.searchClearBtn.x + 6,
                    this.searchClearBtn.y + 2, VanillaUi.buttonText(hover, true));
            if (hover) {
                g.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable("gui.routinebags.clear_search")), mouseX, mouseY);
            }
        }
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawSection(g, this.gridRect.x - 3, this.gridRect.y - 13, GRID_W + 6, this.gridH + 16,
                Component.translatable("gui.routinebags.section.grid"));
        for (int row = 0; row < this.gridRows; row++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int x = this.gridRect.x + col * CELL;
                int y = this.gridRect.y + row * CELL;
                drawCell(g, x, y);
            }
        }
        if (this.bags.isEmpty()) {
            VanillaUi.centeredText(g, this.font, Component.translatable("gui.routinebags.no_bags"),
                    this.gridRect.x + GRID_W / 2, this.gridRect.y + this.gridH / 2 - 4, VanillaUi.TEXT_DIM);
            return;
        }
        if (this.visible.isEmpty()) {
            Component empty = this.query.isEmpty()
                    ? Component.translatable("gui.routinebags.no_items")
                    : Component.translatable("gui.routinebags.no_search_results", this.query);
            VanillaUi.centeredText(g, this.font, empty, this.gridRect.x + GRID_W / 2,
                    this.gridRect.y + this.gridH / 2 - 4, VanillaUi.TEXT_DIM);
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
            g.itemDecorations(this.font, entry.display, x + 1, y + 1, AggregatedIndex.formatCount(entry.total));
            if (!entry.anyMutable) {
                g.fill(x + CELL - 5, y + 1, x + CELL - 2, y + 4, VanillaUi.DANGER);
            }
            if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                VanillaUi.slotHover(g, x, y, CELL);
                hovered = entry;
            }
        }
        ScrollMetrics gridScroll = gridScrollMetrics();
        if (gridScroll != null) {
            VanillaUi.scrollbar(g, gridScroll.trackX, gridScroll.trackY, gridScroll.trackH,
                    gridScroll.thumbY, gridScroll.thumbH);
        }
        if (hovered != null) {
            g.setTooltipForNextFrame(this.font, entryTooltip(hovered), hovered.display.getTooltipImage(),
                    mouseX, mouseY, hovered.display.get(DataComponents.TOOLTIP_STYLE));
        }
    }

    private List<Component> entryTooltip(Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(entry.display.getHoverName());
        lines.add(Component.translatable("gui.routinebags.total", entry.total).withStyle(ChatFormatting.GOLD));
        for (Source src : entry.sources) {
            if (src.bagOrdinal() >= this.bags.size()) {
                continue;
            }
            BagView bag = this.bags.get(src.bagOrdinal());
            Component line = Component.literal("  #" + (src.bagOrdinal() + 1) + " ")
                    .append(bag.displayName().copy().withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" ×" + src.count()).withStyle(ChatFormatting.WHITE));
            if (!src.mutable()) {
                line = line.copy().append(Component.translatable("gui.routinebags.readonly_tag").withStyle(ChatFormatting.RED));
            }
            lines.add(line);
        }
        if (entry.anyMutable) {
            lines.add(Component.translatable("gui.routinebags.hint_extract").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            lines.add(Component.translatable("gui.routinebags.readonly").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawSection(g, this.sidebarRect.x - 3, this.sidebarRect.y - 13, SIDEBAR_W + 6, this.gridH + 16,
                Component.translatable("gui.routinebags.section.bags"));
        int shown = Math.min(this.bags.size() - this.bagScroll, this.sidebarVisibleRows);
        for (int row = 0; row < shown; row++) {
            int ordinal = this.bagScroll + row;
            BagView bag = this.bags.get(ordinal);
            int x = this.sidebarRect.x;
            int y = this.sidebarRect.y + row * SIDEBAR_ROW_H;
            boolean hover = mouseX >= x && mouseX < x + SIDEBAR_W && mouseY >= y && mouseY < y + SIDEBAR_ROW_H - 2;
            boolean selected = this.bagFilter == ordinal;
            int rowW = SIDEBAR_W - (bagScrollMetrics() != null ? VanillaUi.SCROLLBAR_W + 1 : 0);
            VanillaUi.listRow(g, x, y, rowW, SIDEBAR_ROW_H - 2, hover, selected);
            g.item(bag.bagStack, x + 1, y + 1);
            String label = "#" + (ordinal + 1);
            VanillaUi.text(g, this.font, label, x + 20, y + 1,
                    bag.mutable ? VanillaUi.TEXT : VanillaUi.DANGER);
            int barX = x + 20 + this.font.width(label) + 3;
            int barW = Math.max(8, rowW - (barX - x) - 4);
            float pct = bag.fillFraction();
            VanillaUi.progressBar(g, barX, y + 3, barW, pct, capacityColor(pct));
            VanillaUi.text(g, this.font, capacityText(bag), x + 20, y + 10, VanillaUi.TEXT_DIM);
            if (hover) {
                // 原版 bundle 预览组件白嫖：自带内容网格和选中高亮
                g.setTooltipForNextFrame(this.font, bagTooltip(bag, ordinal),
                        bag.bagStack.getTooltipImage(), mouseX, mouseY, bag.bagStack.get(DataComponents.TOOLTIP_STYLE));
            }
        }
        ScrollMetrics bagScrollBar = bagScrollMetrics();
        if (bagScrollBar != null) {
            VanillaUi.scrollbar(g, bagScrollBar.trackX, bagScrollBar.trackY, bagScrollBar.trackH,
                    bagScrollBar.thumbY, bagScrollBar.thumbH);
        }
        int usedUnits = 0;
        int totalUnits = 0;
        for (BagView bag : this.bags) {
            if (bag.kind == BagKind.BUNDLE && bag.mutable) {
                usedUnits += bag.usedUnits();
                totalUnits += BagView.DISPLAY_UNITS;
            }
        }
        if (!this.bags.isEmpty()) {
            Component footer;
            if (this.bags.size() > this.sidebarVisibleRows && totalUnits > 0) {
                footer = Component.translatable("gui.routinebags.bag_footer",
                        this.bagScroll + 1, Math.min(this.bagScroll + shown, this.bags.size()), this.bags.size(),
                        usedUnits, totalUnits);
            } else if (this.bags.size() > this.sidebarVisibleRows) {
                footer = Component.translatable("gui.routinebags.bag_footer_page",
                        this.bagScroll + 1, Math.min(this.bagScroll + shown, this.bags.size()), this.bags.size());
            } else if (totalUnits > 0) {
                footer = Component.translatable("gui.routinebags.bag_footer_capacity", usedUnits, totalUnits);
            } else {
                footer = Component.empty();
            }
            VanillaUi.text(g, this.font, footer, this.sidebarRect.x + 2,
                    this.sidebarRect.y + this.gridH - 9, VanillaUi.TEXT_DIM);
        }
    }

    private String capacityText(BagView bag) {
        if (bag.kind == BagKind.BUNDLE) {
            return bag.usedUnits() + "/" + BagView.DISPLAY_UNITS;
        }
        return bag.slotCapacity > 0 ? bag.slotsUsed + "/" + bag.slotCapacity : String.valueOf(bag.slotsUsed);
    }

    private List<Component> bagTooltip(BagView bag, int ordinal) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("#" + (ordinal + 1) + " ").append(bag.displayName()));
        if (bag.kind == BagKind.BUNDLE) {
            lines.add(Component.translatable("gui.routinebags.capacity_units", bag.usedUnits(), BagView.DISPLAY_UNITS)
                    .withStyle(ChatFormatting.GRAY));
            int sel = BundleItem.getSelectedItemIndex(InvOps.stackAt(bag.menuSlot));
            if (sel >= 0 && sel < bag.entries.size()) {
                lines.add(Component.translatable("gui.routinebags.selected_entry",
                        bag.entries.get(sel).getHoverName()).withStyle(ChatFormatting.AQUA));
            }
            lines.add(Component.translatable("gui.routinebags.hint_bag_vanilla").withStyle(ChatFormatting.DARK_GRAY));
        } else if (bag.slotCapacity > 0) {
            lines.add(Component.translatable("gui.routinebags.capacity_slots", bag.slotsUsed, bag.slotCapacity)
                    .withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("gui.routinebags.capacity_slots_unknown", bag.slotsUsed)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!bag.mutable) {
            lines.add(Component.translatable("gui.routinebags.readonly").withStyle(ChatFormatting.RED));
        }
        lines.add(Component.translatable("gui.routinebags.hint_filter").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static int capacityColor(float pct) {
        if (pct >= 0.999F) {
            return 0xFFCC5555;
        }
        return pct > 0.75F ? 0xFFCCAA44 : 0xFF55BB66;
    }

    private void drawButtons(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawButton(g, this.sortBtn, Component.translatable("gui.routinebags.sort"), mouseX, mouseY,
                !this.sorter.isActive() && !this.runner.busy() && !this.waitingServerSort
                        && this.pendingTakeRequest < 0 && !RecipeBagSupport.isBusy()
                        && !ServerBridge.hasOperationInFlight());
        drawButton(g, this.cancelBtn, Component.translatable("gui.routinebags.cancel"), mouseX, mouseY,
                this.sorter.isActive() || this.runner.busy() || this.waitingServerSort || this.pendingTakeRequest >= 0);
        drawButton(g, this.modeBtn, Component.translatable(this.sortMode.translationKey()), mouseX, mouseY, true);
        if (this.sortBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable(canUseServerSort()
                    ? "gui.routinebags.tooltip.sort_server"
                    : "gui.routinebags.tooltip.sort_client")), mouseX, mouseY);
        } else if (this.cancelBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable("gui.routinebags.tooltip.cancel")), mouseX, mouseY);
        } else if (this.modeBtn.contains(mouseX, mouseY)) {
            g.setComponentTooltipForNextFrame(this.font, List.of(Component.translatable("gui.routinebags.tooltip.mode")), mouseX, mouseY);
        }
    }

    private void drawButton(GuiGraphicsExtractor g, Rect r, Component label, int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && r.contains(mouseX, mouseY);
        VanillaUi.button(g, r.x, r.y, r.w, r.h, hover, enabled);
        VanillaUi.centeredText(g, this.font, label, r.x + r.w / 2, r.y + (r.h - 8) / 2,
                VanillaUi.buttonText(hover, enabled));
    }

    private void drawPlayerInventory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        drawSection(g, this.invRect.x - 3, this.invRect.y - 13, GRID_W + 6, 4 * CELL + 20,
                Component.translatable("gui.routinebags.section.inventory"));
        ItemStack hoveredStack = ItemStack.EMPTY;
        for (int i = 0; i < 27; i++) {
            int menuSlot = InventoryMenu.INV_SLOT_START + i;
            int x = this.invRect.x + (i % 9) * CELL;
            int y = this.invRect.y + (i / 9) * CELL;
            hoveredStack = drawInvCell(g, menuSlot, x, y, mouseX, mouseY, hoveredStack);
        }
        for (int i = 0; i < 9; i++) {
            int menuSlot = InventoryMenu.USE_ROW_SLOT_START + i;
            hoveredStack = drawInvCell(g, menuSlot, this.hotbarRect.x + i * CELL, this.hotbarRect.y, mouseX, mouseY, hoveredStack);
        }
        hoveredStack = drawInvCell(g, InventoryMenu.SHIELD_SLOT, this.offhandRect.x, this.offhandRect.y, mouseX, mouseY, hoveredStack);
        if (!hoveredStack.isEmpty() && this.minecraft != null && InvOps.carried().isEmpty()) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(this.minecraft, hoveredStack));
            if (BundleContents.canItemBeInBundle(hoveredStack)) {
                lines.add(Component.translatable("gui.routinebags.weight_per_item", unitsPerItem(hoveredStack))
                        .withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("gui.routinebags.hint_store").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add(Component.translatable("gui.routinebags.cant_fit").withStyle(ChatFormatting.RED));
            }
            g.setTooltipForNextFrame(this.font, lines, hoveredStack.getTooltipImage(),
                    mouseX, mouseY, hoveredStack.get(DataComponents.TOOLTIP_STYLE));
        }
    }

    private ItemStack drawInvCell(GuiGraphicsExtractor g, int menuSlot, int x, int y, int mouseX, int mouseY, ItemStack hovered) {
        drawCell(g, x, y);
        ItemStack stack = InvOps.stackAt(menuSlot);
        if (!stack.isEmpty()) {
            g.item(stack, x + 1, y + 1);
            g.itemDecorations(this.font, stack, x + 1, y + 1);
        }
        if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
            VanillaUi.slotHover(g, x, y, CELL);
            return stack;
        }
        return hovered;
    }

    private void drawSection(GuiGraphicsExtractor g, int x, int y, int w, int h, Component title) {
        VanillaUi.text(g, this.font, title, x + 4, y + 2, VanillaUi.TEXT);
    }

    private void drawCell(GuiGraphicsExtractor g, int x, int y) {
        VanillaUi.slot(g, x, y, CELL);
    }

    private void drawStatus(GuiGraphicsExtractor g) {
        int y = this.top + this.imgH - 12;
        Component filterNote = null;
        int filterWidth = 0;
        if (this.bagFilter >= 0 && this.bagFilter < this.bags.size()) {
            filterNote = Component.translatable("gui.routinebags.filter_bag", "#" + (this.bagFilter + 1));
            filterWidth = this.font.width(filterNote);
            VanillaUi.text(g, this.font, filterNote, this.left + IMG_W - PAD - filterWidth, y, VanillaUi.TEXT_DIM);
        }
        Component leftText = this.status != null ? this.status : summaryText();
        int maxWidth = IMG_W - PAD * 2 - (filterNote == null ? 0 : filterWidth + 8);
        VanillaUi.text(g, this.font, fitText(leftText, maxWidth), this.left + PAD, y,
                this.status != null ? VanillaUi.STATUS : VanillaUi.TEXT_DIM);
    }

    private Component summaryText() {
        int totalStacks = 0;
        for (Entry entry : this.entries) {
            totalStacks += entry.sources.size();
        }
        return Component.translatable("gui.routinebags.summary",
                this.visible.size(), this.entries.size(), this.bags.size(), totalStacks);
    }

    private void drawModeBadge(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Component label = ServerBridge.modeLabel();
        int x = this.sidebarRect.x + 2;
        int y = this.modeBtn.y + this.modeBtn.h + 5;
        int w = Math.min(SIDEBAR_W - 2, this.font.width(label) + 8);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11;
        g.fill(x, y + 3, x + 3, y + 6, hover ? VanillaUi.STATUS : VanillaUi.TEXT_DIM);
        VanillaUi.text(g, this.font, label, x + 7, y, hover ? VanillaUi.STATUS : VanillaUi.TEXT_DIM);
        if (hover) {
            g.setComponentTooltipForNextFrame(this.font, List.of(ServerBridge.providerTooltip()), mouseX, mouseY);
        }
    }

    private Component fitText(Component text, int maxWidth) {
        if (maxWidth <= 0) {
            return Component.empty();
        }
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        String suffix = "…";
        return Component.literal(this.font.plainSubstrByWidth(text.getString(),
                Math.max(0, maxWidth - this.font.width(suffix))) + suffix);
    }

    private void drawCarried(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        ItemStack carried = InvOps.carried();
        if (!carried.isEmpty()) {
            g.nextStratum();
            g.item(carried, mouseX - 8, mouseY - 8);
            g.itemDecorations(this.font, carried, mouseX - 8, mouseY - 8);
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        double mx = event.x();
        double my = event.y();
        boolean rightClick = event.button() == 1;
        if (this.searchBox != null) {
            this.searchBox.setFocused(false);
        }
        if (this.searchClearBtn.contains(mx, my) && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (this.searchBox != null && this.searchBox.isMouseOver(mx, my) && rightClick && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (!rightClick && beginScrollDrag(mx, my)) {
            return true;
        }
        if (this.sortBtn.contains(mx, my)) {
            if (!InvOps.carried().isEmpty()) {
                this.status = Component.translatable("gui.routinebags.status.cursor_busy");
            } else if (!this.sorter.isActive() && !this.runner.busy() && !this.waitingServerSort
                    && this.pendingTakeRequest < 0 && !RecipeBagSupport.isBusy()
                    && !ServerBridge.hasOperationInFlight()) {
                if (canUseServerSort()) {
                    this.waitingServerSort = true;
                    this.status = Component.translatable("gui.routinebags.status.server_sorting");
                    ServerBridge.requestSort(this.sortMode);
                    return true;
                }
                this.sorter.start();
            }
            return true;
        }
        if (this.cancelBtn.contains(mx, my)) {
            this.sorter.cancel();
            this.runner.cancelSafely(Component.translatable("gui.routinebags.status.operation_cancelled"));
            this.waitingServerSort = false;
            ServerBridge.cancelStoreRequest();
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
        if (this.sidebarRect.contains(mx, my)) {
            handleSidebarClick(mx, my, rightClick);
            return true;
        }
        int invSlot = invMenuSlotAt(mx, my);
        if (invSlot != -1) {
            handleInventoryClick(invSlot, rightClick, event.hasShiftDown());
            return true;
        }
        return false;
    }

    /**
     * 聚合网格：把多个收纳袋当成一个物品区操作。
     * 空光标：左键=拿一组到光标（跨袋自动凑齐）、右键=拿半组、Shift+左键=收进背包。
     * 光标持物：左键=跨袋全存、右键=只存一个。
     */
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
            int amount = rightClick ? 1 : carried.getCount();
            if (ServerBridge.requestStoreFromCursor(amount)) {
                this.waitingServerSort = true;
                this.status = Component.translatable("gui.routinebags.status.server_storing");
                return;
            }
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

    /** 袋子行：原版语义。光标有物品左键=存入该袋；空光标右键=取出选中项到光标；空光标左键=筛选 */
    private void handleSidebarClick(double mx, double my, boolean rightClick) {
        int row = (int) ((my - this.sidebarRect.y) / SIDEBAR_ROW_H);
        int rowOffset = (int) (my - this.sidebarRect.y) % SIDEBAR_ROW_H;
        int ordinal = this.bagScroll + row;
        if (row < 0 || row >= this.sidebarVisibleRows || rowOffset >= SIDEBAR_ROW_H - 2
                || ordinal >= this.bags.size()) {
            return;
        }
        BagView bag = this.bags.get(ordinal);
        ItemStack carried = InvOps.carried();
        if (!carried.isEmpty()) {
            if (!bag.mutable) {
                this.status = Component.translatable("gui.routinebags.readonly");
                return;
            }
            // 原版 bundle 点击语义：左键整堆塞入，右键无操作（vanilla 会自行忽略）
            InvOps.leftClick(bag.menuSlot);
            reportLeftover();
            refresh();
            return;
        }
        if (rightClick) {
            if (!bag.mutable) {
                this.status = Component.translatable("gui.routinebags.readonly");
                return;
            }
            InvOps.rightClick(bag.menuSlot);
            refresh();
            return;
        }
        this.bagFilter = this.bagFilter == ordinal ? -1 : ordinal;
        this.scrollRow = 0;
    }

    /** 背包区：普通左右键=原版拿起/放下语义；Shift+左键=智能存入袋子 */
    private void handleInventoryClick(int menuSlot, boolean rightClick, boolean shift) {
        if (!rightClick && shift) {
            smartStore(menuSlot);
            return;
        }
        if (rightClick) {
            InvOps.rightClick(menuSlot);
        } else {
            InvOps.leftClick(menuSlot);
        }
        refresh();
    }

    private void smartStore(int menuSlot) {
        ItemStack stack = InvOps.stackAt(menuSlot);
        if (stack.isEmpty()) {
            return;
        }
        if (!BundleContents.canItemBeInBundle(stack)) {
            this.status = Component.translatable("gui.routinebags.cant_fit");
            return;
        }
        if (isBundleStack(stack)) {
            this.status = Component.translatable("gui.routinebags.status.bundle_cursor_store_blocked");
            return;
        }
        if (totalInsertable(stack) <= 0) {
            this.status = bagsFullMessage(stack);
            return;
        }
        this.status = null;
        if (ServerBridge.requestStore(menuSlot)) {
            this.waitingServerSort = true;
            this.status = Component.translatable("gui.routinebags.status.server_storing");
            return;
        }
        CursorOps.storeInventoryStack(this.runner, menuSlot, ItemKey.of(stack));
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

    /** 失败提示带上数字，方便判断是总容量不足，还是单件物品本身占满 64 单位。 */
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

    private void reportLeftover() {
        if (!InvOps.carried().isEmpty()) {
            this.status = Component.translatable("gui.routinebags.status.partial_insert");
        } else {
            this.status = null;
        }
    }

    private boolean busyBlocked() {
        if (this.runner.busy() || this.sorter.isActive() || this.waitingServerSort || this.pendingTakeRequest >= 0
                || RecipeBagSupport.isBusy() || ServerBridge.hasOperationInFlight()) {
            this.status = Component.translatable("gui.routinebags.status.busy");
            return true;
        }
        return false;
    }

    private boolean canUseServerSort() {
        return this.sortMode != SortMode.BY_CREATIVE && ServerBridge.canSortOnServer();
    }

    private boolean beginScrollDrag(double mx, double my) {
        ScrollMetrics gridScroll = gridScrollMetrics();
        if (gridScroll != null && VanillaUi.overScrollbar(mx, my, gridScroll.trackX, gridScroll.trackY, gridScroll.trackH)) {
            this.draggingGridScroll = true;
            this.draggingBagScroll = false;
            applyGridScrollAt(my, gridScroll);
            return true;
        }
        ScrollMetrics bagScroll = bagScrollMetrics();
        if (bagScroll != null && VanillaUi.overScrollbar(mx, my, bagScroll.trackX, bagScroll.trackY, bagScroll.trackH)) {
            this.draggingBagScroll = true;
            this.draggingGridScroll = false;
            applyBagScrollAt(my, bagScroll);
            return true;
        }
        return false;
    }

    private void applyGridScrollAt(double my, ScrollMetrics metrics) {
        this.scrollRow = VanillaUi.scrollRowAt(my, metrics.trackY, metrics.trackH, metrics.thumbH, metrics.maxScroll);
        refresh();
    }

    private void applyBagScrollAt(double my, ScrollMetrics metrics) {
        this.bagScroll = VanillaUi.scrollRowAt(my, metrics.trackY, metrics.trackH, metrics.thumbH, metrics.maxScroll);
        refresh();
    }

    private @Nullable ScrollMetrics gridScrollMetrics() {
        if (this.gridRect == null) {
            return null;
        }
        int totalRows = Math.max(1, (this.visible.size() + GRID_COLS - 1) / GRID_COLS);
        int maxScroll = Math.max(0, totalRows - this.gridRows);
        if (maxScroll <= 0) {
            return null;
        }
        int trackX = this.gridRect.x + GRID_W + 1;
        int thumbH = VanillaUi.thumbHeight(this.gridH, this.gridRows, totalRows);
        int thumbY = VanillaUi.thumbY(this.gridRect.y, this.gridH, thumbH, this.scrollRow, maxScroll);
        return new ScrollMetrics(trackX, this.gridRect.y, this.gridH, thumbY, thumbH, maxScroll);
    }

    private @Nullable ScrollMetrics bagScrollMetrics() {
        if (this.sidebarRect == null) {
            return null;
        }
        int maxScroll = Math.max(0, this.bags.size() - this.sidebarVisibleRows);
        if (maxScroll <= 0) {
            return null;
        }
        int trackX = this.sidebarRect.x + SIDEBAR_W - VanillaUi.SCROLLBAR_W;
        int trackH = this.sidebarVisibleRows * SIDEBAR_ROW_H - 2;
        int thumbH = VanillaUi.thumbHeight(trackH, this.sidebarVisibleRows, this.bags.size());
        int thumbY = VanillaUi.thumbY(this.sidebarRect.y, trackH, thumbH, this.bagScroll, maxScroll);
        return new ScrollMetrics(trackX, this.sidebarRect.y, trackH, thumbY, thumbH, maxScroll);
    }

    private record ScrollMetrics(int trackX, int trackY, int trackH, int thumbY, int thumbH, int maxScroll) {
    }

    private int invMenuSlotAt(double mx, double my) {
        if (this.invRect.contains(mx, my)) {
            int col = (int) ((mx - this.invRect.x) / CELL);
            int row = (int) ((my - this.invRect.y) / CELL);
            return InventoryMenu.INV_SLOT_START + row * 9 + col;
        }
        if (this.hotbarRect.contains(mx, my)) {
            return InventoryMenu.USE_ROW_SLOT_START + (int) ((mx - this.hotbarRect.x) / CELL);
        }
        if (this.offhandRect.contains(mx, my)) {
            return InventoryMenu.SHIELD_SLOT;
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (event.button() == 0 && this.draggingGridScroll) {
            ScrollMetrics metrics = gridScrollMetrics();
            if (metrics != null) {
                applyGridScrollAt(event.y(), metrics);
            }
            return true;
        }
        if (event.button() == 0 && this.draggingBagScroll) {
            ScrollMetrics metrics = bagScrollMetrics();
            if (metrics != null) {
                applyBagScrollAt(event.y(), metrics);
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            this.draggingGridScroll = false;
            this.draggingBagScroll = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        ScrollMetrics gridScroll = gridScrollMetrics();
        if (gridScroll != null && VanillaUi.overScrollbar(x, y, gridScroll.trackX, gridScroll.trackY, gridScroll.trackH)) {
            int step = shiftDown() ? Math.max(1, this.gridRows - 1) : 1;
            this.scrollRow -= (int) Math.signum(scrollY) * step;
            refresh();
            return true;
        }
        ScrollMetrics bagScroll = bagScrollMetrics();
        if (bagScroll != null && VanillaUi.overScrollbar(x, y, bagScroll.trackX, bagScroll.trackY, bagScroll.trackH)) {
            int step = shiftDown() ? Math.max(1, this.sidebarVisibleRows - 1) : 1;
            this.bagScroll -= (int) Math.signum(scrollY) * step;
            refresh();
            return true;
        }
        if (this.gridRect != null && this.gridRect.contains(x, y)) {
            int step = shiftDown() ? Math.max(1, this.gridRows - 1) : 1;
            this.scrollRow -= (int) Math.signum(scrollY) * step;
            refresh();
            return true;
        }
        if (this.sidebarRect != null && this.sidebarRect.contains(x, y)) {
            int row = (int) ((y - this.sidebarRect.y) / SIDEBAR_ROW_H);
            int ordinal = this.bagScroll + row;
            // 悬停在袋子行上滚轮 = 原版的选中条目切换；行外空白处 = 滚动袋子列表
            if (row < this.sidebarVisibleRows && ordinal < this.bags.size() && hoverOnBagRow(y, row)) {
                BagView bag = this.bags.get(ordinal);
                if (bag.mutable && !bag.entries.isEmpty()) {
                    ItemStack live = InvOps.stackAt(bag.menuSlot);
                    int cur = BundleItem.getSelectedItemIndex(live);
                    int next = ScrollWheelHandler.getNextScrollWheelSelection(scrollY, cur, bag.entries.size());
                    InvOps.selectBundleEntry(bag.menuSlot, next);
                    return true;
                }
            }
            int step = shiftDown() ? Math.max(1, this.sidebarVisibleRows - 1) : 1;
            this.bagScroll -= (int) Math.signum(scrollY) * step;
            refresh();
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private boolean hoverOnBagRow(double my, int row) {
        double inRow = my - this.sidebarRect.y - row * SIDEBAR_ROW_H;
        return inRow >= 0 && inRow < SIDEBAR_ROW_H - 2;
    }

    private boolean shiftDown() {
        return this.minecraft != null && (InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && !this.query.isEmpty()) {
            clearSearch();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_SLASH && this.searchBox != null && !this.searchBox.isFocused()) {
            this.searchBox.setFocused(true);
            return true;
        }
        if (super.keyPressed(event)) {
            return true;
        }
        if (this.searchBox != null && !this.searchBox.isFocused()
                && (Keybinds.OPEN_UNIFIED.get().matches(event)
                        || (this.minecraft != null && this.minecraft.options.keyInventory.matches(event)))) {
            onClose();
            return true;
        }
        return false;
    }

    private void clearSearch() {
        this.query = "";
        this.scrollRow = 0;
        if (this.searchBox != null) {
            this.searchBox.setValue("");
        }
        refresh();
    }

    @Override
    public void onClose() {
        if (this.runner.hasCancelCleanup()) {
            this.sorter.cancel();
            this.runner.cancelSafely(Component.translatable("gui.routinebags.status.operation_cancelled"));
            this.closeWhenRunnerStops = true;
            return;
        }
        closeImmediately();
    }

    private void closeImmediately() {
        if (this.parent != null && this.minecraft != null && this.minecraft.player != null
                && this.parent instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> containerScreen
                && this.minecraft.player.containerMenu == containerScreen.getMenu()) {
            this.minecraft.setScreen(this.parent);
            return;
        }
        super.onClose();
    }

    @Override
    public void removed() {
        // 关屏即弃：脚本半途而废没关系，每一步都是独立合法点击，不存在半完成的坏状态
        this.runner.clear();
        this.sorter.cancel();
        ServerBridge.cancelStoreRequest();
        ServerBridge.cancelTakeRequest(this.pendingTakeRequest);
        this.pendingTakeRequest = -1;
        this.pendingTakeTicks = 0;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
