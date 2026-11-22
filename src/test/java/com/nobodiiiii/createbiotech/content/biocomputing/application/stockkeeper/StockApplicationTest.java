package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;

class StockApplicationTest {
    static final StockApplication.Limits LIMITS = new StockApplication.Limits(8, 4, 100000, 256, 128);
    static final StockApplication.Application APP = new StockApplication.Application(UUID.randomUUID(), UUID.randomUUID());
    static final StockApplication.Access ACCESS = new StockApplication.Access(APP, true, false);
    static final UUID PLAYER = UUID.randomUUID();
    static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
    static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i=0; i<pairs.length; i+=2) fields.put((String)pairs[i], (DataValue)pairs[i+1]);
        return new DataValue.RecordValue(fields);
    }
    static StockApplication.Entry entry() {
        var nested = new DataValue.NbtValue(10, record("custom", new DataValue.NbtValue(10,
            record("name", new DataValue.NbtValue(8, text("preserved")), "level", new DataValue.NbtValue(3, number(7))))));
        return new StockApplication.Entry("recipe:a", "Application recipe", "batch",
            record("item", text("minecraft:stone"), "count", number(2), "components", nested), record("opaque", text("application-owned")));
    }
    static StockApplication.State initial() {
        return StockApplication.bind(StockApplication.closed(UUID.randomUUID(), UUID.randomUUID()), APP, UUID.randomUUID()).state();
    }
    static StockApplication.Request request(StockApplication.State state) {
        return new StockApplication.Request(state.fence(), state.nextSequence(), List.of(new StockApplication.Selection("recipe:a", 3)), "Depot A");
    }
    @Test void authorityFencesAndOneConfirmationIdentityRejectWithoutMutation() {
        StockApplication.State state;
        var start = initial(); state = StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), LIMITS).state();
        var wrong = new StockApplication.Access(new StockApplication.Application(APP.computer(), UUID.randomUUID()), true, false);
        assertSame(state, StockApplication.replace(state, wrong, state.fence(), List.of(), LIMITS).state());
        assertTrue(StockApplication.poll(state, wrong, state.fence()).input().isEmpty());
        for (var access : List.of(wrong, new StockApplication.Access(APP, false, false), new StockApplication.Access(APP, true, true))) {
            assertNotEquals("input_queued", StockApplication.confirm(state, access, PLAYER, request(state), LIMITS).code());
        }
        var req = request(state); var accepted = StockApplication.confirm(state, ACCESS, PLAYER, req, LIMITS);
        var retained = accepted.state();
        assertEquals("existing", StockApplication.confirm(retained, ACCESS, PLAYER, req, LIMITS).code());
        assertSame(retained, StockApplication.confirm(retained, ACCESS, PLAYER, req, LIMITS).state());
        assertEquals("conflict", StockApplication.confirm(retained, ACCESS, UUID.randomUUID(), req, LIMITS).code());
        var conflict = new StockApplication.Request(req.fence(), req.sequence(), req.selections(), "Different address");
        assertEquals("conflict", StockApplication.confirm(retained, ACCESS, PLAYER, conflict, LIMITS).code());
        assertSame(retained, StockApplication.ack(retained, ACCESS, retained.fence(), 2).state());
        var acked = StockApplication.ack(retained, ACCESS, retained.fence(), 1).state();
        assertEquals("retired", StockApplication.confirm(acked, ACCESS, PLAYER, req, LIMITS).code());
        assertEquals("input_queued", StockApplication.confirm(acked, ACCESS, PLAYER, request(acked), LIMITS).code());
        var old = new StockApplication.Fence(state.device(), UUID.randomUUID(), state.revision());
        assertEquals("stale_session", StockApplication.poll(state, ACCESS, old).code());
        assertSame(state, StockApplication.replace(state, ACCESS, new StockApplication.Fence(state.device(), state.session(), 0), List.of(), LIMITS).state());
    }
    @Test void finiteCapacityAndMalformedBundlesAreAtomicAndIncludeAllFrozenValues() {
        var start = initial();
        var published = StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), LIMITS).state();
        long units = StockApplication.retainedUnits(published);
        assertEquals("capacity", StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), new StockApplication.Limits(1,4,units-1,256,128)).code());
        assertEquals("accepted", StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), new StockApplication.Limits(1,4,units,256,128)).code());
        assertEquals("capacity", StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), new StockApplication.Limits(0,4,100000,256,128)).code());
        assertSame(start, StockApplication.replace(start, ACCESS, start.fence(), List.of(entry(), entry()), LIMITS).state());
        var accepted = StockApplication.confirm(published, ACCESS, PLAYER, request(published), LIMITS);
        long queuedUnits = StockApplication.retainedUnits(accepted.state());
        assertTrue(queuedUnits > units + entry().data().storageUnits() + entry().icon().storageUnits());
        assertEquals("capacity", StockApplication.confirm(published, ACCESS, PLAYER, request(published), new StockApplication.Limits(1,1,queuedUnits-1,256,128)).code());
        assertEquals("input_queued", StockApplication.confirm(published, ACCESS, PLAYER, request(published), new StockApplication.Limits(1,1,queuedUnits,256,128)).code());
        assertEquals("capacity", StockApplication.confirm(accepted.state(), ACCESS, PLAYER, request(accepted.state()), new StockApplication.Limits(1,1,100000,256,128)).code());
        for (var selected : List.of(List.<StockApplication.Selection>of(), List.of(new StockApplication.Selection("recipe:a", 0)),
                List.of(new StockApplication.Selection("recipe:a", 1), new StockApplication.Selection("missing", 1)))) {
            var req = new StockApplication.Request(published.fence(), published.nextSequence(), selected, "A");
            assertSame(published, StockApplication.confirm(published, ACCESS, PLAYER, req, LIMITS).state());
        }
        var ref = new DataValue.LocalReference(APP.computer(),0,"a".repeat(64),UUID.randomUUID(),0,
            new com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.RefType(com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER,false));
        var invalid = new StockApplication.Entry("b","b","b",entry().icon(),record("nested",new DataValue.ListValue(List.of(ref))));
        assertSame(start, StockApplication.replace(start, ACCESS, start.fence(), List.of(invalid), LIMITS).state());
        var badIcon = new StockApplication.Entry("b","b","b",record("item",text("minecraft:stone"),"count",number(0),"components",new DataValue.NbtValue(10,record())),record());
        assertSame(start, StockApplication.replace(start, ACCESS, start.fence(), List.of(badIcon), LIMITS).state());
    }
    @Test void exactRetainedDuplicateReceiptSurvivesSessionRefreshButOldAdmissionsDoNot() {
        var start=initial();var catalog=StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state();
        var original=request(catalog);var pending=StockApplication.confirm(catalog,ACCESS,PLAYER,original,LIMITS).state();
        var restored=StockApplicationCodec.load(StockApplicationCodec.save(pending),UUID.randomUUID());
        var rebound=StockApplication.bind(restored,APP,UUID.randomUUID()).state();
        for(var refreshed:List.of(restored,rebound)) {
            var receipt=StockApplication.confirm(refreshed,ACCESS,PLAYER,original,LIMITS);
            assertEquals("existing",receipt.code());assertSame(refreshed,receipt.state());
            assertEquals(pending.inputs().getFirst(),receipt.input().orElseThrow());assertEquals(2,receipt.state().nextSequence());
            var otherPlayer=StockApplication.confirm(refreshed,ACCESS,UUID.randomUUID(),original,LIMITS);
            assertEquals("conflict",otherPlayer.code());assertTrue(otherPlayer.input().isEmpty());assertSame(refreshed,otherPlayer.state());
            var otherProgram=new StockApplication.Access(new StockApplication.Application(APP.computer(),UUID.randomUUID()),true,false);
            var denied=StockApplication.confirm(refreshed,otherProgram,PLAYER,original,LIMITS);
            assertEquals("permission",denied.code());assertTrue(denied.input().isEmpty());assertSame(refreshed,denied.state());
            var rewritten=new StockApplication.Request(refreshed.fence(),original.sequence(),original.selections(),original.address());
            assertEquals("conflict",StockApplication.confirm(refreshed,ACCESS,PLAYER,rewritten,LIMITS).code());
            var staleAdmission=StockApplication.confirm(refreshed,ACCESS,PLAYER,request(pending),LIMITS);
            assertEquals("stale_session",staleAdmission.code());assertSame(refreshed,staleAdmission.state());assertTrue(staleAdmission.input().isEmpty());
            var wrongDevice=new StockApplication.Request(new StockApplication.Fence(UUID.randomUUID(),original.fence().session(),original.fence().revision()),original.sequence(),original.selections(),original.address());
            assertEquals("device_changed",StockApplication.confirm(refreshed,ACCESS,PLAYER,wrongDevice,LIMITS).code());
        }
    }
    @Test void reloadRefreshesSessionAndNeverReassignsOrTruncatesPendingInputs() {
        var start = initial(); var catalog = StockApplication.replace(start, ACCESS, start.fence(), List.of(entry()), LIMITS).state();
        var pending = StockApplication.confirm(catalog, ACCESS, PLAYER, request(catalog), LIMITS).state();
        var saved = StockApplicationCodec.save(pending);
        var loaded = StockApplicationCodec.load(saved, UUID.randomUUID());
        assertEquals(pending.catalog(), loaded.catalog()); assertEquals(pending.inputs(), loaded.inputs());
        assertEquals(pending.nextSequence(), loaded.nextSequence());
        assertEquals("stale_session", StockApplication.ack(loaded, ACCESS, pending.fence(), 1).code());
        assertEquals("stale_session", StockApplication.confirm(loaded, ACCESS, PLAYER, request(pending), LIMITS).code());
        var other = new StockApplication.Application(APP.computer(), UUID.randomUUID());
        assertEquals("busy", StockApplication.bind(loaded, other, UUID.randomUUID()).code());
        var revoked = StockApplication.revoke(loaded, UUID.randomUUID());
        assertEquals(loaded.inputs(), revoked.inputs()); assertEquals(loaded.catalog(), revoked.catalog());
        assertEquals("closed", StockApplication.poll(revoked, ACCESS, revoked.fence()).code());
        assertEquals("busy", StockApplication.bind(revoked, other, UUID.randomUUID()).code());
        var reopened = StockApplication.bind(revoked, APP, UUID.randomUUID()).state();
        assertEquals(pending.inputs(), StockApplication.poll(reopened, ACCESS, reopened.fence()).state().inputs());
        var small = new StockApplication.Limits(0,0,1,1,1);
        assertSame(loaded, StockApplication.confirm(loaded, ACCESS, PLAYER, request(loaded), small).state());
        assertEquals(saved, StockApplicationCodec.save(pending));
        var corrupt = saved.copy(); corrupt.putInt("Version", 42);
        assertThrows(IllegalArgumentException.class, () -> StockApplicationCodec.load(corrupt, UUID.randomUUID()));
        var extra = saved.copy(); extra.putString("Unknown", "field");
        assertThrows(IllegalArgumentException.class, () -> StockApplicationCodec.load(extra, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> StockApplicationCodec.load(saved, pending.session()));
    }
    @Test void exhaustedCountersNeverWrapAndMalformedArchiveCountersFailClosed() {
        var start = initial(); var published = StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state();
        var sequenceEnd = new StockApplication.State(published.device(),published.session(),published.revision(),published.application(),true,Long.MAX_VALUE,published.catalog(),List.of());
        assertEquals("sequence_exhausted",StockApplication.confirm(sequenceEnd,ACCESS,PLAYER,request(sequenceEnd),LIMITS).code());
        assertEquals(Long.MAX_VALUE,StockApplicationCodec.load(StockApplicationCodec.save(sequenceEnd),UUID.randomUUID()).nextSequence());
        var versionEnd = new StockApplication.State(published.device(),published.session(),Long.MAX_VALUE,published.application(),true,published.nextSequence(),published.catalog(),List.of());
        assertEquals("version_exhausted",StockApplication.replace(versionEnd,ACCESS,versionEnd.fence(),List.of(),LIMITS).code());
        assertEquals("version_exhausted",StockApplication.bind(versionEnd,APP,UUID.randomUUID()).code());
        assertFalse(StockApplication.revoke(versionEnd,UUID.randomUUID()).open());
        var pending = StockApplication.confirm(published,ACCESS,PLAYER,request(published),LIMITS).state();
        var invalid = new StockApplication.State(pending.device(),pending.session(),pending.revision(),pending.application(),true,4,pending.catalog(),pending.inputs());
        assertThrows(IllegalArgumentException.class,()->StockApplicationCodec.save(invalid));
        var root = StockApplicationCodec.save(pending); var value=root.getCompound("State"); value.putInt("Unexpected",1);
        assertThrows(IllegalArgumentException.class,()->StockApplicationCodec.load(root,UUID.randomUUID()));
        System.out.println("retained_units catalog="+StockApplication.retainedUnits(published)+" input="+StockApplication.retainedUnits(pending));
    }
    @Test void textAddressAndSelectionBoundsApplyToCompleteImmutableBundles() {
        var start=initial();var source=new ArrayList<>(List.of(entry()));
        var catalog=StockApplication.replace(start,ACCESS,start.fence(),source,LIMITS).state(); source.clear();
        assertEquals(1,catalog.catalog().size());
        assertThrows(UnsupportedOperationException.class,()->catalog.catalog().clear());
        assertThrows(UnsupportedOperationException.class,()->entry().icon().fields().clear());
        var tooLong=new StockApplication.Entry("key","a".repeat(257),"batch",entry().icon(),record());
        assertSame(start,StockApplication.replace(start,ACCESS,start.fence(),List.of(tooLong),LIMITS).state());
        var nonNative=new StockApplication.Entry("key","title","batch",record("item",text("minecraft:stone"),"count",number(1),"components",record()),record());
        assertSame(start,StockApplication.replace(start,ACCESS,start.fence(),List.of(nonNative),LIMITS).state());
        var duplicate=new StockApplication.Request(catalog.fence(),1,List.of(new StockApplication.Selection("recipe:a",1),new StockApplication.Selection("recipe:a",1)),"A");
        assertSame(catalog,StockApplication.confirm(catalog,ACCESS,PLAYER,duplicate,LIMITS).state());
        var tooFar=new StockApplication.Request(catalog.fence(),1,request(catalog).selections(),"a".repeat(129));
        assertSame(catalog,StockApplication.confirm(catalog,ACCESS,PLAYER,tooFar,LIMITS).state());
        var emptyAddress=new StockApplication.Request(catalog.fence(),1,request(catalog).selections()," ");
        assertSame(catalog,StockApplication.confirm(catalog,ACCESS,PLAYER,emptyAddress,LIMITS).state());
        var wrongDevice=new StockApplication.Fence(UUID.randomUUID(),catalog.session(),catalog.revision());
        assertEquals("device_changed",StockApplication.poll(catalog,ACCESS,wrongDevice).code());
        var changed=StockApplication.replace(catalog,ACCESS,catalog.fence(),List.of(),LIMITS).state();
        assertEquals("stale_revision",StockApplication.confirm(changed,ACCESS,PLAYER,request(catalog),LIMITS).code());
    }
    @Test void logicalValueOccurrenceExhaustionCannotOverflowOrPublish() {
        DataValue huge=text("payload");
        for(int n=0;n<55;n++) huge=new DataValue.ListValue(List.of(huge,huge));
        var start=initial();var large=new StockApplication.Entry("key","title","unit",entry().icon(),record("data",huge));
        var accepted=StockApplication.replace(start,ACCESS,start.fence(),List.of(large),new StockApplication.Limits(1,1,Long.MAX_VALUE,256,128));
        assertEquals("capacity",accepted.code());assertSame(start,accepted.state());
        var values=new ArrayList<StockApplication.Entry>();
        for(int n=0;n<64;n++)values.add(new StockApplication.Entry("key:"+n,"title","unit",entry().icon(),record("data",huge)));
        assertEquals("capacity",StockApplication.replace(start,ACCESS,start.fence(),values,new StockApplication.Limits(64,1,Long.MAX_VALUE,256,128)).code());
    }
    @Test void publishedCatalogIsFrozenIntoExplicitlyPolledAndAcknowledgedApplicationInput() {
        var initial = initial();
        var published = StockApplication.replace(initial, ACCESS, initial.fence(), List.of(entry()), LIMITS);
        assertEquals("accepted", published.code());
        var confirmed = StockApplication.confirm(published.state(), ACCESS, PLAYER, request(published.state()), LIMITS);
        assertEquals("input_queued", confirmed.code());
        var input = confirmed.input().orElseThrow();
        assertEquals(entry(), input.items().getFirst().entry());
        assertEquals(3, input.items().getFirst().quantity());
        assertEquals(APP, input.application());
        var cleared = StockApplication.replace(confirmed.state(), ACCESS, confirmed.state().fence(), List.of(), LIMITS).state();
        var polled = StockApplication.poll(cleared, ACCESS, cleared.fence());
        assertSame(cleared, polled.state()); assertEquals(input, polled.input().orElseThrow());
        var acked = StockApplication.ack(cleared, ACCESS, cleared.fence(), input.sequence());
        assertEquals("acknowledged", acked.code());
        assertTrue(StockApplication.poll(acked.state(), ACCESS, acked.state().fence()).input().isEmpty());
        assertEquals(2, acked.state().nextSequence());
    }
}

