package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.function.Function;

/** Independent pure graph package: fixed dependency bodies and layouts, never an application or RAM. */
public record SubgraphDocument(ModuleDefinition.Key root, Map<ModuleDefinition.Key, ModuleDefinition> modules,
        Map<String, Map<String, ProgramDocument.Position>> positions) {
    public SubgraphDocument {
        Objects.requireNonNull(root); Objects.requireNonNull(modules); Objects.requireNonNull(positions);
        var closure = collect(root, modules::get);
        if (!closure.keySet().equals(modules.keySet())) throw new IllegalArgumentException("Subgraph package contains unrelated modules");
        ProgramCompiler.validateModules(closure);
        modules = Collections.unmodifiableMap(closure);
        var known = new LinkedHashMap<String, Set<String>>();
        modules.forEach((key, module) -> known.put(ProgramDocument.graphId(key), module.graph().nodes().keySet()));
        positions = GraphLayouts.copy(known, positions);
    }
    static LinkedHashMap<ModuleDefinition.Key, ModuleDefinition> collect(ModuleDefinition.Key root,
            Function<ModuleDefinition.Key, ModuleDefinition> lookup) {
        var closure = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
        var pending = new ArrayDeque<ModuleDefinition.Key>(); pending.add(Objects.requireNonNull(root));
        while (!pending.isEmpty()) {
            var key = pending.removeFirst(); if (closure.containsKey(key)) continue;
            var module = lookup.apply(key);
            if (module == null || !key.equals(module.key())) throw new ComputationException(
                ComputationException.Code.UNKNOWN_MODULE, key.toString(), "Exact module revision not found");
            closure.put(key, module); pending.addAll(ProgramCompiler.moduleReferences(module.graph()));
        }
        return closure;
    }
}
