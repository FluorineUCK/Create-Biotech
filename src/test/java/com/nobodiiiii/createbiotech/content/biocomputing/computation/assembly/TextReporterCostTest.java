package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TextReporterCostTest {
    @Test void letterPaysPerExaminedCodePointAndDoesNotOverwriteAliasedInputOnRamFailure() {
        var image=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.TextLetter(0,0,1),0,"letter")),
            List.of(DataType.Scalar.TEXT,DataType.Scalar.INTEGER),List.of(),Map.of("text",0,"index",1),Map.of("answer",0,"index",1),1);
        var text=new DataValue.TextValue("A😀中");
        var initial=image.begin(new DataValue.RecordValue(Map.of("text",text,"index",new DataValue.IntegerValue(2))));
        var pending=image.step(initial);
        image.validate(pending,1,false);
        assertEquals(text,pending.registers().get(0));
        assertEquals(1,pending.scan().orElseThrow().points());
        assertThrows(ComputationException.class,()->image.step(pending,Map.of(),Map.of(),2,units->{
            assertEquals(3,units);
            throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"letter","Full RAM");
        }));
        assertEquals(text,pending.registers().get(0));
        var done=image.step(pending);image.validate(done,2,false);
        assertTrue(image.complete(done));
        assertEquals(new DataValue.TextValue("😀"),done.registers().get(0));
        assertEquals(text,initial.registers().get(0));
    }

    @Test void failedSearchPaysForEveryComparisonAndPublishesOnlyAfterReservation() {
        var image=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.TextContains(2,0,1),0,"contains")),
            List.of(DataType.Scalar.TEXT,DataType.Scalar.TEXT,DataType.Scalar.BOOLEAN),List.of(),
            Map.of("text",0,"needle",1,"result",2),Map.of("text",0,"needle",1,"result",2),1);
        var initial=image.begin(new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("aaaaab"),
            "needle",new DataValue.TextValue("aaac"),"result",new DataValue.BooleanValue(true))));
        var state=initial;
        // 4+4+4+3+2+1 comparisons, plus admission and final atomic publication.
        for(int paid=1;paid<=19;paid++) {
            state=image.step(state);image.validate(state,paid,false);
            assertEquals(paid,state.build().orElseThrow().paidWork());
            assertEquals(0,state.build().orElseThrow().ownedUnits());
            assertEquals(new DataValue.BooleanValue(true),state.registers().get(2));
        }
        var pending=state;
        assertThrows(ComputationException.class,()->image.step(pending,Map.of(),Map.of(),20,units->{
            assertEquals(1,units);
            throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"contains","Full RAM");
        }));
        assertEquals(new DataValue.BooleanValue(true),pending.registers().get(2));
        var done=image.step(pending);image.validate(done,20,false);
        assertTrue(image.complete(done));assertTrue(done.build().isEmpty());
        assertEquals(new DataValue.BooleanValue(false),done.registers().get(2));
    }
}
