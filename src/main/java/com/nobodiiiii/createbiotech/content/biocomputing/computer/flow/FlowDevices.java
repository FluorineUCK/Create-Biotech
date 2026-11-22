package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import java.util.*;

/** Shared instruction-derived scalar device facts. Immutable definitions cache only verified facts;
 * weak keys do not retain documents. Installed machines sample their already compiled image. */
public final class FlowDevices {
    private FlowDevices() {}
    public record Use(RegisterDevices.Kind kind, String pin, String source) {}
    private static final Map<FlowProgram.Procedure, List<Use>> AUTHORED = Collections.synchronizedMap(new WeakHashMap<>());
    public static List<Use> authored(FlowProgram.Procedure procedure){return authored(procedure,Map.of());}
    public static List<Use> authored(FlowProgram.Procedure procedure,Map<String,com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition.Cell> cells) {
        if(!cells.isEmpty()){if(procedure.assembly().isEmpty())return List.of();var image=ProcedureAssembler.assemble(procedure.assembly().orElseThrow(),procedure.inputs(),procedure.outputs(),procedure.returns(),com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES,Long.MAX_VALUE,cells);return instructions(image.program()).stream().map(d->new Use(d.kind(),d.pin(),procedure.sourceId()+"/assembly/"+d.source())).toList();}
        if (procedure.assembly().isEmpty()) return List.of();
        synchronized (AUTHORED) {
            var cached = AUTHORED.get(procedure);
            if (cached != null) return cached;
            var image = ProcedureAssembler.assemble(procedure.assembly().orElseThrow(), procedure.inputs(), procedure.outputs(), procedure.returns(),
                com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES, Long.MAX_VALUE);
            var uses = instructions(image.program()).stream().map(d -> new Use(d.kind(), d.pin(), procedure.sourceId()+"/assembly/"+d.source())).toList();
            // Structural cache bound, not hardware capacity or game balance. Weak keys never retain documents.
            if (AUTHORED.size() >= 64) AUTHORED.remove(AUTHORED.keySet().iterator().next());
            AUTHORED.put(procedure, uses); return uses;
        }
    }
    public static Set<String> statusPins(List<Use> uses) {
        var pins = new TreeSet<String>();
        uses.stream().filter(d -> d.kind() == RegisterDevices.Kind.XBUS_STATUS).forEach(d -> pins.add(d.pin()));
        return Collections.unmodifiableSet(pins);
    }
    public static List<Use> instructions(RegisterProgram image) {
        return image.code().stream().filter(c -> c.instruction() instanceof RegisterDevices.Instruction).map(c -> {
            var d = (RegisterDevices.Instruction)c.instruction(); return new Use(d.kind(), d.pin(), c.location());
        }).toList();
    }
    public static List<Use> uses(List<FlowProgram.Step> steps){return uses(steps,Map.of());}
    public static List<Use> uses(List<FlowProgram.Step> steps,Map<String,com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition.Cell> cells) {
        var result = new ArrayList<Use>();
        for (var step : steps) {
            if (step instanceof FlowProgram.Procedure p) result.addAll(authored(p,cells));
            else if (step instanceof FlowProgram.SignalRead r) result.add(new Use(RegisterDevices.Kind.SIO_READ, r.port(), r.sourceId()));
            else if (step instanceof FlowProgram.SignalWrite w) result.add(new Use(RegisterDevices.Kind.SIO_WRITE, w.port(), w.sourceId()));
            else if (step instanceof FlowProgram.XBusStatus s) result.add(new Use(RegisterDevices.Kind.XBUS_STATUS, s.port(), s.sourceId()));
        }
        return List.copyOf(result);
    }
    public static Set<String> selectors(RegisterDevices.Kind kind) {
        return switch (kind) {
            case SIO_READ -> FlowBlockDeclarations.Kind.SIGNAL_READ.selectors();
            case SIO_WRITE -> FlowBlockDeclarations.Kind.SIGNAL_WRITE.selectors();
            case XBUS_STATUS -> FlowBlockDeclarations.Kind.XBUS_STATUS.selectors();
        };
    }
}
