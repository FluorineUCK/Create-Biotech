package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import java.util.Optional;

/** Host-specific validation kept out of portable program documents and the pure graph compiler. */
public final class FlowPins {
    private FlowPins() {}
    public static final class Unavailable extends IllegalArgumentException {
        private Unavailable(String message) { super(message); }
        private Unavailable(String pin, String source, java.util.List<String> pins) {
            super("Simple I/O pin '" + pin + "' is unavailable at " + source + "; hardware pins: " + pins);
        }
        private Unavailable(String pin, String source, HardwarePins hardware) {
            super("XBus pin '" + pin + "' is unavailable at " + source + "; hardware pins: " + hardware.xbus());
        }
    }
    public static void validate(FlowProgram program, HardwarePins hardware) {
        for (var use : FlowDevices.uses(program.steps(),program.expressions().program().cells())) {
            if (use.kind() == com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices.Kind.XBUS_STATUS) {
                if (!hardware.hasXBus(use.pin())) throw new Unavailable(use.pin(), use.source(), hardware);
            } else if (!hardware.hasSimpleIo(use.pin())) throw new Unavailable(use.pin(), use.source(), hardware.simpleIo());
        }
        for (var step : program.steps()) {
            if(step instanceof FlowProgram.Procedure p)for(var code:FlowXBus.authored(p,program.expressions().program().cells()))if(code.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Instruction d && !hardware.hasXBus(d.pin()))throw new Unavailable(d.pin(),p.sourceId()+"/assembly/"+code.location(),hardware);
            String pin = pin(step);
            if (pin != null && !hardware.hasXBus(pin)) throw new Unavailable(pin, step.sourceId(), hardware);
        }
    }
    public static void validate(FlowProgram program, com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits) {
        FlowTasks.requireStartupCapacity(program,limits.taskSlots());
        validateInstructionSets(program, limits);
        validate(program, limits.pins());
        for (var step : program.steps()) {
            if(step instanceof FlowProgram.Procedure p)for(var code:FlowXBus.authored(p,program.expressions().program().cells()))if(code.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterSerial.Instruction d && !limits.serial().ports().containsKey(d.pin()))throw new Unavailable("Serial interface '"+d.pin()+"' is unavailable at "+p.sourceId()+"/assembly/"+code.location());
            String port = step instanceof FlowProgram.SerialSend send ? send.port() : step instanceof FlowProgram.SerialReceive receive
                ? receive.port() : step instanceof FlowProgram.SerialStatus status ? status.port() : null;
            if (port != null && !limits.serial().ports().containsKey(port))
                throw new Unavailable("Serial interface '" + port + "' is unavailable at " + step.sourceId());
        }
    }
    public static void validateInstructionSets(FlowProgram program, com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits) {
        if (program.execution() == FlowProgram.Execution.LEGACY_GRAPH) {
            program.inputs().forEach((name,type)->validateDeclaration(type,"input "+name,limits));
            for(var step:program.steps()) {
                if(step instanceof FlowProgram.Procedure p) {
                    p.inputs().forEach((name,type)->validateDeclaration(type,p.sourceId()+" input "+name,limits));
                    p.outputs().forEach((name,type)->validateDeclaration(type,p.sourceId()+" output "+name,limits));
                } else if(step instanceof FlowProgram.Read read) validateDeclaration(read.type(),read.sourceId(),limits);
            }
            if (program.inputs().values().stream().anyMatch(AssemblyTypeRequirements::semantic))
                throw new Unavailable("Semantic recipe declarations require the ISA execution backend at flow input");
            for (var step : program.steps()) {
                boolean semantic = step instanceof FlowProgram.Procedure p
                    && (p.inputs().values().stream().anyMatch(AssemblyTypeRequirements::semantic)
                        || p.outputs().values().stream().anyMatch(AssemblyTypeRequirements::semantic))
                    || step instanceof FlowProgram.Read read && AssemblyTypeRequirements.semantic(read.type());
                if (semantic) throw new Unavailable("Semantic recipe declarations require the ISA execution backend at " + step.sourceId());
            }
            validateLegacyInstructionSets(program.expressions(), limits);
        }
        if (limits.isa1a() && limits.isa1b()) return;
        var images = program.execution() == FlowProgram.Execution.REGISTER_V1
            ? java.util.stream.Stream.of(FlowXBus.image(program))
            : program.steps().stream().filter(FlowProgram.Procedure.class::isInstance)
                .map(FlowProgram.Procedure.class::cast).flatMap(p -> FlowXBus.authoredImage(p,program.expressions().program().cells()).stream());
        images.forEach(image -> {
            // Job and resident level are independent. Report both missing capabilities for
            // this image so fixing one cannot reveal a second, previously hidden requirement.
            Optional<String> parser = limits.isa1a() ? Optional.empty() : image.structuredTypeLocation()
                .or(() -> image.code().stream().filter(c -> c.instruction().family() == InstructionFamily.ISA_1A)
                    .map(RegisterProgram.Code::location).findFirst());
            Optional<String> master = limits.isa1b() ? Optional.empty() : image.advancedTypeLocation();
            // Put both names before any source IDs: the menu bounds its detail string.
            if (parser.isPresent() && master.isPresent())
                throw new Unavailable("ISA-1a and ISA-1b required (Witch or master Cleric Parser); ISA-1a at "
                    + parser.get() + "; ISA-1b at " + master.get());
            if (parser.isPresent()) throw new Unavailable("ISA-1a requires Parser hardware (Cleric Parser or Witch) at " + parser.get());
            if (master.isPresent()) throw new Unavailable("ISA-1b requires Master (level 5) or Witch hardware at " + master.get());
        });
    }
    public static void validateLegacyInstructionSets(com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive archive,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits) {
        if(!limits.isa1a()) com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.LegacyInstructionRequirements.firstDeclaration(archive,AssemblyTypeRequirements::structured)
            .ifPresent(location->{throw new Unavailable("ISA-1a requires Parser hardware (Cleric Parser or Witch) at "+location);});
        if(!limits.isa1b()) com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.LegacyInstructionRequirements.firstDeclaration(archive,AssemblyTypeRequirements::containsAny)
            .ifPresent(location->{throw new Unavailable("ISA-1b requires Master (level 5) or Witch hardware at "+location);});
        com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.LegacyInstructionRequirements.firstSemanticDeclaration(archive).ifPresent(location -> {
            throw new Unavailable("Semantic recipe declarations require the ISA execution backend at " + location);
        });
        if (!limits.isa1a()) com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.LegacyInstructionRequirements
            .firstStructuredOperation(archive).ifPresent(location -> {
                throw new Unavailable("ISA-1a requires Parser hardware (Cleric Parser or Witch) at compatibility operation " + location);
            });
    }
    private static void validateDeclaration(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType type,String location,
            com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits) {
        if(!limits.isa1a() && AssemblyTypeRequirements.structured(type)) throw new Unavailable("ISA-1a requires Parser hardware (Cleric Parser or Witch) at "+location);
        if(!limits.isa1b() && (AssemblyTypeRequirements.containsAny(type)||AssemblyTypeRequirements.semantic(type))) throw new Unavailable("ISA-1b requires Master (level 5) or Witch hardware at "+location);
    }
    public static String pin(FlowProgram.Step step) {
        return switch (step) {
            case FlowProgram.Monitor ignored -> null;
            case FlowProgram.Broadcast ignored -> null;
            case FlowProgram.Atomic ignored -> null;
            case FlowProgram.EndAtomic ignored -> null;
            case FlowProgram.Reference ignored -> null;
            case FlowProgram.Shared ignored -> null;
            case FlowProgram.Memory ignored -> null;
            case FlowProgram.Procedure ignored -> null;
            case FlowProgram.EndProcedure ignored -> null;
            case FlowProgram.Invoke ignored -> null;
            case FlowProgram.Watch ignored -> null;
            case FlowProgram.TryLaunch ignored -> null;
            case FlowProgram.TaskStart ignored -> null;
            case FlowProgram.TaskJoin ignored -> null;
            case FlowProgram.Correlation ignored -> null;
            case FlowProgram.SerialSend ignored -> null;
            case FlowProgram.SerialReceive ignored -> null;
            case FlowProgram.SerialStatus ignored -> null;
            case FlowProgram.XBusStatus ignored -> null; // Observation never joins the blocking wait group.
            case FlowProgram.Read read -> read.port();
            case FlowProgram.Write write -> write.port();
            case FlowProgram.AwaitReadable wait -> wait.port();
            case FlowProgram.TimerReset ignored -> null;
            case FlowProgram.Ask ignored -> null;
            case FlowProgram.SignalRead ignored -> null;
            case FlowProgram.SignalWrite ignored -> null;
            case FlowProgram.Sleep ignored -> null;
            case FlowProgram.Evaluate ignored -> null;
            case FlowProgram.If ignored -> null;
            case FlowProgram.Otherwise ignored -> null;
            case FlowProgram.EndIf ignored -> null;
            case FlowProgram.Forever ignored -> null;
            case FlowProgram.EndForever ignored -> null;
            case FlowProgram.Repeat ignored -> null;
            case FlowProgram.EndRepeat ignored -> null;
            case FlowProgram.Until ignored -> null;
            case FlowProgram.WaitUntil ignored -> null;
            case FlowProgram.StopScript ignored -> null;
            case FlowProgram.EndUntil ignored -> null;
        };
    }
}
