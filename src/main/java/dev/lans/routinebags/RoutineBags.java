package dev.lans.routinebags;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 主入口。本 mod 是纯客户端 mod，所有实际逻辑都在 client 包里；
 * 这个类只承载 modid 常量和日志器，保持 dist 无关，避免专用服务器上误加载客户端类。
 */
@Mod(RoutineBags.MOD_ID)
public class RoutineBags {
    public static final String MOD_ID = "routinebags";
    public static final Logger LOGGER = LogUtils.getLogger();
}
