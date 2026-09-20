package dev.lans.routinebags.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

final class VanillaUi {
    static final int PANEL = 0xFFC6C6C6;
    static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
    static final int PANEL_SHADOW = 0xFF555555;
    static final int SLOT = 0xFF8B8B8B;
    static final int SLOT_SHADOW = 0xFF373737;
    static final int SLOT_HOVER = 0x80FFFFFF;
    static final int TEXT = 0xFF404040;
    static final int TEXT_DIM = 0xFF606060;
    static final int TEXT_LIGHT = 0xFFFFFFFF;
    static final int TEXT_DISABLED = 0xFFA0A0A0;
    static final int TEXT_HOVER = 0xFFFFFFA0;
    static final int STATUS = 0xFF9A6A00;
    static final int DANGER = 0xFFB03030;
    static final int BUTTON = 0xFF6A6A6A;
    static final int BUTTON_HOVER = 0xFF7E7E7E;
    static final int BUTTON_DISABLED = 0xFF5A5A5A;
    static final int SCROLLBAR_W = 6;
    static final int SCROLL_TRACK = 0xFF000000;
    static final int SCROLL_THUMB = 0xFFC6C6C6;

    private VanillaUi() {
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, PANEL_HIGHLIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, PANEL_HIGHLIGHT);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, PANEL_SHADOW);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, PANEL_SHADOW);
    }

    static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean enabled) {
        int fill = enabled ? (hover ? BUTTON_HOVER : BUTTON) : BUTTON_DISABLED;
        int highlight = enabled && hover ? PANEL_HIGHLIGHT : TEXT_DISABLED;
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y + 1, x + w - 2, y + 2, highlight);
        g.fill(x + 1, y + 1, x + 2, y + h - 2, highlight);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SLOT_SHADOW);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, SLOT_SHADOW);
    }

    static int buttonText(boolean hover, boolean enabled) {
        if (!enabled) {
            return TEXT_DISABLED;
        }
        return hover ? TEXT_HOVER : TEXT_LIGHT;
    }

    static void slot(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x, y, x + size, y + size, PANEL);
        g.fill(x, y, x + size - 1, y + size - 1, SLOT_SHADOW);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT);
        g.fill(x + 1, y + size - 1, x + size, y + size, PANEL_HIGHLIGHT);
        g.fill(x + size - 1, y + 1, x + size, y + size, PANEL_HIGHLIGHT);
    }

    static void slotHover(GuiGraphicsExtractor g, int x, int y, int size) {
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_HOVER);
    }

    static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int thumbY, int thumbH) {
        g.fill(x, y, x + SCROLLBAR_W, y + h, SCROLL_TRACK);
        g.fill(x + 1, y, x + SCROLLBAR_W - 1, y + h, SLOT_SHADOW);
        g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, SCROLL_THUMB);
        g.fill(x, thumbY, x + SCROLLBAR_W - 1, thumbY + 1, PANEL_HIGHLIGHT);
        g.fill(x, thumbY, x + 1, thumbY + thumbH - 1, PANEL_HIGHLIGHT);
        g.fill(x + 1, thumbY + thumbH - 1, x + SCROLLBAR_W, thumbY + thumbH, PANEL_SHADOW);
        g.fill(x + SCROLLBAR_W - 1, thumbY + 1, x + SCROLLBAR_W, thumbY + thumbH, PANEL_SHADOW);
    }

    static int thumbHeight(int trackH, int visibleRows, int totalRows) {
        if (totalRows <= visibleRows || trackH <= 0) {
            return trackH;
        }
        return Math.max(8, trackH * visibleRows / totalRows);
    }

    static int thumbY(int trackY, int trackH, int thumbH, int scrollRow, int maxScroll) {
        if (maxScroll <= 0 || trackH <= thumbH) {
            return trackY;
        }
        return trackY + (trackH - thumbH) * scrollRow / maxScroll;
    }

    static int scrollRowAt(double mouseY, int trackY, int trackH, int thumbH, int maxScroll) {
        if (maxScroll <= 0) {
            return 0;
        }
        double usable = Math.max(1, trackH - thumbH);
        double rel = Mth.clamp(mouseY - trackY - thumbH * 0.5, 0.0, usable);
        return Mth.clamp((int) Math.round(rel * maxScroll / usable), 0, maxScroll);
    }

    static boolean overScrollbar(double mx, double my, int trackX, int trackY, int trackH) {
        return mx >= trackX && mx < trackX + SCROLLBAR_W && my >= trackY && my < trackY + trackH;
    }
}
