package com.randomrecipes;

import com.randomrecipes.mixin.RecipeManagerAccessor;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public final class RecipeShuffler {
    private static final Logger LOGGER = LoggerFactory.getLogger("random-recipes");

    private static final Set<Item> PROTECTED_RESULTS = Set.of(
            Items.WOODEN_PICKAXE,
            Items.STONE_PICKAXE,
            Items.IRON_PICKAXE
    );

    private static boolean configProtectionLoaded = false;
    private static Set<String> configProtectedOutputItems = Set.of();
    private static Set<String> configProtectedRecipeIds = Set.of();

    /** 刷新配置快照（在 shuffle 前调用） */
    private static void refreshConfigSnapshot() {
        configProtectedOutputItems = RandomRecipesConfig.getProtectedOutputItems();
        configProtectedRecipeIds = RandomRecipesConfig.getProtectedRecipeIds();
        configProtectionLoaded = true;
    }

    private RecipeShuffler() {
    }

    // ==================== 对外入口 ====================

    /**
     * 对服务器中所有已注册的工作台配方执行随机化。
     * 在 {@link net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents#SERVER_STARTED} 时调用。
     */
    public static void shuffleAllRecipes(MinecraftServer server, long seed) {
        DynamicRegistryManager registryManager = server.getRegistryManager();
        RecipeManager manager = server.getRecipeManager();
        Map<Identifier, Recipe<?>> recipes = ((RecipeManagerAccessor) manager).getRecipesById();

        shuffleRecipes(recipes, registryManager, seed);
    }

    /**
     * 由 mixin 在 {@code /reload} 时调用（从当前 server 取 RegistryManager）。
     */
    public static void shuffleRecipes(Map<Identifier, Recipe<?>> recipes, long seed) {
        MinecraftServer server = RandomRecipesMod.getCurrentServer();
        if (server == null) {
            LOGGER.warn("Cannot shuffle: current server is null");
            return;
        }
        shuffleRecipes(recipes, server.getRegistryManager(), seed);
    }

    // ==================== 核心逻辑 ====================

    private static void shuffleRecipes(Map<Identifier, Recipe<?>> recipes,
                                       DynamicRegistryManager registryManager,
                                       long seed) {
        Random random = new Random(seed);
        refreshConfigSnapshot();

        // 1. 收集需要随机化的工作台配方
        List<Map.Entry<Identifier, Recipe<?>>> craftingEntries = recipes.entrySet().stream()
                .filter(e -> e.getValue().getType() == RecipeType.CRAFTING)
                .filter(e -> !isProtected(e.getKey(), e.getValue(), registryManager))
                .sorted(Map.Entry.comparingByKey())   // 固定顺序保证种子确定性
                .collect(Collectors.toList());

        LOGGER.info("Shuffling {} crafting recipes (seed={})", craftingEntries.size(), seed);

        // 2. 逐个处理
        for (Map.Entry<Identifier, Recipe<?>> entry : craftingEntries) {
            try {
                processRecipe(entry.getValue(), random);
            } catch (Exception ex) {
                LOGGER.warn("Failed to shuffle recipe {}: {}", entry.getKey(), ex.getMessage());
            }
        }

        // 3. 重复检测
        checkForDuplicates(recipes, registryManager);

        LOGGER.info("Recipe shuffling complete");
    }

    // ==================== 保护检测 ====================

    private static boolean isProtected(Identifier recipeId, Recipe<?> recipe,
                                       DynamicRegistryManager registryManager) {
        // 1) 硬编码三把镐子
        if (PROTECTED_RESULTS.contains(recipe.getOutput(registryManager).getItem())) {
            return true;
        }

        // 2) 配置中 protected_output_items
        String outputId = Registries.ITEM.getId(
                recipe.getOutput(registryManager).getItem()).toString();
        if (configProtectedOutputItems.contains(outputId)) {
            return true;
        }

        // 3) 配置中 protected_recipe_ids
        if (configProtectedRecipeIds.contains(recipeId.toString())) {
            return true;
        }

        return false;
    }

    // ==================== 配方处理流程 ====================

    private static void processRecipe(Recipe<?> recipe, Random random) {
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;

        boolean isShapeless = recipe instanceof ShapelessRecipe;

        // Step 1: 打乱物品顺序
        shuffleList(ingredients, random);

        // Step 2: 增减 / 替换
        modifyIngredients(ingredients, random, isShapeless);
    }

    // ==================== 打乱顺序 ====================

    private static void shuffleList(List<Ingredient> list, Random random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(list, i, j);
        }
    }

    // ==================== 增减 / 替换 ====================

    private static void modifyIngredients(DefaultedList<Ingredient> ingredients,
                                          Random random,
                                          boolean canChangeSize) {
        List<Item> availableItems = collectUsedItems(ingredients);
        if (availableItems.isEmpty()) return;

        int changes = 1 + random.nextInt(Math.min(2, canChangeSize ? 2 : 1));

        for (int m = 0; m < changes; m++) {
            if (ingredients.isEmpty()) break;

            if (canChangeSize && random.nextDouble() < 0.35 && ingredients.size() < 9) {
                ingredients.add(Ingredient.ofItems(
                        availableItems.get(random.nextInt(availableItems.size()))));
            } else if (canChangeSize && random.nextDouble() < 0.35 && ingredients.size() > 1) {
                ingredients.remove(random.nextInt(ingredients.size()));
            } else {
                replaceNonEmpty(ingredients, availableItems, random);
            }
        }
    }

    private static List<Item> collectUsedItems(DefaultedList<Ingredient> ingredients) {
        Set<Item> items = new HashSet<>();
        for (Ingredient ing : ingredients) {
            if (ing == null || ing.isEmpty()) continue;
            for (ItemStack stack : ing.getMatchingStacks()) {
                if (stack != null && !stack.isEmpty()) {
                    items.add(stack.getItem());
                }
            }
        }
        items.remove(Items.AIR);
        return new ArrayList<>(items);
    }

    private static void replaceNonEmpty(DefaultedList<Ingredient> ingredients,
                                        List<Item> candidates,
                                        Random random) {
        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ing = ingredients.get(i);
            if (ing != null && !ing.isEmpty()) {
                nonEmpty.add(i);
            }
        }
        if (nonEmpty.isEmpty()) return;

        int idx = nonEmpty.get(random.nextInt(nonEmpty.size()));
        ingredients.set(idx, Ingredient.ofItems(
                candidates.get(random.nextInt(candidates.size()))));
    }

    // ==================== 重复检测 ====================

    private static void checkForDuplicates(Map<Identifier, Recipe<?>> recipes,
                                           DynamicRegistryManager registryManager) {
        Map<Item, Set<Set<Item>>> seen = new HashMap<>();
        int duplicateCount = 0;

        for (Map.Entry<Identifier, Recipe<?>> entry : recipes.entrySet()) {
            Recipe<?> recipe = entry.getValue();
            if (recipe.getType() != RecipeType.CRAFTING) continue;

            Item result = recipe.getOutput(registryManager).getItem();
            Set<Item> ingredientSet = new HashSet<>();
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                ItemStack[] stacks = ing.getMatchingStacks();
                if (stacks != null && stacks.length > 0 && !stacks[0].isEmpty()) {
                    ingredientSet.add(stacks[0].getItem());
                }
            }

            Set<Set<Item>> existing = seen.computeIfAbsent(result, k -> new HashSet<>());
            if (!existing.add(ingredientSet)) {
                duplicateCount++;
                LOGGER.warn("Duplicate recipe detected: {} → {} (ingredients: {})",
                        entry.getKey(),
                        result.getName().getString(),
                        ingredientSet.stream()
                                .map(i -> i.getName().getString())
                                .collect(Collectors.toSet()));
            }
        }

        if (duplicateCount > 0) {
            LOGGER.warn("Found {} duplicate crafting recipes after shuffling", duplicateCount);
        } else {
            LOGGER.info("No duplicate recipes found after shuffling");
        }
    }
}