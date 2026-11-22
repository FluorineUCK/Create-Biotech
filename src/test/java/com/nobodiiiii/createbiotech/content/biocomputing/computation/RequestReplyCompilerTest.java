package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestReplyCompilerTest {
	private static final ResourceLimits ROOMY = new ResourceLimits(1_000, 100_000, 10);
	private static final DataType.RecordType PAYLOAD = new DataType.RecordType(Map.of("amount", INTEGER));

	@Test
	void allDispatchModesCompileAndSelectionPreservesOneEffectCharge() {
		var message = new ProgramDefinition.Send("endpoint", "payload");
		var request = ProgramDefinition.Send.request("endpoint", "payload", "pending", "reply");
		var reply = ProgramDefinition.Send.reply("payload");
		var definition = new ProgramDefinition(graph(), Map.of(), List.of(), List.of(message, request), Map.of(
			"request", new ProgramDefinition.Entry(graph(), List.of(), List.of(reply)),
			"reply", new ProgramDefinition.Entry(graph(), List.of(), List.of())));

		var installed = ProgramCompiler.compile(definition, Map.of(), ROOMY);
		assertEquals(List.of(message, request), installed.sends());
		assertEquals(List.of(reply), installed.select("request").sends());
		assertEquals(List.of(), installed.select("reply").sends());
		assertEquals(9, installed.usage().programUnits());
		assertEquals(installed.usage(), installed.select("request").usage());
		assertEquals(new ProgramDefinition.Send("endpoint", "payload",
			ProgramDefinition.Dispatch.MESSAGE, "", ""), message);
	}

	@Test
	void replyIsRestrictedToTheRequestEntryAndToOneEffect() {
		assertCode(INVALID_GRAPH, () -> compile(List.of(ProgramDefinition.Send.reply("payload")), Map.of()));
		assertCode(INVALID_GRAPH, () -> compile(List.of(), Map.of(
			"other", entry(List.of(ProgramDefinition.Send.reply("payload"))))));
		assertCode(INVALID_GRAPH, () -> compile(List.of(), Map.of(
			"request", entry(List.of(ProgramDefinition.Send.reply("payload"), ProgramDefinition.Send.reply("payload"))))));
	}

	@Test
	void requestsRequireExistingReplyEntriesAndUniqueSlotsPerEvent() {
		var first = ProgramDefinition.Send.request("endpoint", "payload", "pending", "reply");
		var duplicate = ProgramDefinition.Send.request("endpoint", "payload", "pending", "reply");
		assertCode(MISSING_REFERENCE, () -> compile(List.of(first), Map.of()));
		assertCode(WRITE_CONFLICT, () -> compile(List.of(first, duplicate), Map.of("reply", entry(List.of()))));
		assertDoesNotThrow(() -> compile(List.of(first), Map.of(
			"request", entry(List.of(ProgramDefinition.Send.request("endpoint", "payload", "pending", "reply"))),
			"reply", entry(List.of()))));
	}

	@Test
	void dispatchModesEnforceTheirExactFieldShapesImmediately() {
		assertCode(INVALID_GRAPH, () -> new ProgramDefinition.Send("endpoint", "payload",
			ProgramDefinition.Dispatch.MESSAGE, "slot", ""));
		assertCode(INVALID_GRAPH, () -> new ProgramDefinition.Send("endpoint", "payload",
			ProgramDefinition.Dispatch.REQUEST, "", "reply"));
		assertCode(INVALID_GRAPH, () -> new ProgramDefinition.Send("endpoint", "payload",
			ProgramDefinition.Dispatch.REQUEST, "slot", ""));
		assertCode(INVALID_GRAPH, () -> new ProgramDefinition.Send("endpoint", "payload",
			ProgramDefinition.Dispatch.REPLY, "", ""));
		assertCode(INVALID_GRAPH, () -> new ProgramDefinition.Send("", "payload",
			ProgramDefinition.Dispatch.MESSAGE, "", ""));
		assertCode(INVALID_GRAPH, () -> ProgramDefinition.Send.reply(" "));
	}

	@Test
	void requestAndMessageRequireTextEndpointsAndEveryPayloadIsARecord() {
		var badEndpoint = new ComputationGraph(Map.of("endpoint", INTEGER, "payload", PAYLOAD), Map.of(
			"endpointNode", new ComputationGraph.Input("endpoint"),
			"payloadNode", new ComputationGraph.Input("payload")),
			Map.of("endpoint", "endpointNode", "payload", "payloadNode"));
		var badPayload = new ComputationGraph(Map.of("endpoint", TEXT, "payload", INTEGER), Map.of(
			"endpointNode", new ComputationGraph.Input("endpoint"),
			"payloadNode", new ComputationGraph.Input("payload")),
			Map.of("endpoint", "endpointNode", "payload", "payloadNode"));
		assertCode(TYPE_MISMATCH, () -> compile(badEndpoint,
			List.of(ProgramDefinition.Send.request("endpoint", "payload", "slot", "reply")),
			Map.of("reply", entry(List.of()))));
		assertCode(TYPE_MISMATCH, () -> compile(badPayload,
			List.of(new ProgramDefinition.Send("endpoint", "payload")), Map.of()));
		assertCode(TYPE_MISMATCH, () -> compile(badPayload, List.of(), Map.of(
			"request", entry(badPayload, List.of(ProgramDefinition.Send.reply("payload"))))));
	}

	private static ProgramCompiler.InstalledProgram compile(List<ProgramDefinition.Send> sends,
		Map<String, ProgramDefinition.Entry> entries) {
		return compile(graph(), sends, entries);
	}

	private static ProgramCompiler.InstalledProgram compile(ComputationGraph graph, List<ProgramDefinition.Send> sends,
		Map<String, ProgramDefinition.Entry> entries) {
		return ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of(), sends, entries), Map.of(), ROOMY);
	}

	private static ProgramDefinition.Entry entry(List<ProgramDefinition.Send> sends) {
		return entry(graph(), sends);
	}

	private static ProgramDefinition.Entry entry(ComputationGraph graph, List<ProgramDefinition.Send> sends) {
		return new ProgramDefinition.Entry(graph, List.of(), sends);
	}

	private static ComputationGraph graph() {
		return new ComputationGraph(Map.of("endpoint", TEXT, "payload", PAYLOAD), Map.of(
			"endpointNode", new ComputationGraph.Input("endpoint"),
			"payloadNode", new ComputationGraph.Input("payload")),
			Map.of("endpoint", "endpointNode", "payload", "payloadNode"));
	}

	private static void assertCode(ComputationException.Code expected, org.junit.jupiter.api.function.Executable action) {
		assertEquals(expected, assertThrows(ComputationException.class, action).code());
	}
}
