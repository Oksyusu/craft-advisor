package com.kuh.craftadvisor;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// Client-only mod: this class never loads on a dedicated server.
@Mod(value = CraftAdvisor.MODID, dist = Dist.CLIENT)
public class CraftAdvisor {
    public static final String MODID = "craftadvisor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CraftAdvisor(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        // Mods screen > Craft Advisor > Config
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
