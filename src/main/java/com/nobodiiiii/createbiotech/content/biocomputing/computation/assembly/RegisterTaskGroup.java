package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Publish the caller's explicitly staged local task addresses together or not at all.
 * No channel or receiver table is encoded in this instruction. Address operands are restricted
 * to this linked image's verified preceding loads (also checked after cold restore). */
public record RegisterTaskGroup(List<Integer> arguments,boolean await) implements RegisterProgram.Instruction {
    public RegisterTaskGroup { arguments=List.copyOf(arguments); }
    @Override public int words(){return Math.addExact(2,arguments.size());}
    public record Dispatch(boolean await,List<ModuleDefinition.Key> receivers,boolean valid){}
}
