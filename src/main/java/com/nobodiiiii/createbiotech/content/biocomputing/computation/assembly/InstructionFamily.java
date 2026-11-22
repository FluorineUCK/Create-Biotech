package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Requirements derived from lowered instructions, never from player-authored capability claims.
 * Families do not change source dialect versions, opcode identity, cost or continuation encoding.
 */
public enum InstructionFamily {
    ISA_1("ISA-1"),
    ISA_1A("ISA-1a"),
    ISA_1B("ISA-1b"),
    XBUS("XBus"),
    SIMPLE_IO("Simple I/O"),
    SERIAL("Serial");

    private final String id;
    InstructionFamily(String id) { this.id = id; }
    public String id() { return id; }

    public static InstructionFamily of(RegisterProgram.Instruction instruction) {
        // Exhaustive: adding an instruction must make an explicit classification decision.
        return switch (instruction) {
            case RegisterMonitor ignored -> ISA_1;
            case RegisterTimer ignored -> ISA_1;
            case RegisterPrompt p -> p.asks() ? ISA_1A : ISA_1;
            case RegisterKeyDown ignored -> ISA_1;
            case RegisterProgram.DefaultNumber ignored -> ISA_1;
            case RegisterReference ignored -> ISA_1;
            case RegisterNbt.Instruction ignored -> ISA_1A;
            case RegisterProgram.FormatCheck ignored -> ISA_1B;
            case RegisterProgram.FormatProject ignored -> ISA_1B;
            case RegisterProgram.ValueEquals ignored -> ISA_1A;
            case RegisterProgram.ValueCheck ignored -> ISA_1A;
            case RegisterProgram.Field ignored -> ISA_1A;
            case RegisterProgram.HasField ignored -> ISA_1A;
            case RegisterProgram.Index ignored -> ISA_1A;
            case RegisterProgram.DynamicIndex ignored -> ISA_1A;
            case RegisterProgram.ListLength ignored -> ISA_1A;
            case RegisterProgram.AppendList ignored -> ISA_1A;
            case RegisterListEdit ignored -> ISA_1A;
            case RegisterProgram.MakeList ignored -> ISA_1A;
            case RegisterProgram.MakeRecord ignored -> ISA_1A;
            case RegisterProgram.TextNext ignored -> ISA_1A;
            case RegisterProgram.TextLetter ignored -> ISA_1A;
            case RegisterProgram.TextContains ignored -> ISA_1A;
            case RegisterProgram.TextConcat ignored -> ISA_1A;
            case RegisterProgram.TextLength ignored -> ISA_1A;
            case RegisterXBus.Instruction ignored -> XBUS;
            case RegisterDevices.Instruction device -> switch (device.kind()) {
                case XBUS_STATUS -> XBUS;
                case SIO_READ, SIO_WRITE -> SIMPLE_IO;
            };
            case RegisterSerial.Instruction ignored -> SERIAL;
            case RegisterProgram.Constant ignored -> ISA_1;
            case RegisterProgram.Move ignored -> ISA_1;
            case RegisterProgram.Alu ignored -> ISA_1;
            case RegisterProgram.Nonnegative ignored -> ISA_1;
            case RegisterProgram.JumpFalse ignored -> ISA_1;
            case RegisterProgram.Jump ignored -> ISA_1;
            case RegisterProgram.Sleep ignored -> ISA_1;
            case RegisterProgram.Call ignored -> ISA_1;
            case RegisterProgram.Return ignored -> ISA_1;
            case RegisterProgram.Drop ignored -> ISA_1;
            case RegisterTaskGroup ignored -> ISA_1;
            case RegisterEvent ignored -> ISA_1A;
            case RegisterProgram.TryLaunch ignored -> ISA_1;
            case RegisterProgram.TaskStart ignored -> ISA_1;
            case RegisterProgram.TaskWait ignored -> ISA_1;
            case RegisterProgram.TaskClaim ignored -> ISA_1;
            case RegisterProgram.TaskStop ignored -> ISA_1;
            case RegisterProgram.RandomRange ignored -> ISA_1;
            case RegisterProgram.RandomBelow ignored -> ISA_1;
            case RegisterProgram.Correlation ignored -> ISA_1;
            case RegisterMemoryOp ignored -> ISA_1;
        };
    }

    static Set<InstructionFamily> requirements(List<RegisterProgram.Code> code) {
        var families = EnumSet.of(ISA_1);
        for (var entry : code) families.add(entry.instruction().family());
        return Collections.unmodifiableSet(families);
    }
}
