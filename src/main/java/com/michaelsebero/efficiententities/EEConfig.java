package com.michaelsebero.efficiententities;

import net.minecraftforge.common.config.Config;
import com.michaelsebero.efficiententities.efficiententities.Tags;

@Config(modid = Tags.MOD_ID)
public class EEConfig {

    @Config.Name("Renderer Statistics Logging Period")
    @Config.Comment({
        "Logs renderer statistics every N frames.",
        "0 = Disabled"
    })
    @Config.RangeInt(min = 0)
    public static int logPeriodFrames = 0;
}