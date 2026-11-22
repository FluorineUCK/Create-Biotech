package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real endpoint handoff/reload proof; not a player wiring or sequential-block editor acceptance test. */
@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingReceptionGameTests {
    @GameTest(template = "empty", timeoutTicks = 140)
    public static void registeredComputerPersistsReadyRequestBeforeConfirmingAndNeverReceivesTwice(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        var block = BiocomputingWorldGameTests.computer(helper, pos);
        var host = BiocomputingWorldGameTests.host(block);
        var original = BiocomputingWorldGameTests.receiver().program();
        host.install(new ProgramArchive(new ProgramDefinition(original.graph(), original.cells(), original.writes(), original.sends(),
            Map.of("request", original.entries().get("message"))), Map.of()), ComputerRuntime.MemoryPolicy.RESET);
        host.configureEndpoint(new EndpointConfiguration("receiver", true));
        host.setPaused(true);
        var id = host.snapshot().orElseThrow().id();
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        var context = new RequestContext(new RequestCorrelation(source, event, 0), "caller");
        var envelope = new MessageEnvelope(source, 0, event, 0, BiocomputingWorldGameTests.record("sample", 12), new MessageProtocol.Request(context));
        var endpoint = Objects.requireNonNull(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST));
        helper.assertValueEqual(endpoint.receiveReady("receiver", id, envelope), ReceptionStatus.PAUSED, "A paused endpoint is not a ready receiver");
        helper.assertTrue(host.snapshot().orElseThrow().mailbox().queued().isEmpty(), "No hidden buffered fallback");
        host.setPaused(false);
        helper.assertValueEqual(endpoint.receiveReady("wrong-port", id, envelope), ReceptionStatus.CLOSED, "Named port must match");
        helper.assertValueEqual(endpoint.receiveReady("receiver", UUID.randomUUID(), envelope), ReceptionStatus.TARGET_CHANGED, "Name cannot replace the fixed device identity");
        helper.assertValueEqual(endpoint.receiveReady("receiver", id, envelope), ReceptionStatus.RECEIVED, "Ready request transfers into owned continuation");
        helper.assertValueEqual(host.snapshot().orElseThrow().machine().workUnits(), 0L, "Handoff never ticks the graph");
        helper.assertValueEqual(BiocomputingWorldGameTests.memory(host, "deliveries"), new DataValue.IntegerValue(0), "Received is not business-completed");
        var state = block.getBlockState(); var saved = block.saveWithFullMetadata(level.registryAccess());
        level.removeBlockEntity(pos);
        var restoredBlock = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(pos, state, saved, level.registryAccess()));
        level.setBlockEntity(restoredBlock);
        var restored = BiocomputingWorldGameTests.host(restoredBlock);
        var restoredEndpoint = Objects.requireNonNull(level.getCapability(CommunicationEndpoint.CAPABILITY, pos, Direction.WEST));
        helper.assertValueEqual(restoredEndpoint.receiveReady("receiver", id, envelope), ReceptionStatus.RECEIVED, "Lost sender confirmation recovers the same handoff");
        helper.assertValueEqual(restored.snapshot().orElseThrow().rpc().request(), Optional.of(context), "Original return context persists");
        helper.runAfterDelay(24, () -> {
            helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "deliveries"), new DataValue.IntegerValue(1), "Receiver evaluates once on its own ticks");
            helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "received"), envelope.payload(), "Named data reaches receiver by value");
            helper.assertValueEqual(restoredEndpoint.receiveReady("receiver", id, envelope), ReceptionStatus.RECEIVED, "Completed retry does not restart");
            helper.runAfterDelay(12, () -> {
                helper.assertValueEqual(BiocomputingWorldGameTests.memory(restored, "deliveries"), new DataValue.IntegerValue(1), "No repeated event after retry");
                helper.succeed();
            });
        });
    }
}
