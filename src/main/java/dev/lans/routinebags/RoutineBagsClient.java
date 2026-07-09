package dev.lans.routinebags;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端入口。@Mod(dist = Dist.CLIENT) 保证这个类只在物理客户端构造，
 * 专用服务器上完全不加载，天然避免 NoClassDefFoundError。
 */
@Mod(value = RoutineBags.MOD_ID, dist = Dist.CLIENT)
public class RoutineBagsClient {
    public RoutineBagsClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
