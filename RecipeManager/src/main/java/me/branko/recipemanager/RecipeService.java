package me.branko.recipemanager;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Applies the config to the server's recipes and can undo everything it did.
 * Only uses stable Bukkit API calls so it works across 1.21.11 and newer versions.
 */
public final class RecipeService {

    public static final class Result {
        public int disabled;
        public int custom;
        public final List<String> warnings = new ArrayList<>();
    }

    private final JavaPlugin plugin;
    /** Original recipes we removed or replaced, so they can be restored on reload. */
    private final Map<NamespacedKey, Recipe> backups = new LinkedHashMap<>();
    /** Keys we registered ourselves (new or replacing). */
    private final Set<NamespacedKey> customKeys = new LinkedHashSet<>();
    private final Set<NamespacedKey> disabledKeys = new LinkedHashSet<>();

    public RecipeService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Set<NamespacedKey> getDisabledKeys() {
        return disabledKeys;
    }

    public Set<NamespacedKey> getCustomKeys() {
        return customKeys;
    }

    public Result reload() {
        plugin.reloadConfig();
        rollback();

        Result result = new Result();
        ConfigurationSection cfg = plugin.getConfig();

        // ---- disabling ----
        Set<NamespacedKey> toDisable = new LinkedHashSet<>();
        for (String entry : cfg.getStringList("disabled")) {
            collectDisabled(entry.trim(), toDisable, result);
        }

        Set<Material> blockedResults = new HashSet<>();
        for (String name : cfg.getStringList("disabled-results")) {
            Material m = Material.matchMaterial(name.trim());
            if (m == null) {
                warn(result, "Unknown material in disabled-results: " + name);
            } else {
                blockedResults.add(m);
            }
        }
        if (!blockedResults.isEmpty()) {
            for (Recipe recipe : snapshot()) {
                if (recipe instanceof Keyed keyed && blockedResults.contains(recipe.getResult().getType())) {
                    toDisable.add(keyed.getKey());
                }
            }
        }

        for (NamespacedKey key : toDisable) {
            Recipe existing = Bukkit.getRecipe(key);
            if (existing == null) {
                warn(result, "Recipe not found, cannot disable: " + key);
                continue;
            }
            backups.putIfAbsent(key, existing);
            Bukkit.removeRecipe(key);
            disabledKeys.add(key);
        }

        // ---- custom / replacement recipes ----
        ConfigurationSection recipes = cfg.getConfigurationSection("recipes");
        if (recipes != null) {
            for (String name : recipes.getKeys(false)) {
                ConfigurationSection sec = recipes.getConfigurationSection(name);
                if (sec == null) {
                    warn(result, "Recipe '" + name + "' is not a section, skipped.");
                    continue;
                }
                if (!sec.getBoolean("enabled", true)) {
                    continue;
                }
                try {
                    NamespacedKey key = parseKey(name, false);
                    Recipe recipe = build(key, sec);
                    register(key, recipe);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    warn(result, "Recipe '" + name + "': " + e.getMessage());
                }
            }
        }

        result.disabled = disabledKeys.size();
        result.custom = customKeys.size();

        pushUpdates(cfg.getBoolean("discover-custom-recipes", true));
        return result;
    }

