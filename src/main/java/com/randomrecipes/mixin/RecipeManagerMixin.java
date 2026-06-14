package com.randomrecipes.mixin;

import com.randomrecipes.RandomRecipesConfig;
import com.randomrecipes.RandomRecipesMod;
import com.randomrecipes.RecipeShuffler;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 在 {@code /reload} 时重新随机化配方。
 * 初始加载时 {@link RandomRecipesMod#isInitialized()} 为 false，跳过；
 * 当世界种子可用后（已有存档 + reload），每次都重新应用相同的随机化。
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {

    @Inject(method = "apply", at = @At("RETURN"))
    private void onApply(Map<Identifier, Recipe<?>> map,
                         ResourceManager resourceManager,
                         Profiler profiler,
                         CallbackInfo ci) {
        if (RandomRecipesMod.isInitialized()) {
            // /reload 时重新加载配置，使玩家对配置文件的修改即时生效
            RandomRecipesConfig.reload();
            RecipeShuffler.shuffleRecipes(map, RandomRecipesMod.getWorldSeed());
        }
    }
}