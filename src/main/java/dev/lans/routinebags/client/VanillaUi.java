package dev.lans.routinebags.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;

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
        g.fill(x, y, x + 3, y + h, SLOT_SHADOW);
        g.fill(x, thumbY, x + 3, thumbY + thumbH, PANEL);
        g.fill(x, thumbY, x + 1, thumbY + thumbH, PANEL_HIGHLIGHT);
        g.fill(x + 2, thumbY, x + 3, thumbY + thumbH, PANEL_SHADOW);
    }
}
