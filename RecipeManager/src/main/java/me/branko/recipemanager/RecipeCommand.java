package me.branko.recipemanager;

import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RecipeCommand implements TabExecutor {

    private final RecipeManagerPlugin plugin;
    private final RecipeService service;

    public RecipeCommand(RecipeManagerPlugin plugin, RecipeService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("recipemanager.admin")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eUsage: /" + label + " <reload|list>");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                RecipeService.Result r = service.reload();
                sender.sendMessage("§aRecipes reloaded: §f" + r.disabled + "§a disabled, §f" + r.custom + "§a custom/replaced.");
                if (!r.warnings.isEmpty()) {
                    sender.sendMessage("§e" + r.warnings.size() + " warning(s):");
                    r.warnings.stream().limit(8).forEach(w -> sender.sendMessage("§e - " + w));
                    if (r.warnings.size() > 8) {
                        sender.sendMessage("§e ... see console for the rest.");
                    }
                }
            }
            case "list" -> {
                sender.sendMessage("§6Disabled (" + service.getDisabledKeys().size() + "):");
                for (NamespacedKey k : service.getDisabledKeys()) sender.sendMessage("§7 - " + k);
                sender.sendMessage("§6Custom/replaced (" + service.getCustomKeys().size() + "):");
                for (NamespacedKey k : service.getCustomKeys()) sender.sendMessage("§7 - " + k);
            }
            default -> sender.sendMessage("§eUsage: /" + label + " <reload|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("recipemanager.admin")) {
            for (String s : List.of("reload", "list")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }
}
