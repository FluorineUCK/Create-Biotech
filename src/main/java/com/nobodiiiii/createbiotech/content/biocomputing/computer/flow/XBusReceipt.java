package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Evidence of one consumed rendezvous, not queued input. The complete cohort is frozen at consumption. */
public record XBusReceipt(Position writer, Position reader, DataValue.RecordValue payload,
        List<Position> otherWriters, List<Position> otherReaders) {
    public record Position(UUID computer, UUID flow, int cursor, String pin, long generation, long operation, int slot, long incarnation, int instruction) {
        public Position {
            Objects.requireNonNull(computer); Objects.requireNonNull(flow); FlowProgram.name(pin);
            if (instruction < -1 || cursor < 0 || generation < 0 || operation < cursor || slot < 0 || incarnation < 0 || slot == 0 && incarnation != 0)
                throw new IllegalArgumentException("Invalid XBus position/generation/operation/slot");
        }
        public Position(UUID computer, UUID flow, int cursor, String pin, long generation, long operation, int slot,long incarnation) {this(computer,flow,cursor,pin,generation,operation,slot,incarnation,-1);}
        public Position(UUID computer, UUID flow, int cursor, String pin, long generation, long operation) { this(computer, flow, cursor, pin, generation, operation, 0, 0); }
        public Position(UUID computer, UUID flow, int cursor, String pin, long generation) { this(computer, flow, cursor, pin, generation, cursor); }
        public Position(UUID computer, UUID flow, int cursor, String pin) { this(computer, flow, cursor, pin, 0); }
        public boolean owns(FlowRuntime.Checkpoint state) { return computer.equals(state.computer()) && flow.equals(state.flow()); }
        public DataValue.RecordValue value() {
            var fields = new LinkedHashMap<String, DataValue>(Map.of("computer", new DataValue.TextValue(computer.toString()),
                "flow", new DataValue.TextValue(flow.toString()), "cursor", new DataValue.IntegerValue(cursor), "pin", new DataValue.TextValue(pin),
                "generation", new DataValue.IntegerValue(generation)));
            if(instruction>=0)fields.put("instruction",new DataValue.IntegerValue(instruction));
            if (operation != cursor) fields.put("operation", new DataValue.IntegerValue(operation));
            if (slot != 0) { fields.put("slot", new DataValue.IntegerValue(slot)); fields.put("incarnation", new DataValue.IntegerValue(incarnation)); }
            return new DataValue.RecordValue(fields);
        }
    }
    public XBusReceipt {
        Objects.requireNonNull(writer); Objects.requireNonNull(reader); Objects.requireNonNull(payload);LocalReferences.portable(payload);
        otherWriters = List.copyOf(otherWriters); otherReaders = List.copyOf(otherReaders);
        var contexts = new HashSet<List<UUID>>();
        var all = new ArrayList<>(List.of(writer, reader)); all.addAll(otherWriters); all.addAll(otherReaders);
        for (var p : all) if (!contexts.add(List.of(p.computer(), p.flow())))
            throw new IllegalArgumentException("One context cannot participate twice in a rendezvous");
        if (writer.computer().equals(reader.computer())) throw new IllegalArgumentException("XBus receipt requires distinct Computers");
    }
    public XBusReceipt(Position writer, Position reader, DataValue.RecordValue payload) { this(writer, reader, payload, List.of(), List.of()); }
    public boolean grouped() { return !otherWriters.isEmpty() || !otherReaders.isEmpty(); }
    public List<Position> writers() { var all = new ArrayList<>(List.of(writer)); all.addAll(otherWriters); return List.copyOf(all); }
    public List<Position> readers() { var all = new ArrayList<>(List.of(reader)); all.addAll(otherReaders); return List.copyOf(all); }
    public List<Position> positions() { var all = new ArrayList<>(writers()); all.addAll(readers()); return List.copyOf(all); }
    public List<Position> local(UUID computer) { return positions().stream().filter(p -> p.computer().equals(computer)).toList(); }
    public List<Position> remote(UUID computer) { return positions().stream().filter(p -> !p.computer().equals(computer)).toList(); }
    public boolean writing(Position position) { return writers().contains(position); }
    public Position own(FlowRuntime.Checkpoint state) {
        return positions().stream().filter(p -> p.owns(state)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Receipt belongs to another flow"));
    }
    public Position peer(FlowRuntime.Checkpoint state) { return own(state).equals(writer) ? reader : writer; }
    public Position own(UUID computer) {
        if (local(computer).size() == 1) return local(computer).getFirst();
        throw new IllegalArgumentException("Receipt belongs to another Computer");
    }
    public Position peer(UUID computer) { return own(computer).equals(writer) ? reader : writer; }
    public DataValue.RecordValue value() {
        var fields = new LinkedHashMap<String, DataValue>(Map.of("writer", writer.value(), "reader", reader.value(), "payload", payload));
        if (grouped()) {
            fields.put("other_writers", new DataValue.ListValue(otherWriters.stream().map(p -> (DataValue)p.value()).toList()));
            fields.put("other_readers", new DataValue.ListValue(otherReaders.stream().map(p -> (DataValue)p.value()).toList()));
        }
        return new DataValue.RecordValue(fields);
    }
    public void validate(FlowProgram program, FlowRuntime.Checkpoint state) {
        var own = own(state);
        if (state.entry().isEmpty() != (own.slot() == 0)) throw new IllegalArgumentException("Receipt task kind differs from its slot");
        if (own.operation() >= state.operation()) throw new IllegalArgumentException("Receipt is not behind the consumed operation");
        if (own.cursor() >= program.steps().size()) throw new IllegalArgumentException("Receipt source is outside the installed flow");
        var op=FlowXBus.at(program,own.cursor(),own.instruction());
        boolean valid=op.pin().equals(own.pin()) && (writing(own)?op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.WRITE:op.kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.READ && op.payload().accepts(payload));
        if (!valid) throw new IllegalArgumentException("Receipt differs from installed read/write operation");
    }
}
