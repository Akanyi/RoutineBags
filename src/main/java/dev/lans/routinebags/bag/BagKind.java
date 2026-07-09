package dev.lans.routinebags.bag;

public enum BagKind {
    /** minecraft:bundle_contents —— 原版收纳袋语义，可通过合法点击读写 */
    BUNDLE,
    /** minecraft:container —— 潜影盒等容器物品，物品形态在原版服务器上只读 */
    CONTAINER
}
