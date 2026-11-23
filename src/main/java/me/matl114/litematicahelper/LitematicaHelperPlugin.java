package me.matl114.litematicahelper;

import me.matl114.hacks.SurvivalTasks;
import me.matl114.litematicahelper.modules.LitematicaHelper;
import me.matl114.hooks.LitematicaHooks;
import me.matl114.plugins.SlimefunHelperPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class LitematicaHelperPlugin implements SlimefunHelperPlugin {
    public static final String MOD_ID = "litematica_helper";

    @Override
    public void onConfigBootstrap() {}

    @Override
    public void onListenerBootstrap() {}

    @Override
    public void onModuleBootstrap() {
        if (!LitematicaHooks.getInstance().isEnabled()) {
            throw new IllegalStateException(
                    "Litematica is required, but SlimefunHelper could not initialize LitematicaHooks.");
        }
        SurvivalTasks.getModuleManager().registerFactories(manager -> new LitematicaHelper().register(manager));
    }

    @Override
    public void postInitialize() {}

    @Override
    public String getPluginId() {
        return MOD_ID;
    }

    @Override
    public ModContainer getContainer() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .orElseThrow(() -> new IllegalStateException("Litematica Helper plugin container not found."));
    }
}
