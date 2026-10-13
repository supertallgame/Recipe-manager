package me.branko.recipemanager;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RecipeManagerPlugin extends JavaPlugin {

    private RecipeService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        checkVersion();
        service = new RecipeService(this);
        getServer().getPluginManager().registerEvents(new RecipeListener(service), this);

        PluginCommand cmd = getCommand("recipes");
        if (cmd != null) {
            RecipeCommand handler = new RecipeCommand(this, service);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Run one tick later so recipes from all other plugins are registered first.
        Bukkit.getScheduler().runTask(this, () -> {
            RecipeService.Result r = service.reload();
            getLogger().info("Applied config: " + r.disabled + " recipe(s) disabled, " + r.custom + " custom/replaced.");
        });
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.rollback(); // restore vanilla recipes so a plugin reload leaves nothing behind
        }
    }

    private void checkVersion() {
        try {
            String[] p = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
            int a = Integer.parseInt(p[0]);
            int b = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int c = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            boolean ok = a > 1 || (a == 1 && (b > 21 || (b == 21 && c >= 11)));
            if (!ok) {
                getLogger().warning("RecipeManager targets Minecraft 1.21.11+, you are on "
                        + Bukkit.getBukkitVersion() + ". Things may not work.");
            }
        } catch (RuntimeException ignored) {
            // unknown version format: assume it is newer and fine
        }
    }
}
