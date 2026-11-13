package com.kuh.craftadvisor.tools;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.kuh.craftadvisor.ai.OutgoingRequest;
import com.kuh.craftadvisor.ai.ToolCall;
import com.kuh.craftadvisor.ai.ToolResult;
import com.kuh.craftadvisor.ai.ToolSpec;
import com.kuh.craftadvisor.planner.CraftPlanner;
import com.kuh.craftadvisor.planner.IngredientKey;
import com.kuh.craftadvisor.planner.IngredientSlot;
import com.kuh.craftadvisor.planner.RecipeOption;
import com.kuh.craftadvisor.planner.RecipeSource;

/**
 * The four game-data tools of the free-question loop. Results are compact JSON with size limits; names are
 * default names only. The share settings apply: without inventory sharing, {@code get_inventory} is not offered
 * and {@code compute_missing} plans from an empty inventory. Call {@link #run} where the game data may be read.
 */
public final class AdvisorTools {
    public static final String SEARCH_ITEMS = "search_items";
    public static final String GET_RECIPES = "get_recipes";
    public static final String GET_INVENTORY = "get_inventory";
    public static final String COMPUTE_MISSING = "compute_missing";
    static final int SEARCH_LIMIT = 15;
    static final int RECIPE_LIMIT = 5;
    static final int ALTERNATIVES_SHOWN = 3;
    static final int INVENTORY_LIMIT = 40;
    static final int MAX_AMOUNT = 999;

    private static final ToolSpec SEARCH_SPEC = spec(SEARCH_ITEMS,
            "Search JEI ingredients (items, fluids and other kinds such as bees) by name in the game language, "
                    + "English name, or id. Returns up to " + SEARCH_LIMIT + " matches with their uid, kind, name, "
                    + "English name and whether a recipe makes them. Try other words if nothing matches.",
            """
            {"type":"object","properties":{"query":{"type":"string","description":"Name or id, e.g. mechanical piston"}},
             "required":["query"]}""");
    private static final ToolSpec RECIPES_SPEC = spec(GET_RECIPES,
            "Recipes that make an ingredient, read from JEI: category, machine, inputs with amounts (fluids in mB, "
                    + "at most " + ALTERNATIVES_SHOWN + " alternatives shown per slot) and outputs. At most "
                    + RECIPE_LIMIT + " recipes; totalRecipes tells how many exist.",
            """
            {"type":"object","properties":{"uid":{"type":"string","description":"uid from search_items"}},
             "required":["uid"]}""");
    private static final ToolSpec INVENTORY_SPEC = spec(GET_INVENTORY,
            "What the player carries: up to " + INVENTORY_LIMIT + " kinds, most first, and the number of kinds.",
            """
            {"type":"object","properties":{}}""");
    private static final ToolSpec MISSING_SPEC = spec(COMPUTE_MISSING,
            "Compute in code how to make an item: the steps to craft, machines, raw materials still missing "
                    + "with exact counts, and where the planner chose between alternatives. Items only.",
            """
            {"type":"object","properties":{"uid":{"type":"string","description":"item uid from search_items"},
             "amount":{"type":"integer","minimum":1,"maximum":999}},"required":["uid","amount"]}""");

    /** Game data the tools read. {@code names} gives default names, never names a player wrote. */
    public record Data(Supplier<NameMatcher> index, RecipeSource recipes, CraftPlanner planner,
            Supplier<Map<IngredientKey, Integer>> inventory, Function<IngredientKey, String> names) {
    }

    private final Data data;
    private final OutgoingRequest.Sharing sharing;
    private NameMatcher index;

    public AdvisorTools(Data data, OutgoingRequest.Sharing sharing) {
        this.data = data;
        this.sharing = sharing;
    }

    /** Tools offered under {@code sharing}, in a fixed order (part of the cached request prefix). */
    public static List<ToolSpec> offered(OutgoingRequest.Sharing sharing) {
        return sharing.inventory()
                ? List.of(SEARCH_SPEC, RECIPES_SPEC, INVENTORY_SPEC, MISSING_SPEC)
                : List.of(SEARCH_SPEC, RECIPES_SPEC, MISSING_SPEC);
    }

    /** Only search_items: for finding one item by its meaning (/ai add), which needs no other game data. */
    public static List<ToolSpec> searchOnly() {
        return List.of(SEARCH_SPEC);
    }

    /** Tools left out by {@code sharing}, for the preview. */
    public static List<String> off(OutgoingRequest.Sharing sharing) {
        return sharing.inventory() ? List.of() : List.of(GET_INVENTORY);
    }

    /** Never throws for bad input: problems become error results the AI can correct. */
    public ToolResult run(ToolCall call) {
        try {
            JsonElement result = switch (call.name()) {
                case SEARCH_ITEMS -> search(text(call, "query"));
                case GET_RECIPES -> recipes(resolve(text(call, "uid")));
                case GET_INVENTORY -> inventory();
                case COMPUTE_MISSING -> missing(resolve(text(call, "uid")), amount(call));
                default -> throw new BadInput("Unknown tool: " + call.name());
            };
            return ToolResult.ok(call.id(), result.toString());
        } catch (BadInput e) {
            return ToolResult.error(call.id(), e.getMessage());
        }
    }

