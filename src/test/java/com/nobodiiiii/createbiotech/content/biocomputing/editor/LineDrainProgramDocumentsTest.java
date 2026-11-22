package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.MotionPanelTerminalFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationNetworkLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderSamplingProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

class LineDrainProgramDocumentsTest {
    private static final Path EXAMPLES = Path.of("examples/biocomputing");
    private static final String ID = "line_drain_a";
    private static final String SWITCH_ID = "line_filter_switch_a";
    private static final String MACHINE_ID = "line_machine_switch_a";
    private static final String PANEL_ID = "line_process_panel_a";
    private static final String RESUME_ID = "line_resume_panel_a";
    private static final String REPORT_ID = "line_result_panel_a";
    private static final String REPORTED_RESUME_ID = "line_resume_panel_report_a";
    private static final DataValue.RecordValue OUTLET_ONE = at(19), OUTLET_TWO = at(20), INTERLOCK_PROBE = at(21);
    private static final DataValue.RecordValue PANEL_POS = at(18);
    private static final String BLOCK = "create_biotech:block";
    private static final String REDSTONE = "create_biotech:redstone";
    private static final String FLUID = "create_biotech:fluid";
    private static final String[] CHECKS = {"item_gate", "fluid_gate", "items", "fluid", "arm", "deployer"};
    private static final DataType.RecordType ADDRESS = new DataType.RecordType(Map.of("x", INTEGER, "y", INTEGER, "z", INTEGER));
    private static final DataType.RecordType BINDING = new DataType.RecordType(Map.of("reader", ADDRESS, "block", ADDRESS));
    private static final DataType.RecordType LAYOUT = new DataType.RecordType(Map.of("item_gate", BINDING, "fluid_gate", BINDING,
        "items", BINDING, "fluid", BINDING, "arm", ADDRESS, "deployer", ADDRESS));
    private static final DataType.RecordType RULE = new DataType.RecordType(Map.of("domain", TEXT, "mode", TEXT, "matchers", ANY));
    private static final DataType.RecordType FILTER = new DataType.RecordType(Map.of("target", ADDRESS, "rule", RULE));
    private static final DataType.RecordType POINT = new DataType.RecordType(Map.of("index", INTEGER, "offset", ADDRESS, "type", TEXT));
    private static final DataType.RecordType ROUTE = new DataType.RecordType(Map.of("input", POINT, "output", POINT));
    private static final DataType.RecordType PROFILE = new DataType.RecordType(Map.of("item_filter", FILTER,
        "fluid_filter", FILTER, "deployer_mode", TEXT, "arm_route", ROUTE));
    private static final DataValue NULL = DataValue.NullValue.INSTANCE;
    private enum Stage { DRAIN, FILTERS, MACHINES, PANEL, RESUME, REPORT }
    private enum Scenario { CLEAR, OPEN_VALVE, ITEM_IN_LAST_SLOT, DEPOT_CLEAR, DEPOT_REMAINDER, FLUID_LEFT, DENIED_COMPONENT, ABSENT_COMPARATOR, WRONG_TARGET,
        ARM_HOLDING, DEPLOYER_PENDING, MISSING_HELD, GATE_REOPENS, NO_READ_RESULT, FILTER_CHANGED, FILTER_REPLY_LOST,
        DEPLOYER_MODE_MISMATCH, ARM_POINT_MOVED, PANEL_TWO, PANEL_STALE, PANEL_NO_CHOICE, RESUME_CLEAR, RESUME_BLOCKED, RESUME_LIVE_INTERLOCK,
        RESUME_WRONG_TARGET, RESUME_NOT_ARMED, RESUME_NO_CONFIGURATION, ROUTE_READY, ROUTE_REFUSED,
        ROUTE_ACK_LOST, ROUTE_OTHER_ACTION, ROUTE_BAD_SNAPSHOT, ROUTE_MOVING_THEN_DONE, ROUTE_STILL_MOVING, REPORT_STALE }

    @Test void freshEvidenceAllowsOnlyTheObservedWorkcellToProceed() throws Exception {
        var fixture = new Workcell(Scenario.CLEAR);
        var result = fixture.run();
        assertEquals(new DataValue.BooleanValue(true), result.field("guard_ok"));
        assertEquals(text("observed_drained"), result.field("status"));
        assertEquals(List.of("item_gate/status", "item_gate/sample", "fluid_gate/status", "fluid_gate/sample",
            "arm/arm_status", "deployer/deployer_status", "items/status", "items/sample",
            "fluid/status", "fluid/sample", "item_gate/status", "item_gate/sample", "fluid_gate/status", "fluid_gate/sample"),
            fixture.calls);
    }

    @ParameterizedTest
    @CsvSource({
        "OPEN_VALVE, fluid_gate, gate_not_closed",
        "ITEM_IN_LAST_SLOT, items, material_remaining",
        "FLUID_LEFT, fluid, material_remaining",
        "DENIED_COMPONENT, items, sample_unavailable",
        "ABSENT_COMPARATOR, items, sample_unavailable",
        "WRONG_TARGET, fluid, wrong_observation_target",
        "ARM_HOLDING, arm, arm_not_idle",
        "DEPLOYER_PENDING, deployer, deployer_not_idle",
        "MISSING_HELD, arm, arm_not_idle",
        "GATE_REOPENS, item_gate, gate_not_closed",
        "NO_READ_RESULT, fluid_gate, device_unavailable"
    })
    void refusedOrIncompleteEvidenceNeverPermitsASettingChange(Scenario scenario, String checkpoint, String status) throws Exception {
        var fixture = new Workcell(scenario);
        var result = fixture.run();
        assertEquals(new DataValue.BooleanValue(false), result.field("guard_ok"));
        assertEquals(text(status), result.field("status"), () -> "calls=" + fixture.calls + " outputs=" + result.fields());
        assertEquals(text(checkpoint), result.field("checkpoint"));
        assertTrue(fixture.calls.getLast().startsWith(checkpoint + "/"));
    }

    @Test void workcellAppliesAndRechecksBothFilterRulesWithoutReopeningTheIntakes() throws Exception {
        var fixture = new Workcell(Scenario.CLEAR, true);
        var result = fixture.run();
        assertEquals(text("filtered_hold"), result.field("filter_result"), () -> "calls=" + fixture.calls + " outputs=" + result.fields());
        assertEquals(text("applied"), result.field("item_write"));
        assertEquals(text("applied"), result.field("fluid_write"));
        assertEquals(text("verified"), result.field("item_verify"));
        assertEquals(text("verified"), result.field("fluid_verify"));
        assertEquals(List.of("item_filter/status", "item_filter/set", "fluid_filter/status", "fluid_filter/set",
            "item_filter/verify", "fluid_filter/verify"), fixture.calls.subList(14, fixture.calls.size()));
    }

    @Test void workcellWithRemainingMaterialDoesNotSendAnyFilterCommand() throws Exception {
        var fixture = new Workcell(Scenario.ITEM_IN_LAST_SLOT, true);
        var result = fixture.run();
        assertEquals(text("material_remaining"), result.field("status"));
        assertEquals(text("drain_blocked"), result.field("filter_result"));
        assertEquals(text("not_attempted"), result.field("item_write"));
        assertEquals(text("not_attempted"), result.field("fluid_write"));
        assertEquals("items/sample", fixture.calls.getLast());
        assertTrue(fixture.installedRules.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "FILTER_CHANGED, verification_failed, item_filter, verify, applied, version_conflict",
        "FILTER_REPLY_LOST, write_not_confirmed, fluid_filter, set, receive_timeout, not_attempted"
    })
    void workcellReportsPartialProgressAndNeverRetries(Scenario scenario, String resultCode, String checkpoint,
            String operation, String fluidWrite, String itemVerify) throws Exception {
        var fixture = new Workcell(scenario, Stage.MACHINES);
        var result = fixture.run();
        assertEquals(text(resultCode), result.field("filter_result"));
        assertEquals(text(checkpoint), result.field("filter_checkpoint"));
        assertEquals(text(operation), result.field("filter_operation"));
        assertEquals(text("applied"), result.field("item_write"));
        assertEquals(text(fluidWrite), result.field("fluid_write"));
        assertEquals(text(itemVerify), result.field("item_verify"));
        assertEquals(text("not_attempted"), result.field("fluid_verify"));
        assertEquals(text("filters_blocked"), result.field("machine_result"));
        assertEquals(text("not_attempted"), result.field("deployer_write"));
        assertEquals(checkpoint + "/" + operation, fixture.calls.getLast());
        assertEquals(1, Collections.frequency(fixture.calls, "item_filter/set"));
        assertEquals(1, Collections.frequency(fixture.calls, "fluid_filter/set"));
        assertEquals(2, fixture.installedRules.size(), "A missing reply does not imply the change didn't happen");
    }

