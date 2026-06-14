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

    private static void refreshConfigSnapshot() {
        configProtectedOutputItems = RandomRecipesConfig.getProtectedOutputItems();
        configProtectedRecipeIds = RandomRecipesConfig.getProtectedRecipeIds();
        configProtectionLoaded = true;
    }

    private RecipeShuffler() {
    }

    // ==================== 对外入口 ====================

    public static void shuffleAllRecipes(MinecraftServer server, long seed) {
        DynamicRegistryManager registryManager = server.getRegistryManager();
        RecipeManager manager = server.getRecipeManager();
        Map<Identifier, Recipe<?>> recipes = ((RecipeManagerAccessor) manager).getRecipesById();
        shuffleRecipes(recipes, registryManager, seed);
    }

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
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toList());

        // 2. 建立全局物品池（所有配方中出现过的物品），供单材料配方使用
        List<Item> globalItemPool = buildGlobalPool(craftingEntries, registryManager);

        LOGGER.info("Shuffling {} crafting recipes (seed={}), global pool size={}",
                craftingEntries.size(), seed, globalItemPool.size());

        // 3. 逐个处理
        for (Map.Entry<Identifier, Recipe<?>> entry : craftingEntries) {
            try {
                processRecipe(entry.getValue(), random, globalItemPool);
            } catch (Exception ex) {
                LOGGER.warn("Failed to shuffle recipe {}: {}", entry.getKey(), ex.getMessage());
            }
        }

        // 4. 重复检测 + 修正
        int duplicates = checkForDuplicates(recipes, registryManager);
        if (duplicates > 0) {
            LOGGER.warn("Found {} duplicate crafting recipes; applying conflict resolution", duplicates);
            resolveDuplicates(recipes, craftingEntries, random, registryManager, globalItemPool);
        }

        LOGGER.info("Recipe shuffling complete");
    }

    // ==================== 全局物品池 ====================

    /** 收集所有工作台配方中出现过的非保护物品 */
    private static List<Item> buildGlobalPool(
            List<Map.Entry<Identifier, Recipe<?>>> entries,
            DynamicRegistryManager registryManager) {
        Set<Item> pool = new LinkedHashSet<>();
        for (Map.Entry<Identifier, Recipe<?>> entry : entries) {
            Recipe<?> recipe = entry.getValue();
            if (isProtected(entry.getKey(), recipe, registryManager)) continue;
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing == null || ing.isEmpty()) continue;
                for (ItemStack stack : ing.getMatchingStacks()) {
                    if (stack != null && !stack.isEmpty()) {
                        pool.add(stack.getItem());
                    }
                }
            }
        }
        pool.remove(Items.AIR);
        return new ArrayList<>(pool);
    }

    // ==================== 保护检测 ====================

    private static boolean isProtected(Identifier recipeId, Recipe<?> recipe,
                                       DynamicRegistryManager registryManager) {
        if (PROTECTED_RESULTS.contains(recipe.getOutput(registryManager).getItem())) {
            return true;
        }

        String outputId = Registries.ITEM.getId(
                recipe.getOutput(registryManager).getItem()).toString();
        if (configProtectedOutputItems.contains(outputId)) {
            return true;
        }

        if (configProtectedRecipeIds.contains(recipeId.toString())) {
            return true;
        }

        return false;
    }

    // ==================== 配方处理流程 ====================

    private static void processRecipe(Recipe<?> recipe, Random random, List<Item> globalPool) {
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;

        boolean isShapeless = recipe instanceof ShapelessRecipe;

        // Step 1: 打乱物品顺序
        shuffleList(ingredients, random);

        // Step 2: 增减 / 替换
        modifyIngredients(ingredients, random, isShapeless, globalPool);
    }

    // ==================== 打乱顺序 ====================

    @SuppressWarnings("unchecked")
    private static void shuffleList(List<Ingredient> list, Random random) {
        // Fisher-Yates, 只对非空槽位洗牌
        List<Integer> nonEmpty = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Ingredient ing = list.get(i);
            if (ing != null && !ing.isEmpty()) {
                nonEmpty.add(i);
            }
        }
        // 洗牌下标
        for (int i = nonEmpty.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Collections.swap(list, nonEmpty.get(i), nonEmpty.get(j));
        }
    }

    // ==================== 增减 / 替换 ====================

    private static void modifyIngredients(DefaultedList<Ingredient> ingredients,
                                          Random random,
                                          boolean canChangeSize,
                                          List<Item> globalPool) {
        List<Item> availableItems = collectUsedItems(ingredients);
        if (availableItems.isEmpty()) return;

        // ★ 核心修复：如果只有1种材料，从全局池补充候选
        if (availableItems.size() <= 1 && globalPool != null && !globalPool.isEmpty()) {
            // 取全局池中与该配方不同的物品，最多补充 5 种
            Set<Item> expanded = new HashSet<>(availableItems);
            Item existing = availableItems.get(0);
            // 从全局池中随机挑选不重复的
            List<Item> shuffledGlobal = new ArrayList<>(globalPool);
            Collections.shuffle(shuffledGlobal, random);
            for (Item item : shuffledGlobal) {
                if (!item.equals(existing) && !item.equals(Items.AIR)) {
                    expanded.add(item);
                    if (expanded.size() >= 6) break; // 原1种 + 最多5种新
                }
            }
            availableItems = new ArrayList<>(expanded);
            LOGGER.debug("Expanded single-material pool from [{}] to {} items",
                    Registries.ITEM.getId(existing), availableItems.size());
        }

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

    /** 返回检测到的重复配方对数 */
    private static int checkForDuplicates(Map<Identifier, Recipe<?>> recipes,
                                          DynamicRegistryManager registryManager) {
        Map<Item, Set<Set<Item>>> seen = new HashMap<>();
        int duplicateCount = 0;

        for (Map.Entry<Identifier, Recipe<?>> entry : recipes.entrySet()) {
            Recipe<?> recipe = entry.getValue();
            if (recipe.getType() != RecipeType.CRAFTING) continue;

            Item result = recipe.getOutput(registryManager).getItem();
            Set<Item> ingredientSet = extractIngredientSet(recipe);

            Set<Set<Item>> existing = seen.computeIfAbsent(result, k -> new HashSet<>());
            if (!existing.add(ingredientSet)) {
                duplicateCount++;
                LOGGER.warn("Duplicate recipe: {} → {} (materials: {})",
                        entry.getKey(),
                        result.getName().getString(),
                        ingredientSet.stream()
                                .map(i -> i.getName().getString())
                                .collect(Collectors.toSet()));
            }
        }
        return duplicateCount;
    }

    /** 提取配方中非空原料的 Item 集合 */
    private static Set<Item> extractIngredientSet(Recipe<?> recipe) {
        Set<Item> set = new HashSet<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing == null || ing.isEmpty()) continue;
            ItemStack[] stacks = ing.getMatchingStacks();
            if (stacks != null && stacks.length > 0 && !stacks[0].isEmpty()) {
                set.add(stacks[0].getItem());
            }
        }
        return set;
    }

    // ==================== 重复修正 ====================

    /** 对重复配方做二次随机化，强制破坏相同性 */
    private static void resolveDuplicates(Map<Identifier, Recipe<?>> recipes,
                                          List<Map.Entry<Identifier, Recipe<?>>> entries,
                                          Random random,
                                          DynamicRegistryManager registryManager,
                                          List<Item> globalPool) {
        // 分组：(产物, 原料集合) → 配方列表
        Map<Item, Map<Set<Item>, List<Recipe<?>>>> groups = new HashMap<>();

        for (Map.Entry<Identifier, Recipe<?>> entry : entries) {
            Recipe<?> recipe = entry.getValue();
            Item result = recipe.getOutput(registryManager).getItem();
            Set<Item> ingredientSet = extractIngredientSet(recipe);

            groups.computeIfAbsent(result, k -> new HashMap<>())
                    .computeIfAbsent(ingredientSet, k -> new ArrayList<>())
                    .add(recipe);
        }

        int fixed = 0;
        for (Map.Entry<Item, Map<Set<Item>, List<Recipe<?>>>> resultGroup : groups.entrySet()) {
            for (Map.Entry<Set<Item>, List<Recipe<?>>> ingGroup : resultGroup.getValue().entrySet()) {
                List<Recipe<?>> dupes = ingGroup.getValue();
                if (dupes.size() <= 1) continue;

                // 保留第一个，修改后面的
                for (int i = 1; i < dupes.size(); i++) {
                    Recipe<?> dupe = dupes.get(i);
                    DefaultedList<Ingredient> ingredients = dupe.getIngredients();
                    if (ingredients.isEmpty()) continue;

                    // 强行替换一个非空槽位为全局池中的随机物品
                    List<Integer> nonEmpty = new ArrayList<>();
                    for (int j = 0; j < ingredients.size(); j++) {
                        Ingredient ing = ingredients.get(j);
                        if (ing != null && !ing.isEmpty()) nonEmpty.add(j);
                    }
                    if (!nonEmpty.isEmpty() && globalPool != null && !globalPool.isEmpty()) {
                        int slot = nonEmpty.get(random.nextInt(nonEmpty.size()));
                        Item newItem = globalPool.get(random.nextInt(globalPool.size()));
                        ingredients.set(slot, Ingredient.ofItems(newItem));
                        fixed++;
                    }
                }
            }
        }
        LOGGER.info("Resolved {} duplicate conflicts", fixed);
    }
}