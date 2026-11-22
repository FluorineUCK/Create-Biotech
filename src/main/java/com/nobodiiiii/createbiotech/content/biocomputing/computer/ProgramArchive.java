package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Portable program and pinned module bodies, not a reference to a mutable library or recipe ROM. */
public record ProgramArchive(ProgramDefinition program, Map<ModuleDefinition.Key, ModuleDefinition> modules) {
	public ProgramArchive {
		Objects.requireNonNull(program);
		modules.forEach((key, module) -> { Objects.requireNonNull(key); Objects.requireNonNull(module); });
		modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
	}
	public ProgramCompiler.InstalledProgram compile(ResourceLimits hardware) {
		return ProgramCompiler.compile(program, modules, hardware);
	}
	public static ProgramArchive from(ProgramCompiler.InstalledProgram installed) {
		var modules = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
		installed.modules().forEach((key, graph) -> modules.put(key, new ModuleDefinition(key, graph.graph())));
		return new ProgramArchive(installed.definition(), modules);
	}
}
