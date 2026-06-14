package com.randomrecipes.mixin;

import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(RecipeManager.class)
public interface RecipeManagerAccessor {

    /**
     * Yarn: {@code recipesById}<br>
     * Mojang: {@code recipes}<br>
     * {@code Map<Identifier, Recipe<?>>}
     */
    @Accessor("recipesById")
    Map<Identifier, Recipe<?>> getRecipesById();

    /**
     * Yarn: {@code recipes}<br>
     * Mojang: {@code recipesByType}<br>
     * {@code Map<RecipeType<?>, Map<Identifier, Recipe<?>>>}
     */
    @Accessor("recipes")
    Map<RecipeType<?>, Map<Identifier, Recipe<?>>> getRecipesByType();
}