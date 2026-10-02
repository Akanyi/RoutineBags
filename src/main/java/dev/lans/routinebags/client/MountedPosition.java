package dev.lans.routinebags.client;

/** 相对位置让不同 GUI 缩放/窗口尺寸复用拖动结果，小窗口只限制显示，不覆盖保存值。 */
final class MountedPosition {
    static int resolve(int saved, int fallback, int screenSize, int elementSize) {
        int range = Math.max(0, screenSize - elementSize - 8);
        return saved < 0 ? Math.clamp(fallback, 4, 4 + range)
                : 4 + (int) Math.round(Math.clamp(saved, 0, 10000) * range / 10000.0);
    }

    static int encode(int pixel, int screenSize, int elementSize) {
        int range = Math.max(0, screenSize - elementSize - 8);
        return range == 0 ? 0 : Math.clamp((int) Math.round((pixel - 4) * 10000.0 / range), 0, 10000);
    }

    private MountedPosition() {}
}
