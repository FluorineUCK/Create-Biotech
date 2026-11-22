package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SortTaskTest {
    static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.ListValue values(DataValue... values) { return new DataValue.ListValue(List.of(values)); }

    @Test void stableBothDirectionsReturnOriginalRecordsWithoutMutatingSource() {
        var a = new DataValue.RecordValue(Map.of("label", text("a")));
        var b = new DataValue.RecordValue(Map.of("label", text("b")));
        var c = new DataValue.RecordValue(Map.of("label", text("c")));
        var source = values(a,b,c);
        var keys = values(integer(2),integer(1),integer(2));
        assertEquals(values(b,a,c), run(source, keys, false));
        assertEquals(values(a,c,b), run(source, keys, true));
        assertEquals(values(a,b,c), source);
        assertEquals(values(integer(2),integer(1),integer(2)), keys);
    }

    @Test void emptySingletonAndAllComparableScalarKinds() {
        assertEquals(values(), run(values(),values(),false));
        assertEquals(values(text("only")), run(values(text("only")),values(integer(9)),true));
        var source = values(integer(0),integer(1),integer(2));
        assertEquals(values(integer(1),integer(0),integer(2)), run(source,values(
            new DataValue.BooleanValue(true),new DataValue.BooleanValue(false),new DataValue.BooleanValue(true)),false));
        assertEquals(source, run(source,values(new DataValue.DecimalValue(-0.0),new DataValue.DecimalValue(0.0),new DataValue.DecimalValue(1)),false));
        assertEquals(values(integer(1),integer(0),integer(2)), run(source,values(text("\uD800\uDC00"),text("\uE000"),text("\uD83D\uDE00")),false));
        assertEquals(values(integer(1),integer(2),integer(0)), run(source,values(integer(Long.MAX_VALUE),integer(Long.MIN_VALUE),integer(0)),false));
    }

    @Test void longTextComparisonIsSlicedAndResumesInsideUnicodePrefix() {
        String prefix="\uD83D\uDE00".repeat(300);
        var source=values(integer(0),integer(1)); var keys=values(text(prefix+"b"),text(prefix+"a"));
        long[] charges={0}; var task=SortTask.start(source,keys,false,"sort",n->charges[0]+=n);
        int steps=0;
        while(!task.complete()) {
            assertTrue(steps++<1000);
            var restored=SortTask.restore(source,keys,false,task.checkpoint(),"sort");
            assertEquals(task.checkpoint(),restored.checkpoint());
            assertEquals(task.liveUnits(),restored.liveUnits());
            task=restored; task.advance(n->charges[0]+=n);
            assertTrue(charges[0]>=task.liveUnits());
        }
        assertTrue(steps>=300,"A shared prefix must not be compared in one unmetered step");
        assertEquals(values(integer(1),integer(0)),task.result());
    }

    @Test void everyStepRestoreMatchesFixedSeedIndependentReference() {
        var random=new Random(721935);
        for(int count:new int[]{0,1,2,3,17,100}) for(boolean descending:new boolean[]{false,true}) {
            var source=new ArrayList<DataValue>(); var keys=new ArrayList<DataValue>(); var expected=new ArrayList<Integer>();
            for(int i=0;i<count;i++){source.add(integer(i));keys.add(integer(random.nextInt(9)-4));expected.add(i);}
            expected.sort((a,b)->{
                int order=Long.compare(((DataValue.IntegerValue)keys.get(a)).value(),((DataValue.IntegerValue)keys.get(b)).value());
                return descending?-order:order;
            });
            var actual=run(new DataValue.ListValue(source),new DataValue.ListValue(keys),descending);
            assertEquals(new DataValue.ListValue(expected.stream().map(source::get).toList()),actual);
        }
    }

    @Test void nullErrorRecordsAndMixedKeysFailExplicitlyEvenForSingleton() {
        for(var keys:List.of(values(DataValue.NullValue.INSTANCE),values(new DataValue.ErrorValue("x","x")),
            values(new DataValue.RecordValue(Map.of())),values(integer(1),text("1")))) {
            var source=new DataValue.ListValue(Collections.nCopies(keys.values().size(),integer(0)));
            var error=assertThrows(ComputationException.class,()->run(source,keys,false));
            assertEquals(TYPE_MISMATCH,error.code());
        }
    }

    @Test void outputUnavailableBeforeCompletionAndReservationFailureIsNotPartialSuccess() {
        var source=values(integer(2),integer(1));
        var task=SortTask.start(source,source,false,"sort",n->{});
        assertFalse(task.complete());
        assertThrows(IllegalStateException.class,task::result);
        var before=task.checkpoint();
        assertThrows(ComputationException.class,()->task.advance(n->{throw new ComputationException(MEMORY_LIMIT,"sort","full");}));
        assertEquals(before,task.checkpoint());
        assertFalse(task.complete());
    }

    static DataValue.ListValue run(DataValue.ListValue source,DataValue.ListValue keys,boolean descending) {
        long[] charges={0}; var task=SortTask.start(source,keys,descending,"sort",n->charges[0]+=n);
        for(int i=0;!task.complete();i++) {
            assertTrue(i<100_000,"Sort must make bounded forward progress");
            var state=task.checkpoint();
            var restored=SortTask.restore(source,keys,descending,state,"sort");
            assertEquals(state,restored.checkpoint());
            task=restored;task.advance(n->charges[0]+=n);
            assertTrue(charges[0]>=task.liveUnits());
        }
        return task.result();
    }

    @Test void restoreRejectsPermutationThatMovesItemsAcrossUnmergedRuns() {
        var source=values(integer(2),integer(1),integer(3));
        var task=SortTask.start(source,source,false,"sort",n->{});
        while(!task.checkpoint().field("phase").equals(text("MERGE")))task.advance(n->{});
        var fields=new LinkedHashMap<>(task.checkpoint().fields());
        fields.put("order",values(integer(1),integer(0),integer(2)));
        var error=assertThrows(ComputationException.class,()->SortTask.restore(source,source,false,new DataValue.RecordValue(fields),"sort"));
        assertEquals(INVALID_CHECKPOINT,error.code());
    }

    @Test void malformedCountersIndicesOutputAndSurrogateOffsetsAreRejected() {
        var source=values(integer(0),integer(1));var keys=values(text("\uD83D\uDE00b"),text("\uD83D\uDE00a"));
        var task=SortTask.start(source,keys,false,"sort",n->{});
        while(!task.checkpoint().field("offset").equals(integer(2)))task.advance(n->{});
        var state=task.checkpoint();
        for(var mutation:Map.of("offset",integer(1),"width",integer(3),"left",integer(100),"order",values(integer(0),integer(0)),
            "output",values(integer(9)),"phase",text("UNKNOWN")).entrySet()) {
            var fields=new LinkedHashMap<>(state.fields());fields.put(mutation.getKey(),mutation.getValue());
            assertEquals(INVALID_CHECKPOINT,assertThrows(ComputationException.class,
                ()->SortTask.restore(source,keys,false,new DataValue.RecordValue(fields),"sort")).code());
        }
    }
}
