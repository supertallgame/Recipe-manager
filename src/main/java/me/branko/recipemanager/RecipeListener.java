package me.branko.recipemanager;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;

import java.util.ArrayList;

public final class RecipeListener implements Listener {

    private final RecipeService service;

    public RecipeListener(RecipeService service) {
        this.service = service;
    }

    // Stops the "recipe unlocked" toast and recipe-book entry for disabled recipes
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDiscover(PlayerRecipeDiscoverEvent event) {
        if (service.getDisabledKeys().contains(event.getRecipe())) {
            event.setCancelled(true);
        }
    }

    // Cleans disabled recipes out of the recipe book when a player joins
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (service.getDisabledKeys().isEmpty()) {
            return;
        }
        try {
            event.getPlayer().undiscoverRecipes(new ArrayList<>(service.getDisabledKeys()));
        } catch (RuntimeException ignored) {
        }
    }
}
