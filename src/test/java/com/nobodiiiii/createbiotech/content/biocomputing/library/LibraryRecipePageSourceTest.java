package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.*;

class LibraryRecipePageSourceTest {
    static final BlockPos SHELF=new BlockPos(102,64,101);
    static final LibraryTrackAddress TRACK=new LibraryTrackAddress(2,0,1);
    static final LibraryDriveLimits LIMITS=new LibraryDriveLimits(1,0,1,1,1024,65536,LibraryBookMediaTest.LIMITS);
    static DataComponentType<String> volumeType;
    @Test void physicalVersionThreePageReturnsTheLayoutAlongsideTheOrderedMaterials() {
        var f = new Fixture();
        f.pages("""
            {"version":3,"type":"standard","inputs":[{"item":"minecraft:paper","count":1},{"item":"minecraft:stick","count":1}],
             "outputs":[{"item":"minecraft:map","count":1}],"crafting_slots":[1,3],"address":"cut"}
            """);
        f.reindex();
        var value=(DataValue.RecordValue)finish(LibraryReadHead.recipePage(selected(f),0,f.source,LIMITS),f).result().orElseThrow().value();
        var body=(DataValue.RecordValue)value.field("body");
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(1),new DataValue.IntegerValue(3))),body.field("crafting_slots"));
        assertEquals(new DataValue.TextValue("minecraft:paper"),((DataValue.RecordValue)((DataValue.ListValue)value.field("inputs")).values().getFirst()).field("item"));
        // A previously installed v1 consumer must not silently accept a layout it does not understand.
        assertThrows(IllegalArgumentException.class,()->new DataValue.RecipeValue("recipe",1,value));
        var shaped=new DataValue.RecipeValue("recipe",2,value);
        assertEquals(2,shaped.version());
        assertEquals(value,shaped.projection());
    }

    static final String RAW="""
        {"version":2,"type":"standard","inputs":[{"item":"minecraft:paper","count":3}],"outputs":[{"item":"minecraft:book","count":2}],"address":"press"}
        """;
    @BeforeAll static void bootstrap() throws Exception {
        LibraryBookMediaTest.bootstrap();
        volumeType=Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,ResourceLocation.fromNamespaceAndPath("createbiotech_test","rom_volume"),
            DataComponentType.<String>builder().persistent(Codec.STRING).build());
    }
    static class Fixture implements LibraryBookAccess.View {
        final LibraryBookVolumes volumes=new LibraryBookVolumes(volumeType);
        final LibraryBookMedia media=new LibraryBookMedia(DataComponents.CUSTOM_DATA,LibraryBookMediaTest.LIMITS);
        ItemStack book=new ItemStack(Items.WRITABLE_BOOK);
        RecipeSourceLocation location=new RecipeSourceLocation(Level.OVERWORLD,null,SHELF);
        final RecipePageIndex index=new RecipePageIndex(); boolean allowed=true,loaded=true; int writes,reads;
        final LibraryPositionedReadSource source;
        Fixture() {
            pages(RAW); book=volumes.rename(book,"recipes");
            reindex();
            var access=new LibraryBookAccess(this,media,volumes);
            source=new LibraryPositionedReadSource(access,track->{
                assertEquals(TRACK,track); if(!loaded) throw new LibraryBookAccess.Failure("unloaded"); return SHELF;
            },volume->{assertEquals("recipes",volume);return allowed;},null,
                new RecipePageRomAdapter(index,ignored->location,RegistryAccess.EMPTY,LIMITS));
        }
        void pages(String... raw) {book.set(DataComponents.WRITABLE_BOOK_CONTENT,new WritableBookContent(Arrays.stream(raw).map(Filterable::passThrough).toList()));}
        void reindex() {
            var key=new PatternPageKey(location,0,0); index.rebuildPageOrder(List.of(key));
            for(int i=0;i<3&&!index.indexPassComplete();i++) index.tick(ignored->book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().getFirst().raw(),new PatternJsonParser(RegistryAccess.EMPTY),1);
            assertTrue(index.indexPassComplete());
        }
        public boolean mayEdit(){return true;}
        public List<BlockPos> shelves(){return List.of(SHELF);}
        public List<ItemStack> contents(BlockPos shelf){assertEquals(SHELF,shelf);reads++;return List.of(book);}
        public boolean replace(BlockPos shelf,int slot,ItemStack before,ItemStack after){writes++;throw new AssertionError("ROM mutation");}
    }
    static LibraryReadHead.State selected(Fixture f) {
        var state=finish(LibraryReadHead.seek(LibraryReadHead.State.empty(),TRACK,f.source,LIMITS),f);
        state=LibraryReadHead.take(state,state.sequence(),LIMITS).state();
        state=finish(LibraryReadHead.select(state,0,f.source,LIMITS),f);
        return LibraryReadHead.take(state,state.sequence(),LIMITS).state();
    }
    static LibraryReadHead.State finish(LibraryReadHead.State state,Fixture f) {
        for(int i=0;i<10000&&LibraryReadHead.busy(state);i++)state=LibraryReadHead.advance(state,f.source,LIMITS);
        assertFalse(LibraryReadHead.busy(state));return state;
    }
    static String admissionFailure(Fixture f,int page) {
        return assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.recipePage(selected(f),page,f.source,LIMITS)).code();
    }
    @Test void segmentedRomReadsRecheckThePhysicalBookAndGrantEvenAfterRestore() {
        var f=new Fixture();var original=f.book.copy();
        var limits=new LibraryDriveLimits(1,0,1,1,1024,131072,LIMITS.archive());
        var state=LibraryReadHead.beginRecipePageTransfer(selected(f),0,f.source,limits);
        for(int tick=0;tick<20000 && state.reading().isPresent() && !state.reading().orElseThrow().ready();tick++)
            state=LibraryReadHead.prepareTick(state,f.source,limits).next();
        assertTrue(state.reading().isPresent(),state.result().toString());
        var ready=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,limits),limits);
        var info=LibraryReadHead.readInfo(ready,f.source);
        assertEquals(new DataValue.TextValue("recipe_page"),info.field("kind"));
        assertEquals(DataValue.NullValue.INSTANCE,info.field("version"),"A ROM read is not a writable entry/version");
        f.allowed=false;
        assertEquals("denied",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.readChunk(ready,0,1,f.source)).code());
        f.allowed=true;assertTrue(ItemStack.matches(original,f.book));assertEquals(0,f.writes);
        f.pages(RAW.replace("press","other"));f.reindex();
        assertEquals("changed",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.readInfo(ready,f.source)).code());
        assertTrue(LibraryReadHead.closeReadTransfer(ready).reading().isEmpty());
    }
    @Test void exposesOwnedTypedRecipeWithoutChangingBookOrIndex() {
        var f=new Fixture();var original=f.book.copy();var index=f.index.save(RegistryAccess.EMPTY);
        var result=finish(LibraryReadHead.recipePage(selected(f),0,f.source,LIMITS),f).result().orElseThrow();
        assertEquals("recipe_page",result.code());var value=(DataValue.RecordValue)result.value();
        var semantic=new DataValue.RecipeValue("recipe",1,value);
        assertSame(value,semantic.projection());
        assertFalse(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Semantic.RECIPE.accepts(value));
        assertEquals(semantic,ValueNbtCodec.decode(ValueNbtCodec.encode(semantic,LIMITS.archive()),LIMITS.archive()));
        assertEquals(new DataValue.IntegerValue(1),value.field("pages"));assertEquals(new DataValue.TextValue("standard"),value.field("type"));
        var input=(DataValue.RecordValue)((DataValue.ListValue)value.field("inputs")).values().getFirst();
        assertEquals(new DataValue.IntegerValue(3),input.field("count"));assertSame(DataValue.NullValue.INSTANCE,input.field("tag"));
        assertInstanceOf(DataValue.NbtValue.class,input.field("components"));assertSame(DataValue.NullValue.INSTANCE,value.field("priority"));
        assertEquals(new DataValue.TextValue("press"),((DataValue.RecordValue)value.field("body")).field("address"));
        assertTrue(ItemStack.matches(original,f.book));assertEquals(index,f.index.save(RegistryAccess.EMPTY));assertEquals(0,f.writes);
        var fact=f.index.pageSnapshot(new PatternPageKey(f.location,0,0));
        fact.record().orElseThrow().mainOutput().stack().stack().setCount(30);
        assertEquals(1,f.index.pageSnapshot(new PatternPageKey(f.location,0,0)).record().orElseThrow().mainOutput().stack().stack().getCount());
    }
    @Test void completionAfterCheckpointRejectsLiveGrantRawPagesReplacementRemovalAndSpaceChanges() {
        for(var change:List.of("grant","raw","replacement","removed","unloaded","space","dimension","reindexing")) {
            var f=new Fixture();var pending=LibraryReadHead.recipePage(selected(f),0,f.source,LIMITS);
            var revision=f.media.revision(f.book);String expected;
            switch(change) {
                case "grant" -> {f.allowed=false;expected="denied";}
                case "raw" -> {f.pages(RAW.replace("press","changed"));expected="changed";}
                case "replacement" -> {f.book=f.book.copy();f.book.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("different book"));expected="changed";}
                case "removed" -> {f.book=ItemStack.EMPTY;expected="missing_medium";}
                case "unloaded" -> {f.loaded=false;expected="unloaded";}
                case "space" -> {f.location=new RecipeSourceLocation(Level.OVERWORLD,UUID.randomUUID(),SHELF);expected="unindexed";}
                case "dimension" -> {f.location=new RecipeSourceLocation(Level.NETHER,null,SHELF);expected="unindexed";}
                default -> {f.index.rebuildPageOrder(f.index.pageOrder());expected="reindexing";}
            }
            if(change.equals("raw")||change.equals("replacement")) assertEquals(revision,f.media.revision(f.book),"Generic revision deliberately unchanged");
            var result=finish(LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(pending,LIMITS),LIMITS),f).result().orElseThrow();
            assertEquals(expected,result.code(),change);assertSame(DataValue.NullValue.INSTANCE,result.value());assertEquals(0,f.writes);
        }
    }
    @Test void admissionRejectsInvalidUnpositionedDeniedUnindexedAndStaleFactsWithoutFallbackParsing() {
        var f=new Fixture();
        assertEquals("not_positioned",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.recipePage(LibraryReadHead.State.empty(),0,f.source,LIMITS)).code());
        var positioned=new LibraryReadHead.State(1,Optional.of(TRACK),-1,Optional.empty(),Optional.empty());
        assertEquals("not_selected",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.recipePage(positioned,0,f.source,LIMITS)).code());
        assertEquals("invalid_page",admissionFailure(f,-1));assertEquals("invalid_page",admissionFailure(f,1));
        f.allowed=false;assertEquals("denied",admissionFailure(f,0));f.allowed=true;
        f.pages(RAW.replace("press","changed"));assertEquals("changed",admissionFailure(f,0));
        f.index.rebuildPageOrder(List.of());assertEquals("unindexed",admissionFailure(f,0));
        f.index.rebuildPageOrder(List.of(new PatternPageKey(f.location,0,0)));assertEquals("reindexing",admissionFailure(f,0));
    }
    @Test void sequencedProjectionPreservesStageOrderNativeComponentsAndExplicitPriority() {
        var f=new Fixture();
        f.pages("""
            {"version":2,"type":"sequenced","inputs":[],"outputs":[{"item":"minecraft:book","count":2,"components":{"minecraft:max_stack_size":16}}],
            "stages":[{"address":"first","inputs":[{"item":"minecraft:paper","count":3,"components":{"minecraft:max_stack_size":32}}]},
            {"address":"second","inputs":[]}],"priority":-4}
            """); f.reindex();
        var value=(DataValue.RecordValue)finish(LibraryReadHead.recipePage(selected(f),0,f.source,LIMITS),f).result().orElseThrow().value();
        assertEquals(new DataValue.TextValue("sequenced"),value.field("type"));assertEquals(new DataValue.IntegerValue(-4),value.field("priority"));
        assertSame(value,new DataValue.RecipeValue("recipe",1,value).projection());
        var stages=(DataValue.ListValue)((DataValue.RecordValue)value.field("body")).field("stages");assertEquals(2,stages.values().size());
        var first=(DataValue.RecordValue)stages.values().getFirst();var second=(DataValue.RecordValue)stages.values().get(1);
        assertEquals(new DataValue.IntegerValue(0),first.field("index"));assertEquals(new DataValue.TextValue("second"),second.field("address"));
        var input=(DataValue.RecordValue)((DataValue.ListValue)first.field("inputs")).values().getFirst();
        var nativeLimits=new NativeNbtCodec.Limits(65536,65536,LIMITS.archive());
        var predicate=(net.minecraft.nbt.CompoundTag)NativeNbtCodec.toTag((DataValue.NbtValue)input.field("components"),nativeLimits);
        assertEquals(32,predicate.getInt("minecraft:max_stack_size"));
        var output=(DataValue.RecordValue)((DataValue.ListValue)value.field("outputs")).values().getFirst();
        assertEquals(16,((net.minecraft.nbt.CompoundTag)NativeNbtCodec.toTag((DataValue.NbtValue)output.field("components"),nativeLimits)).getInt("minecraft:max_stack_size"));
    }
    @Test void tagIngredientRemainsATagSelectorNotAnArbitraryRepresentativeItem() {
        var tag=net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("createbiotech_test","rom_materials"));
        BuiltInRegistries.ITEM.getOrCreateTag(tag);
        var f=new Fixture();f.pages(RAW.replace("\"item\":\"minecraft:paper\"","\"tag\":\"createbiotech_test:rom_materials\""));f.reindex();
        var value=(DataValue.RecordValue)finish(LibraryReadHead.recipePage(selected(f),0,f.source,LIMITS),f).result().orElseThrow().value();
        var input=(DataValue.RecordValue)((DataValue.ListValue)value.field("inputs")).values().getFirst();
        assertSame(DataValue.NullValue.INSTANCE,input.field("item"));
        assertEquals(new DataValue.TextValue("createbiotech_test:rom_materials"),input.field("tag"));
        assertEquals(new DataValue.IntegerValue(3),input.field("count"));
    }
    @Test void nonRecipePagesAreExplicitFailuresNotEmptyRecipesOrFreshlyParsedFallbacks() {
        for(var rawAndCode:List.of(new String[]{"","blank_page"},new String[]{"not json","invalid_recipe"},
            new String[]{RAW.replace("standard","future"),"unsupported_recipe"})) {
            var f=new Fixture();f.pages(rawAndCode[0]);f.reindex();assertEquals(rawAndCode[1],admissionFailure(f,0));
        }
    }
    @Test void nativeValueLimitsRejectAtomicallyAndLoadedStaleIndexCannotServeOldFacts() {
        var f=new Fixture();var book=f.book.copy();var checkpoint=f.index.save(RegistryAccess.EMPTY);
        var tiny=new LibraryDriveLimits(1,0,1,1,1024,65536,new ArchiveLimits(1,1,1));
        var bounded=new RecipePageRomAdapter(f.index,ignored->f.location,RegistryAccess.EMPTY,tiny);
        assertEquals("value_limit",assertThrows(LibraryBookAccess.Failure.class,()->bounded.read(SHELF,0,0,f.book)).code());
        assertTrue(ItemStack.matches(book,f.book));assertEquals(checkpoint,f.index.save(RegistryAccess.EMPTY));
        var loaded=RecipePageIndex.load(checkpoint,RegistryAccess.EMPTY);assertFalse(loaded.hadCorruption());
        f.pages(RAW.replace("press","modified"));
        var stale=new RecipePageRomAdapter(loaded.index(),ignored->f.location,RegistryAccess.EMPTY,LIMITS);
        assertEquals("changed",assertThrows(LibraryBookAccess.Failure.class,()->stale.read(SHELF,0,0,f.book)).code());
        assertEquals(checkpoint,loaded.index().save(RegistryAccess.EMPTY),"ROM never repairs/reparses a stale loaded index");
    }
    @Test void fullBookWitnessIsChargedAndBufferRejectionPreservesOldState() {
        var f=new Fixture();var selected=selected(f);
        var pending=LibraryReadHead.recipePage(selected,0,f.source,LIMITS);
        var sample=pending.pending().orElseThrow().sample().orElseThrow();
        var bytes=new java.io.ByteArrayOutputStream();
        try{net.minecraft.nbt.NbtIo.write(ValueNbtCodec.encode(new DataValue.RecordValue(Map.of("fence",sample.fence(),"value",sample.value())),LIMITS.archive()),new java.io.DataOutputStream(bytes));}
        catch(java.io.IOException e){throw new AssertionError(e);}
        var exact=new LibraryDriveLimits(1,0,1,1,1024,bytes.size(),LIMITS.archive());
        assertEquals(pending,LibraryReadHead.recipePage(selected,0,f.source,exact));
        var tiny=new LibraryDriveLimits(1,0,1,1,1024,bytes.size()-1,LIMITS.archive());
        assertEquals("buffer_full",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.recipePage(selected,0,f.source,tiny)).code());
        assertEquals(selected,LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(selected,LIMITS),LIMITS));
        f.pages(RAW,"unrelated raw page".repeat(20));
        assertEquals("buffer_full",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.recipePage(selected,0,f.source,exact)).code());
        assertEquals(0,f.writes);
    }
}
