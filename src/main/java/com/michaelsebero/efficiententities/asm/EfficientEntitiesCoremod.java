package com.michaelsebero.efficiententities.asm;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.launcher.FMLInjectionAndSortingTweaker;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import zone.rong.mixinbooter.IEarlyMixinLoader;
import java.util.Collections;
import java.util.List;

import com.michaelsebero.efficiententities.EEConfig;

/**
 * FML core-mod plugin for Efficient Entities.
 *
 * Registers {@link EfficientEntitiesTweaker} so the ASM transformer is loaded
 * after FML's own class-loading infrastructure is ready, and bootstraps Mixin
 * when running in a non-obfuscated development environment.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions("com.michaelsebero.efficiententities.asm")
public class EfficientEntitiesCoremod
        implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override public String[] getASMTransformerClass()              { return new String[] {EfficientEntitiesTransformer.class.getName()}; }
    @Override public String   getModContainerClass()                { return null; }
    @Override public String   getSetupClass()                       { return null; }
    @Override public String   getAccessTransformerClass()           { return null; }
	@Override
public List<String> getMixinConfigs() {
    return Collections.singletonList("mixins.efficiententities.json");
}

    @Override
	public void injectData(Map<String, Object> data) {
	}
}