    @Test void appliesAndReadsBackNativeModeAndChecksTheChosenRouteWithoutMoving() throws Exception {
        var fixture = new Workcell(Scenario.CLEAR, Stage.MACHINES);
        var result = fixture.run();
        assertEquals(text("machines_checked_hold"), result.field("machine_result"), () -> "calls=" + fixture.calls + ", " + result.fields());
        assertEquals(text("applied"), result.field("deployer_write"));
        assertEquals(text("verified"), result.field("deployer_verify"));
        assertEquals(text("verified"), result.field("arm_verify"));
        assertEquals(route(), result.field("confirmed_route"));
        assertEquals(List.of("deployer/status", "deployer/set", "deployer/deployer_status", "arm/arm_status"),
            fixture.calls.subList(20, fixture.calls.size()));
    }

    @ParameterizedTest
    @CsvSource({
        "DEPLOYER_MODE_MISMATCH, deployer_verification_failed, deployer_verify, mode_mismatch, not_attempted, deployer/deployer_status",
        "ARM_POINT_MOVED, arm_verification_failed, arm_verify, verified, route_mismatch, arm/arm_status"
    })
    void actualModeAndPointDisagreementsHoldEvenAfterASettingWasApplied(Scenario scenario, String resultCode,
            String checkpoint, String deployer, String arm, String lastCall) throws Exception {
        var fixture = new Workcell(scenario, Stage.MACHINES);
        var result = fixture.run();
        assertEquals(text("filtered_hold"), result.field("filter_result"));
        assertEquals(text("applied"), result.field("deployer_write"));
        assertEquals(text(resultCode), result.field("machine_result"));
        assertEquals(text(checkpoint), result.field("machine_checkpoint"));
        assertEquals(text(deployer), result.field("deployer_verify"));
        assertEquals(text(arm), result.field("arm_verify"));
        assertEquals(NULL, result.field("confirmed_route"));
        assertEquals(lastCall, fixture.calls.getLast());
        assertEquals(1, Collections.frequency(fixture.calls, "deployer/set"), "No retry or rollback of the mode");
    }

    @Test void theEditablePanelArchiveCompilesWithTheWorkcellBudget() throws Exception {
        new Workcell(Scenario.PANEL_TWO, Stage.PANEL);
    }

    @Test void operatorUsesThePhysicalPanelChoiceToConfigureOnlyTheSelectedProcess() throws Exception {
        var fixture = new Workcell(Scenario.PANEL_TWO, Stage.PANEL);
        var result = fixture.run();
        assertEquals(text("process_two"), result.field("chosen_process"));
        assertEquals(text("machines_checked_hold"), result.field("machine_result"));
        assertEquals(text("unchanged"), result.field("deployer_write"));
        assertEquals(record(Map.of("input", point(0, 1), "output", point(0, 2))), result.field("confirmed_route"));
        assertEquals(rule("item", "minecraft:gold_ingot"), fixture.installedRules.get(6));
        assertEquals(rule("fluid", "minecraft:lava"), fixture.installedRules.get(7));
        assertEquals(List.of("terminal/status", "terminal/publish", "terminal/status", "terminal/take_event", "item_gate/status"),
            fixture.calls.subList(0, 5));
        assertEquals(0, fixture.panel.queued());
        assertFalse(fixture.calls.contains("deployer/set"), "Process one must not be applied before the operator chooses");
    }

    @Test void earlierPanelClickCannotConfigureTheNewRun() throws Exception {
        var fixture = new Workcell(Scenario.PANEL_STALE, Stage.PANEL);
        var result = fixture.run();
        assertEquals(text("stale_choice"), result.field("choice_status"));
        assertEquals(text(""), result.field("chosen_process"));
        assertEquals(List.of("terminal/status", "terminal/publish", "terminal/status", "terminal/take_event"), fixture.calls);
        assertTrue(fixture.installedRules.isEmpty());
    }

    @Test void panelPollsOnlyABoundedNumberOfTimesWithoutAChoice() throws Exception {
        var fixture = new Workcell(Scenario.PANEL_NO_CHOICE, Stage.PANEL);
        var result = fixture.run();
        assertEquals(text("no_choice"), result.field("choice_status"));
        assertEquals(text(""), result.field("chosen_process"));
        assertEquals(22, fixture.calls.size(), "One status, one publication and 20 polls");
        assertTrue(fixture.calls.subList(2, fixture.calls.size()).stream().allMatch("terminal/status"::equals));
        assertTrue(fixture.installedRules.isEmpty());
    }

    @Test void blockedReleaseIsReportedOnTheSamePhysicalTerminal() throws Exception {
        var fixture = new Workcell(Scenario.RESUME_BLOCKED, Stage.REPORT);
        var result = fixture.run();
        assertEquals(text("outlet_blocked"), result.field("resume_status"));
        assertEquals(text("outlet_two"), result.field("resume_checkpoint"));
        assertEquals(List.of(0), fixture.feedTransitions);
        assertFalse(fixture.calls.contains("arm/arm_move"));
        assertEquals(2, Collections.frequency(fixture.calls, "terminal/publish"),
            "Show the result on the same terminal rather than requiring the computer debugger");
        assertEquals(text("published"), result.field("report_status"));
        var shown = fixture.panel.visibleText();
        assertEquals(text("工艺切换结果"), shown.getFirst());
        assertResult(shown, "选择的工艺", "process_one");
        assertResult(shown, "停料与排空", "observed_drained");
        assertResult(shown, "过滤规则", "filtered_hold");
        assertResult(shown, "设备核对", "machines_checked_hold");
        assertResult(shown, "复产信号", "outlet_blocked");
        assertResult(shown, "复产检查点", "outlet_two");
        assertResult(shown, "机械臂搬运", "not_requested");
    }

    @Test void anOlderRunCannotReplaceAnotherPublishedView() throws Exception {
        var fixture = new Workcell(Scenario.REPORT_STALE, Stage.REPORT);
        var result = fixture.run();
        assertEquals(text("changed"), result.field("report_status"));
        assertEquals(2, Collections.frequency(fixture.calls, "terminal/publish"), "Never reacquire a current revision to retry");
        assertEquals("terminal/publish", fixture.calls.getLast(), "No status refresh or retry after the report is rejected");
        assertEquals(List.of(text("另一个程序的页面")), fixture.panel.visibleText());
    }

    @Test void successfulMovePublishesOnlyAfterFeedReturnsLow() throws Exception {
        var fixture = new Workcell(Scenario.RESUME_CLEAR, Stage.REPORT);
        var result = fixture.run();
        assertEquals(text("published"), result.field("report_status"));
        assertEquals(List.of(0, 15, 0), fixture.feedTransitions);
        assertEquals(1, Collections.frequency(fixture.calls, "arm/arm_move"));
        assertEquals("terminal/publish", fixture.calls.getLast());
        var shown = fixture.panel.visibleText();
        assertResult(shown, "复产信号", "release_pulsed");
        assertResult(shown, "机械臂搬运", "transfer_completed");
    }

    private static void assertResult(List<DataValue> shown, String label, String expected) {
        assertTrue(shown.contains(text(label + "：" + expected)), "The panel shows " + label + " = " + expected);
    }

    @Test void anUnchosenRunDoesNotPublishResultsOrMutateTheLine() throws Exception {
        var fixture = new Workcell(Scenario.PANEL_NO_CHOICE, Stage.REPORT);
        var result = fixture.run();
        assertEquals(text("no_choice"), result.field("choice_status"));
        assertEquals(1, Collections.frequency(fixture.calls, "terminal/publish"));
        assertEquals(List.of(0), fixture.feedTransitions);
        assertTrue(fixture.installedRules.isEmpty());
    }

