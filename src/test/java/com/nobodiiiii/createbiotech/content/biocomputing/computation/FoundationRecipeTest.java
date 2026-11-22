package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class FoundationRecipeTest {
    public static final ArchiveLimits LIMITS = new ArchiveLimits(100000,1000000,10000000);
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
    static DataValue.RecordValue record(Map<String,DataValue> m) { return new DataValue.RecordValue(m); }
    public static DataValue.RecordValue projection(boolean sequenced) {
        var nativeData = new DataValue.NbtValue(10,record(Map.of("metadata",new DataValue.NbtValue(8,text("not authority")))));
        var f = new LinkedHashMap<String,DataValue>();
        f.put("page",number(0));f.put("pages",number(1));
        f.put("pattern_id",text("00000000-0000-0000-0000-000000000000"));
        f.put("type",text(sequenced?"sequenced":"standard"));
        var input=record(Map.of("item",DataValue.NullValue.INSTANCE,"tag",text("c:ingots/iron"),"count",number(2),"components",nativeData));
        f.put("inputs",new DataValue.ListValue(List.of(input)));
        f.put("outputs",new DataValue.ListValue(List.of(record(Map.of("item",text("minecraft:iron_ingot"),"count",number(3),"components",nativeData)))));
        f.put("body",sequenced?record(Map.of("stages",new DataValue.ListValue(List.of(record(Map.of("index",number(0),"address",text("factory A"),"inputs",new DataValue.ListValue(List.of()))))))):record(Map.of("address",text("factory A"))));
        f.put("priority",DataValue.NullValue.INSTANCE);f.put("fingerprint",text("a".repeat(64)));f.put("source",nativeData);
        return record(f);
    }
    static DataValue.RecipeValue recipe(DataValue.RecordValue r) { return new DataValue.RecipeValue("recipe",1,r); }
    public static DataValue.RecordValue shapedProjection() {
        var raw=projection(false);
        var rows=(DataValue.ListValue)raw.field("inputs");
        var item=replace((DataValue.RecordValue)rows.values().getFirst(),"count",number(1));
        var body=replace((DataValue.RecordValue)raw.field("body"),"crafting_slots",new DataValue.ListValue(List.of(number(2),number(7))));
        return replace(replace(raw,"inputs",new DataValue.ListValue(List.of(item,item))),"body",body);
    }
    @Test void shapedSchemaChecksPositionsCountsAndNominalVersion() {
        var shaped=shapedProjection();
        var body=(DataValue.RecordValue)shaped.field("body");
        for(var slots:List.of(List.of(number(2),number(2)),List.of(number(9),number(0)),List.of(number(2)),List.of(number(-1),number(0)))) {
            var bad=replace(shaped,"body",replace(body,"crafting_slots",new DataValue.ListValue(List.copyOf(slots))));
            assertThrows(IllegalArgumentException.class,()->new DataValue.RecipeValue("recipe",2,bad));
        }
        var original=projection(false);
        var multi=((DataValue.ListValue)original.field("inputs")).values().getFirst();
        assertThrows(IllegalArgumentException.class,()->new DataValue.RecipeValue("recipe",2,replace(shaped,"inputs",new DataValue.ListValue(List.of(multi,multi)))));
        var v1=new DataValue.RecipeValue("recipe",1,original);
        var v2=new DataValue.RecipeValue("recipe",2,original);
        assertNotEquals(v1,v2);
        assertFalse(DataType.Semantic.RECIPE.accepts(v2));
        assertFalse(DataType.Semantic.RECIPE_V2.accepts(v1));
    }
    static DataValue.RecordValue replace(DataValue.RecordValue r,String key,DataValue value) { var f=new LinkedHashMap<>(r.fields());f.put(key,value);return record(f); }
    @Test void recipeIdentityAndArchiveRoundtripAreNominal() {
        for(boolean sequenced:List.of(false,true)) {
            var raw=projection(sequenced);var value=recipe(raw);
            assertEquals(DataType.Semantic.RECIPE,DataType.typeOf(value));
            assertTrue(DataType.Semantic.RECIPE.accepts(value));assertFalse(DataType.Semantic.RECIPE.accepts(raw));
            assertNotEquals(raw,value);assertFalse(DataType.typeOf(raw).accepts(value));
            var copy=ValueNbtCodec.decode(ValueNbtCodec.encode(value,LIMITS),LIMITS);
            assertEquals(value,copy);assertEquals(value.hashCode(),copy.hashCode());
            assertTrue(value.storageUnits()>raw.storageUnits());assertSame(raw,value.projection());
            assertEquals(DataType.Semantic.RECIPE,TypeArchiveCodec.decode(TypeArchiveCodec.encode(DataType.Semantic.RECIPE)));
        }
    }
    @Test void rejectsMalformedFieldsCountsBodiesAndVersions() {
        var raw=projection(false);
        assertThrows(IllegalArgumentException.class,()->new DataValue.RecipeValue("unknown",1,raw));
        assertThrows(IllegalArgumentException.class,()->new DataValue.RecipeValue("recipe",3,raw));
        for(var bad:List.of(replace(raw,"pages",number(0)),replace(raw,"page",number(-1)),replace(raw,"source",record(Map.of())),
                replace(raw,"body",record(Map.of("stages",new DataValue.ListValue(List.of())))),replace(raw,"outputs",new DataValue.ListValue(List.of())),
                replace(raw,"pattern_id",text("bad")),replace(raw,"fingerprint",text("G".repeat(64))),replace(raw,"priority",number(Long.MAX_VALUE)))) {
            assertThrows(IllegalArgumentException.class,()->recipe(bad));
        }
        var input=(DataValue.RecordValue)((DataValue.ListValue)raw.field("inputs")).values().getFirst();
        for(var bad:List.of(replace(input,"count",number(0)),replace(input,"item",text("minecraft:iron_ingot")),replace(input,"components",text("{}")),replace(input,"tag",text("No:Uppercase")))) {
            assertThrows(IllegalArgumentException.class,()->recipe(replace(raw,"inputs",new DataValue.ListValue(List.of(bad)))));
        }
    }
    @Test void immutableSourceAndResumableProofAreBranchableAndUnforgeable() {
        var fields=new LinkedHashMap<>(projection(false).fields());var raw=record(fields);
        var proof=RecipeValidation.begin("recipe",1,raw);fields.clear();
        assertThrows(IllegalStateException.class,proof::finish);assertThrows(IllegalStateException.class,proof::projection);
        int steps=0;while(!proof.done()){var next=proof.advance();assertEquals(next.storageUnits(),proof.advance().storageUnits());proof=next;steps++;}
        assertTrue(steps>64);assertEquals(recipe(raw),proof.finish());
        assertThrows(UnsupportedOperationException.class,()->raw.fields().clear());
    }
    @Test void descriptionsAreDataNotPhysicalCapacityOrDeliveryAuthority() {
        var raw=projection(false);
        var output=(DataValue.RecordValue)((DataValue.ListValue)raw.field("outputs")).values().getFirst();
        var large=replace(raw,"outputs",new DataValue.ListValue(List.of(replace(output,"count",number(Long.MAX_VALUE)))));
        assertEquals(Long.MAX_VALUE,((DataValue.IntegerValue)((DataValue.RecordValue)((DataValue.ListValue)recipe(large).projection().field("outputs")).values().getFirst()).field("count")).value());
        assertNotNull(recipe(replace(raw,"pages",number(Long.MAX_VALUE))));
        var emptyInputs=replace(raw,"inputs",new DataValue.ListValue(List.of()));
        assertNotNull(recipe(emptyInputs));
        var seq=projection(true);
        var stage=record(Map.of("index",number(1),"address",text("arbitrary metadata"),"inputs",new DataValue.ListValue(List.of())));
        var badBody=record(Map.of("stages",new DataValue.ListValue(List.of(stage))));
        assertThrows(IllegalArgumentException.class,()->recipe(replace(seq,"body",badBody)));
        var extra=replace(raw,"unknown",number(1));assertThrows(IllegalArgumentException.class,()->recipe(extra));
        assertThrows(IllegalArgumentException.class,()->TypeArchiveCodec.decode(record(Map.of("nodes",new DataValue.ListValue(List.of(record(Map.of("kind",text("semantic"),"fmt",text("future"),"version",number(1))))),"root",number(0)))));
    }
    @Test void boundedDeepLargeArchivesKeepIdentityAndOldVersionOutputs() {
        DataValue deep=recipe(projection(false));for(int i=0;i<2000;i++)deep=new DataValue.ListValue(List.of(deep));
        var archive=ValueNbtCodec.encode(deep,LIMITS);assertEquals(4,archive.getInt("Version"));assertEquals(deep,ValueNbtCodec.decode(archive,LIMITS));
        final DataValue source=deep;
        assertThrows(ArchiveException.class,()->ValueNbtCodec.encode(source,new ArchiveLimits(10,100,100)));
        assertEquals(2,ValueNbtCodec.encode(text("old"),LIMITS).getInt("Version"));
        assertEquals(3,ValueNbtCodec.encode(new DataValue.NbtValue(8,text("native")),LIMITS).getInt("Version"));
        archive.putInt("Version",3);assertThrows(ArchiveException.class,()->ValueNbtCodec.decode(archive,LIMITS));
        var raw=projection(false);var items=Collections.nCopies(2000,((DataValue.ListValue)raw.field("inputs")).values().getFirst());
        var proof=RecipeValidation.begin("recipe",1,replace(raw,"inputs",new DataValue.ListValue(items)));int count=0;
        while(!proof.done()){proof=proof.advance();count++;}assertTrue(count>2000);
    }
    @Test void nominalTypeChecksAndComparisonsResumeAcrossEveryCheckpoint() {
        var a=recipe(projection(true));var b=recipe(projection(true));
        var check=ValueTypeTask.start(new ValueTypeTask.Schema(DataType.Semantic.RECIPE),a,n->{});
        while(!check.complete())check=check.advance(n->{});assertTrue(check.matches());
        var raw=ValueTypeTask.start(new ValueTypeTask.Schema(DataType.Semantic.RECIPE),a.projection(),n->{});
        while(!raw.complete())raw=raw.advance(n->{});assertFalse(raw.matches());
        var comparison=TypedComparison.start(a,b,n->{});
        for(int i=0;!comparison.complete();i++) {
            assertTrue(i<10000);comparison.advance(n->{});
            comparison=TypedComparison.copy(comparison.snapshot());
            comparison=TypedComparison.restore(a,b,comparison.checkpoint());
        }
        assertTrue(comparison.equal());
        var wrong=TypedComparison.start(a,a.projection(),n->{});while(!wrong.complete())wrong.advance(n->{});assertFalse(wrong.equal());
    }
    @Test void browserDisplaysIdentityAndReadOnlyProjectionWithoutLossyLiteral() {
        var value=recipe(projection(false));var path=new ValuePath("value",List.of());
        var tree=new ValueTree(record(Map.of("value",value)));tree.toggle(path);
        assertEquals("recipe@1",ValueTree.kind(value));assertEquals(value.projection(),tree.page(0,20).rows().get(1).value());
        assertThrows(IllegalArgumentException.class,()->EditorValueFormat.format(value));
    }
}
