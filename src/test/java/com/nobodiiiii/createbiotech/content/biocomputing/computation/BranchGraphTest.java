package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompilerTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachineTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;

class BranchGraphTest {
	@Test void onlySelectedModuleRunsAndEveryWorkOneCheckpointResumesIdentically() {
		var installation = BranchProgramFixtures.archive().compile(new ResourceLimits(1_000, 100_000, 1));
		assertResult(installation, 24, 0, 0);
		assertResult(installation, 24, 3, 8);
	}

	@Test void installationValidatesBothModulesAndTheirIdenticalInterfaces() {
		var archive = BranchProgramFixtures.archive();
		var missing = new LinkedHashMap<>(archive.modules()); missing.remove(BranchProgramFixtures.ZERO);
		assertCode(UNKNOWN_MODULE, () -> ProgramCompiler.compile(archive.program(), missing, ROOMY));

		var impureGraph = new ComputationGraph(Map.of("x", INTEGER, "d", INTEGER),
			Map.of("read", new ComputationGraph.ReadMemory("cell")), Map.of("value", "read"));
		var impure = new LinkedHashMap<>(archive.modules());
		impure.put(BranchProgramFixtures.ZERO, new ModuleDefinition(BranchProgramFixtures.ZERO, impureGraph));
		assertCode(INVALID_GRAPH, () -> ProgramCompiler.compile(archive.program(), impure, ROOMY));

		var wrongInputs = new ComputationGraph(Map.of("x", INTEGER),
			Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(0))), Map.of("value", "value"));
		var badInputs = new LinkedHashMap<>(archive.modules());
		badInputs.put(BranchProgramFixtures.ZERO, new ModuleDefinition(BranchProgramFixtures.ZERO, wrongInputs));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(archive.program(), badInputs, ROOMY));

		var wrongOutput = new ComputationGraph(Map.of("x", INTEGER, "d", INTEGER),
			Map.of("value", new ComputationGraph.Constant(new DataValue.TextValue("zero"))), Map.of("other", "value"));
		var badOutputs = new LinkedHashMap<>(archive.modules());
		badOutputs.put(BranchProgramFixtures.ZERO, new ModuleDefinition(BranchProgramFixtures.ZERO, wrongOutput));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(archive.program(), badOutputs, ROOMY));
	}

	@Test void branchArgumentsAreImmutableAndConditionPrecedesNamedDependencies() {
		var arguments = new LinkedHashMap<String,String>();arguments.put("x","x");arguments.put("d","d");
		var branch = new ComputationGraph.Branch("condition", BranchProgramFixtures.DIVIDE, BranchProgramFixtures.ZERO, arguments);
		arguments.put("x", "changed");
		assertEquals(Map.of("x", "x", "d", "d"), branch.arguments());
		assertEquals(List.of("condition", "x", "d"), branch.dependencies());
		assertThrows(UnsupportedOperationException.class, () -> branch.arguments().put("z", "z"));
	}

	@Test void staticallyNonBooleanConditionIsRejected() {
		var archive = BranchProgramFixtures.archive();
		var nodes = new LinkedHashMap<>(archive.program().graph().nodes());
		nodes.put("choice", new ComputationGraph.Branch("x", BranchProgramFixtures.DIVIDE, BranchProgramFixtures.ZERO,
			Map.of("x", "x", "d", "d")));
		var invalid = new ProgramDefinition(new ComputationGraph(archive.program().graph().inputs(), nodes,
			archive.program().graph().outputs()), Map.of(), List.of());
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(invalid, archive.modules(), ROOMY));
	}

	@Test void dynamicNonBooleanConditionFailsAtBranchWithoutPublishingEffects() {
		var base = BranchProgramFixtures.archive();
		var inputs = new LinkedHashMap<>(base.program().graph().inputs());
		inputs.put("condition", ANY);
		var nodes = new LinkedHashMap<>(base.program().graph().nodes());
		nodes.put("condition", new ComputationGraph.Input("condition"));
		nodes.put("choice", new ComputationGraph.Branch("condition", BranchProgramFixtures.DIVIDE,
			BranchProgramFixtures.ZERO, Map.of("x", "x", "d", "d")));
		nodes.put("value", new ComputationGraph.Field("choice", "value"));
		nodes.put("endpoint", new ComputationGraph.Constant(new DataValue.TextValue("factory:test")));
		nodes.put("payload", new ComputationGraph.MakeRecord(Map.of("value", "value")));
		var graph = new ComputationGraph(inputs, nodes,
			Map.of("value", "value", "endpoint", "endpoint", "payload", "payload"));
		var program = new ProgramDefinition(graph,
			Map.of("count", new ProgramDefinition.Cell(INTEGER, integer(7))),
			List.of(new ProgramDefinition.Write("count", "value")),
			List.of(new ProgramDefinition.Send("endpoint", "payload")), Map.of());
		var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY, new ArchiveLimits(100_000, 1_000_000, 10_000_000)));
		var host = new ComputerBlockHost(() -> {});
		host.install(new ProgramArchive(program, base.modules()), ComputerRuntime.MemoryPolicy.RESET, limits, true);
		var before = host.snapshot().orElseThrow().machine().memory();
		var values = new LinkedHashMap<>(BranchProgramFixtures.inputs(24, 3).fields());
		values.put("condition", integer(1));
		host.begin(new DataValue.RecordValue(values), limits, true);
		assertEquals(ComputationMachine.Status.RUNNING, host.snapshot().orElseThrow().machine().status());
		for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100);
			host.step(limits, true);
		}
		var failed = host.snapshot().orElseThrow().machine();
		assertEquals(ComputationMachine.Status.FAILED, failed.status());
		assertEquals(new ComputationMachine.Diagnostic(TYPE_MISMATCH, "event/choice", "Branch condition must be boolean"),
			failed.diagnostic().orElseThrow());
		assertEquals(before, failed.memory());
		assertTrue(failed.output().isEmpty());
		assertTrue(host.outbound(limits, true).isEmpty());
	}

	@Test void unselectedLiteralAloneCrossesInstallationMemoryBoundary() {
		var selected = new ModuleDefinition.Key("selected", 1);
		var unselected = new ModuleDefinition.Key("unselected", 1);
		var root = new ComputationGraph(Map.of(), Map.of(
			"condition", new ComputationGraph.Constant(new DataValue.BooleanValue(true)),
			"choice", new ComputationGraph.Branch("condition", selected, unselected, Map.of())),
			Map.of("result", "choice"));
		var emptyText = new ComputationGraph(Map.of(),
			Map.of("value", new ComputationGraph.Constant(new DataValue.TextValue(""))), Map.of("value", "value"));
		var oneCharacter = new ComputationGraph(Map.of(),
			Map.of("value", new ComputationGraph.Constant(new DataValue.TextValue("x"))), Map.of("value", "value"));
		var modules = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
		modules.put(selected, new ModuleDefinition(selected, emptyText));
		modules.put(unselected, new ModuleDefinition(unselected, emptyText));
		// Three one-unit literals plus two one-unit empty memory images fit exactly.
		var boundary = new ResourceLimits(1_000, 5, 1);
		var fitting = ProgramCompiler.compile(program(root), modules, boundary);
		assertEquals(3, fitting.usage().constantUnits());
		assertEquals(1, fitting.usage().initialMemoryUnits());
		modules.put(unselected, new ModuleDefinition(unselected, oneCharacter));
		assertCode(MEMORY_LIMIT, () -> ProgramCompiler.compile(program(root), modules, boundary));
	}

	@Test void unselectedRecursiveModuleIsRejectedAndBothSidesConsumeProgramCapacity() {
		var archive=BranchProgramFixtures.archive();
		assertCode(PROGRAM_LIMIT,()->ProgramCompiler.compile(archive.program(),archive.modules(),new ResourceLimits(9,100_000,1)));
		var recursiveGraph=new ComputationGraph(Map.of("x",INTEGER,"d",INTEGER),Map.of(
			"x",new ComputationGraph.Input("x"),"d",new ComputationGraph.Input("d"),
			"self",new ComputationGraph.Call(BranchProgramFixtures.ZERO,Map.of("x","x","d","d"))),Map.of("value","self"));
		var recursive=new LinkedHashMap<>(archive.modules());
		recursive.put(BranchProgramFixtures.ZERO,new ModuleDefinition(BranchProgramFixtures.ZERO,recursiveGraph));
		assertCode(RECURSIVE_MODULE,()->ProgramCompiler.compile(archive.program(),recursive,ROOMY));
	}

	@Test void outputFieldsUseSelectJoinRuleForNullAndDifferentScalarTypes() {
		var trueKey=new ModuleDefinition.Key("integer",1);var falseKey=new ModuleDefinition.Key("null",1);
		var inputs=Map.<String,DataType>of();
		var integerGraph=new ComputationGraph(inputs,Map.of("v",new ComputationGraph.Constant(new DataValue.IntegerValue(1))),Map.of("value","v"));
		var nullGraph=new ComputationGraph(inputs,Map.of("v",new ComputationGraph.Constant(DataValue.NullValue.INSTANCE)),Map.of("value","v"));
		var root=new ComputationGraph(Map.of(),Map.of("c",new ComputationGraph.Constant(new DataValue.BooleanValue(true)),
			"b",new ComputationGraph.Branch("c",trueKey,falseKey,Map.of())),Map.of("result","b"));
		var installed=ProgramCompiler.compile(program(root),Map.of(trueKey,new ModuleDefinition(trueKey,integerGraph),falseKey,new ModuleDefinition(falseKey,nullGraph)),ROOMY);
		assertEquals(new DataType.RecordType(Map.of("value",new DataType.OptionalType(INTEGER))),installed.root().outputType().fields().get("result"));
	}

	@Test void restoreRejectsAParentConditionFlipOrAChildModuleSwap() {
		var installation = BranchProgramFixtures.archive().compile(new ResourceLimits(1_000, 100_000, 1));
		var machine = new ComputationMachine(installation); machine.begin(BranchProgramFixtures.inputs(24, 3));
		MachineCheckpoint saved = null;
		for (int i = 0; i < 100; i++) {
			var candidate = machine.checkpoint();
			if (candidate.frames().size() == 2) { saved = candidate; break; }
			machine.step();
		}
		assertNotNull(saved);
		var frames = new ArrayList<>(saved.frames());
		var parent = (MachineCheckpoint.GraphState) frames.get(0);
		var child = (MachineCheckpoint.GraphState) frames.get(1);
		var parentValues = new LinkedHashMap<>(parent.values().fields());
		parentValues.put("nonzero", new DataValue.BooleanValue(false));
		frames.set(0, new MachineCheckpoint.GraphState(parent.module(), parent.cursor(), parent.inputs(), new DataValue.RecordValue(parentValues)));
		assertInvalid(installation, withFrames(saved, frames));
		frames.set(0, parent);
		frames.set(1, new MachineCheckpoint.GraphState(Optional.of(BranchProgramFixtures.ZERO), child.cursor(), child.inputs(), child.values()));
		assertInvalid(installation, withFrames(saved, frames));
	}

	@Test void selectedBranchMayNestCallMapAndFilterWhileUnselectedBranchStaysLazy() {
		var predicateKey=new ModuleDefinition.Key("below",1);
		var workKey=new ModuleDefinition.Key("work",1);
		var emptyKey=new ModuleDefinition.Key("empty",1);
		var predicate=new ComputationGraph(Map.of("item",INTEGER,"limit",INTEGER),Map.of(
			"item",new ComputationGraph.Input("item"),"limit",new ComputationGraph.Input("limit"),
			"keep",new ComputationGraph.Scalar(ScalarOperation.LESS_INTEGER,Map.of("left","item","right","limit"))),Map.of("keep","keep"));
		var branchInputs=Map.<String,DataType>of("items",new DataType.ListType(INTEGER),"limit",INTEGER);
		var work=new ComputationGraph(branchInputs,Map.of("items",new ComputationGraph.Input("items"),"limit",new ComputationGraph.Input("limit"),
			"filtered",new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER,"items",predicateKey,"item",Map.of("limit","limit"),"keep"),
			"mapped",new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP,"filtered",DOUBLE,"value",Map.of(),"result")),Map.of("value","mapped"));
		var empty=new ComputationGraph(branchInputs,Map.of("value",new ComputationGraph.Constant(new DataValue.ListValue(List.of()))),Map.of("value","value"));
		var root=new ComputationGraph(Map.of("condition",BOOLEAN,"items",new DataType.ListType(INTEGER),"limit",INTEGER),Map.of(
			"condition",new ComputationGraph.Input("condition"),"items",new ComputationGraph.Input("items"),"limit",new ComputationGraph.Input("limit"),
			"branch",new ComputationGraph.Branch("condition",workKey,emptyKey,Map.of("items","items","limit","limit"))),Map.of("result","branch"));
		var library=Map.of(predicateKey,new ModuleDefinition(predicateKey,predicate),workKey,new ModuleDefinition(workKey,work),
			emptyKey,new ModuleDefinition(emptyKey,empty),DOUBLE,doubleModule());
		var installation=ProgramCompiler.compile(program(root),library,new ResourceLimits(1_000,100_000,1));
		var input=new DataValue.RecordValue(Map.of("condition",new DataValue.BooleanValue(true),"items",numbers(1,3,2),"limit",integer(3)));
		var result=run(installation,input).report().output().orElseThrow();
		assertEquals(numbers(2,4),((DataValue.RecordValue)result.field("result")).field("value"));
		var falseInput=new DataValue.RecordValue(Map.of("condition",new DataValue.BooleanValue(false),"items",numbers(1,3,2),"limit",integer(3)));
		assertEquals(numbers(),((DataValue.RecordValue)run(installation,falseInput).report().output().orElseThrow().field("result")).field("value"));
	}

	@Test void realHostSaveLoadAfterEveryStepPreservesBranchContinuation() {
		var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1_000,100_000,1),new ArchiveLimits(100_000,1_000_000,10_000_000)));
		var host=new ComputerBlockHost(()->{});host.install(BranchProgramFixtures.archive(),ComputerRuntime.MemoryPolicy.RESET,limits,true);
		host.begin(BranchProgramFixtures.inputs(24,3),limits,true);
		for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){
			assertTrue(i<100);var restored=new ComputerBlockHost(()->{});restored.read(host.write());restored.step(limits,true);host.step(limits,true);
			assertEquals(host.snapshot(),restored.snapshot());host=restored;
		}
		assertEquals(new DataValue.IntegerValue(8),((DataValue.RecordValue)host.snapshot().orElseThrow().machine().output().orElseThrow().field("result")).field("value"));
	}

	@Test void selectedFailurePublishesNeitherRamNorOutboundMessage() {
		var base=BranchProgramFixtures.archive();var nodes=new LinkedHashMap<>(base.program().graph().nodes());
		nodes.put("nonzero",new ComputationGraph.Constant(new DataValue.BooleanValue(true)));
		nodes.put("value",new ComputationGraph.Field("choice","value"));
		nodes.put("endpoint",new ComputationGraph.Constant(new DataValue.TextValue("factory:test")));
		nodes.put("payload",new ComputationGraph.MakeRecord(Map.of("value","value")));
		var graph=new ComputationGraph(base.program().graph().inputs(),nodes,Map.of("value","value","endpoint","endpoint","payload","payload"));
		var program=new ProgramDefinition(graph,Map.of("count",new ProgramDefinition.Cell(INTEGER,new DataValue.IntegerValue(7))),
			List.of(new ProgramDefinition.Write("count","value")),List.of(new ProgramDefinition.Send("endpoint","payload")),Map.of());
		var archive=new ProgramArchive(program,base.modules());
		var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(ROOMY,new ArchiveLimits(100_000,1_000_000,10_000_000)));var host=new ComputerBlockHost(()->{});
		host.install(archive,ComputerRuntime.MemoryPolicy.RESET,limits,true);host.begin(BranchProgramFixtures.inputs(24,0),limits,true);
		for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<100);host.step(limits,true);}
		assertEquals(ComputationMachine.Status.FAILED,host.snapshot().orElseThrow().machine().status());
		assertEquals(new DataValue.IntegerValue(7),host.snapshot().orElseThrow().machine().memory().field("count"));
		assertTrue(host.outbound(limits,true).isEmpty());
	}

	private static void assertResult(ProgramCompiler.InstalledProgram installation, long x, long d, long expected) {
		var baseline = run(installation, BranchProgramFixtures.inputs(x, d));
		assertEquals(new DataValue.IntegerValue(expected), ((DataValue.RecordValue) baseline.report().output().orElseThrow().field("result")).field("value"));
		var machine = new ComputationMachine(installation); machine.begin(BranchProgramFixtures.inputs(x, d));
		for (int i = 0; i < 100 && machine.report().status() == ComputationMachine.Status.RUNNING; i++) {
			machine = ComputationMachine.restore(installation, machine.checkpoint()); machine.tick();
		}
		assertEquals(baseline.report(), machine.report());
	}
	private static ComputationMachine run(ProgramCompiler.InstalledProgram installation, DataValue.RecordValue input) {
		var machine = new ComputationMachine(installation); machine.begin(input);
		for (int i = 0; i < 100 && machine.report().status() == ComputationMachine.Status.RUNNING; i++) machine.tick();
		return machine;
	}
	private static MachineCheckpoint withFrames(MachineCheckpoint source, List<MachineCheckpoint.FrameState> frames) {
		return new MachineCheckpoint(source.memory(), source.status(), source.paused(), source.workUnits(), source.arenaUnits(), source.output(), source.diagnostic(), frames);
	}
	private static void assertInvalid(ProgramCompiler.InstalledProgram installation, MachineCheckpoint checkpoint) {
		assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.restore(installation, checkpoint));
	}
}
