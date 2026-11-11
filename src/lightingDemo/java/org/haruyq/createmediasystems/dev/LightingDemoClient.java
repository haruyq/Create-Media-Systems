package org.haruyq.createmediasystems.dev;

import java.nio.file.Files;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "create_media_systems", value = Dist.CLIENT)
public final class LightingDemoClient {
    private static boolean requested;

    private LightingDemoClient() {
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (LightingLab.NETWORK) {
            return;
        }
        if (minecraft.level != null && minecraft.level.getGameTime() % 20 == 0) {
            LightingDemoCache.verify();
        }
        if (requested || minecraft.level != null || !(minecraft.screen instanceof TitleScreen) || minecraft.getOverlay() != null) {
            return;
        }
        requested = true;
        if (Files.exists(minecraft.gameDirectory.toPath().resolve("saves").resolve(LightingLab.LEVEL_ID).resolve("level.dat"))) {
            minecraft.createWorldOpenFlows().openWorld(LightingLab.LEVEL_ID, null);
            return;
        }
        LevelSettings settings = new LevelSettings(LightingLab.LEVEL_NAME, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel(LightingLab.LEVEL_ID, settings, WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createNormalWorldDimensions, (Screen) null);
    }
}
