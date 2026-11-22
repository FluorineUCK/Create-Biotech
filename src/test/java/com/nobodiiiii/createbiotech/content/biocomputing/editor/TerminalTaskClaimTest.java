package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalTaskClaimTest {
    @Test void malformedWireIndexIsAValidationFailureRatherThanAnUncaughtArithmeticException() {
        for (long index : new long[]{Long.MIN_VALUE,-1,(long)Integer.MAX_VALUE+1})
            assertThrows(IllegalArgumentException.class,()->TerminalTaskClaim.decode(new DataValue.RecordValue(Map.of(
                "view",new DataValue.IntegerValue(1),"index",new DataValue.IntegerValue(index)))));
    }
}