    private JsonElement search(String query) {
        JsonArray results = new JsonArray();
        for (NameMatcher.Entry entry : index().search(query, SEARCH_LIMIT)) {
            JsonObject json = new JsonObject();
            json.addProperty("uid", entry.id());
            json.addProperty("kind", entry.key().kind());
            json.addProperty("name", entry.name());
            if (!entry.nameEn().isEmpty() && !entry.nameEn().equals(entry.name())) {
                json.addProperty("nameEn", entry.nameEn());
            }
            json.addProperty("hasRecipe", !data.recipes().recipesFor(entry.key()).isEmpty());
            results.add(json);
        }
        return results;
    }

    private JsonElement recipes(IngredientKey key) {
        List<RecipeOption> all = data.recipes().recipesFor(key);
        JsonArray recipes = new JsonArray();
        all.stream().limit(RECIPE_LIMIT).forEach(recipe -> recipes.add(recipeJson(key, recipe)));
        JsonObject json = new JsonObject();
        json.add("recipes", recipes);
        json.addProperty("totalRecipes", all.size());
        return json;
    }

    private JsonObject recipeJson(IngredientKey target, RecipeOption recipe) {
        JsonObject json = new JsonObject();
        json.addProperty("category", recipe.category());
        if (!recipe.machines().isEmpty()) {
            json.addProperty("machine", data.names().apply(recipe.machines().get(0)));
        }
        JsonArray inputs = new JsonArray();
        recipe.inputs().forEach(slot -> inputs.add(slotJson(slot)));
        json.add("inputs", inputs);
        JsonArray outputs = new JsonArray();
        List<IngredientSlot> outputSlots = recipe.outputs().isEmpty()
                ? List.of(new IngredientSlot(List.of(target), recipe.outputCount()))
                : recipe.outputs();
        outputSlots.forEach(slot -> outputs.add(slotJson(slot)));
        json.add("outputs", outputs);
        return json;
    }

    private JsonObject slotJson(IngredientSlot slot) {
        IngredientKey first = slot.alternatives().get(0);
        JsonObject json = amountJson(first, slot.count());
        if (slot.alternatives().size() > 1) {
            JsonArray alternatives = new JsonArray();
            slot.alternatives().stream().limit(ALTERNATIVES_SHOWN).forEach(alt -> alternatives.add(alt.toString()));
            json.add("alternatives", alternatives);
            json.addProperty("alternativeCount", slot.alternatives().size());
        }
        return json;
    }

    private JsonObject amountJson(IngredientKey key, int amount) {
        JsonObject json = new JsonObject();
        json.addProperty("name", data.names().apply(key));
        json.addProperty("uid", key.toString());
        json.addProperty("amount", amount);
        json.addProperty("unit", IngredientKey.FLUID.equals(key.kind()) ? "mB" : "items");
        return json;
    }

    private JsonElement inventory() {
        if (!sharing.inventory()) {
            throw new BadInput("The player does not share the inventory.");
        }
        Map<IngredientKey, Integer> counts = data.inventory().get();
        JsonArray items = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<IngredientKey, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().toString()))
                .limit(INVENTORY_LIMIT)
                .forEach(e -> {
                    JsonObject json = new JsonObject();
                    json.addProperty("uid", e.getKey().toString());
                    json.addProperty("name", data.names().apply(e.getKey()));
                    json.addProperty("count", e.getValue());
                    items.add(json);
                });
        JsonObject json = new JsonObject();
        json.add("items", items);
        json.addProperty("totalKinds", counts.size());
        return json;
    }

    /** The same plan JSON the advice request carries; numbers straight from {@link CraftPlanner}. */
    private JsonElement missing(IngredientKey key, int amount) {
        if (!key.isItem()) {
            throw new BadInput("compute_missing works for items only; use get_recipes for " + key);
        }
        Map<IngredientKey, Integer> inventory = sharing.inventory() ? data.inventory().get() : Map.of();
        return JsonParser.parseString(OutgoingRequest.planJson(data.planner().plan(key, amount, inventory), this::label));
    }

    private String label(IngredientKey key) {
        return data.names().apply(key) + " (" + key + ")";
    }

    private IngredientKey resolve(String uid) {
        Optional<NameMatcher.Entry> entry = index().byId(uid);
        if (entry.isEmpty()) {
            throw new BadInput("Unknown uid: " + uid + ". Use a uid exactly as search_items returned it.");
        }
        return entry.get().key();
    }

    private NameMatcher index() {
        if (index == null) {
            index = data.index().get();
        }
        return index;
    }

    private static String text(ToolCall call, String field) {
        JsonElement value = call.input() == null ? null : call.input().get(field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString() || primitive.getAsString().isBlank()) {
            throw new BadInput("Input needs a non-empty string field \"" + field + "\".");
        }
        return primitive.getAsString();
    }

    private static int amount(ToolCall call) {
        JsonElement value = call.input() == null ? null : call.input().get("amount");
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new BadInput("Input needs an integer field \"amount\" from 1 to " + MAX_AMOUNT + ".");
        }
        double amount = primitive.getAsDouble();
        if (amount != Math.rint(amount) || amount < 1 || amount > MAX_AMOUNT) {
            throw new BadInput("\"amount\" must be an integer from 1 to " + MAX_AMOUNT + ".");
        }
        return (int) amount;
    }

    private static ToolSpec spec(String name, String description, String schema) {
        return new ToolSpec(name, description, JsonParser.parseString(schema).getAsJsonObject());
    }

    /** A problem the AI can fix by calling again differently. */
    private static final class BadInput extends RuntimeException {
        BadInput(String message) {
            super(message);
        }
    }
}
