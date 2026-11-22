package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryServiceDocuments;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Repeated public service transactions, not a fresh owner for every request. */
class NativeLibraryServiceLifetimeTest {
    static final ModuleDefinition.Key CALL = new ModuleDefinition.Key("query", 1);

    @Test void serviceImagesFitEachResidentsActualCodeCapacity() {
        var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
        for (String role : List.of("b", "c", "d")) {
            var doc = NativeLibraryServiceDocuments.build(role);
            var limits = NativeLibraryOwnersTest.limits("cleric", 5);
            var program = doc.flow().orElseThrow().program(doc.archive());
            // Measurement only; installation below must use the actual resident's unchanged capacity.
            var probe = new ResourceLimits(10000, limits.hardware().memoryUnits(), limits.hardware().workPerSlice());
            long words = program.compile(probe).assembly().orElseThrow().codeWords();
            System.out.println("Service " + role + " code=" + words + " capacity=" + limits.hardware().programUnits());
            checks.add(() -> assertTrue(words <= limits.hardware().programUnits(), role + " exceeds resident code capacity: " + words));
        }
        assertAll(checks);
    }

    @Test void completedMatcherTransactionsReleaseLocalReceiptsWithoutResettingTheComputer() {
        var lc = NativeLibraryOwnersTest.limits("cleric", 5);
        var la = NativeLibraryOwnersTest.limits("cleric", 5);
        var service = NativeLibraryOwnersTest.install(NativeLibraryServiceDocuments.build("c"), lc, null);
        var caller = new ComputerBlockHost(() -> {});
        var program = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("call", CALL, Map.of("context", CONTEXT), Map.of("outcome", MATCH), Map.of("outcome", "outcome")),
            new FlowProgram.Write("send", "x0", "context"),
            new FlowProgram.Read("receive", "x0", "outcome", MATCH),
            new FlowProgram.EndProcedure("return"))).withAssembly();
        caller.installFlow(program, record(), la, true);
        caller.setPaused(false, la, true); service.setPaused(false, lc, true);
        caller.tick(la, true);
        var history = new XBusHistorySavedData();
        var identity = service.flowSnapshot().orElseThrow().state().flow();
        long generation = service.flowSnapshot().orElseThrow().control().generation();
        net.minecraft.nbt.Tag oldCaller = null, oldService = null;
        long peakReceiptUnits = 0;
        for (int round = 0; round < 64; round++) {
            var packet = record("kind", "library_command", "target", record("x", 1, "y", 2, "z", 3),
                "request", "request-" + round, "operation", "status", "sequence", 0, "data", null);
            var reply = record("kind", "library_reply", "target", packet.field("target"), "request", packet.field("request"),
                "code", "idle", "sequence", round, "value", null);
            var id = caller.startFlowTask(CALL, record("context", record("p", packet, "r", reply)), la, true);
            boolean completed = false;
            for (int tick = 0; tick < 1500; tick++) {
                caller.tick(la, true);
                service.tick(lc, true, Map.of("x0", NativeLibraryStartupTest.status(service, "x0", lc, caller, "x0", la)));
                if (oldCaller == null && caller.flowTask(id).orElseThrow().status() == FlowRuntime.Status.WAITING_WRITE
                    && service.flowSnapshot().orElseThrow().tasks().contexts().stream().anyMatch(t -> t.status() == FlowRuntime.Status.WAITING_READ)) {
                    oldCaller = caller.write(); oldService = service.write();
                }
                NativeLibraryTerminalExecutionTest.exchange(caller, "x0", la, service, "x0", lc, history);
                assertTrue(service.loaded(), service.diagnostic().toString());
                var state = service.flowSnapshot().orElseThrow();
                peakReceiptUnits = Math.max(peakReceiptUnits, state.receipts().stream().mapToLong(r -> r.value().storageUnits()).sum());
                assertTrue(state.receipts().size() <= 2, "Only the active transaction may retain payload receipts");
                if (caller.flowTask(id).orElseThrow().status() == FlowRuntime.Status.COMPLETE
                    && state.tasks().contexts().isEmpty() && state.state().status() == FlowRuntime.Status.SLEEPING) {
                    completed = true; break;
                }
            }
            assertTrue(completed, "Bounded request must return and release its service task");
            assertEquals(record("outcome", record("m", true)), caller.claimFlowTask(id, la, true).locals());
            var state = service.flowSnapshot().orElseThrow();
            assertEquals(identity, state.state().flow(), "No hidden reset between requests");
            assertEquals(generation, state.control().generation());
            assertTrue(state.receipts().isEmpty(), "Completed service must retire local payload receipts, not accumulate them forever");
            assertEquals(1, state.tasks().slots().entries().size(), "Reuse one bounded task slot, not one slot per historic request");
            assertEquals(round, state.tasks().slots().entries().getFirst().incarnation());
            assertTrue(state.tasks().slots().entries().getFirst().retired());
            if (round % 8 == 7) {
                service = NativeLibraryOwnersTest.reload(service, lc, null);
                caller = NativeLibraryOwnersTest.reload(caller, la, null);
                var journal = history.save(new CompoundTag(), RegistryAccess.EMPTY);
                history = XBusHistorySavedData.load(journal, RegistryAccess.EMPTY);
                assertEquals(journal, history.save(new CompoundTag(), RegistryAccess.EMPTY));
            }
        }
        assertNotNull(oldCaller); assertNotNull(oldService);
        assertTrue(history.observe(List.of(caller.flowSnapshot().orElseThrow(), service.flowSnapshot().orElseThrow()), 65536, 262144).isEmpty());
        var staleCaller = new ComputerBlockHost(() -> {}); staleCaller.read(oldCaller); staleCaller.tick(la, true);
        var staleService = new ComputerBlockHost(() -> {}); staleService.read(oldService); staleService.tick(lc, true);
        assertTrue(staleCaller.loaded()); assertTrue(staleService.loaded());
        var beforeCaller = staleCaller.write(); var beforeService = staleService.write();
        assertEquals(XBusExchange.Status.RETIRED, NativeLibraryTerminalExecutionTest.access(staleCaller, "x0", la, history)
            .exchange(NativeLibraryTerminalExecutionTest.access(staleService, "x0", lc, history)));
        assertEquals(beforeCaller, staleCaller.write()); assertEquals(beforeService, staleService.write());
        System.out.println("64 matcher requests, 8 owner/history reloads, unchanged main identity; peak retained receipt units=" + peakReceiptUnits);
    }
}