    @Test void freshReceiverObservationsAndLiveInterlockAllowOneBoundedFeedRequest() throws Exception {
        var fixture = new Workcell(Scenario.RESUME_CLEAR, Stage.RESUME);
        var result = fixture.run();
        assertEquals(text("release_pulsed"), result.field("resume_status"));
        assertEquals(List.of("outlet_one/status", "outlet_one/sample", "outlet_two/status", "outlet_two/sample",
            "interlock/status", "interlock/sample"), fixture.calls);
        assertEquals(List.of(0, 15, 0), fixture.feedTransitions, "Issue one pulse and lower the permit line");
    }

    @Test void admitsOnlyTheVerifiedRouteBeforeFeedAndReadsBackTheSameAction() throws Exception {
        var fixture = new Workcell(Scenario.ROUTE_READY, Stage.RESUME);
        var result = fixture.run();
        assertEquals(text("release_pulsed"), result.field("resume_status"));
        assertEquals(text("transfer_completed"), result.fields().get("route_status"));
        assertEquals(Workcell.ARM_ACTION, result.field("route_action"));
        assertEquals(List.of("outlet_one/status", "outlet_one/sample", "outlet_two/status", "outlet_two/sample",
            "interlock/status", "interlock/sample", "arm/arm_move", "arm/arm_status"), fixture.calls);
        assertEquals(List.of(0, 15, 0), fixture.feedTransitions);
    }

    @Test void observesBothEmptyAndOccupiedNativeDepotWithNoAdditionalFrame() throws Exception {
        var occupied = new Workcell(Scenario.DEPOT_REMAINDER);
        var result = occupied.run();
        assertEquals(text("material_remaining"), result.field("status"));
        assertEquals(text("items"), result.field("checkpoint"));
        assertEquals(1, Collections.frequency(occupied.calls, "items/sample"));
        var empty = new Workcell(Scenario.DEPOT_CLEAR);
        assertEquals(text("observed_drained"), empty.run().field("status"));
        assertEquals(1, Collections.frequency(empty.calls, "items/sample"));
    }

    @Test void routeRejectionOrUnknownAdmissionNeverReleasesOrResubmits() throws Exception {
        for (var scenario : List.of(Scenario.ROUTE_REFUSED, Scenario.ROUTE_ACK_LOST, Scenario.ROUTE_BAD_SNAPSHOT)) {
            var fixture = new Workcell(scenario, Stage.RESUME);
            var result = fixture.run();
            assertEquals(List.of(0), fixture.feedTransitions, scenario.toString());
            assertEquals(scenario == Scenario.ROUTE_BAD_SNAPSHOT ? 0 : 1,
                Collections.frequency(fixture.calls, "arm/arm_move"));
            assertFalse(fixture.calls.contains("arm/arm_status"));
            assertEquals(switch (scenario) {
                case ROUTE_REFUSED -> text("version_conflict");
                case ROUTE_ACK_LOST -> text("admission_unknown");
                default -> text("route_snapshot_invalid");
            }, result.fields().get("route_status"));
        }
    }

    @Test void unrelatedCompletedTransferIsNotReportedAsTheCurrentDelivery() throws Exception {
        var fixture = new Workcell(Scenario.ROUTE_OTHER_ACTION, Stage.RESUME);
        var result = fixture.run();
        assertEquals(text("release_pulsed"), result.field("resume_status"));
        assertEquals(text("action_changed"), result.fields().get("route_status"));
        assertEquals(Workcell.ARM_ACTION, result.field("route_action"));
        assertEquals(1, Collections.frequency(fixture.calls, "arm/arm_move"));
    }

    @Test void observesAnInFlightRouteWithoutEverReissuingTheMoveOrTheFeedPulse() throws Exception {
        var completing = new Workcell(Scenario.ROUTE_MOVING_THEN_DONE, Stage.RESUME);
        var arrived = completing.run();
        assertEquals(text("transfer_completed"), arrived.field("route_status"));
        assertEquals(2, Collections.frequency(completing.calls, "arm/arm_status"));
        assertEquals(1, Collections.frequency(completing.calls, "arm/arm_move"));
        assertEquals(List.of(0, 15, 0), completing.feedTransitions);

        var stillMoving = new Workcell(Scenario.ROUTE_STILL_MOVING, Stage.RESUME);
        var observed = stillMoving.run();
        assertEquals(text("moving"), observed.field("route_status"));
        assertEquals(3, Collections.frequency(stillMoving.calls, "arm/arm_status"), "Observation has a finite budget");
        assertEquals(1, Collections.frequency(stillMoving.calls, "arm/arm_move"));
        assertEquals(List.of(0, 15, 0), stillMoving.feedTransitions);
    }

    @ParameterizedTest
    @CsvSource({
        "RESUME_BLOCKED, outlet_blocked, outlet_two, 4",
        "RESUME_LIVE_INTERLOCK, interlock_not_ready, hardware_interlock, 6",
        "RESUME_WRONG_TARGET, wrong_outlet_target, outlet_two, 4",
        "RESUME_NOT_ARMED, release_not_armed, none, 0",
        "RESUME_NO_CONFIGURATION, configuration_unconfirmed, none, 0"
    })
    void failedReleaseChecksNeverEmitAnEdge(Scenario scenario, String status, String checkpoint, int calls) throws Exception {
        var fixture = new Workcell(scenario, Stage.RESUME);
        var result = fixture.run();
        assertEquals(text(status), result.field("resume_status"));
        assertEquals(text(checkpoint), result.field("resume_checkpoint"));
        assertEquals(calls, fixture.calls.size());
        assertEquals(List.of(0), fixture.feedTransitions);
    }

