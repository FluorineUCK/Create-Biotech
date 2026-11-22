package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.CraftingSlots;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;

/** Unsaved recipe values only; neither a catalog nor a second persistence layer. */
public final class PatternRecipeDraft {
    private final List<PatternIngredient> inputs;
    private final List<PatternOutput> outputs;
    private final List<PatternStage> stages;
    private boolean sequenced;
    private List<Integer> craftingCells; // null only for an explicitly unordered draft
    private String address;
    private OptionalInt priority;

    public PatternRecipeDraft() {
        inputs = new ArrayList<>(); outputs = new ArrayList<>(); stages = new ArrayList<>();
        address = ""; priority = OptionalInt.empty();
    }
    public PatternRecipeDraft(PatternRecord original) {
        inputs = new ArrayList<>(original.inputs());
        outputs = new ArrayList<>(original.outputs());
        sequenced = original.body() instanceof SequencedPatternBody;
        stages = new ArrayList<>(sequenced ? ((SequencedPatternBody) original.body()).stages() : List.of());
        address = sequenced ? stages.getFirst().address() : ((StandardPatternBody) original.body()).address();
        if (original.body() instanceof StandardPatternBody s && s.grid().shaped()) craftingCells = new ArrayList<>(s.grid().cells());
        priority = original.priorityOverride();
    }
    public boolean sequenced() { return sequenced; }
    /** Initialize only an empty draft. Keep operator-owned address and priority. */
    public void initialize(RecipeDraftPreview preview) {
        if (shaped() || sequenced || !stages.isEmpty() || !inputs.isEmpty() || !outputs.isEmpty())
            throw new IllegalStateException("Draft already contains a recipe");
        inputs.addAll(preview.inputs());
        if (preview.grid().shaped()) craftingCells = new ArrayList<>(preview.grid().cells());
        outputs.add(preview.output());
    }
    public List<PatternIngredient> inputs(int stage) { return List.copyOf(stage < 0 ? inputs : stages.get(stage).inputs()); }
    public List<PatternOutput> outputs() { return List.copyOf(outputs); }
    public boolean shaped() { return craftingCells != null; }
    public void detachGrid() { craftingCells = null; }
    /** Screen slots include holes. The material and its edge keep their own list index. */
    public int inputAtCell(int slot) { return shaped() ? craftingCells.indexOf(slot) : slot < inputs.size() ? slot : -1; }
    public List<PatternStage> stages() { return List.copyOf(stages); }
    public String address(int stage) { return stage < 0 ? address : stages.get(stage).address(); }
    public OptionalInt priority() { return priority; }
    public void setPriority(OptionalInt value) { priority = value; }
    public void setAddress(int stage, String value) {
        if (stage < 0) address = value;
        else stages.set(stage, new PatternStage(stage, value, stages.get(stage).inputs()));
    }
    public void setInput(int stage, int index, PatternIngredient value) {
        List<PatternIngredient> list = new ArrayList<>(inputs(stage));
        if (stage < 0 && shaped()) {
            if (value.count()!=1) throw new IllegalArgumentException("Crafting slots each consume one");
            if (index == list.size()) {
                int free=0; while (free < CraftingSlots.CAPACITY && craftingCells.contains(free)) free++;
                if (free == CraftingSlots.CAPACITY) throw new IllegalArgumentException("Crafting grid full");
                craftingCells.add(free);
            }
        }
        if (index == list.size()) list.add(value); else list.set(index, value);
        replaceInputs(stage, list);
    }
    public void setInputCount(int stage, int index, int value) {
        PatternIngredient old = inputs(stage).get(index);
        setInput(stage, index, new PatternIngredient(old.itemId(), old.tagId(), value, old.components()));
    }
    public void removeInput(int stage, int index) {
        var list = new ArrayList<>(inputs(stage)); list.remove(index); replaceInputs(stage, list);
        if (stage < 0 && shaped()) craftingCells.remove(index);
    }
    private void replaceInputs(int stage, List<PatternIngredient> value) {
        if (stage < 0) { inputs.clear(); inputs.addAll(value); }
        else stages.set(stage, new PatternStage(stage, stages.get(stage).address(), value));
    }
    public void setOutput(int index, PatternOutput value) {
        if (index == outputs.size()) outputs.add(value); else outputs.set(index, value);
    }
    public void setOutputCount(int index, int value) { outputs.set(index, new PatternOutput(outputs.get(index).stack(), value)); }
    public void removeOutput(int index) { outputs.remove(index); }
    public void addStage() {
        addStage(address);
    }
    public void addStage(String stageAddress) {
        if (shaped()) throw new IllegalArgumentException("Detach workbench layout before changing recipe kind");
        var stage = new PatternStage(stages.size(), stageAddress, List.of());
        if (stages.isEmpty() && address.isBlank()) address = stage.address();
        stages.add(stage); sequenced = true;
    }
    public void moveStage(int from, int to) { Collections.swap(stages, from, to); reindexStages(); }
    public void removeStage(int index) { stages.remove(index); reindexStages(); }
    private void reindexStages() {
        for (int i = 0; i < stages.size(); i++) {
            var stage = stages.get(i); stages.set(i, new PatternStage(i, stage.address(), stage.inputs()));
        }
    }
    public void setSequenced(boolean value) {
        if (value && stages.isEmpty()) addStage();
        sequenced = value;
    }
    public PatternEditData snapshot() {
        if (shaped() && craftingCells.isEmpty()) throw new IllegalArgumentException("Crafting grid is empty");
        return new PatternEditData(inputs, outputs, sequenced ? new SequencedPatternBody(stages)
            : new StandardPatternBody(address, shaped() ? new CraftingSlots(craftingCells) : CraftingSlots.NONE), priority);
    }
}

