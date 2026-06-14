package com.randomrecipes;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RandomRecipesMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("random-recipes");

    private static long worldSeed = 0;
    private static boolean initialized = false;
    private static MinecraftServer currentServer = null;

    @Override
    public void onInitialize() {
        // 加载配置文件（默认保护木镐/石镐/铁镐）
        RandomRecipesConfig.load();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            currentServer = server;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            worldSeed = server.getOverworld().getSeed();
            initialized = true;
            LOGGER.info("Captured world seed: {}", worldSeed);

            RecipeShuffler.shuffleAllRecipes(server, worldSeed);
        });
    }

    public static long getWorldSeed() {
        return worldSeed;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }
}