    @Test void publishedArchiveContainsTheSameGatedReleaseProcedureAndKeepsItDisarmedByDefault() throws Exception {
        var source = buildResumePanel();
        var config = new BiocomputingConfig(new ModConfigSpec.Builder());
        var hardware = config.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
            ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var limits = new ComputerHostLimits(new ResourceLimits(6144, hardware.hardware().memoryUnits(), hardware.hardware().workPerSlice()),
            hardware.archive(), hardware.mailbox(), hardware.pins(), hardware.serial(), hardware.taskSlots(),
            hardware.isa1a(), hardware.isa1b());
        var path = EXAMPLES.resolve(RESUME_ID + ".bcp");
        if ("1".equals(System.getenv("GENERATE_LINE_RESUME")))
            Files.writeString(path, Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(source, limits.archive())) + "\n");
        var deployed = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(path).strip()), limits.archive());
        assertEquals(source, deployed);
        assertEquals(new DataValue.BooleanValue(false), deployed.flow().orElseThrow().initial().field("release_armed"));
        assertEquals(new DataValue.BooleanValue(true), deployed.flow().orElseThrow().initial().field("route_enabled"));
        PreparedProgram.prepare(deployed, limits);
        var units = deployed.flow().orElseThrow().program(deployed.archive()).usage(limits.hardware()).programUnits();
        System.out.println("Workcell " + RESUME_ID + ": " + units + " code units");
    }

    @Test void reportedResumeArchiveCompilesWithTheWorkcellBudget() throws Exception {
        var source = buildReportedResumePanel();
        var config = new BiocomputingConfig(new ModConfigSpec.Builder());
        var hardware = config.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
            ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var limits = new ComputerHostLimits(new ResourceLimits(6144, hardware.hardware().memoryUnits(), hardware.hardware().workPerSlice()),
            hardware.archive(), hardware.mailbox(), hardware.pins(), hardware.serial(), hardware.taskSlots(),
            hardware.isa1a(), hardware.isa1b());
        var path = EXAMPLES.resolve(REPORTED_RESUME_ID + ".bcp");
        if ("1".equals(System.getenv("GENERATE_LINE_REPORT")))
            Files.writeString(path, Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(source, limits.archive())) + "\n");
        var deployed = ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(path).strip()), limits.archive());
        assertEquals(source, deployed);
        assertEquals(new DataValue.BooleanValue(false), deployed.flow().orElseThrow().initial().field("release_armed"));
        PreparedProgram.prepare(deployed, limits);
        var units = deployed.flow().orElseThrow().program(deployed.archive()).usage(limits.hardware()).programUnits();
        System.out.println("Workcell " + REPORTED_RESUME_ID + ": " + units + " code units");
    }

    @Test void reportLayoutAndTenResultLinesFitAnUnmodifiedTerminalFrame() throws Exception {
        var config = new BiocomputingConfig(new ModConfigSpec.Builder());
        var codec = config.serialCodec(false);
        int frameBytes = config.terminalUiSerial(false).ports().get("s0").maxFrameBytes();
        var wire = new SerialRecordCodec.Limits(frameBytes, codec.maxDecodeHeapBytes(), codec.values());
        var doc = ProgramDocumentWire.decode(Base64.getDecoder().decode(
            Files.readString(EXAMPLES.resolve(REPORTED_RESUME_ID + ".bcp")).strip()), codec.values());
        var body = doc.flow().orElseThrow();
        var report = body.program(doc.archive()).steps().stream().filter(FlowProgram.Procedure.class::isInstance)
            .map(FlowProgram.Procedure.class::cast).filter(procedure -> procedure.key().id().equals(REPORT_ID)).findFirst().orElseThrow();
        var panel = new MotionPanelTerminalFixture(config.terminalUiLimits(false), PANEL_POS);
        panel.seedPriorView(processPanel());
        panel.click("process_two");
        var request = Map.<String, DataValue>of("kind", text("terminal_ui_command"), "version", number(1),
            "target", PANEL_POS, "request", text("take-test"), "operation", text("status"), "arguments", record(Map.of()));
        var state = (DataValue.RecordValue)panel.reply(record(request)).field("state");
        var take = new LinkedHashMap<>(request);
        take.put("operation", text("take_event"));
        take.put("arguments", record(Map.of("state", state, "sequence", state.field("next_event"))));
        var choice = panel.reply(record(take));
        assertEquals(text("event"), choice.field("code"));

        var values = new LinkedHashMap<String, DataValue>();
        var sample = List.of("process_two", "observed_drained", "finished", "filtered_hold", "finished",
            "machines_checked_hold", "finished", "release_pulsed", "finished", "transfer_completed");
        for (int i = 0; i < RESULT_FIELDS.size(); i++) values.put(RESULT_FIELDS.get(i), text(sample.get(i)));
        values.put("terminal_pos", PANEL_POS);
        values.put("choice_reply", choice);
        values.put("result_panel", body.initial().field("result_panel"));
        var hardware = config.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),
            ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        var limits = new ComputerHostLimits(new ResourceLimits(6144, hardware.hardware().memoryUnits(), hardware.hardware().workPerSlice()),
            hardware.archive(), hardware.mailbox(), hardware.pins(), hardware.serial(), hardware.taskSlots(),
            hardware.isa1a(), hardware.isa1b());
        var reporter = new ComputerBlockHost(() -> {});
        reporter.installFlow(new FlowProgram(report.inputs(), List.of(
            new FlowProgram.Invoke("call_report", report.key(), new FlowCallBindings(names(report.inputs()), Map.of("report_status", "report_status"))),
            report, new FlowProgram.EndProcedure("end_report_probe"))).withAssembly(), record(values), limits, true);
        reporter.setPaused(false, limits, true);
        var peer = new ComputerBlockHost(() -> {});
        var packetType = new DataType.RecordType(Map.of("kind", TEXT, "version", INTEGER, "target", ADDRESS,
            "request", TEXT, "operation", TEXT, "arguments", ANY));
        var callType = new DataType.RecordType(Map.of("p", packetType, "e", TEXT));
        peer.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read_outbound", "x0", "call", callType)))
            .withAssembly(), record(Map.of()), limits, true);
        peer.setPaused(false, limits, true);
        DataValue.RecordValue call = null;
        for (int tick = 0; tick < 160; tick++) {
            reporter.tick(limits, true);
            peer.tick(limits, true);
            if (reporter.exchange("x0", limits, true, peer, "x0", limits, true) == XBusExchange.Status.EXCHANGED) {
                call = (DataValue.RecordValue)peer.flowSnapshot().orElseThrow().state().locals().field("call");
                break;
            }
        }
        assertNotNull(call, "The installed report procedure must send over XBus");
        assertEquals(text("terminal_ui_reply"), call.field("e"));
        var packet = (DataValue.RecordValue)call.field("p");
        var args = (DataValue.RecordValue)packet.field("arguments");
        assertEquals(choice.field("state"), args.field("state"), "Use the accepted click's own revision");
        var frame = SerialRecordCodec.encode(packet, wire);
        var received = SerialRecordCodec.decode(frame, wire);
        assertEquals(text("published"), panel.reply(received).field("code"));
        assertResult(panel.visibleText(), "机械臂搬运", "transfer_completed");
        assertEquals(text("changed"), panel.reply(received).field("code"), "The old revision cannot publish twice");
    }

    static ProgramDocument build() throws Exception {
        Map<String, DataType> inputs = Map.of("layout", LAYOUT, "settle_ticks", INTEGER);
        Map<String, DataType> outputs = Map.of("guard_ok", BOOLEAN, "status", TEXT, "checkpoint", TEXT, "reply", ANY);
        var key = new ModuleDefinition.Key(ID, 1);
        var out = names(outputs);
        var flow = new FlowProgram(inputs, List.of(
            new FlowProgram.Invoke("stop_and_inspect", key, new FlowCallBindings(names(inputs), out)),
            new FlowProgram.Procedure("inspect_workcell", key, inputs, outputs, out, FlowBlockRules.NONE,
                Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(ID + ".asm"))))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var layout = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < CHECKS.length; i++) {
            layout.put(CHECKS[i], i < 4 ? record(Map.of("reader", at(10 + i), "block", at(30 + i))) : at(10 + i));
        }
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(flow,
            record(Map.of("layout", record(layout), "settle_ticks", number(20))))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("guard_ok", "status", "checkpoint", "reply")));
        return workspace.snapshot();
    }

    private static ProgramDocument buildSwitch() throws Exception {
        var inspection = build();
        var guard = inspection.flow().orElseThrow().program(inspection.archive());
        var inputs = Map.<String, DataType>of("guard_ok", BOOLEAN, "item_filter", FILTER, "fluid_filter", FILTER);
        var outputs = Map.<String, DataType>of("filter_result", TEXT, "filter_checkpoint", TEXT, "filter_operation", TEXT,
            "item_write", ANY, "fluid_write", ANY, "item_verify", ANY, "fluid_verify", ANY, "filter_reply", ANY);
        var key = new ModuleDefinition.Key(SWITCH_ID, 1);
        var out = names(outputs);
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(guard.steps().getFirst());
        steps.add(new FlowProgram.Invoke("configure_filters", key, new FlowCallBindings(names(inputs), out)));
        steps.addAll(guard.steps().subList(1, guard.steps().size()));
        steps.add(new FlowProgram.Procedure("configure_filters_definition", key, inputs, outputs, out, FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(SWITCH_ID + ".asm"))))));
        steps.add(new FlowProgram.EndProcedure("end_filters"));
        var types = new LinkedHashMap<>(guard.inputs());
        types.put("item_filter", FILTER);
        types.put("fluid_filter", FILTER);
        var initial = new LinkedHashMap<>(inspection.flow().orElseThrow().initial().fields());
        initial.put("item_filter", record(Map.of("target", at(16), "rule", rule("item", "minecraft:iron_ingot"))));
        initial.put("fluid_filter", record(Map.of("target", at(17), "rule", rule("fluid", "minecraft:water"))));
        var work = new ProgramWorkspace();
        work.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(types, steps).withAssembly(), record(initial))));
        work.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("status", "checkpoint", "filter_result", "filter_checkpoint",
            "filter_operation", "item_write", "fluid_write", "item_verify", "fluid_verify", "filter_reply")));
        return work.snapshot();
    }

    private static ProgramDocument buildMachines() throws Exception {
        var filters = buildSwitch();
        var switchBody = filters.flow().orElseThrow();
        var switchFlow = switchBody.program(filters.archive());
        var inputs = Map.<String, DataType>of("filter_result", TEXT, "layout", LAYOUT, "deployer_mode", TEXT, "arm_route", ROUTE);
        var outputs = Map.<String, DataType>of("machine_result", TEXT, "machine_checkpoint", TEXT,
            "deployer_write", ANY, "deployer_verify", ANY, "arm_verify", ANY, "machine_reply", ANY, "confirmed_route", ANY);
        var key = new ModuleDefinition.Key(MACHINE_ID, 1);
        var out = names(outputs);
        var steps = new ArrayList<>(switchFlow.steps());
        steps.add(2, new FlowProgram.Invoke("configure_machines", key, new FlowCallBindings(names(inputs), out)));
        steps.add(new FlowProgram.Procedure("configure_machines_definition", key, inputs, outputs, out, FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(MACHINE_ID + ".asm")).replace("\r\n", "\n")))));
        steps.add(new FlowProgram.EndProcedure("end_machines"));
        var types = new LinkedHashMap<>(switchFlow.inputs());
        types.put("deployer_mode", TEXT);
        types.put("arm_route", ROUTE);
        var initial = new LinkedHashMap<>(switchBody.initial().fields());
        initial.put("deployer_mode", text("punch"));
        initial.put("arm_route", route());
        var work = new ProgramWorkspace();
        work.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(types, steps).withAssembly(), record(initial))));
        work.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("status", "filter_result", "machine_result", "machine_checkpoint",
            "deployer_write", "deployer_verify", "arm_verify", "confirmed_route", "machine_reply")));
        return work.snapshot();
    }

    private static ProgramDocument buildPanel() throws Exception {
        var machines = buildMachines();
        var body = machines.flow().orElseThrow();
        var program = body.program(machines.archive());
        var inputs = Map.<String, DataType>of("terminal_pos", ADDRESS, "choice_panel", ANY,
            "process_one", PROFILE, "process_two", PROFILE);
        var outputs = Map.<String, DataType>of("choice_ready", BOOLEAN, "choice_status", TEXT, "chosen_process", TEXT,
            "choice_reply", ANY, "item_filter", FILTER, "fluid_filter", FILTER, "deployer_mode", TEXT, "arm_route", ROUTE);
        var key = new ModuleDefinition.Key(PANEL_ID, 1);
        var out = names(outputs);
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.Invoke("choose_process", key, new FlowCallBindings(names(inputs), out)));
        steps.add(new FlowProgram.If("choice_is_new", new FlowInput.Local("choice_ready")));
        steps.addAll(program.steps().subList(0, 3));
        steps.add(new FlowProgram.EndIf("end_chosen_process"));
        steps.addAll(program.steps().subList(3, program.steps().size()));
        steps.add(new FlowProgram.Procedure("choose_process_definition", key, inputs, outputs, out, FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(PANEL_ID + ".asm")).replace("\r\n", "\n")))));
        steps.add(new FlowProgram.EndProcedure("end_choice"));
        var types = new LinkedHashMap<>(program.inputs());
        var initial = new LinkedHashMap<>(body.initial().fields());
        var first = new LinkedHashMap<String, DataValue>();
        for (String field : PROFILE.fields().keySet()) {
            types.remove(field);
            first.put(field, Objects.requireNonNull(initial.remove(field)));
        }
        types.putAll(inputs);
        initial.put("terminal_pos", PANEL_POS);
        initial.put("choice_panel", TerminalCodec.encode(processPanel()));
        initial.put("process_one", record(first));
        initial.put("process_two", record(Map.of(
            "item_filter", record(Map.of("target", at(16), "rule", rule("item", "minecraft:gold_ingot"))),
            "fluid_filter", record(Map.of("target", at(17), "rule", rule("fluid", "minecraft:lava"))),
            "deployer_mode", text("use"),
            "arm_route", record(Map.of("input", point(0, 1), "output", point(0, 2))))));
        var work = new ProgramWorkspace();
        work.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(types, steps).withAssembly(), record(initial))));
        work.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("choice_status", "chosen_process", "status", "filter_result",
            "machine_result", "machine_checkpoint", "deployer_write", "deployer_verify", "arm_verify", "confirmed_route")));
        return work.snapshot();
    }

    private static TerminalDefinition processPanel() {
        var yes = new TerminalBinding.Literal(new DataValue.BooleanValue(true));
        var empty = new TerminalBinding.Literal(record(Map.of()));
        return new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Column(List.of("process_one", "process_two")),
            "process_one", new TerminalDefinition.Button(new TerminalBinding.Literal(text("工艺一 · 铁锭／水")), yes, "process_one", empty),
            "process_two", new TerminalDefinition.Button(new TerminalBinding.Literal(text("工艺二 · 金锭／岩浆")), yes, "process_two", empty)));
    }

    private static Map<String, DataType> resumeInputs() {
        return Map.of("machine_result", TEXT, "machine_reply", ANY, "confirmed_route", ANY, "layout", LAYOUT,
            "outlet_one", BINDING, "outlet_two", BINDING, "interlock_probe", BINDING, "release_armed", BOOLEAN,
            "route_enabled", BOOLEAN);
    }

    private static Map<String, DataType> resumeOutputs() {
        return Map.of("resume_status", TEXT, "resume_checkpoint", TEXT, "resume_reply", ANY, "route_status", TEXT,
            "route_action", ANY, "route_reply", ANY);
    }

    private static ProgramDocument buildResumePanel() throws Exception {
        var panel = buildPanel();
        var body = panel.flow().orElseThrow();
        var program = body.program(panel.archive());
        var in = resumeInputs();
        var out = resumeOutputs();
        var key = new ModuleDefinition.Key(RESUME_ID, 1);
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : program.steps()) {
            if (step.sourceId().equals("end_chosen_process"))
                steps.add(new FlowProgram.Invoke("resume", key, new FlowCallBindings(names(in), names(out))));
            steps.add(step);
        }
        steps.add(resumeDefinition(key, in, out));
        steps.add(new FlowProgram.EndProcedure("end_resume"));
        var types = new LinkedHashMap<>(program.inputs());
        types.putAll(in);
        // Outputs of the preceding, verified settings procedure, not editable global inputs.
        for (String derived : List.of("machine_result", "machine_reply", "confirmed_route")) types.remove(derived);
        var initial = new LinkedHashMap<>(body.initial().fields());
        initial.putAll(resumeInitial(false));
        initial.put("route_enabled", new DataValue.BooleanValue(true));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(types, steps).withAssembly(), record(initial))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("choice_status", "chosen_process", "status",
            "filter_result", "machine_result", "resume_status", "resume_checkpoint", "resume_reply",
            "route_status", "route_action", "route_reply")));
        return workspace.snapshot();
    }

    private static Map<String, DataValue> resumeInitial(boolean armed) {
        return Map.of("release_armed", new DataValue.BooleanValue(armed),
            "outlet_one", outlet(OUTLET_ONE, at(39)), "outlet_two", outlet(OUTLET_TWO, at(40)),
            "interlock_probe", outlet(INTERLOCK_PROBE, at(41)));
    }

    private static final List<String> RESULT_FIELDS = List.of("chosen_process", "status", "checkpoint",
        "filter_result", "filter_checkpoint", "machine_result", "machine_checkpoint",
        "resume_status", "resume_checkpoint", "route_status");

    private static ProgramDocument buildReportedResumePanel() throws Exception {
        var base = buildResumePanel();
        var body = base.flow().orElseThrow();
        var program = body.program(base.archive());
        var key = new ModuleDefinition.Key(REPORT_ID, 1);
        var in = new LinkedHashMap<String, DataType>();
        in.put("terminal_pos", ADDRESS);
        in.put("choice_reply", ANY);
        in.put("result_panel", ANY);
        RESULT_FIELDS.forEach(field -> in.put(field, TEXT));
        var out = Map.<String, DataType>of("report_status", TEXT);
        var steps = new ArrayList<FlowProgram.Step>();
        for (var step : program.steps()) {
            if (step.sourceId().equals("end_chosen_process"))
                steps.add(new FlowProgram.Invoke("publish_result", key, new FlowCallBindings(names(in), names(out))));
            steps.add(step);
        }
        steps.add(new FlowProgram.Procedure("publish_result_definition", key, in, out, names(out), FlowBlockRules.NONE,
            Optional.of(new AssemblySource(Files.readString(EXAMPLES.resolve(REPORT_ID + ".asm")).replace("\r\n", "\n")))));
        steps.add(new FlowProgram.EndProcedure("end_publish_result"));
        var types = new LinkedHashMap<>(program.inputs());
        types.put("result_panel", ANY);
        var initial = new LinkedHashMap<>(body.initial().fields());
        initial.put("result_panel", TerminalCodec.encode(resultPanel()));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(types, steps).withAssembly(), record(initial))));
        workspace.setTerminal(Optional.of(NativeLibraryResultLayouts.fields("choice_status", "chosen_process", "status",
            "filter_result", "machine_result", "resume_status", "resume_checkpoint", "resume_reply",
            "route_status", "route_action", "route_reply", "report_status")));
        return workspace.snapshot();
    }

    private static TerminalDefinition resultPanel() {
        var row = new ValuePath("row", List.of());
        return new TerminalDefinition("root", Map.of(
            "root", new TerminalDefinition.Column(List.of("title", "results")),
            "title", new TerminalDefinition.Text(new TerminalBinding.Literal(text("工艺切换结果"))),
            "results", new TerminalDefinition.Repeat(new TerminalBinding.Path(new ValuePath("output", List.of())), "line"),
            "line", new TerminalDefinition.Text(new TerminalBinding.Path(row))));
    }

    private static ProgramDocument buildResumeProbe(Scenario scenario) throws Exception {
        var in = resumeInputs();
        var out = resumeOutputs();
        var k = new ModuleDefinition.Key(RESUME_ID, 1);
        var steps = List.<FlowProgram.Step>of(new FlowProgram.Invoke("resume", k, new FlowCallBindings(names(in), names(out))),
            resumeDefinition(k, in, out), new FlowProgram.EndProcedure("end_resume"));
        var program = new FlowProgram(in, steps).withAssembly();
        var ws = new ProgramWorkspace();
        var initial = new LinkedHashMap<>(resumeInitial(scenario != Scenario.RESUME_NOT_ARMED));
        initial.put("machine_result", text(scenario == Scenario.RESUME_NO_CONFIGURATION ? "deployer_verification_failed" : "machines_checked_hold"));
        initial.put("layout", build().flow().orElseThrow().initial().field("layout"));
        initial.put("confirmed_route", route());
        initial.put("route_enabled", new DataValue.BooleanValue(switch (scenario) {
            case ROUTE_READY, ROUTE_REFUSED, ROUTE_ACK_LOST, ROUTE_OTHER_ACTION, ROUTE_BAD_SNAPSHOT,
                ROUTE_MOVING_THEN_DONE, ROUTE_STILL_MOVING -> true;
            default -> false;
        }));
        initial.put("machine_reply", record(Map.of("target", at(scenario == Scenario.ROUTE_BAD_SNAPSHOT ? 15 : 14),
            "state", record(Map.of("token", Workcell.ARM_TOKEN)))));
        ws.setFlow(Optional.of(FlowDocumentBody.fromProgram(program, record(initial))));
        return ws.snapshot();
    }

    private static FlowProgram.Procedure resumeDefinition(ModuleDefinition.Key key, Map<String, DataType> in,
            Map<String, DataType> out) throws Exception {
        var asm = Files.readString(EXAMPLES.resolve(RESUME_ID + ".asm")).replace("\r\n", "\n");
        return new FlowProgram.Procedure("resume_definition", key, in, out, names(out), FlowBlockRules.NONE,
            Optional.of(new AssemblySource(asm)));
    }

    private static DataValue.RecordValue outlet(DataValue reader, DataValue block) {
        return record(Map.of("reader",reader,"block",block));
    }

    private static Map<String, String> names(Map<String, DataType> typedef) {
        var result = new LinkedHashMap<String, String>();
        typedef.keySet().forEach(name -> result.put(name, name));
        return result;
    }

    private static final class Workcell {
        private final BiocomputingConfig config = new BiocomputingConfig(new ModConfigSpec.Builder());
        private final ComputerHostLimits bridgeLimits = config.select(new ResidentHardwareIdentity(
            ResourceLocation.parse("minecraft:villager"), ResourceLocation.parse("minecraft:cleric"), 5), false).limits();
        private final ComputerHostLimits limits;
        private final SerialDevice sender = new SerialDevice(bridgeLimits::serial, () -> true, () -> config.serialCodec(false), () -> {});
        private final SerialDevice endpoint;
        private final ComputerBlockHost controller = new ComputerBlockHost(() -> {});
        private final ComputerBlockHost bridge = new ComputerBlockHost(() -> {}, pin -> {}, sender);
        private final SerialNetwork channel = new SerialNetwork();
        private final MotionPanelTerminalFixture panel = new MotionPanelTerminalFixture(config.terminalUiLimits(false), PANEL_POS);
        private final Set<DataValue> correlations = new HashSet<>();
        private final List<String> calls = new ArrayList<>();
        private final Map<Integer, DataValue> installedRules = new HashMap<>();
        private final List<Integer> feedTransitions = new ArrayList<>();
        private final Stage stage;
        private final Scenario scenario;
        private String deployerMode = "use";
        private static final DataValue MODE_TOKEN = new DataValue.TextValue("test-mode-token");
        private static final DataValue ARM_TOKEN = new DataValue.TextValue("test-checked-route-token");
        private static final DataValue ARM_ACTION = new DataValue.TextValue("test-native-action");

        Workcell(Scenario scenario) throws Exception {
            this(scenario, Stage.DRAIN);
        }

        Workcell(Scenario scenario, boolean switchFilters) throws Exception {
            this(scenario, switchFilters ? Stage.FILTERS : Stage.DRAIN);
        }

        Workcell(Scenario scenario, Stage stage) throws Exception {
            this.scenario = scenario;
            this.stage = stage;
            this.endpoint = new SerialDevice(
                () -> stage == Stage.REPORT ? config.terminalUiSerial(false) : bridgeLimits.serial(),
                () -> true, () -> config.serialCodec(false), () -> {});
            if (scenario == Scenario.PANEL_STALE) {
                panel.seedPriorView(processPanel());
                panel.click("process_two");
            }
            limits = new ComputerHostLimits(
                new ResourceLimits(switch (stage) { case DRAIN -> 1280; case FILTERS -> 2048; case MACHINES -> 3072; case PANEL -> 4096; case RESUME, REPORT -> 6144; },
                    bridgeLimits.hardware().memoryUnits(), bridgeLimits.hardware().workPerSlice()),
                bridgeLimits.archive(), bridgeLimits.mailbox(), bridgeLimits.pins(), bridgeLimits.serial(), bridgeLimits.taskSlots(),
                bridgeLimits.isa1a(), bridgeLimits.isa1b());
            var source = switch (stage) { case DRAIN -> build(); case FILTERS -> buildSwitch(); case MACHINES -> buildMachines(); case PANEL -> buildPanel(); case RESUME -> buildResumeProbe(scenario); case REPORT -> buildReportedResumePanel(); };
            String id = switch (stage) { case DRAIN -> ID; case FILTERS -> SWITCH_ID; case MACHINES -> MACHINE_ID; case PANEL -> PANEL_ID; case RESUME -> RESUME_ID; case REPORT -> REPORTED_RESUME_ID; };
            if (stage != Stage.RESUME && stage != Stage.REPORT && "1".equals(System.getenv("GENERATE_LINE_DRAIN"))) {
                Files.writeString(EXAMPLES.resolve(id + ".bcp"), Base64.getEncoder()
                    .encodeToString(ProgramDocumentWire.encode(source, limits.archive())) + "\n");
            }
            var packaged = stage == Stage.RESUME ? source : read(id);
            assertEquals(source, packaged);
            if (stage == Stage.REPORT) packaged = withArmedRelease(packaged);
            if (scenario == Scenario.CLEAR || scenario == Scenario.PANEL_TWO) {
                var usage = packaged.flow().orElseThrow().program(packaged.archive()).usage(limits.hardware());
                System.out.println("Workcell " + id + ": " + usage.programUnits() + " code units");
            }
            controller.installProgram(PreparedProgram.prepare(packaged, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true);
            bridge.installProgram(PreparedProgram.prepare(read("serial_exchange"), bridgeLimits), ComputerRuntime.MemoryPolicy.RESET, bridgeLimits, true);
            bridge.setPaused(false, bridgeLimits, true);
            controller.setPaused(false, limits, true);
            sender.configure("s0", Direction.EAST, true);
            endpoint.configure("s0", Direction.WEST, true);
        }

        private static ProgramDocument withArmedRelease(ProgramDocument document) {
            var body = document.flow().orElseThrow();
            var initial = new LinkedHashMap<>(body.initial().fields());
            initial.put("release_armed", new DataValue.BooleanValue(true));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(body.program(document.archive()), record(initial))));
            workspace.setTerminal(document.terminal());
            return workspace.snapshot();
        }

        DataValue.RecordValue run() {
            var cable = new BlockPos(1, 0, 0);
            var devices = Map.of(BlockPos.ZERO, sender, new BlockPos(2, 0, 0), endpoint);
            var world = new CableDiscovery.World() {
                public boolean loaded(BlockPos pos) { return true; }
                public CableDiscovery.Cell cell(BlockPos pos) {
                    return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE
                        : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
            };
            var networkLimits = new CommunicationNetworkLimits(64, 4);
            var medium = config.serialMedium(false);
            for (int tick = 0; tick < 30_000; tick++) {
                controller.tick(limits, true);
                var snapshot = controller.flowSnapshot().orElseThrow().state();
                bridge.tick(bridgeLimits, true, Map.of("x0", snapshot.status() == FlowRuntime.Status.WAITING_WRITE ? 15 : 0));
                controller.exchange("x0", limits, true, bridge, "x0", bridgeLimits, true);
                // Report scenarios test state transitions, not elapsed wall time. Advance the
                // virtual cable clock faster without changing its frame/buffer admission or the
                // Computers' activity ticks. The native workcell uses the real 1:1 clock.
                int mediumSteps = stage == Stage.REPORT ? 16 : 1;
                for (int step = 0; step < mediumSteps; step++) channel.tick(world, devices, networkLimits, medium);
                int level = snapshot.signals().outputs().getOrDefault("p0", 0);
                if (feedTransitions.isEmpty() || level != feedTransitions.getLast()) feedTransitions.add(level);
                if (endpoint.status("s0").receive().frames() > 0) {
                    var pending = endpoint.prepareReceive("s0");
                    var command = assertInstanceOf(DataValue.RecordValue.class, pending.result().field("value"));
                    if (stage == Stage.RESUME) {
                        boolean observingRoute = command.field("operation").equals(text("arm_status"));
                        assertEquals(observingRoute ? List.of(0, 15, 0) : List.of(0), feedTransitions,
                            "Only a confirmed route observation may follow the pulse");
                    } else if (command.field("target").equals(PANEL_POS)) {
                        if (stage == Stage.REPORT && calls.contains("terminal/take_event"))
                            assertEquals(0, level, "Reporting never drives the feed signal");
                        else
                            assertFalse(snapshot.signals().outputs().containsKey("p0"), "The panel must not drive p0 while choosing");
                    } else {
                        assertEquals(0, snapshot.signals().outputs().get("p0"), "stop signal precedes workcell observations");
                    }
                    var reply = observe(command, tick);
                    if (reply == null) assertTrue(pending.publish());
                    else assertTrue(pending.replying(reply).publish());
                }
                assertNotEquals(FlowRuntime.Status.FAILED, snapshot.status(), () -> snapshot.diagnostic().toString());
                if (snapshot.status() == FlowRuntime.Status.COMPLETE) {
                    if (stage == Stage.RESUME)
                        assertEquals(0, level, "The request does not leave a high latched permission");
                    else if (scenario == Scenario.PANEL_STALE || scenario == Scenario.PANEL_NO_CHOICE)
                        assertFalse(snapshot.signals().outputs().containsKey("p0"), "Unchosen run never writes even the stop signal");
                    else
                        assertEquals(0, snapshot.signals().outputs().get("p0"), "guard never reopens feed");
                    return snapshot.locals();
                }
            }
            throw new AssertionError("Guard did not finish: " + calls);
        }

        private ProgramDocument read(String name) throws Exception {
            var bytes = Base64.getDecoder().decode(Files.readString(EXAMPLES.resolve(name + ".bcp")).strip());
            return ProgramDocumentWire.decode(bytes, limits.archive());
        }

        private DataValue.RecordValue observe(DataValue.RecordValue cmd, int tick) {
            var to = assertInstanceOf(DataValue.RecordValue.class, cmd.field("target"));
            int index = (int)assertInstanceOf(DataValue.IntegerValue.class, to.field("x")).value() - 10;
            assertEquals(at(10 + index), to);
            String op = assertInstanceOf(DataValue.TextValue.class, cmd.field("operation")).value();
            assertTrue(correlations.add(cmd.field("request")), "query correlation should be fresh");
            if (scenario == Scenario.PANEL_TWO && calls.isEmpty())
                assertEquals(PANEL_POS, to, "Do not inspect or configure the workcell until a player selects a process");
            if (to.equals(PANEL_POS)) {
                calls.add("terminal/" + op);
                boolean initialPublication = Collections.frequency(calls, "terminal/publish") == 1;
                boolean revisionChanged = scenario == Scenario.REPORT_STALE && op.equals("publish") && !initialPublication;
                if (revisionChanged)
                    panel.seedPriorView(new TerminalDefinition("not_overwritten", Map.of("not_overwritten",
                        new TerminalDefinition.Text(new TerminalBinding.Literal(text("另一个程序的页面"))))));
                var reply = panel.reply(cmd);
                if (op.equals("publish")) {
                    assertEquals(text(revisionChanged ? "changed" : "published"), reply.field("code"));
                    if (scenario == Scenario.PANEL_TWO) panel.click("process_two");
                    if (stage == Stage.REPORT && initialPublication && scenario != Scenario.PANEL_NO_CHOICE) panel.click("process_one");
                }
                return reply;
            }
            if (to.equals(INTERLOCK_PROBE)) {
                calls.add("interlock/" + op);
                var service = new ReaderSamplingProtocol(new UUID(4, 3), new UUID(5, 3), to, Set.of(BLOCK));
                return service.respond(true, cmd, selected -> {
                    assertEquals(Set.of(BLOCK), selected);
                    String power = scenario == Scenario.RESUME_LIVE_INTERLOCK ? "0" : "15";
                    return record(Map.of("game_time", number(tick), "target", at(41),
                        "components", record(Map.of(BLOCK, record(Map.of("id", text("minecraft:redstone_wire"),
                            "state", record(Map.of("power", text(power)))))))));
                }).orElseThrow();
            }
            if (to.equals(OUTLET_ONE) || to.equals(OUTLET_TWO)) {
                int which = to.equals(OUTLET_ONE) ? 1 : 2;
                calls.add((which==1 ? "outlet_one" : "outlet_two") + "/" + op);
                var components = Set.of(BLOCK, REDSTONE);
                var service = new ReaderSamplingProtocol(new UUID(4, which), new UUID(5, which), to, components);
                return service.respond(true, cmd, selected -> {
                    assertEquals(components, selected);
                    int block = which == 2 && scenario == Scenario.RESUME_WRONG_TARGET ? 99 : 38 + which;
                    int comparator = which == 2 && (scenario == Scenario.RESUME_BLOCKED || scenario == Scenario.REPORT_STALE) ? 15 : 0;
                    return record(Map.of("game_time", number(tick), "target", at(block), "components",
                        record(Map.of(BLOCK, record(Map.of("id", text("minecraft:barrel"))),
                            REDSTONE, record(Map.of("comparator", number(comparator)))))));
                }).orElseThrow();
            }
            if (index == 4 && (stage == Stage.RESUME || stage == Stage.REPORT
                    && (op.equals("arm_move") || calls.contains("arm/arm_move")))) {
                calls.add("arm/" + op);
                assertEquals(text("component_settings_command"), cmd.field("kind"));
                var args = assertInstanceOf(DataValue.RecordValue.class, cmd.field("arguments"));
                if (op.equals("arm_move")) {
                    assertEquals(record(Map.of("token", ARM_TOKEN, "input", number(0), "output", number(1))), args);
                    assertEquals(1, Collections.frequency(calls, "arm/arm_move"), "Never reissue a native move");
                    if (scenario == Scenario.ROUTE_ACK_LOST) return null;
                    if (scenario == Scenario.ROUTE_REFUSED) return machineReply(cmd, to, "version_conflict", NULL);
                    return machineReply(cmd, to, "accepted", record(Map.of("action", ARM_ACTION)));
                }
                assertEquals("arm_status", op);
                assertTrue(args.fields().isEmpty());
                boolean moving = scenario == Scenario.ROUTE_STILL_MOVING || scenario == Scenario.ROUTE_MOVING_THEN_DONE
                    && Collections.frequency(calls, "arm/arm_status") == 1;
                return machineReply(cmd, to, "ok", record(Map.of("action",
                    scenario == Scenario.ROUTE_OTHER_ACTION ? text("other-native-action") : ARM_ACTION,
                    "transfer", text(moving ? "moving" : "transfer_completed"))));
            }
            if (index >= CHECKS.length) return filterReply(index, to, op, cmd);
            var machine = CHECKS[index];
            calls.add(machine + "/" + op);
            if (index < 4) {
                assertTrue(Set.of("status", "sample").contains(op), "no device writes");
                if (index == 1 && scenario == Scenario.NO_READ_RESULT) return null;
                String component = index < 2 ? BLOCK : index == 2 ? REDSTONE : FLUID;
                var required = index == 2 ? Set.of(BLOCK, component) : Set.of(component);
                var reader = new ReaderSamplingProtocol(new UUID(0, index), new UUID(1, index), to, required);
                return reader.respond(true, cmd, selected -> {
                    assertEquals(required, selected);
                    var observation = switch (index) {
                        case 0 -> record(Map.of("id", text("create:brass_funnel"),
                            "state", record(Map.of("powered", text(scenario == Scenario.GATE_REOPENS
                                && Collections.frequency(calls, "item_gate/sample") > 1 ? "false" : "true")))));
                        case 1 -> record(Map.of("id", text("create:fluid_valve"),
                            "state", record(Map.of("enabled", text(scenario == Scenario.OPEN_VALVE ? "true" : "false")))));
                        case 2 -> switch (scenario) {
                            case DENIED_COMPONENT -> new DataValue.ErrorValue("access_denied", "");
                            case ABSENT_COMPARATOR -> record(Map.of("comparator", new DataValue.ErrorValue("unsupported", "")));
                            default -> record(Map.of("comparator", number(
                                scenario == Scenario.ITEM_IN_LAST_SLOT || scenario == Scenario.DEPOT_REMAINDER ? 1 : 0)));
                        };
                        default -> list(record(Map.of("tank", number(0), "amount", number(scenario == Scenario.FLUID_LEFT ? 125 : 0))));
                    };
                    var block = scenario == Scenario.DEPOT_CLEAR || scenario == Scenario.DEPOT_REMAINDER ? "create:depot" : "minecraft:barrel";
                    var values = index == 2 ? record(Map.of(BLOCK, record(Map.of("id", text(block))), component, observation))
                        : record(Map.of(component, observation));
                    return record(Map.of("game_time", number(tick), "target", at(index == 3 && scenario == Scenario.WRONG_TARGET ? 99 : 30 + index),
                        "components", values));
                }).orElseThrow();
            }
            assertEquals(text("component_settings_command"), cmd.field("kind"));
            var args = assertInstanceOf(DataValue.RecordValue.class, cmd.field("arguments"));
            if (index == 5 && op.equals("status")) {
                assertEquals(record(Map.of("field", text("mode"))), args);
                return machineReply(cmd, to, "ok", record(Map.of("device", text("create:deployer"), "field", text("mode"),
                    "value", text(deployerMode), "token", MODE_TOKEN)));
            }
            if (index == 5 && op.equals("set")) {
                assertEquals(record(Map.of("field", text("mode"), "token", MODE_TOKEN, "value", text("punch"))), args);
                assertEquals("use", deployerMode, "A mode must not be submitted twice");
                deployerMode = "punch";
                return machineReply(cmd, to, "applied", NULL);
            }
            assertEquals(index == 4 ? "arm_status" : "deployer_status", op, "No motion or processing command in the switch");
            assertTrue(args.fields().isEmpty());
            DataValue.RecordValue state = index == 4
                ? scenario == Scenario.MISSING_HELD ? record(Map.of("phase", text("search_inputs"), "transfer", text("idle")))
                    : record(Map.of("phase", text("search_inputs"), "transfer", text("idle"),
                        "held", scenario == Scenario.ARM_HOLDING ? record(Map.of("count", number(1))) : NULL,
                        "token", ARM_TOKEN,
                        "inputs", list(visiblePoint(0, 1, "ready")),
                        "outputs", list(visiblePoint(0, 2, "ready"), visiblePoint(1,
                            scenario == Scenario.ARM_POINT_MOVED && Collections.frequency(calls, "arm/arm_status") > 1 ? 4 : 3, "ready"))))
                : record(Map.of("phase", text("waiting"), "process", text(scenario == Scenario.DEPLOYER_PENDING ? "waiting_for_material" : "idle"),
                    "locked", new DataValue.BooleanValue(true),
                    "mode", text(scenario == Scenario.DEPLOYER_MODE_MISMATCH ? "use" : deployerMode), "held", record(Map.of("item", text("tool")))));
            return machineReply(cmd, to, "ok", state);
        }

        private DataValue.RecordValue machineReply(DataValue.RecordValue cmd, DataValue.RecordValue to, String code, DataValue state) {
            return record(Map.of("kind", text("component_settings_reply"), "version", number(1), "target", to,
                "request", cmd.field("request"), "code", text(code), "state", state));
        }

        private DataValue.RecordValue filterReply(int index, DataValue.RecordValue to, String op, DataValue.RecordValue cmd) {
            assertTrue(index == 6 || index == 7);
            assertEquals(text("virtual_filter_command"), cmd.field("kind"));
            String side = index == 6 ? "item" : "fluid";
            calls.add(side + "_filter/" + op);
            var args = assertInstanceOf(DataValue.RecordValue.class, cmd.field("arguments"));
            var rule = rule(side, scenario == Scenario.PANEL_TWO
                ? index == 6 ? "minecraft:gold_ingot" : "minecraft:lava"
                : index == 6 ? "minecraft:iron_ingot" : "minecraft:water");
            String code;
            if (op.equals("status")) {
                assertTrue(args.fields().isEmpty());
                code = "ok";
            } else {
                assertEquals(rule, args.field("rule"));
                assertEquals(filterState(index), args.field("state"), "No stale or cross-device credentials");
                if (op.equals("set")) {
                    assertNull(installedRules.put(index, rule), "No duplicate filter submission");
                    if (index == 7 && scenario == Scenario.FILTER_REPLY_LOST) return null;
                    code = "applied";
                } else {
                    assertEquals("verify", op);
                    assertEquals(rule, installedRules.get(index));
                    code = index == 6 && scenario == Scenario.FILTER_CHANGED ? "version_conflict" : "verified";
                }
            }
            return record(Map.of("kind", text("virtual_filter_reply"), "version", number(1), "target", to,
                "request", cmd.field("request"), "code", text(code), "state", filterState(index)));
        }

        private DataValue.RecordValue filterState(int index) {
            return record(Map.of("target_id", text(new UUID(0, index).toString()), "session", text(new UUID(1, index).toString()),
                "revision", number(installedRules.containsKey(index) ? 1 : 0)));
        }
    }

    private static DataValue.RecordValue route() {
        return record(Map.of("input", point(0, 1), "output", point(1, 3)));
    }
    private static DataValue.RecordValue point(int index, int offset) {
        return record(Map.of("index", number(index), "offset", at(offset), "type", text("create:depot")));
    }
    private static DataValue.RecordValue visiblePoint(int index, int offset, String status) {
        var fields = new LinkedHashMap<>(point(index, offset).fields());
        fields.put("status", text(status));
        return record(fields);
    }
    private static DataValue.RecordValue rule(String domain, String id) {
        return record(Map.of("domain", text(domain), "mode", text("allow"), "matchers",
            list(record(Map.of("kind", text("id"), "name", text(id))))));
    }
    private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
    private static DataValue.ListValue list(DataValue... entries) { return new DataValue.ListValue(List.of(entries)); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue number(long value) { return new DataValue.IntegerValue(value); }
    private static DataValue.RecordValue at(int x) { return record(Map.of("x", number(x), "y", number(0), "z", number(0))); }
}
