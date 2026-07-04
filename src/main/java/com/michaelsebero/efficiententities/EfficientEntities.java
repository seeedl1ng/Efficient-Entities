package com.michaelsebero.efficiententities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.michaelsebero.efficiententities.renderer.EfficientModelRenderer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import com.michaelsebero.efficiententities.efficiententities.Tags;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION, acceptableRemoteVersions = "*")
public class EfficientEntities {

    public static final Logger LOG = LogManager.getLogger(Tags.MOD_ID);

    @EventHandler
    public void onConstruction(FMLConstructionEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == Phase.START) {
            EfficientModelRenderer.instance().beginFrame();
        } else {
            EfficientModelRenderer.instance().finishFrame();
        }
    }
}
