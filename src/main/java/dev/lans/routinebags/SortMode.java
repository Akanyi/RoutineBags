package dev.lans.routinebags;

public enum SortMode {
    BY_CREATIVE, BY_ID, BY_NAME, BY_COUNT;

    public SortMode next() {
        SortMode[] v = values();
        return v[(this.ordinal() + 1) % v.length];
    }

    public String translationKey() {
        return "gui.routinebags.sort_mode." + this.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static SortMode byOrdinal(int ordinal) {
        SortMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BY_CREATIVE;
    }
}
