package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.TextProgramFixtures.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class TextOperationTest {
    @Test void catalogSignaturesAreImmutableStrictAndAcceptDynamicBoundary(){
        for(var example:examples()){
            var op=example.operation();assertThrows(UnsupportedOperationException.class,()->op.inputs().clear());
            var types=new LinkedHashMap<String,DataType>();op.inputs().forEach((k,v)->types.put(k,DataType.Scalar.ANY));
            assertDoesNotThrow(()->op.outputType(types,"x"));
            assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,()->op.outputType(Map.of(),"x")).code());
        }
        assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->TextOperation.LENGTH.outputType(Map.of("text",DataType.Scalar.INTEGER),"x")).code());
        // Even an empty pattern cannot bypass validation of required scalar inputs.
        assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->TextTask.start(TextOperation.CONTAINS,Map.of("text",integer(1),"needle",text("")),"x",n->{})).code());
        assertDoesNotThrow(()->TextOperation.JOIN.outputType(Map.of("items",new DataType.ListType(DataType.Scalar.ANY),"separator",DataType.Scalar.TEXT),"x"));
    }
    @Test void emptyPatternsUnicodeBoundsAndNonrecursiveReplacement(){
        assertEquals(integer(0),run(TextOperation.INDEX_OF,Map.of("text",text(""),"needle",text(""))));
        for(var op:List.of(TextOperation.CONTAINS,TextOperation.CONTAINS_IGNORE_CASE,TextOperation.STARTS_WITH,TextOperation.ENDS_WITH))
            assertEquals(bool(true),run(op,Map.of("text",text(""),"needle",text(""))));
        assertEquals(text("aaaa"),run(TextOperation.REPLACE,Map.of("text",text("aa"),"needle",text("a"),"replacement",text("aa"))));
        assertEquals(text(""),run(TextOperation.SUBSTRING,Map.of("text",text("😀"),"start",integer(1),"end",integer(1))));
        assertEquals(integer(2),run(TextOperation.LENGTH,Map.of("text",text("e\u0301"))));
        assertEquals(bool(false),run(TextOperation.CONTAINS_IGNORE_CASE,Map.of("text",text("ß"),"needle",text("ss"))));
        for(var args:List.of(Map.<String,DataValue>of("text",text("😀"),"index",integer(-1)),Map.<String,DataValue>of("text",text("😀"),"index",integer(1))))
            assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS,assertThrows(ComputationException.class,()->run(TextOperation.CHARACTER_AT,args)).code());
    }
    @Test void appendReservesBeforeGrowingOutput(){
        long[] units={0};var task=TextTask.start(TextOperation.CONCAT,Map.of("left",text("😀"),"right",text("中")),"x",n->units[0]+=n);
        assertEquals(1,units[0]);
        var before=task.checkpoint();
        assertThrows(IllegalStateException.class,()->task.advance(n->{throw new IllegalStateException("capacity");}));
        assertEquals(before,task.checkpoint(),"failed reservation must not append or advance");
    }
    private static DataValue run(TextOperation op,Map<String,DataValue> args){
        var task=TextTask.start(op,args,"x",n->{});for(int i=0;!task.complete();i++){assertTrue(i<10000);task.advance(n->{});}return task.result();
    }
    @Test void allDeclaredOperationsHaveLiteralResultsAfterEveryAdvanceRestore() {
        assertEquals(15,TextOperation.values().length);
        assertEquals(Set.of(TextOperation.values()),examples().stream().map(Example::operation).collect(java.util.stream.Collectors.toSet()));
        for(var example:examples()){
            long[] charged={0};var task=TextTask.start(example.operation(),example.arguments(),"text",n->charged[0]+=n);
            for(int step=0;!task.complete();step++){
                assertTrue(step<1000,example.operation().name());
                task=TextTask.restore(example.operation(),example.arguments(),task.checkpoint(),"text");
                task.advance(n->charged[0]+=n);assertTrue(charged[0]>=task.liveUnits());
            }
            assertEquals(example.expected(),task.result(),example.operation().name());
            var restored=TextTask.restore(example.operation(),example.arguments(),task.checkpoint(),"text");
            assertTrue(restored.complete());assertEquals(example.expected(),restored.result());
            var types=new LinkedHashMap<String,DataType>();example.arguments().forEach((k,v)->types.put(k,DataType.typeOf(v)));
            assertTrue(example.operation().outputType(types,"text").accepts(task.result()));
        }
    }
    @Test void adversarialSearchIsNotOneUnbudgetedScan() {
        var task=TextTask.start(TextOperation.CONTAINS,Map.of("text",text("a".repeat(1000)),"needle",text("aaaaab")),"text",n->{});
        int steps=0;while(!task.complete()){task.advance(n->{});assertTrue(++steps<20000);}
        assertTrue(steps>5000);assertEquals(bool(false),task.result());
    }
    @Test void joinChecksLateDynamicElementsOnlyWhenConsumed() {
        var args=Map.<String,DataValue>of("items",new DataValue.ListValue(List.of(text("abc"),integer(2))),"separator",text("|"));
        var task=assertDoesNotThrow(()->TextTask.start(TextOperation.JOIN,args,"join",n->{}));
        var failure=assertThrows(ComputationException.class,()->{for(int i=0;i<100;i++)task.advance(n->{});});
        assertEquals(ComputationException.Code.TYPE_MISMATCH,failure.code());
    }
    @Test void illegalDelimiterAndBoundsAreExplicitFailures() {
        for(var op:List.of(TextOperation.SPLIT,TextOperation.REPLACE)){
            var args=new LinkedHashMap<String,DataValue>();args.put("text",text("abc"));args.put(op==TextOperation.SPLIT?"delimiter":"needle",text(""));
            if(op==TextOperation.REPLACE)args.put("replacement",text("x"));
            assertEquals(ComputationException.Code.INVALID_ARGUMENT,assertThrows(ComputationException.class,()->TextTask.start(op,args,"x",n->{})).code());
        }
    }
}
