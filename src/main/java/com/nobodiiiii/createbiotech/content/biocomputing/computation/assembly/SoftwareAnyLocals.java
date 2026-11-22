package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import java.util.*;

/** Assembler-only closed scalar representation. No instruction or value type is added to the ISA. */
final class SoftwareAnyLocals {
    static final String PREFIX = "__any_";
    private record Variant(int tag, DataValue initial) {}
    private static final Map<DataType, Variant> VARIANTS = Map.of(
        DataType.Scalar.INTEGER, new Variant(1, new DataValue.IntegerValue(0)),
        DataType.Scalar.BOOLEAN, new Variant(2, new DataValue.BooleanValue(false)));
    private record Local(int tagRegister, Map<DataType, Integer> payloads) {}
    private final Map<String, Local> values = new LinkedHashMap<>();
    private final List<RegisterProgram.Code> prologue = new ArrayList<>();
    private final List<DataType> types;
    private final List<DataValue> constants;
    private int compareTag = -1, match = -1;

    SoftwareAnyLocals(List<DataType> types, List<DataValue> constants) {
        this.types = types;
        this.constants = constants;
    }

    boolean contains(String name) { return values.containsKey(name); }
    List<RegisterProgram.Code> prologue() { return prologue; }

    void declare(String[] tokens, int line) {
        var payloads = new LinkedHashMap<DataType, Integer>();
        int tag = allocate(DataType.Scalar.INTEGER);
        initialize(tag, new DataValue.IntegerValue(0), line);
        for (int i = 3; i < tokens.length; i++) {
            var type = variant(tokens[i]);
            if (payloads.containsKey(type)) throw new IllegalArgumentException("Duplicate software ANY variant: " + tokens[i]);
            int payload = allocate(type);
            payloads.put(type, payload);
            initialize(payload, VARIANTS.get(type).initial(), line);
        }
        if (compareTag < 0) {
            compareTag = allocate(DataType.Scalar.INTEGER);
            match = allocate(DataType.Scalar.BOOLEAN);
        }
        values.put(tokens[1], new Local(tag, payloads));
    }

    static int width(String opcode) {
        return switch (opcode) {
            case "ANY.SET", "ANY.IS" -> 2;
            case "ANY.GET" -> 4;
            default -> 1;
        };
    }

    List<RegisterProgram.Instruction> lower(String[] t, Map<String, Integer> registers, Map<String, Integer> labels) {
        int arity = t[0].equals("ANY.SET") ? 3 : 4;
        if (t.length != arity) throw new IllegalArgumentException("Wrong operand count for " + t[0]);
        String dynamicName = t[t[0].equals("ANY.SET") ? 1 : 2];
        var local = values.get(dynamicName);
        if (local == null) throw new IllegalArgumentException("Not a declared software ANY local: " + dynamicName);
        String registerName = t[t[0].equals("ANY.SET") ? 2 : 1];
        var register = registers.get(registerName);
        if (register == null) throw new IllegalArgumentException("Expected concrete register: " + registerName);
        DataType type = t[0].equals("ANY.IS") ? variant(t[3]) : types.get(register);
        var payload = local.payloads().get(type);
        if (payload == null) throw new IllegalArgumentException("Variant not declared for " + dynamicName + ": " + type);
        var tag = new DataValue.IntegerValue(VARIANTS.get(type).tag());
        return switch (t[0]) {
            case "ANY.SET" -> List.of(new RegisterProgram.Move(payload, register), constant(local.tagRegister(), tag));
            case "ANY.IS" -> {
                if (types.get(register) != DataType.Scalar.BOOLEAN) throw new IllegalArgumentException("ANY.IS requires BOOLEAN destination");
                yield List.of(constant(compareTag, tag), compare(local, register));
            }
            case "ANY.GET" -> {
                var failure = labels.get(t[3]);
                if (failure == null) throw new IllegalArgumentException("Undefined local label: " + t[3]);
                yield List.of(constant(compareTag, tag), compare(local, match),
                    new RegisterProgram.JumpFalse(match, failure), new RegisterProgram.Move(register, payload));
            }
            default -> throw new IllegalArgumentException("Unknown software ANY opcode: " + t[0]);
        };
    }

    private RegisterProgram.Alu compare(Local value, int result) {
        return new RegisterProgram.Alu(ScalarOperation.EQUAL_INTEGER, result, List.of(value.tagRegister(), compareTag));
    }
    private static DataType variant(String name) {
        return switch (name) {
            case "INTEGER" -> DataType.Scalar.INTEGER;
            case "BOOLEAN" -> DataType.Scalar.BOOLEAN;
            default -> throw new IllegalArgumentException("Unsupported software ANY variant: " + name);
        };
    }
    private int allocate(DataType type) {
        int register = types.size();
        types.add(type);
        return register;
    }
    private RegisterProgram.Constant constant(int register, DataValue value) {
        int pool = constants.indexOf(value);
        if (pool < 0) { pool = constants.size(); constants.add(value); }
        return new RegisterProgram.Constant(register, pool);
    }
    private void initialize(int register, DataValue value, int line) {
        prologue.add(new RegisterProgram.Code(constant(register, value), 0, "line " + line));
    }
}
