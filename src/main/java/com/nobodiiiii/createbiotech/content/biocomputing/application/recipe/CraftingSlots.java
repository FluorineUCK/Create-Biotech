package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;

/** For each input, its zero-based cell on the 3×3 workbench (x + 3*y). Missing cells are empty.
 * Normal workbench translation and horizontal reflection, not rotation, are allowed. */
public record CraftingSlots(List<Integer> cells) {
    public static final int SIDE = 3, CAPACITY = SIDE * SIDE;
    public static final CraftingSlots NONE = new CraftingSlots(List.of());
    public CraftingSlots {
        if (cells.size() > CAPACITY) throw new IllegalArgumentException("Too many crafting cells");
        cells = List.copyOf(cells);
        int seen = 0;
        for (int slot : cells) {
            if (slot < 0 || slot >= CAPACITY || (seen & (1 << slot)) != 0)
                throw new IllegalArgumentException("Invalid or repeated crafting cell");
            seen |= 1 << slot;
        }
    }
    public boolean shaped() { return !cells.isEmpty(); }
    public void validate(List<PatternIngredient> inputs) {
        if (shaped() && (cells.size() != inputs.size() || inputs.stream().anyMatch(i -> i.count() != 1)))
            throw new IllegalArgumentException("Each occupied crafting cell needs one ingredient");
    }
    public JsonArray json() {
        var array = new JsonArray(cells.size());
        cells.forEach(array::add);
        return array;
    }
    public static CraftingSlots read(JsonElement element) {
        if (!element.isJsonArray()) throw new IllegalArgumentException("Expected crafting cells");
        var array = element.getAsJsonArray();
        if (array.isEmpty() || array.size() > CAPACITY) throw new IllegalArgumentException("Invalid crafting grid");
        var slots = new ArrayList<Integer>(array.size());
        for (var v : array) {
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid crafting cell");
            slots.add(v.getAsBigDecimal().intValueExact());
        }
        return new CraftingSlots(slots);
    }
}
