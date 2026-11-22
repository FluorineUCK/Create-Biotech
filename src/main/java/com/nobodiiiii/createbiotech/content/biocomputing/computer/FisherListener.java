package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterEvent;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwareSelection;
import java.util.*;
import java.util.function.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Two physical channels. Each channel persists its binding, last writer identity and a single pending bit;
 * additional edges coalesce until admission succeeds. Never buffers payload or executes a handler. */
final class FisherListener {
    static final String ROOT = "BiocomputingFisher";
    private static final int CHANNELS = 2;
    private static final ResourceLocation VILLAGER = ResourceLocation.withDefaultNamespace("villager");
    private static final ResourceLocation FISHERMAN = ResourceLocation.withDefaultNamespace("fisherman");

    static boolean present(HardwareSelection selection) {
        return selection.ready() && selection.resident().filter(r -> VILLAGER.equals(r.entityType())
            && FISHERMAN.equals(r.profession())).isPresent();
    }

    record Binding(String pin, String mode, String entry) {
        Binding {
            if (pin.isBlank() || pin.length() > HardwarePins.MAX_NAME
                    || !Set.of("ready", "rising", "falling").contains(mode)) throw new IllegalArgumentException("invalid_binding");
            RegisterEvent.requireChannel(entry);
        }
        boolean supported(HardwarePins pins) { return ready() ? pins.hasXBus(pin) : pins.hasSimpleIo(pin); }
        boolean ready() { return mode.equals("ready"); }
    }

    record Writer(UUID owner, UUID flow, String pin, long operation) {
        Writer {
            Objects.requireNonNull(owner); Objects.requireNonNull(flow);
            if (pin.isBlank() || pin.length()>HardwarePins.MAX_NAME || operation<0) throw new IllegalArgumentException("invalid_writer");
        }
        static Writer of(com.nobodiiiii.createbiotech.content.biocomputing.network.XBusPortState state) {
            return new Writer(state.computer(),state.flow(),state.pin(),state.operation());
        }
        CompoundTag save() {
            var tag=new CompoundTag(); tag.putUUID("Owner",owner); tag.putUUID("Flow",flow);
            tag.putString("Pin",pin); tag.putLong("Operation",operation); return tag;
        }
        static Writer load(CompoundTag tag) {
            if (!tag.hasUUID("Owner") || !tag.hasUUID("Flow") || !tag.contains("Operation",Tag.TAG_LONG)) throw new IllegalArgumentException("invalid_writer");
            return new Writer(tag.getUUID("Owner"),tag.getUUID("Flow"),tag.getString("Pin"),tag.getLong("Operation"));
        }
    }

    private static final class Channel {
        final Binding binding;
        Object previous;
        Writer writer;
        boolean sampled, pending;
        String status = "armed";
        Channel(Binding binding) { this.binding = binding; }
        void gap() { previous = null; sampled = false; }
    }

    private final Channel[] channels = new Channel[CHANNELS];
    private long last = Long.MIN_VALUE;
    private int first;

    boolean bound() { return channels[0] != null || channels[1] != null; }
    boolean needsSignals() { return Arrays.stream(channels).anyMatch(c -> c != null && !c.binding.ready()); }

    void configure(DataValue.RecordValue request, HardwarePins pins, Predicate<String> publicEvent) {
        if (!(request.fields().get("channel") instanceof DataValue.IntegerValue n) || n.value() < 0 || n.value() >= CHANNELS)
            throw new IllegalArgumentException("invalid_channel");
        int index = (int)n.value();
        String op = string(request,"op");
        if (op.equals("clear") && request.fields().keySet().equals(Set.of("op","channel"))) {
            channels[index] = null; return;
        }
        if (!op.equals("bind") || !request.fields().keySet().equals(Set.of("op","channel","pin","mode","entry")))
            throw new IllegalArgumentException("invalid_binding");
        var binding = new Binding(string(request,"pin"),string(request,"mode"),string(request,"entry"));
        if (!binding.supported(pins)) throw new IllegalArgumentException("missing_pin");
        if (!publicEvent.test(binding.entry())) throw new IllegalArgumentException("no_listener");
        channels[index] = new Channel(binding);
    }

