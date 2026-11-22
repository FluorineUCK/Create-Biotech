package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class RegisterListEditCostTest {
    private static RegisterProgram image(ListOperation operation) {
        var operands=operation==ListOperation.REMOVE ? List.of(0,1) : List.of(0,1,2);
        return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterListEdit(operation,0,operands),0,"edit")),
            List.of(new DataType.ListType(DataType.Scalar.INTEGER),DataType.Scalar.INTEGER,DataType.Scalar.INTEGER),
            List.of(),Map.of("items",0,"index",1,"value",2),Map.of("items",0,"index",1,"value",2),1);
    }
    @ParameterizedTest @EnumSource(value=ListOperation.class,names={"INSERT","REMOVE","REPLACE","SLICE"})
    void oneCopyStepPerResultElementAndAtomicAliasedPublication(ListOperation operation) {
        var image=image(operation);
        var source=new DataValue.ListValue(List.of(new DataValue.IntegerValue(3),new DataValue.IntegerValue(5),new DataValue.IntegerValue(9)));
        var state=image.begin(new DataValue.RecordValue(Map.of("items",source,"index",new DataValue.IntegerValue(1),"value",new DataValue.IntegerValue(3))));
        int count=switch(operation) {case INSERT->4;case REPLACE->3;case REMOVE,SLICE->2;default->throw new AssertionError();};
        for(int paid=1;paid<=count+1;paid++) {
            state=image.step(state);image.validate(state,paid,false);
            assertEquals(0,state.pc());assertEquals(source,state.registers().get(0));
            assertEquals(paid-1,state.build().orElseThrow().cursor());
            assertEquals(paid,state.build().orElseThrow().paidWork());
        }
        var pending=state;
        var failure=assertThrows(ComputationException.class,()->image.step(pending,Map.of(),Map.of(),count+2,units->{
            assertTrue(units>0);
            throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"edit","Full RAM");
        }));
        assertEquals(ComputationException.Code.MEMORY_LIMIT,failure.code());
        assertEquals(source,pending.registers().get(0));
        var done=image.step(pending);image.validate(done,count+2,false);
        assertTrue(image.complete(done));assertTrue(done.build().isEmpty());
        assertEquals(count,((DataValue.ListValue)done.registers().get(0)).values().size());
        assertEquals(3,source.values().size());
    }
    @Test void emptySliceStillPaysAdmissionAndPublicationAndInvalidBoundsDoNotStartCopying() {
        var image=image(ListOperation.SLICE);
        var state=image.begin(new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of()),
            "index",new DataValue.IntegerValue(0),"value",new DataValue.IntegerValue(0))));
        var admitted=image.step(state);assertFalse(image.complete(admitted));image.validate(admitted,1,false);
        var done=image.step(admitted);assertTrue(image.complete(done));image.validate(done,2,false);
        var invalid=image.begin(new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of()),
            "index",new DataValue.IntegerValue(-1),"value",new DataValue.IntegerValue(0))));
        assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS,assertThrows(ComputationException.class,()->image.step(invalid)).code());
        assertTrue(invalid.build().isEmpty());
    }
}
