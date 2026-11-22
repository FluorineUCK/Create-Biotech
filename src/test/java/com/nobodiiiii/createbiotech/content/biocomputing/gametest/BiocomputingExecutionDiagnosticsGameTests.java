package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.WiringStatusView;
import java.util.*;
import net.minecraft.gametest.framework.*;
import net.neoforged.neoforge.gametest.*;

/** Real cable and public Cluster mapping; execution is exclusively ordinary level ticks. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingExecutionDiagnosticsGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectedPayloadReachesMemberAndPublicControllerLogsWithoutConsumingOrLeakingIt(GameTestHelper helper) {
        var f = BiocomputingCableXBusRouteGameTests.fixture(helper);
        var member = f.cluster().b(); var caller = f.caller(); var controller = f.cluster().controller();
        var empty = new DataValue.RecordValue(Map.of());
        member.reset();
        member.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("receive", "x1", "private_name",
            new DataType.RecordType(Map.of("value", DataType.Scalar.TEXT))))), empty);
        var gateway = XBusWiringService.open(controller, "io", f.cluster().player(), () -> true);
        var peers = WiringStatusView.decode(gateway.read(1)).peers();
        int peer = java.util.stream.IntStream.range(0, peers.size()).filter(i -> peers.get(i).endpoint().equals("caller")
            && peers.get(i).pin().equals("x0")).findFirst().orElseThrow();
        gateway.add(2, 1, "diagnostic", "x1", peer); gateway.setEnabled(3, 2, "diagnostic", true);
        caller.reset(); caller.setPaused(false); member.setPaused(false);
        var hardwareA = caller.hardware(); var hardwareB = member.hardware();
        helper.runAfterDelay(6, () -> {
            assertEvent(helper, member.recentEvents(), "execution_type_mismatch", "x1", 1);
            assertEvent(helper, controller.recentEvents(), "execution_type_mismatch", "io", 1);
            var beforeCaller = caller.flowSnapshot(); var beforeMember = member.flowSnapshot();
            helper.assertTrue(beforeCaller.orElseThrow().receipts().isEmpty() && beforeMember.orElseThrow().receipts().isEmpty(), "Rejected value is not received");
            helper.runAfterDelay(5, () -> {
                helper.assertValueEqual(caller.flowSnapshot(), beforeCaller, "Diagnostic sampling cannot execute writer or spend work");
                helper.assertValueEqual(member.flowSnapshot(), beforeMember, "Type failure keeps original read and memory");
                assertEvent(helper, member.recentEvents(), "execution_type_mismatch", "x1", 1);
                assertEvent(helper, controller.recentEvents(), "execution_type_mismatch", "io", 1);
                helper.assertTrue(!controller.recentEvents().toString().contains("private_name"), "No member local names in public log");
                member.reset(); // Correcting an installed program requires an explicit reset of its blocked read.
                member.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", BiocomputingFlowFixture.PACKET),
                    new FlowProgram.Write("reply", "x1", "packet"))), empty);
                member.setPaused(false);
                helper.runAfterDelay(6, () -> {
                    helper.assertValueEqual(caller.flowSnapshot().orElseThrow().state().status(), FlowRuntime.Status.COMPLETE, "Corrected public program permits original caller to finish");
                    helper.assertValueEqual(caller.flowSnapshot().orElseThrow().state().locals().field("answer"), BiocomputingFlowFixture.inputs(12).field("packet"), "Correct typed record returned");
                    helper.assertValueEqual(caller.hardware(), hardwareA, "Caller hardware remains independent");
                    helper.assertValueEqual(member.hardware(), hardwareB, "Member hardware unchanged");
                    assertEvent(helper, controller.recentEvents(), "xbus_exchanged", "io", 2);
                    helper.assertTrue(controller.routingState().orElseThrow().mailbox().queued().isEmpty(), "Gateway does not buffer the exchange");
                    helper.succeed();
                });
            });
        });
    }
    private static void assertEvent(GameTestHelper helper, DataValue.RecordValue log, String code, String subject, long count) {
        long found = ((DataValue.ListValue)log.field("entries")).values().stream().map(DataValue.RecordValue.class::cast)
            .filter(e -> e.field("code").equals(new DataValue.TextValue(code)) && e.field("subject").equals(new DataValue.TextValue(subject))).count();
        helper.assertValueEqual(found, count, code + " / " + subject);
    }
}