    private static String string(DataValue.RecordValue record, String key) {
        if (record.fields().get(key) instanceof DataValue.TextValue text) return text.value();
        throw new IllegalArgumentException("invalid_binding");
    }
    void clear() { Arrays.fill(channels,null); gap(); }
    void gap() { last = Long.MIN_VALUE; for (var c : channels) if (c != null) c.gap(); }

    /** A ready sample is a writer operation witness, or null for an idle bus. SIO is Boolean,
     * null for missing/high-impedance input. Only consecutive active ticks can produce edges. */
    boolean tick(long now, Function<Binding,Object> sample, Function<String,String> admit) {
        if (now == last) return false;
        boolean changed = false;
        int nextFirst = first;
        if (last != Long.MIN_VALUE && now != last + 1) gap();
        last = now;
        for (int offset = 0; offset < CHANNELS; offset++) {
            var c = channels[(first+offset)%CHANNELS];
            if (c == null) continue;
            Object value = sample.apply(c.binding);
            boolean wasPending = c.pending;
            if (c.binding.ready()) {
                if (value instanceof Writer writer && !writer.equals(c.writer)) {
                    c.writer = writer; c.pending = changed = true;
                }
            } else {
                if (c.sampled && value != null && !Objects.equals(c.previous,value)
                    && value.equals(c.binding.mode().equals("rising"))) c.pending = true;
                c.sampled = value != null; c.previous = value;
            }
            if (c.pending) {
                c.status = admit.apply(c.binding.entry());
                if (c.status.equals("ok")) { c.pending = false; nextFirst = (first+offset+1)%CHANNELS; }
            }
            changed |= c.pending != wasPending;
        }
        first = nextFirst;
        return changed;
    }

    DataValue.RecordValue status(boolean available) {
        var fields = new LinkedHashMap<String,DataValue>();
        fields.put("available",new DataValue.BooleanValue(available));
        for (int i=0;i<CHANNELS;i++) {
            var c=channels[i];
            fields.put("ch"+i,c==null ? new DataValue.RecordValue(Map.of("status",new DataValue.TextValue("unbound")))
                : new DataValue.RecordValue(Map.of("status",new DataValue.TextValue(c.status),
                    "pending",new DataValue.BooleanValue(c.pending),"entry",new DataValue.TextValue(c.binding.entry()),
                    "pin",new DataValue.TextValue(c.binding.pin()),"mode",new DataValue.TextValue(c.binding.mode()))));
        }
        return new DataValue.RecordValue(fields);
    }
    CompoundTag save() {
        if (!bound()) return null;
        var tag=new CompoundTag(); tag.putInt("Version",1);
        for (int i=0;i<CHANNELS;i++) if (channels[i]!=null) {
            var b=channels[i].binding; var c=new CompoundTag();
            c.putString("Pin",b.pin()); c.putString("Mode",b.mode()); c.putString("Entry",b.entry());
            c.putBoolean("Pending",channels[i].pending);
            if (channels[i].writer!=null) c.put("Writer",channels[i].writer.save());
            tag.put("Ch"+i,c);
        }
        return tag;
    }
    void load(Tag source) {
        Arrays.fill(channels,null); gap();
        if (!(source instanceof CompoundTag tag) || tag.getInt("Version")!=1) return;
        for (int i=0;i<CHANNELS;i++) if (tag.contains("Ch"+i,Tag.TAG_COMPOUND)) {
            var c=tag.getCompound("Ch"+i);
            try {
                var channel=new Channel(new Binding(c.getString("Pin"),c.getString("Mode"),c.getString("Entry")));
                channel.pending=c.getBoolean("Pending");
                if (channel.pending) channel.status="pending";
                if (c.contains("Writer")) channel.writer=Writer.load(c.getCompound("Writer"));
                channels[i]=channel;
            }
            catch (IllegalArgumentException malformed) { /* An invalid channel never arms. */ }
        }
    }
}
