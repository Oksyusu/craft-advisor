package com.kuh.craftadvisor.jei;

import java.util.Optional;

import com.kuh.craftadvisor.CraftAdvisor;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Discovered by JEI; keeps the runtime so /ai can read recipes and open recipe screens. */
@JeiPlugin
public class CraftAdvisorJeiPlugin implements IModPlugin {
    private static volatile IJeiRuntime runtime;

    public static Optional<IJeiRuntime> runtime() {
        return Optional.ofNullable(runtime);
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(CraftAdvisor.MODID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        Items.forget();
    }
}
