package dev.lans.routinebags;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 客户端配置。opsPerTick 故意默认得很保守：整理走的是真实点击包，
 * 发太快在部分带反作弊插件的服务器上会被限流甚至踢出，2/tick 已经是肉眼很快的速度。
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue OPS_PER_TICK = BUILDER
            .comment("How many simulated inventory clicks to send per game tick while sorting/moving.",
                    "Keep this low on servers with strict anti-cheat plugins.")
            .translation("routinebags.configuration.opsPerTick")
            .defineInRange("opsPerTick", 2, 1, 10);

    public static final ModConfigSpec.IntValue STEP_DELAY_TICKS = BUILDER
            .comment("How many ticks to wait between scripted inventory steps.",
                    "Increase this on servers that delay or rewrite inventory transactions.")
            .translation("routinebags.configuration.stepDelayTicks")
            .defineInRange("stepDelayTicks", 1, 0, 10);

    public static final ModConfigSpec.IntValue MAX_SORT_STEPS = BUILDER
            .comment("Safety cap on the number of moves a single sort run may perform.")
            .translation("routinebags.configuration.maxSortSteps")
            .defineInRange("maxSortSteps", 400, 50, 5000);

    public static final ModConfigSpec.BooleanValue SHOW_READ_ONLY = BUILDER
            .comment("Show read-only container items (e.g. shulker boxes) in the unified view.")
            .translation("routinebags.configuration.showReadOnlyContainers")
            .define("showReadOnlyContainers", true);

    public static final ModConfigSpec.BooleanValue MOUNT_IN_CONTAINER_SCREENS = BUILDER
            .comment("Show the compact Routine Bags panel next to container screens such as chests and crafting tables.")
            .translation("routinebags.configuration.mountInContainerScreens")
            .define("mountInContainerScreens", true);

    public static final ModConfigSpec.BooleanValue MOUNTED_PANEL_OPEN_BY_DEFAULT = BUILDER
            .comment("Open the mounted Routine Bags panel by default when a container screen opens.",
                    "When disabled, only a small toggle tab is shown until clicked.")
            .translation("routinebags.configuration.mountedPanelOpenByDefault")
            .define("mountedPanelOpenByDefault", false);

    public static final ModConfigSpec.EnumValue<SortMode> SORT_MODE = BUILDER
            .comment("Default ordering of the unified view and of sorted bundles.")
            .translation("routinebags.configuration.sortMode")
            .defineEnum("sortMode", SortMode.BY_CREATIVE);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
