package com.randomrecipes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置文件 random-recipes.json —— 让玩家手动指定哪些配方不参与随机化。
 *
 * <pre>{@code
 * {
 *   "protected_output_items": [
 *     "minecraft:wooden_pickaxe",
 *     "minecraft:stone_pickaxe",
 *     "minecraft:iron_pickaxe"
 *   ],
 *   "protected_recipe_ids": []
 * }
 * }</pre>
 */
public final class RandomRecipesConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("random-recipes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("random-recipes.json");

    private static RandomRecipesConfig instance;

    // ==================== 配置字段 ====================

    @SerializedName("protected_output_items")
    private List<String> protectedOutputItems = List.of(
            "minecraft:wooden_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:iron_pickaxe"
    );

    @SerializedName("protected_recipe_ids")
    private List<String> protectedRecipeIds = List.of();

    // ==================== 加载 / 保存 ====================

    private RandomRecipesConfig() {
    }

    /** 首次加载：文件存在则读，不存在则写默认值 */
    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                instance = GSON.fromJson(json, RandomRecipesConfig.class);
                if (instance == null) {
                    instance = new RandomRecipesConfig();
                }
                LOGGER.info("Loaded config from {}", CONFIG_PATH);
            } catch (IOException e) {
                LOGGER.warn("Failed to read config, using defaults: {}", e.getMessage());
                instance = new RandomRecipesConfig();
            }
        } else {
            instance = new RandomRecipesConfig();
            save();
        }

        LOGGER.info("Protected output items: {}", instance.protectedOutputItems);
        LOGGER.info("Protected recipe ids:   {}", instance.protectedRecipeIds);
    }

    /** /reload 时重新读取 */
    public static void reload() {
        load();
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(instance));
            LOGGER.info("Config saved to {}", CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    // ==================== 查询方法（供 RecipeShuffler 调用） ====================

    /** 受保护的输出物品 ID 集合 */
    public static Set<String> getProtectedOutputItems() {
        if (instance == null) return Set.of();
        return new HashSet<>(instance.protectedOutputItems);
    }

    /** 受保护的配方 ID 集合 */
    public static Set<String> getProtectedRecipeIds() {
        if (instance == null) return Set.of();
        return new HashSet<>(instance.protectedRecipeIds);
    }
}