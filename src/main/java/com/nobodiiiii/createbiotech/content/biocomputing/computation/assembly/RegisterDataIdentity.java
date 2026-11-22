package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Full, framed type/value identity for data-bearing ISA images, never a summary toString/hashCode.
 * Iterative traversal also preserves arbitrary UTF-16 strings and native NBT tag distinctions. */
final class RegisterDataIdentity {
    private RegisterDataIdentity() {}
    static void append(MessageDigest digest, List<RegisterProgram.Code> code, List<DataType> types, List<DataValue> constants,
            Map<String, Integer> inputs, Map<String, Integer> locals, int endSource) {
        try {
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            text(out, "register-isa-1-data-1"); out.writeInt(endSource); out.writeInt(code.size());
            for (var entry : code) {
                out.writeInt(entry.source()); text(out, entry.location());
                switch (entry.instruction()) {
                    case RegisterProgram.AppendList a -> {out.writeByte(30);ints(out,a.to(),a.from(),a.element());}
                    case RegisterListEdit edit -> {
                        out.writeByte(36); text(out, edit.operation().name()); ints(out, edit.to(), edit.operands().size());
                        for (int operand : edit.operands()) out.writeInt(operand);
                    }
                    case RegisterProgram.MakeList l -> {
                        out.writeByte(21);
                        ints(out, l.to(), l.from().size());
                        for (int r:l.from())out.writeInt(r);
                    }
                    case RegisterProgram.MakeRecord r -> {
                        out.writeByte(22);
                        ints(out, r.to(), r.fields().size());
                        for (var f:r.fields()) {
                            text(out, f.key());
                            out.writeInt(f.from());
                        }
                    }
                    case RegisterProgram.Constant c -> { out.writeByte(0); ints(out, c.to(), c.pool()); }
                    case RegisterProgram.Move m -> { out.writeByte(1); ints(out, m.to(), m.from()); }
                    case RegisterProgram.Alu a -> {
                        out.writeByte(2); text(out, a.operation().name()); ints(out, a.to(), a.from().size());
                        for (int r : a.from()) out.writeInt(r);
                    }
                    case RegisterProgram.Nonnegative n -> { out.writeByte(3); out.writeInt(n.value()); }
                    case RegisterProgram.JumpFalse j -> { out.writeByte(4); ints(out, j.condition(), j.target()); }
                    case RegisterProgram.Jump j -> { out.writeByte(5); out.writeInt(j.target()); }
                    case RegisterProgram.Field f -> { out.writeByte(6); ints(out, f.to(), f.from()); text(out, f.key()); }
                    case RegisterProgram.ListLength l -> { out.writeByte(20); ints(out, l.to(), l.from()); }
                    case RegisterProgram.DynamicIndex i -> { out.writeByte(19); ints(out, i.to(), i.from(), i.indexRegister()); }
                    case RegisterProgram.Index i -> { out.writeByte(7); ints(out, i.to(), i.from(), i.index()); }
                    case RegisterProgram.HasField h -> { out.writeByte(26); text(out,"presence-1"); ints(out,h.to(),h.from(),h.key()); }
                    case RegisterProgram.ValueEquals e -> { out.writeByte(25); text(out, "typed-equality-1"); ints(out, e.to(), e.left(), e.right()); }
                    case RegisterProgram.FormatCheck f -> { out.writeByte(28); text(out,f.fmt());out.writeInt(f.version());ints(out,f.to(),f.from()); }
                    case RegisterProgram.FormatProject f -> { out.writeByte(29); text(out,f.fmt());out.writeInt(f.version());ints(out,f.to(),f.from()); }
                    case RegisterProgram.ValueCheck c -> { out.writeByte(27); text(out, "typed-check-1"); ints(out, c.to(), c.from()); }
                    case RegisterProgram.RandomBelow random -> { out.writeByte(37); text(out, "random-below-1"); ints(out, random.to(), random.bound()); }
                    case RegisterProgram.RandomRange random -> {
                        out.writeByte(41); text(out, "random-range-1"); ints(out, random.to(), random.from(), random.through()); out.writeBoolean(random.decimal());
                    }
                    case RegisterProgram.Correlation c -> { out.writeByte(24); text(out, "correlation-1"); out.writeInt(c.to()); }
                    case RegisterReference r -> {out.writeByte(33);text(out,"local-cell-ref-1");text(out,r.kind().name());ints(out,r.target(),r.value(),r.output());}
                    case RegisterNbt.Instruction n -> { out.writeByte(32); text(out,n.joining()?"byte-joining-1":n.unitWorkspace()?"typed-value-decoding-units-2":n.decoding()?"typed-value-decoding-1":n.encoding()?"typed-value-encoding-1":"native-data-1");text(out,n.kind().name());ints(out,n.to(),n.from(),n.operand()); }
                    case RegisterProgram.TextLetter t -> { out.writeByte(39); ints(out, t.to(), t.from(), t.index()); }
                    case RegisterMonitor m -> { out.writeByte(m.privateLocal() ? 49 : 48); text(out, m.cell()); out.writeBoolean(m.visible()); }
                    case RegisterProgram.DefaultNumber m -> { out.writeByte(54); ints(out, m.to(), m.from()); }
                    case RegisterTimer t -> { out.writeByte(42); text(out, "activity-timer-1"); out.writeInt(t.destination()); }
                    case RegisterPrompt t -> { out.writeByte(43); text(out, "terminal-prompt-1"); out.writeBoolean(t.asks()); out.writeInt(t.register()); }
                    case RegisterKeyDown k -> { out.writeByte(44); out.writeInt(k.destination()); text(out, k.selector()); }
                    case RegisterProgram.TextContains t -> { out.writeByte(40); text(out, "text-contains-ci-1"); ints(out, t.to(), t.text(), t.needle()); }
                    case RegisterProgram.TextNext t -> { out.writeByte(31); ints(out, t.to(), t.from(), t.offset()); }
                    case RegisterProgram.TextConcat t -> { out.writeByte(34); text(out, "text-concat-1"); ints(out, t.to(), t.left(), t.right()); }
                    case RegisterProgram.TextLength t -> { out.writeByte(8); ints(out, t.to(), t.from()); }
                    case RegisterDevices.Instruction d -> {
                        out.writeByte(9); text(out, d.kind().extension()); out.writeInt(d.kind().version());
                        text(out, d.kind().name()); out.writeInt(d.register()); text(out, d.pin());
                    }
                    case RegisterSerial.Instruction d -> { out.writeByte(23);text(out,d.extension());out.writeInt(d.version());text(out,d.kind().name());ints(out,d.input(),d.output());text(out,d.pin());out.writeBoolean(d.claimFaults());if(d.kind()==RegisterSerial.Kind.RECEIVE)out.writeBoolean(d.nativeNbt()); }
                    case RegisterXBus.Instruction d -> {
                        out.writeByte(10); text(out, d.extension()); out.writeInt(d.version());
                        text(out, d.kind().name()); ints(out, d.input(), d.output()); text(out, d.pin());
                    }
                    case RegisterProgram.Sleep s -> { out.writeByte(11); out.writeInt(s.ticks()); }
                    case RegisterProgram.Call c -> { out.writeByte(12); out.writeInt(c.target()); }
                    case RegisterProgram.Return ignored -> out.writeByte(13);
                    case RegisterProgram.TaskWait t -> { out.writeByte(17); text(out, "task-wait-1"); text(out, t.procedure().id()); out.writeLong(t.procedure().revision()); out.writeInt(t.handle()); }
                    case RegisterProgram.TaskClaim t -> { out.writeByte(18); text(out, "task-claim-1"); text(out, t.procedure().id()); out.writeLong(t.procedure().revision()); out.writeInt(t.handle()); out.writeInt(t.outcome()); }
                    case RegisterProgram.TaskStop t -> { out.writeByte(38); out.writeBoolean(t.all()); }
                    case RegisterTaskGroup group -> {
                        out.writeByte(55);text(out,"task-group-v1");out.writeBoolean(group.await());out.writeInt(group.arguments().size());
                        for(int r:group.arguments())out.writeInt(r);
                    }
                    case RegisterEvent event -> {
                        out.writeByte(35); text(out,event.channel()); out.writeBoolean(event.await()); out.writeInt(event.receivers().size());
                        for (var receiver : event.receivers()) { text(out,receiver.id()); out.writeLong(receiver.revision()); }
                    }
                    case RegisterProgram.TryLaunch t -> {
                        out.writeByte(46); text(out, t.procedure().id()); out.writeLong(t.procedure().revision()); out.writeInt(t.started());
                    }
                    case RegisterProgram.TaskStart t -> { out.writeByte(16); text(out, "task-start-1"); text(out, t.procedure().id()); out.writeLong(t.procedure().revision()); bindings(out, t.arguments()); out.writeInt(t.handle()); }
                    case RegisterProgram.Drop d -> { out.writeByte(14); out.writeInt(d.register()); }
                    case RegisterMemoryOp m -> { out.writeByte(15); text(out, m.kind().name()); ints(out, m.cell(), m.register()); }
                }
            }
            out.writeInt(types.size()); for (var type : types) write(out, type);
            out.writeInt(constants.size()); for (var value : constants) write(out, value);
            bindings(out, inputs); bindings(out, locals);
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static void procedures(MessageDigest digest, Collection<RegisterProcedure> procedures) {
        try {
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            text(out, "register-calls-1"); out.writeInt(procedures.size());
            for (var p : procedures) {
                ints(out, p.entry(), p.end()); text(out, p.name()); out.writeLong(p.revision());
                bindings(out, p.inputs()); bindings(out, p.locals()); bindings(out, p.outputs());
                if(p.assembly().isPresent()) { var source=p.assembly().orElseThrow(); text(out,"standard-assembly");out.writeInt(source.version());text(out,source.text()); }
            }
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static void memory(MessageDigest digest, Map<String, ProgramDefinition.Cell> cells) {
        try {
            var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            text(out, "register-memory-1"); out.writeInt(cells.size());
            for (var e : new TreeMap<>(cells).entrySet()) { text(out, e.getKey()); write(out, e.getValue().type()); write(out, e.getValue().initial()); }
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void ints(DataOutputStream out, int... values) throws IOException { for (int value : values) out.writeInt(value); }
    private static void bindings(DataOutputStream out, Map<String, Integer> values) throws IOException {
        out.writeInt(values.size());
        for (var entry : new TreeMap<>(values).entrySet()) { text(out, entry.getKey()); out.writeInt(entry.getValue()); }
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length()); for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
    private static void write(DataOutputStream out, Object root) throws IOException {
        var pending = new ArrayDeque<Object>(); pending.push(root);
        while (!pending.isEmpty()) {
            switch (pending.pop()) {
                case String s -> { out.writeByte(0); text(out, s); }
                case DataType.RefType ref -> {out.writeByte(18);text(out,"local-cell-ref-1");text(out,ref.target().name());out.writeBoolean(ref.writable());}
                case DataType.Semantic s -> { out.writeByte(16); text(out, "recipe"); out.writeInt(s.version()); }
                case DataType.Scalar s -> { out.writeByte(1); text(out, s.name()); }
                case DataType.ListType t -> { out.writeByte(2); pending.push(t.element()); }
                case DataType.MapType t -> { out.writeByte(3); pending.push(t.element()); }
                case DataType.OptionalType t -> { out.writeByte(4); pending.push(t.value()); }
                case DataType.RecordType t -> { out.writeByte(5); named(out, pending, new TreeMap<>(t.fields())); }
                case DataValue.BooleanValue v -> { out.writeByte(6); out.writeBoolean(v.value()); }
                case DataValue.IntegerValue v -> { out.writeByte(7); out.writeLong(v.value()); }
                case DataValue.DecimalValue v -> { out.writeByte(8); out.writeLong(Double.doubleToRawLongBits(v.value())); }
                case DataValue.TextValue v -> { out.writeByte(9); text(out, v.value()); }
                case DataValue.NullValue ignored -> out.writeByte(10);
                case DataValue.ErrorValue v -> { out.writeByte(11); text(out, v.code()); text(out, v.message()); }
                case DataValue.LocalReference ignored -> throw new IllegalArgumentException("Reference constants are not portable");
                case DataValue.RecipeValue v -> { out.writeByte(17); text(out, v.fmt()); out.writeInt(v.version()); pending.push(v.projection()); }
                case DataValue.NbtValue v -> { out.writeByte(12); out.writeInt(v.tagId()); pending.push(v.value()); }
                case DataValue.ListValue v -> {
                    out.writeByte(13); out.writeInt(v.values().size());
                    for (int i = v.values().size() - 1; i >= 0; i--) pending.push(v.values().get(i));
                }
                case DataValue.RecordValue v -> { out.writeByte(14); named(out, pending, v.fields()); }
                case DataValue.MapValue v -> { out.writeByte(15); named(out, pending, v.entries()); }
                default -> throw new IllegalArgumentException("Unsupported image identity component");
            }
        }
    }
    private static void named(DataOutputStream out, Deque<Object> pending, Map<String, ?> values) throws IOException {
        out.writeInt(values.size()); var entries = new ArrayList<>(values.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) { pending.push(entries.get(i).getValue()); pending.push(entries.get(i).getKey()); }
    }
}
