package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.device.carrier.WitchCarrierStage;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.WitchReceiverFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.PeripheralCaptureService;
import com.nobodiiiii.createbiotech.content.biocomputing.network.EndpointConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.PeerDiscovery;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;

/** Menu-scoped replay/revision fence. The Behaviour remains the sole runtime authority. */
public final class ComputerControlSession {
	public enum Action {
		REFRESH, RUN, START_PAUSED, PAUSE, RESUME, STEP, RESET, READ_PERIPHERALS,
		CONFIGURE_PERIOD, DISABLE_PERIOD, READ_PERIOD, CLEAR_PERIOD,
		CONFIGURE_ENDPOINT, DISABLE_ENDPOINT, CLEAR_ENDPOINT, READ_ENDPOINT, DISCOVER_PEERS, READ_COMMUNICATION, READ_TERMINAL,
		TERMINAL_CLICK, TERMINAL_SUBMIT, READ_WIRING, ADD_WIRING, REMOVE_WIRING, READ_LOG, SET_WIRING_ENABLED, READ_NETWORK,
		READ_SERIAL, CONFIGURE_SERIAL, CONFIGURE_SERIAL_GATEWAY, CLEAR_SERIAL_GATEWAY, TERMINAL_CLAIM,
        READ_SIMPLE_IO, CONFIGURE_SIMPLE_IO, CLEAR_SIMPLE_IO, CONFIGURE_SIMPLE_IO_GATEWAY, CLEAR_SIMPLE_IO_GATEWAY, READ_TASK_DEBUG, TERMINAL_KEY, TERMINAL_KEY_STATE, READ_RADIO, CONFIGURE_RADIO, CONFIGURE_CARRIER_INPUT, CONFIGURE_BREAKPOINT_OUTPUT, RESET_BREAKPOINT, READ_INSTRUMENT, CONFIGURE_INSTRUMENT, CONFIGURE_WITCH_FILTER, CONFIGURE_WITCH_CARRIER, READ_PANEL_SOCKET, CONFIGURE_RECEIVE_LOOP;
        public boolean instrument(){return this == READ_INSTRUMENT || this == CONFIGURE_INSTRUMENT;}
        public boolean radio() { return this == CONFIGURE_RECEIVE_LOOP || this == CONFIGURE_WITCH_CARRIER || this == CONFIGURE_WITCH_FILTER || this == READ_RADIO || this == CONFIGURE_RADIO || this == CONFIGURE_CARRIER_INPUT; }
        public boolean carriesInputs() {
            return switch (this) {
                case RUN, START_PAUSED, CONFIGURE_PERIOD, CONFIGURE_ENDPOINT, TERMINAL_CLICK, TERMINAL_SUBMIT,
                    TERMINAL_KEY, TERMINAL_KEY_STATE, READ_TASK_DEBUG, TERMINAL_CLAIM, ADD_WIRING, REMOVE_WIRING,
                    READ_PANEL_SOCKET, SET_WIRING_ENABLED, CONFIGURE_SERIAL, CONFIGURE_SERIAL_GATEWAY, CLEAR_SERIAL_GATEWAY,
                    CONFIGURE_SIMPLE_IO, CONFIGURE_SIMPLE_IO_GATEWAY, CLEAR_SIMPLE_IO_GATEWAY, CONFIGURE_RECEIVE_LOOP, CONFIGURE_RADIO, CONFIGURE_CARRIER_INPUT, CONFIGURE_WITCH_FILTER, CONFIGURE_WITCH_CARRIER, CONFIGURE_BREAKPOINT_OUTPUT, CONFIGURE_INSTRUMENT -> true;
                default -> false;
            };
        }
        public boolean terminalIntent() { return this==TERMINAL_CLICK || this==TERMINAL_SUBMIT || this==TERMINAL_CLAIM || this==TERMINAL_KEY; }
        public boolean signal() { return this == READ_SIMPLE_IO || this == CONFIGURE_SIMPLE_IO || this == CLEAR_SIMPLE_IO || this == CONFIGURE_SIMPLE_IO_GATEWAY || this == CLEAR_SIMPLE_IO_GATEWAY; }
		public boolean serial() { return this == READ_PANEL_SOCKET || this == CONFIGURE_BREAKPOINT_OUTPUT || this == RESET_BREAKPOINT || this == READ_SERIAL || this == CONFIGURE_SERIAL || this == CONFIGURE_SERIAL_GATEWAY || this == CLEAR_SERIAL_GATEWAY; }
		public boolean periodic() { return this == CONFIGURE_PERIOD || this == DISABLE_PERIOD || this == READ_PERIOD || this == CLEAR_PERIOD; }
		public boolean endpoint() { return this == CONFIGURE_ENDPOINT || this == DISABLE_ENDPOINT || this == CLEAR_ENDPOINT || this == READ_ENDPOINT || this == DISCOVER_PEERS || this == READ_COMMUNICATION || this == READ_NETWORK || wiring(); }
		public boolean wiring() { return this == READ_WIRING || this == ADD_WIRING || this == REMOVE_WIRING || this == SET_WIRING_ENABLED; }
		public Optional<String> capturedInputName() {
			return switch (this) {
				case READ_PERIPHERALS -> Optional.of("peripherals");
				case READ_NETWORK -> Optional.of("network");
				default -> Optional.empty();
			};
		}
	}
	public record Answer(String code, long revision, String detail, Optional<ComputerRuntime.Snapshot> snapshot,
		Optional<DataValue.RecordValue> capturedInputs, Optional<DataValue.RecordValue> dedicatedStatus) {
		public Answer(String code, long revision, String detail, Optional<ComputerRuntime.Snapshot> snapshot,
			Optional<DataValue.RecordValue> capturedInputs) { this(code, revision, detail, snapshot, capturedInputs, Optional.empty()); }
	}
	private final UUID nonce;
	private long lastIntent;
	private record Publication(long view, long revision, Optional<TerminalDefinition> definition, DataValue.RecordValue roots, Optional<String> question) {}
    private record TaskDebugPage(long view, long revision, java.util.List<UUID> ids) {}
    private TaskDebugPage taskDebugPage;
	private Publication terminalPublication;
    private long lastKeyStateIntent;
    void updateKeyState(long intent, long revision, DataValue.RecordValue state, ComputerBehaviour computer) {
        if (intent <= lastKeyStateIntent) return;
        lastKeyStateIntent = intent;
        if (!state.fields().keySet().equals(java.util.Set.of("view", "bits"))
            || !(state.field("view") instanceof DataValue.IntegerValue view)
            || !(state.field("bits") instanceof DataValue.IntegerValue bits)) return;
        // Release is safe even when the observation was consumed or invalidated. It clears only this menu's state.
        if (bits.value() == 0 || terminalPublication != null && view.value() == terminalPublication.view()
                && revision == terminalPublication.revision() && revision == computer.programRevision())
            computer.updateTerminalKeys(nonce, com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalKey.requireBits(bits.value()));
    }
	public ComputerControlSession(UUID nonce) { this.nonce = Objects.requireNonNull(nonce); }
	public Optional<Answer> apply(UUID nonce, long intent, long revision, Action action,
		Supplier<DataValue.RecordValue> inputs, ComputerBehaviour computer) {
		return apply(nonce, intent, revision, action, inputs, computer, () -> {});
	}
	public Optional<Answer> apply(UUID nonce, long intent, long revision, Action action,
		Supplier<DataValue.RecordValue> inputs, ComputerBehaviour computer, Runnable authorizeEndpoint) {
        return apply(nonce, intent, revision, action, inputs, computer, authorizeEndpoint, ignored -> { throw new IllegalArgumentException("open_from_controller"); });
    }
    public Optional<Answer> apply(UUID nonce, long intent, long revision, Action action,
        Supplier<DataValue.RecordValue> inputs, ComputerBehaviour computer, Runnable authorizeEndpoint,
        java.util.function.Function<SerialControlData.GatewayConfiguration, com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding> authorizeGateway) {
        return apply(nonce, intent, revision, action, inputs, computer, authorizeEndpoint, authorizeGateway,
            ignored -> { throw new IllegalArgumentException("open_from_controller"); });
    }
    public Optional<Answer> apply(UUID nonce, long intent, long revision, Action action,
        Supplier<DataValue.RecordValue> inputs, ComputerBehaviour computer, Runnable authorizeEndpoint,
        java.util.function.Function<SerialControlData.GatewayConfiguration, com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialGatewayBinding> authorizeGateway,
        java.util.function.Function<com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.GatewayConfiguration,
            com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterPortBinding> authorizeSignalGateway) {
		if (!this.nonce.equals(nonce) || intent <= lastIntent) return Optional.empty();
		lastIntent = intent;
        if (action != Action.READ_TASK_DEBUG) taskDebugPage = null;
		if (action != Action.READ_TERMINAL && !action.terminalIntent()) terminalPublication = null;
		if (action != Action.REFRESH && action != Action.READ_TERMINAL && action != Action.READ_LOG && action != Action.READ_INSTRUMENT && action != Action.READ_RADIO && revision != computer.programRevision()) {
			if (action.terminalIntent()) terminalPublication = null;
			return answer(computer, action, "stale", "Installed program or settings changed; refresh before controlling it");
		}
		try {
			if (action == Action.READ_LOG) authorizeEndpoint.run(); // Physical menu permission only; does not open or require a communication port.
			if (action.radio() || action.serial() || action.signal() || action.instrument()) authorizeEndpoint.run();
			if (action.endpoint()) { authorizeEndpoint.run(); computer.requireEndpointAvailable(); }
			switch (action) {
                case READ_TASK_DEBUG -> {
                    authorizeEndpoint.run(); // Local physical editor access, not network membership.
                    var request = TaskDebugView.decode(inputs.get());
                    var saved = computer.taskDebugSnapshot();
                    if (saved.isEmpty()) return taskDebugAnswer(computer, "unavailable", "No available installed flow", Optional.empty());
                    if (request instanceof TaskDebugView.PageRequest page) {
                        var listing = TaskDebugView.directory(saved.orElseThrow(), page);
                        taskDebugPage = new TaskDebugPage(intent, computer.programRevision(), listing.ids());
                        return taskDebugAnswer(computer, "ok", "", Optional.of(listing.value()));
                    }
                    var selection = (TaskDebugView.TaskRequest) request;
                    var current = taskDebugPage;
                    if (current == null || current.view() != selection.view() || current.revision() != computer.programRevision()
                            || selection.row() >= current.ids().size())
                        return taskDebugAnswer(computer, "stale", "Refresh the local task list", Optional.empty());
                    UUID id = current.ids().get(selection.row());
                    var task = TaskDebugView.task(saved.orElseThrow(), id);
                    return task.isEmpty() ? taskDebugAnswer(computer, "stale", "Task no longer exists; refresh the list", Optional.empty())
                        : taskDebugAnswer(computer, "ok", "", task);
                }

                case TERMINAL_CLAIM -> {
                    var publication = terminalPublication; terminalPublication = null;
                    var claim = TerminalTaskClaim.decode(inputs.get());
                    if (publication == null || claim.view() != publication.view()) return terminalClaimAnswer(computer, "invalid", "Task view is missing, stale or already used");
                    if (publication.revision() != computer.programRevision() || revision != publication.revision()) return terminalClaimAnswer(computer, "stale", "Installed program changed");
                    computer.claimFlowTask(claim.resolve(publication.roots()));
                    return terminalClaimAnswer(computer, "ok", "claimed");
                }
                case CONFIGURE_WITCH_CARRIER -> computer.configureWitchCarrier(revision,
                    WitchCarrierStage.Setting.decode(inputs.get()));
                case CONFIGURE_WITCH_FILTER -> computer.configureWitchFilter(revision,
                    WitchReceiverFilter.Setting.decode(inputs.get()));
                case READ_RADIO -> { }
                case CONFIGURE_RECEIVE_LOOP -> computer.configureReceiveLoop(revision, RadioControlData.CarrierInput.decode(inputs.get()).face());
                case CONFIGURE_CARRIER_INPUT -> computer.configureCarrierInput(revision, RadioControlData.CarrierInput.decode(inputs.get()).face());
                case CONFIGURE_RADIO -> {
                    var tune = RadioControlData.Tune.decode(inputs.get());
                    computer.configureRadio(revision, tune.preset(), tune.armed());
                }
				case READ_SERIAL -> { }
                case CONFIGURE_BREAKPOINT_OUTPUT -> {
                    var record=inputs.get();
                    if (!record.fields().keySet().equals(java.util.Set.of("pin")) || !(record.field("pin") instanceof DataValue.TextValue pin))
                        throw new IllegalArgumentException("invalid_breakpoint_pin");
                    computer.configureBreakpointOutput(pin.value());
                }
                case RESET_BREAKPOINT -> computer.resetBreakpoint();
                case READ_INSTRUMENT -> { }
                case CONFIGURE_INSTRUMENT -> computer.configureInstrument(inputs.get());
                case READ_SIMPLE_IO -> { }
                case CONFIGURE_SIMPLE_IO -> computer.configureSignal(com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.Configuration.read(inputs.get()));
                case CLEAR_SIMPLE_IO -> computer.clearSignals();
                case CONFIGURE_SIMPLE_IO_GATEWAY -> {
                    var config = com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.GatewayConfiguration.read(inputs.get());
                    computer.configureSignalGateway(config.pin(), Optional.of(authorizeSignalGateway.apply(config)));
                }
                case CLEAR_SIMPLE_IO_GATEWAY -> computer.configureSignalGateway(
                    com.nobodiiiii.createbiotech.content.biocomputing.network.signal.SignalConnections.clearGateway(inputs.get()), Optional.empty());
				case CONFIGURE_SERIAL -> computer.configureSerial(SerialControlData.Configuration.decode(inputs.get()));
                case CONFIGURE_SERIAL_GATEWAY -> {
                    var config = SerialControlData.GatewayConfiguration.decode(inputs.get());
                    computer.configureSerialGateway(config.port(), Optional.of(authorizeGateway.apply(config)));
                }
                case CLEAR_SERIAL_GATEWAY -> computer.configureSerialGateway(SerialControlData.clearGateway(inputs.get()), Optional.empty());
				case READ_LOG -> {
					return Optional.of(new Answer("ok", computer.programRevision(), "", Optional.empty(), Optional.empty(),
						Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.diagnostics.RecentEventWire.statusValue(
							computer.recentEvents(), computer.hardware().limits().archive()))));
				}
				case READ_TERMINAL -> {
					terminalPublication = null;
					var observed = computer.terminalStatus();
					long observedRevision = computer.programRevision();
					try { ProgramEditorWire.encodeValue(observed, computer.hardware().limits().archive()); }
					catch (IllegalArgumentException tooLarge) {
						terminalPublication = null;
						return Optional.of(new Answer("too_large", computer.programRevision(), String.valueOf(tooLarge.getMessage()),
							Optional.empty(), Optional.empty(), Optional.of(new DataValue.RecordValue(java.util.Map.of()))));
					}
					var status = TerminalStatusView.decode(observed);
					terminalPublication = new Publication(intent, observedRevision, status.terminal(), status.roots(), status.question().map(TerminalStatusView.Question::token));
					return Optional.of(new Answer("ok", observedRevision, "", Optional.empty(), Optional.empty(), Optional.of(observed)));
				}
                case TERMINAL_KEY_STATE -> throw new IllegalArgumentException("Keyboard samples use the terminal observation route");
                case TERMINAL_KEY -> {
                    var publication = terminalPublication; terminalPublication = null;
                    var press = TerminalKeyPress.decode(inputs.get());
                    if (publication == null || press.view() != publication.view())
                        return keyAnswer(computer, "invalid", "Terminal view is missing, stale or already used");
                    if (publication.revision() != computer.programRevision() || revision != publication.revision())
                        return keyAnswer(computer, "stale", "Installed program changed; refresh the terminal");
                    return keyAnswer(computer, computer.pressTerminalKey(press.key()), "");
                }
				case TERMINAL_CLICK -> {
					var publication = terminalPublication; terminalPublication = null;
					var click = TerminalClick.decode(inputs.get());
					if (publication == null || click.view() != publication.view())
						return terminalAnswer(computer, "invalid", "Terminal view is missing, stale or already used");
					if (publication.revision() != computer.programRevision() || revision != publication.revision())
						return terminalAnswer(computer, "stale", "Installed program changed; refresh terminal before clicking");
					var invocation = TerminalInteraction.resolve(publication.definition().orElseThrow(() -> new IllegalArgumentException("No terminal layout is defined")), publication.roots(), click.instance());
					computer.beginTerminal(invocation);
					return terminalAnswer(computer, "ok", "accepted");
				}
				case TERMINAL_SUBMIT -> {
					var publication = terminalPublication; terminalPublication = null;
					var submit = TerminalSubmit.decode(inputs.get());
					if (publication == null || submit.view() != publication.view()) return terminalSubmitAnswer(computer, "invalid", "Terminal view is missing, stale or already used");
					if (publication.revision() != computer.programRevision() || revision != publication.revision()) return terminalSubmitAnswer(computer, "stale", "Installed program changed; refresh terminal before submitting");
					if (TerminalSubmit.QUESTION.equals(submit.instance())) {
						computer.answerTerminal(publication.question().orElseThrow(() -> new IllegalArgumentException("No question in this terminal view")), submit.text());
						return terminalSubmitAnswer(computer, "ok", "answered");
					}
					var invocation = TerminalInteraction.resolveSubmit(publication.definition().orElseThrow(() -> new IllegalArgumentException("No terminal layout is defined")), publication.roots(), submit.instance(), submit.text());
					computer.beginTerminal(invocation); return terminalSubmitAnswer(computer, "ok", "accepted");
				}
				case CONFIGURE_ENDPOINT -> computer.configureEndpoint(EndpointConfiguration.decode(inputs.get()));
				case DISABLE_ENDPOINT -> computer.disableEndpoint();
				case CLEAR_ENDPOINT -> computer.clearEndpoint();
				case READ_ENDPOINT -> { }
				case READ_COMMUNICATION -> {
					var observed = computer.communicationStatus();
					return Optional.of(new Answer("ok", computer.programRevision(), "", Optional.empty(), Optional.empty(), Optional.of(observed)));
				}
				case READ_PANEL_SOCKET, DISCOVER_PEERS, READ_WIRING, ADD_WIRING, REMOVE_WIRING, SET_WIRING_ENABLED -> {
					var observed = inputs.get();
					return Optional.of(new Answer("ok", computer.programRevision(), "", Optional.empty(), Optional.empty(), Optional.of(observed)));
				}
				case READ_PERIPHERALS -> {
					var captured = Objects.requireNonNull(inputs.get());
					return Optional.of(new Answer("ok", computer.programRevision(), "", computer.snapshot(), Optional.of(captured)));
				}
				case READ_NETWORK -> {
					return Optional.of(new Answer("ok", computer.programRevision(), "", Optional.empty(), Optional.of(Objects.requireNonNull(inputs.get()))));
				}
				case REFRESH -> { }
				case CONFIGURE_PERIOD -> { var settings = PeriodicControlData.decode(inputs.get()); computer.configurePeriodic(settings.intervalTicks(), settings.inputs()); }
				case DISABLE_PERIOD -> computer.disablePeriodic();
				case CLEAR_PERIOD -> computer.clearPeriodic();
				case READ_PERIOD -> computer.observePeriodic();
				case RUN, START_PAUSED -> { computer.begin(inputs.get()); computer.setPaused(action == Action.START_PAUSED); }
				case PAUSE -> computer.setPaused(true);
				case RESUME -> computer.setPaused(false);
				case STEP -> { computer.setPaused(true); computer.step(); }
				case RESET -> computer.reset();
			}
			return answer(computer, action, "ok", action.radio() || action.endpoint() || action.serial() || action.signal() || action.instrument() ? "" : computer.diagnostic().map(d -> d.code() + ": " + d.message()).orElse(""));
		} catch (PeerDiscovery.DiscoveryException discovery) {
			return answer(computer, action, discovery.code(), discovery.getMessage());
		} catch (PeripheralCaptureService.CaptureException capture) {
			return answer(computer, action, capture.code(), capture.getMessage());
		} catch (ComputerBlockHost.HostException unavailable) {
			return answer(computer, action, "unavailable", unavailable.getMessage());
		} catch (ComputationException invalid) {
            String code = "invalid";
            if ((action.terminalIntent()) && computer.flowSnapshot().isPresent())
                code = switch (invalid.code()) { case BUSY -> "busy"; case MEMORY_LIMIT -> "memory_limit"; case TYPE_MISMATCH -> "type_mismatch"; default -> "invalid"; };
            return answer(computer, action, code, invalid.getMessage());
		} catch (IllegalArgumentException invalid) {
			return answer(computer, action, "invalid", invalid.getMessage());
		}
	}
	public void invalidateTerminalPublication() { terminalPublication = null; }
    private static Optional<Answer> terminalClaimAnswer(ComputerBehaviour computer, String code, String detail) {
        return Optional.of(new Answer(code, computer.programRevision(), detail, Optional.empty(), Optional.empty(),
            Optional.of(new DataValue.RecordValue(java.util.Map.of("terminal_claim", new DataValue.TextValue(code))))));
    }
    private static Optional<Answer> keyAnswer(ComputerBehaviour computer, String code, String detail) {
        return Optional.of(new Answer(code, computer.programRevision(), detail, Optional.empty(), Optional.empty(),
            Optional.of(new DataValue.RecordValue(java.util.Map.of("terminal_key", new DataValue.TextValue(code))))));
    }
	private static Optional<Answer> terminalAnswer(ComputerBehaviour computer, String code, String detail) {
		return Optional.of(new Answer(code, computer.programRevision(), detail, Optional.empty(), Optional.empty(),
			Optional.of(new DataValue.RecordValue(java.util.Map.of("terminal_click", new DataValue.TextValue(code))))));
	}
	private static Optional<Answer> terminalSubmitAnswer(ComputerBehaviour computer, String code, String detail) { return Optional.of(new Answer(code, computer.programRevision(), detail, Optional.empty(), Optional.empty(), Optional.of(new DataValue.RecordValue(java.util.Map.of("terminal_submit", new DataValue.TextValue(code)))))); }
    private static Optional<Answer> taskDebugAnswer(ComputerBehaviour computer, String code, String detail,
            Optional<DataValue.RecordValue> value) {
        return Optional.of(new Answer(code, computer.programRevision(), detail, Optional.empty(), Optional.empty(),
            Optional.of(value.orElseGet(() -> new DataValue.RecordValue(java.util.Map.of())))));
    }
	private static Optional<Answer> answer(ComputerBehaviour computer, Action action, String code, String detail) {
        if (action == Action.READ_TASK_DEBUG) return taskDebugAnswer(computer, code, String.valueOf(detail), Optional.empty());
        if (action.instrument()) return Optional.of(new Answer(code,computer.programRevision(),String.valueOf(detail),
            Optional.empty(),Optional.empty(),Optional.of(code.equals("ok") ? computer.instrumentStatus() : new DataValue.RecordValue(java.util.Map.of()))));
        if (action.signal()) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail),
            Optional.empty(), Optional.empty(), Optional.of(code.equals("ok") ? computer.signalStatus() : new DataValue.RecordValue(java.util.Map.of()))));
        if (action.radio()) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail),
            Optional.empty(), Optional.empty(), Optional.of(code.equals("ok") ? computer.radioStatus() : new DataValue.RecordValue(java.util.Map.of()))));
		if (action.serial()) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail),
			Optional.empty(), Optional.empty(), Optional.of(code.equals("ok") ? computer.serialStatus() : new DataValue.RecordValue(java.util.Map.of()))));
		if (action == Action.READ_LOG) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail),
			Optional.empty(), Optional.empty(), Optional.of(new DataValue.RecordValue(java.util.Map.of()))));
		if (action == Action.READ_TERMINAL) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail), Optional.empty(), Optional.empty(), Optional.of(new DataValue.RecordValue(java.util.Map.of()))));
		if (action == Action.TERMINAL_CLICK) return terminalAnswer(computer, code, String.valueOf(detail));
		if (action == Action.TERMINAL_SUBMIT) return terminalSubmitAnswer(computer, code, String.valueOf(detail));
        if (action == Action.TERMINAL_CLAIM) return terminalClaimAnswer(computer, code, String.valueOf(detail));
        if (action == Action.TERMINAL_KEY) return keyAnswer(computer,code,String.valueOf(detail));
		if (action.endpoint()) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail),
			Optional.empty(), Optional.empty(), Optional.of(code.equals("ok") ? computer.endpointStatus() : new DataValue.RecordValue(java.util.Map.of()))));
		if (!action.periodic()) {
			var flow = computer.flowStatus();
			if (flow.isPresent()) return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail), Optional.empty(), Optional.empty(), flow));
		}
		return Optional.of(new Answer(code, computer.programRevision(), String.valueOf(detail), computer.snapshot(), Optional.empty(),
			action.periodic() ? Optional.of(ComputerStatusView.periodic(computer.snapshot().flatMap(ComputerRuntime.Snapshot::periodic))) : Optional.empty()));
	}
}
