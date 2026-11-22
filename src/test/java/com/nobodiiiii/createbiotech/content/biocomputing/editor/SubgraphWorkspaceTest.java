package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubgraphWorkspaceTest {
    static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);

    @Test void portablePackageCarriesLayoutWithoutRequiringAnApplication() throws Exception {
        var source = new ProgramWorkspace();
        source.root().add(null, 0, 0); // The application is deliberately unfinished.
        var key = source.createModule("portable"); var graph = source.graph(key);
        String node = graph.add(new ComputationGraph.Constant(new DataValue.IntegerValue(42)), 12.5, -7);
        graph.output("answer", node);
        source.createModule("unrelated_empty");
        var packaged = source.exportSubgraph(key, LIMITS);
        assertEquals(key, packaged.root());
        assertEquals(Set.of(key), packaged.modules().keySet());
        assertEquals(Map.of(node, new ProgramDocument.Position(12.5, -7)), packaged.positions().get(ProgramDocument.graphId(key)));
    }

    @Test void importOnlyAddsModulesAndLeavesAnUnfinishedRootUntouched() throws Exception {
        var source = new ProgramWorkspace(); var key = source.createModule("portable");
        String node = source.graph(key).add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 1, 2);
        source.graph(key).output("answer", node);
        var packaged = source.exportSubgraph(key, LIMITS);
        var target = new ProgramWorkspace(); var root = target.root();
        String unfinished = root.add(null, 3, 4); var before = root.nodes();
        target.importSubgraph(packaged, LIMITS);
        assertSame(root, target.root()); assertEquals(before, root.nodes());
        assertNull(root.node(unfinished).definition()); assertTrue(root.outputs().isEmpty());
        assertEquals(List.of(key), target.keys());
        assertEquals(source.graph(key).outputs(), target.graph(key).outputs());
    }

    @Test void conflictAndBudgetFailureLeaveEveryExistingDraftUntouched() {
        var packageOne = SubgraphDocumentTest.fixture();
        var target = new ProgramWorkspace(); target.importSubgraph(packageOne, LIMITS);
        String dummy = target.root().add(new ComputationGraph.Constant(new DataValue.IntegerValue(3)), 0, 0);
        target.root().output("out", dummy);
        var rootDraft = target.root(); var original = target.snapshot();
        var graphs = new LinkedHashMap<>(packageOne.modules());
        var collision = packageOne.root(); var old = graphs.get(collision).graph();
        var nodes = new LinkedHashMap<>(old.nodes()); nodes.put("flag", new ComputationGraph.Constant(new DataValue.BooleanValue(false)));
        graphs.put(collision, new ModuleDefinition(collision, new ComputationGraph(old.inputs(), nodes, old.outputs())));
        var conflicting = new SubgraphDocument(collision, graphs, packageOne.positions());
        assertThrows(IllegalArgumentException.class, () -> target.importSubgraph(conflicting, LIMITS));
        assertEquals(original, target.snapshot()); assertSame(rootDraft, target.root());
        assertThrows(ArchiveException.class, () -> target.importSubgraph(packageOne, new ArchiveLimits(1, 1, 1)));
        assertEquals(original, target.snapshot());
    }

    @Test void duplicateImportPreservesEditedLayoutAndDifferentRevisionCanCoexist() {
        var document = SubgraphDocumentTest.fixture(); var target = new ProgramWorkspace();
        target.importSubgraph(document, LIMITS); var kept = target.graph(document.root());
        kept.moveBy("flag", 22, -33); var position = kept.node("flag");
        target.importSubgraph(document, LIMITS);
        assertSame(kept, target.graph(document.root())); assertEquals(position, kept.node("flag"));
        var revision = new ModuleDefinition.Key(document.root().id(), document.root().revision() + 1);
        var graph = SubgraphDocumentTest.numberGraph(21);
        target.importSubgraph(new SubgraphDocument(revision, Map.of(revision, new ModuleDefinition(revision, graph)), Map.of()), LIMITS);
        assertTrue(target.keys().contains(document.root())); assertTrue(target.keys().contains(revision));
    }

    @Test void aLateDependencyConflictDoesNotPublishTheEarlierNewRoot() {
        var original = SubgraphDocumentTest.fixture(); var target = new ProgramWorkspace(); target.importSubgraph(original, LIMITS);
        var key = new ModuleDefinition.Key("new_parent", 1);
        var graph = new ComputationGraph(Map.of(), Map.of("call", new ComputationGraph.Call(original.root(), Map.of())), Map.of("result", "call"));
        var definitions = new LinkedHashMap<>(original.modules()); definitions.put(key, new ModuleDefinition(key, graph));
        var old = definitions.get(original.root()).graph(); var changed = new LinkedHashMap<>(old.nodes());
        changed.put("flag", new ComputationGraph.Constant(new DataValue.BooleanValue(false)));
        definitions.put(original.root(), new ModuleDefinition(original.root(), new ComputationGraph(old.inputs(), changed, old.outputs())));
        var incoming = new SubgraphDocument(key, definitions, Map.of()); var before = target.keys(); var existing = target.graph(original.root());
        assertEquals(key, incoming.modules().keySet().iterator().next(), "new root is staged before the later conflict");
        assertThrows(IllegalArgumentException.class, () -> target.importSubgraph(incoming, LIMITS));
        assertEquals(before, target.keys()); assertSame(existing, target.graph(original.root()));
    }

    @Test void sourcePackageAndInstalledComputationRemainIndependentAfterImportAndEditing() {
        var source = new ProgramWorkspace(); var key = source.createModule("leaf");
        String value = source.graph(key).add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 1, 2);
        source.graph(key).output("answer", value);
        var packaged = source.exportSubgraph(key, LIMITS);
        var target = new ProgramWorkspace();
        target.importSubgraph(SubgraphDocumentWire.decode(SubgraphDocumentWire.encode(packaged, LIMITS), LIMITS), LIMITS);
        String call = target.root().add(new ComputationGraph.Call(key, Map.of()), 10, 20); target.root().output("out", call);
        var installed = target.snapshot().archive().compile(new ResourceLimits(1000, 100000, 1000));
        target.graph(key).configure(value, new ComputationGraph.Constant(new DataValue.IntegerValue(9)));
        assertEquals(packaged, source.exportSubgraph(key, LIMITS));
        var old = new ComputationMachine(installed); old.begin(new DataValue.RecordValue(Map.of())); old.tick();
        assertEquals(new DataValue.IntegerValue(7), ((DataValue.RecordValue)old.report().output().orElseThrow().field("out")).field("answer"));
        var next = new ComputationMachine(target.snapshot().archive().compile(new ResourceLimits(1000, 100000, 1000)));
        next.begin(new DataValue.RecordValue(Map.of())); next.tick();
        assertEquals(new DataValue.IntegerValue(9), ((DataValue.RecordValue)next.report().output().orElseThrow().field("out")).field("answer"));
    }

    @Test void exportIncludesDisconnectedCompleteReferencesButNotUnrelatedBrokenDrafts() {
        var document = SubgraphDocumentTest.fixture(); var source = new ProgramWorkspace();
        source.importSubgraph(document, LIMITS);
        source.root().add(null, 0, 0);
        var unrelated = source.createModule("broken"); source.graph(unrelated).add(null, 0, 0);
        var copied = source.exportSubgraph(document.root(), LIMITS);
        assertEquals(document.modules(), copied.modules());
        assertTrue(copied.modules().containsKey(new ModuleDefinition.Key("leaf", 99)));
        assertTrue(copied.modules().get(document.root()).graph().nodes().containsKey("unconnected_call"));
        assertThrows(ArchiveException.class, () -> source.exportSubgraph(document.root(), new ArchiveLimits(1, 1, 1)));
    }
}
