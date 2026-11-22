package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;

/** Read-only pin observation, deliberately containing no locals, program body or payload. */
public record XBusPortState(UUID computer, UUID flow, String pin, int cursor, Mode mode, Optional<String> source, long operation) {
    public enum Mode { IDLE, PAUSED, READ, WRITE, READABLE, COMPLETE, FAILED }
    public XBusPortState {
        Objects.requireNonNull(computer); Objects.requireNonNull(flow); Objects.requireNonNull(pin);
        Objects.requireNonNull(mode); Objects.requireNonNull(source);
        if (pin.isBlank() || cursor < 0 || operation < cursor) throw new IllegalArgumentException("Invalid XBus pin observation");
    }
    public XBusPortState(UUID computer, UUID flow, String pin, int cursor, Mode mode, Optional<String> source) {
        this(computer, flow, pin, cursor, mode, source, cursor);
    }
    public static Optional<XBusPortState> of(FlowBlockHost.Saved saved, HardwarePins hardware, String pin) {
        if (!hardware.hasXBus(pin)) return Optional.empty();
        var state = saved.pinState(pin);
        var step = state.cursor() < saved.program().steps().size() ? saved.program().steps().get(state.cursor()) : null;
        Mode mode = state.paused() ? Mode.PAUSED : switch (state.status()) {
            case COMPLETE -> Mode.COMPLETE;
            case FAILED -> Mode.FAILED;
            case WAITING_READ -> pin.equals(FlowXBus.pin(saved.program(), state)) ? Mode.READ : Mode.IDLE;
            case WAITING_WRITE -> pin.equals(FlowXBus.pin(saved.program(), state)) ? Mode.WRITE : Mode.IDLE;
            case WAITING_READABLE -> pin.equals(FlowXBus.pin(saved.program(), state)) ? Mode.READABLE : Mode.IDLE;
            default -> Mode.IDLE;
        };
        return Optional.of(new XBusPortState(state.computer(), state.flow(), pin, state.cursor(), mode,
            Optional.ofNullable(step).map(FlowProgram.Step::sourceId), state.operation()));
    }
}