    /** Remove everything we added and put back everything we removed. */
    public void rollback() {
        for (NamespacedKey key : customKeys) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException ignored) {
            }
        }
        for (Recipe original : backups.values()) {
            try {
                Bukkit.addRecipe(original);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not restore recipe: " + e.getMessage());
            }
        }
        customKeys.clear();
        disabledKeys.clear();
        backups.clear();
    }

    // ------------------------------------------------------------------ helpers

    private void register(NamespacedKey key, Recipe recipe) {
        Recipe existing = Bukkit.getRecipe(key);
        if (existing != null) {
            backups.putIfAbsent(key, existing);
            Bukkit.removeRecipe(key);
        }
        boolean ok;
        try {
            ok = Bukkit.addRecipe(recipe);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            if (existing != null) {
                try {
                    Bukkit.addRecipe(existing);
                } catch (RuntimeException ignored) {
                }
                backups.remove(key);
            }
            throw new IllegalStateException("server refused to register " + key);
        }
        customKeys.add(key);
    }

    private void collectDisabled(String entry, Set<NamespacedKey> out, Result result) {
        if (entry.isEmpty()) {
            return;
        }
        String lower = entry.toLowerCase(Locale.ROOT);
        if (!lower.contains(":")) {
            lower = "minecraft:" + lower;
        }
        if (lower.contains("*")) {
            StringBuilder regex = new StringBuilder();
            for (String part : lower.split("\\*", -1)) {
                if (regex.length() > 0) {
                    regex.append(".*");
                }
                regex.append(Pattern.quote(part));
            }
            Pattern pattern = Pattern.compile(regex.toString());
            boolean any = false;
            for (Recipe recipe : snapshot()) {
                if (recipe instanceof Keyed keyed && pattern.matcher(keyed.getKey().toString()).matches()) {
                    out.add(keyed.getKey());
                    any = true;
                }
            }
            if (!any) {
                warn(result, "Pattern matched no recipes: " + entry);
            }
            return;
        }
        try {
            out.add(parseKey(lower, true));
        } catch (IllegalArgumentException e) {
            warn(result, e.getMessage());
        }
    }

    private List<Recipe> snapshot() {
        List<Recipe> list = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            try {
                list.add(it.next());
            } catch (RuntimeException ignored) {
            }
        }
        return list;
    }

    private NamespacedKey parseKey(String raw, boolean requireNamespace) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key;
        if (s.contains(":")) {
            key = NamespacedKey.fromString(s);
        } else if (requireNamespace) {
            key = NamespacedKey.fromString("minecraft:" + s);
        } else {
            key = NamespacedKey.fromString(plugin.getName().toLowerCase(Locale.ROOT) + ":" + s);
        }
        if (key == null) {
            throw new IllegalArgumentException("Invalid recipe ID: " + raw);
        }
        return key;
    }

    private Recipe build(NamespacedKey key, ConfigurationSection s) {
        String type = s.getString("type", "").toLowerCase(Locale.ROOT);
        ItemStack result = parseResult(s);

        switch (type) {
            case "shaped": {
                List<String> shape = s.getStringList("shape");
                if (shape.isEmpty() || shape.size() > 3) {
                    throw new IllegalArgumentException("shape needs 1-3 rows");
                }
                ShapedRecipe r = new ShapedRecipe(key, result);
                r.shape(shape.toArray(new String[0]));
                ConfigurationSection ing = s.getConfigurationSection("ingredients");
                if (ing == null) {
                    throw new IllegalArgumentException("missing 'ingredients'");
                }
                for (String k : ing.getKeys(false)) {
                    if (k.length() != 1) {
                        throw new IllegalArgumentException("ingredient key must be one character: " + k);
                    }
                    r.setIngredient(k.charAt(0), choice(ing.get(k), k));
                }
                for (String row : shape) {
                    for (char c : row.toCharArray()) {
                        if (c != ' ' && r.getChoiceMap().get(c) == null) {
                            throw new IllegalArgumentException("no ingredient defined for '" + c + "'");
                        }
                    }
                }
                group(r, s);
                return r;
            }
            case "shapeless": {
                List<?> list = s.getList("ingredients");
                if (list == null || list.isEmpty() || list.size() > 9) {
                    throw new IllegalArgumentException("ingredients needs 1-9 entries");
                }
                ShapelessRecipe r = new ShapelessRecipe(key, result);
                for (Object o : list) {
                    r.addIngredient(choice(o, "ingredients"));
                }
                group(r, s);
                return r;
            }
            case "smelting":
                return new FurnaceRecipe(key, result, choice(s.get("ingredient"), "ingredient"),
                        (float) s.getDouble("experience", 0), s.getInt("cooking-time", 200));
            case "blasting":
                return new BlastingRecipe(key, result, choice(s.get("ingredient"), "ingredient"),
                        (float) s.getDouble("experience", 0), s.getInt("cooking-time", 100));
            case "smoking":
                return new SmokingRecipe(key, result, choice(s.get("ingredient"), "ingredient"),
                        (float) s.getDouble("experience", 0), s.getInt("cooking-time", 100));
            case "campfire":
                return new CampfireRecipe(key, result, choice(s.get("ingredient"), "ingredient"),
                        (float) s.getDouble("experience", 0), s.getInt("cooking-time", 600));
            case "stonecutting":
                return new StonecuttingRecipe(key, result, choice(s.get("ingredient"), "ingredient"));
            case "smithing":
                return new SmithingTransformRecipe(key, result,
                        choice(s.get("template"), "template"),
                        choice(s.get("base"), "base"),
                        choice(s.get("addition"), "addition"));
            default:
                throw new IllegalArgumentException("unknown or missing type '" + type + "'");
        }
    }

    private void group(Recipe r, ConfigurationSection s) {
        String g = s.getString("group");
        if (g == null) {
            return;
        }
        if (r instanceof ShapedRecipe sr) {
            sr.setGroup(g);
        } else if (r instanceof ShapelessRecipe sl) {
            sl.setGroup(g);
        }
    }

    private ItemStack parseResult(ConfigurationSection s) {
        String name = s.getString("result");
        if (name == null) {
            throw new IllegalArgumentException("missing 'result'");
        }
        Material m = Material.matchMaterial(name.trim());
        if (m == null || !m.isItem()) {
            throw new IllegalArgumentException("unknown result item: " + name);
        }
        int amount = Math.max(1, Math.min(s.getInt("amount", 1), m.getMaxStackSize()));
        return new ItemStack(m, amount);
    }

    private RecipeChoice.MaterialChoice choice(Object raw, String field) {
        List<String> names = new ArrayList<>();
        if (raw instanceof String str) {
            names.add(str);
        } else if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                names.add(String.valueOf(o));
            }
        } else {
            throw new IllegalArgumentException("missing or invalid '" + field + "'");
        }

        Set<Material> mats = new LinkedHashSet<>();
        for (String n : names) {
            n = n.trim();
            if (n.startsWith("#")) {
                NamespacedKey tagKey = NamespacedKey.fromString(n.substring(1).toLowerCase(Locale.ROOT));
                Tag<Material> tag = tagKey == null ? null
                        : Bukkit.getTag(Tag.REGISTRY_ITEMS, tagKey, Material.class);
                if (tag == null) {
                    throw new IllegalArgumentException("unknown item tag: " + n);
                }
                mats.addAll(tag.getValues());
            } else {
                Material m = Material.matchMaterial(n);
                if (m == null || !m.isItem()) {
                    throw new IllegalArgumentException("unknown item '" + n + "' in " + field);
                }
                mats.add(m);
            }
        }
        if (mats.isEmpty()) {
            throw new IllegalArgumentException("'" + field + "' is empty");
        }
        return new RecipeChoice.MaterialChoice(new ArrayList<>(mats));
    }

    /** Re-sync recipes to clients (Paper) and show custom recipes in the recipe book. */
    private void pushUpdates(boolean discover) {
        try {
            Bukkit.class.getMethod("updateRecipes").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not available on this server software; players see changes after relog
        }
        if (discover && !customKeys.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    p.discoverRecipes(customKeys);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void warn(Result result, String msg) {
        result.warnings.add(msg);
        plugin.getLogger().warning(msg);
    }
}
