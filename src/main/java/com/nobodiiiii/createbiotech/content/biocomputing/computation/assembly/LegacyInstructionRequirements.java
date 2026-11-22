package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.Optional;
import java.util.stream.Stream;

/** Temporary admission guard, not a compiler or executor. Old documents cannot bypass hardware
 * requirements by choosing the compatibility backend. Remove with that backend. */
public final class LegacyInstructionRequirements {
    private LegacyInstructionRequirements() {}
    public static Optional<String> firstDeclaration(ProgramArchive archive, java.util.function.Predicate<DataType> requires) {
        if(archive.program().cells().values().stream().anyMatch(c->requires.test(c.type()))) return Optional.of("cell declaration");
        return Stream.concat(Stream.concat(Stream.of(archive.program().graph()),archive.program().entries().values().stream().map(ProgramDefinition.Entry::graph)),archive.modules().values().stream().map(ModuleDefinition::graph))
            .filter(g->g.inputs().values().stream().anyMatch(requires)).map(g->"graph input declaration").findFirst();
    }

    public static Optional<String> firstSemanticDeclaration(ProgramArchive archive) {
        if (archive.program().cells().values().stream().anyMatch(c -> AssemblyTypeRequirements.semantic(c.type()) || AssemblyTypeRequirements.semantic(c.initial()))) return Optional.of("cell");
        return Stream.concat(Stream.concat(Stream.of(archive.program().graph()), archive.program().entries().values().stream().map(ProgramDefinition.Entry::graph)), archive.modules().values().stream().map(ModuleDefinition::graph))
            .filter(g -> g.inputs().values().stream().anyMatch(AssemblyTypeRequirements::semantic) || g.nodes().values().stream().anyMatch(n -> n instanceof ComputationGraph.Constant c && AssemblyTypeRequirements.semantic(c.value())))
            .map(g -> "graph declaration or constant").findFirst();
    }

    public static Optional<String> firstStructuredOperation(ProgramArchive archive) {
        return Stream.concat(Stream.concat(Stream.of(archive.program().graph()),
                archive.program().entries().values().stream().map(ProgramDefinition.Entry::graph)),
                archive.modules().values().stream().map(ModuleDefinition::graph))
            .flatMap(graph -> graph.nodes().entrySet().stream())
            .filter(entry -> structured(entry.getValue())).map(java.util.Map.Entry::getKey).findFirst();
    }

    private static boolean structured(ComputationGraph.Node node) {
        return switch (node) {
            case ComputationGraph.Constant ignored -> false;
            case ComputationGraph.Input ignored -> false;
            case ComputationGraph.ReadMemory ignored -> false;
            case ComputationGraph.Binary ignored -> false;
            case ComputationGraph.Scalar scalar -> scalar.operation() == ScalarOperation.LIST_LENGTH;
            case ComputationGraph.ProcedureValue ignored -> false; // Standalone graph compilation rejects it.
            case ComputationGraph.Call ignored -> false; // Fixed module bodies are also inspected.
            case ComputationGraph.Branch ignored -> false;
            case ComputationGraph.Field ignored -> true;
            case ComputationGraph.DynamicField ignored -> true;
            case ComputationGraph.HasField ignored -> true;
            case ComputationGraph.Keys ignored -> true;
            case ComputationGraph.Index ignored -> true;
            case ComputationGraph.DynamicIndex ignored -> true;
            case ComputationGraph.ListEdit ignored -> true;
            case ComputationGraph.ListPosition ignored -> true;
            case ComputationGraph.ListSearch ignored -> true;
            case ComputationGraph.Distinct ignored -> true;
            case ComputationGraph.RandomRange ignored -> false; // Requires the typed ISA runtime.
            case ComputationGraph.Timer ignored -> false;
            case ComputationGraph.Answer ignored -> false;
            case ComputationGraph.KeyDown ignored -> false;
            case ComputationGraph.ValueEquals ignored -> true;
            case ComputationGraph.MakeRecord ignored -> true;
            case ComputationGraph.MakeList ignored -> true;
            case ComputationGraph.ConcatLists ignored -> true;
            case ComputationGraph.Iterate ignored -> true;
            case ComputationGraph.Fold ignored -> true;
            case ComputationGraph.Sort ignored -> true;
            case ComputationGraph.Text ignored -> true;
        };
    }
}
