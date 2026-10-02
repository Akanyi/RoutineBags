package dev.lans.routinebags.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

final class VanillaUi {
    static final int PANEL = 0xFFC6C6C6;
    static final int TEXT = 0xFF404040;
    static final int TEXT_DIM = 0xFF606060;
    static final int TEXT_LIGHT = 0xFFFFFFFF;
    static final int TEXT_DISABLED = 0xFFA0A0A0;
    static final int TEXT_HOVER = 0xFFFFFFA0;
    static final int STATUS = 0xFF9A6A00;
    static final int DANGER = 0xFFB03030;
    static final int SCROLLBAR_W = 12;

    private static final Identifier PANEL_SPRITE = Identifier.withDefaultNamespace("popup/background");
    private static final Identifier BUTTON_SPRITE = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_DISABLED_SPRITE = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final Identifier BUTTON_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier CROSS_BUTTON_SPRITE = Identifier.withDefaultNamespace("widget/cross_button");
    private static final Identifier CROSS_BUTTON_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("widget/cross_button_highlighted");
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier SLOT_HIGHLIGHT_SPRITE = Identifier.withDefaultNamespace("container/slot_highlight_front");
    private static final Identifier SCROLLER_BACKGROUND_SPRITE = Identifier.withDefaultNamespace("widget/scroller_background");
    private static final Identifier CREATIVE_SCROLLER_SPRITE = Identifier.withDefaultNamespace("container/creative_inventory/scroller");
    private static final Identifier BUNDLE_PROGRESS_BORDER_SPRITE = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_border");
    private static final Identifier BUNDLE_PROGRESS_FILL_SPRITE = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_fill");
    private static final Identifier BUNDLE_PROGRESS_FULL_SPRITE = Identifier.withDefaultNamespace("container/bundle/bundle_progressbar_full");

    private VanillaUi() {
    }

    static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.text(font, text, x, y, color, false);
    }

    static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, text, x, y, color, false);
    }

    static void centeredText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, text, x - font.width(text) / 2, y, color, false);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL_SPRITE, x, y, w, h);
        g.fill(x + 5, y + 5, x + w - 5, y + h - 5, PANEL);
    }

    static void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean enabled) {
        Identifier sprite = enabled ? (hover ? BUTTON_HIGHLIGHTED_SPRITE : BUTTON_SPRITE) : BUTTON_DISABLED_SPRITE;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, w, h);
    }

    static int buttonText(boolean hover, boolean enabled) {
        if (!enabled) {
            return TEXT_DISABLED;
        }
        return hover ? TEXT_HOVER : TEXT_LIGHT;
    }

    static void listRow(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean selected) {
        button(g, x, y, w, h, hover || selected, true);
    }

    static void progressBar(GuiGraphicsExtractor g, int x, int y, int w, float progress) {
        int filled = Math.round(Math.max(0, w - 2) * Mth.clamp(progress, 0.0F, 1.0F));
        if (filled > 0) {
            Identifier fill = progress >= 0.999F ? BUNDLE_PROGRESS_FULL_SPRITE : BUNDLE_PROGRESS_FILL_SPRITE;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, fill, x + 1, y, filled, 6);
        }
        g.blitSprite(RenderPipelines.GUI_TEXTURED, BUNDLE_PROGRESS_BORDER_SPRITE, x, y, w, 6);
    }

    static void slot(GuiGraphicsExtractor g, int x, int y, int size) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x, y, size, size);
    }

    static void slotHover(GuiGraphicsExtractor g, int x, int y, int size) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_SPRITE, x, y, size, size);
    }

    static void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int thumbY, int thumbH) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND_SPRITE, x, y, SCROLLBAR_W, h);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, CREATIVE_SCROLLER_SPRITE, x, thumbY, SCROLLBAR_W, thumbH);
    }

    static void crossButton(GuiGraphicsExtractor g, int x, int y, int size, boolean hover) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED,
                hover ? CROSS_BUTTON_HIGHLIGHTED_SPRITE : CROSS_BUTTON_SPRITE, x, y, size, size);
    }

    static int thumbHeight(int trackH, int visibleRows, int totalRows) {
        if (totalRows <= visibleRows || trackH <= 0) {
            return trackH;
        }
        return Math.min(15, trackH);
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
