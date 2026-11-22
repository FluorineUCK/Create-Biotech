package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.*;

class LibraryWriteHeadTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }
    static final LibraryDriveLimits LIMITS = new LibraryDriveLimits(2, 1, 2, 3, 128, 32768, LibraryBookMediaTest.LIMITS);
    final LibraryPositionedWriteSourceTest owner = new LibraryPositionedWriteSourceTest();
    final LibraryPositionedWriteSourceTest.Fixture fixture = owner.new Fixture();
    final LibraryReadHead.Source source = new LibraryPositionedReadSource(fixture.access,
        track -> LibraryPositionedWriteSourceTest.SHELF, volume -> true, fixture.source);
    final LibraryWriteBatch batch = new LibraryWriteBatch(UUID.randomUUID(), 10);
    final LibraryDriveCommand.Admission admission = new LibraryDriveCommand.Admission(UUID.randomUUID(), batch);
    LibraryReadHead.State selected() {
        return new LibraryReadHead.State(2, Optional.of(LibraryPositionedWriteSourceTest.TRACK), 2, Optional.empty(), Optional.empty());
    }
    LibraryDriveCommand command() {
        return new LibraryDriveCommand(new LibraryTrackAddress(0, 0, 0).value(), new DataValue.TextValue("call"),
            "write", 2, fixture.operation().mutation().value());
    }
    @Test void segmentedBeginReservesOnlyTheDeviceAndAbortSurvivesMissingMedium() {
        var original = fixture.book.copy();
        var fields = new LinkedHashMap<>(fixture.operation().mutation().value().fields());
        fields.remove("op"); fields.remove("value");
        fields.put("format", new DataValue.TextValue("typed_record_v1"));
        fields.put("bytes", new DataValue.IntegerValue(4097));
        var begin = new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("begin"),
            "write_begin",2,new DataValue.RecordValue(fields));
        var accepted = begin.apply(selected(),source,LIMITS,admission);
        assertEquals(new DataValue.TextValue("receiving"),accepted.reply().field("code"));
        assertEquals(3,accepted.state().sequence());
        var saved = LibraryReadHeadCodec.encode(accepted.state(),LIMITS);
        var restored = LibraryReadHeadCodec.decode(saved,LIMITS);
        assertEquals(accepted.state(),restored);
        assertTrue(LibraryReadHead.busy(restored));
        assertSame(restored,LibraryReadHead.prepareTick(restored,source,LIMITS).next(),"No clock-driven implicit commit");
        assertEquals("busy",assertThrows(LibraryBookAccess.Failure.class,()->LibraryReadHead.seek(restored,
            LibraryPositionedWriteSourceTest.TRACK,source,LIMITS)).code());
        assertEquals("stale_operation",assertThrows(LibraryBookAccess.Failure.class,
            ()->begin.apply(restored,source,LIMITS,admission)).code());
        assertEquals(0,fixture.writes); assertTrue(ItemStack.matches(original,fixture.book));
        fixture.available=false;
        var abort = new LibraryDriveCommand(begin.target(),new DataValue.TextValue("abort"),"write_abort",3,DataValue.NullValue.INSTANCE);
        var cancelled = abort.apply(restored,source,LIMITS,admission);
        assertEquals(new DataValue.TextValue("aborted"),cancelled.reply().field("code"));
        assertFalse(LibraryReadHead.busy(cancelled.state()));
        assertEquals(3,cancelled.state().sequence(),"Retired operation numbers are not reused");
        assertEquals(0,fixture.writes); assertTrue(ItemStack.matches(original,fixture.book));
    }
    @Test void segmentedReservationRechecksGrantVersionAndChargesDeclaredCapacityBeforePublishing() {
        var fields = new LinkedHashMap<>(fixture.operation().mutation().value().fields());
        fields.remove("op"); fields.remove("value");
        fields.put("format",new DataValue.TextValue("typed_record_v1"));
        fields.put("bytes",new DataValue.IntegerValue(4097));
        var begin = new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("begin"),
            "write_begin",2,new DataValue.RecordValue(fields));
        for (var denied : List.of(LibraryServiceConfiguration.Access.READ_ONLY,LibraryServiceConfiguration.Access.CLOSED)) {
            fixture.grant=denied;
            assertEquals(denied==LibraryServiceConfiguration.Access.READ_ONLY?"readonly":"denied",
                assertThrows(LibraryBookAccess.Failure.class,()->begin.apply(selected(),source,LIMITS,admission)).code());
            assertEquals(0,fixture.writes);
        }
        fixture.grant=LibraryServiceConfiguration.Access.READ_WRITE;
        var narrow = new LibraryDriveLimits(2,1,2,3,128,4097,LIMITS.archive());
        assertEquals("buffer_full",assertThrows(LibraryBookAccess.Failure.class,
            ()->begin.apply(selected(),source,narrow,admission)).code(),"Header plus declared bytes must fit, not just the empty buffer");
        assertEquals(0,fixture.writes);
        fixture.access.put(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF,2),"reading",
            new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.IntegerValue(45)));
        assertEquals("stale_version",assertThrows(LibraryBookAccess.Failure.class,
            ()->begin.apply(selected(),source,LIMITS,admission)).code());
        assertEquals(1,fixture.writes,"Only the explicit intervening edit wrote the book");
    }
    @Test void segmentedChunksResumeAtAnExplicitOffsetWithoutPublishingTheBook() {
        var original = fixture.book.copy();
        var fields = new LinkedHashMap<>(fixture.operation().mutation().value().fields());
        fields.remove("op"); fields.remove("value");
        fields.put("format",new DataValue.TextValue("typed_record_v1"));
        fields.put("bytes",new DataValue.IntegerValue(260));
        var begin = new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("begin"),
            "write_begin",2,new DataValue.RecordValue(fields));
        var initial = begin.apply(selected(),source,LIMITS,admission).state();
        var bytes = new DataValue.NbtValue(7,new DataValue.ListValue(
            java.util.stream.IntStream.range(0,130).mapToObj(i -> (DataValue)new DataValue.IntegerValue((byte)i)).toList()));
        var chunk = new LibraryDriveCommand(begin.target(),new DataValue.TextValue("part"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(0),"bytes",bytes)));
        var admitted = chunk.apply(initial,source,LIMITS,admission);
        assertEquals(new DataValue.TextValue("accepted"),admitted.reply().field("code"));
        var state = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(admitted.state(),LIMITS),LIMITS);
        assertEquals(admitted.state(),state);
        byte[] expectedBytes = new byte[130];
        for (int i=0;i<expectedBytes.length;i++) expectedBytes[i]=(byte)i;
        assertArrayEquals(expectedBytes,HexFormat.of().parseHex(state.transfer().orElseThrow().segments().getFirst().hex()));
        assertEquals(3,state.sequence(),"All chunks belong to one write identity");
        var status = new LibraryDriveCommand(begin.target(),new DataValue.TextValue("status"),"status",0,DataValue.NullValue.INSTANCE);
        assertEquals(new DataValue.TextValue("busy"),status.apply(state,source,LIMITS).reply().field("code"));
        assertEquals(new DataValue.IntegerValue(130),((DataValue.RecordValue)status.apply(state,source,LIMITS).reply().field("value")).field("offset"));
        final var copying = state;
        assertEquals("busy",assertThrows(LibraryBookAccess.Failure.class,()->chunk.apply(copying,source,LIMITS,admission)).code());
        for (long tick=0;tick<LIMITS.readTicks(130);tick++) {
            var prepared=LibraryReadHead.prepareTick(state,source,LIMITS);
            assertTrue(prepared.recovery().isEmpty()); prepared.commit().run();
            state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(prepared.next(),LIMITS),LIMITS);
        }
        assertEquals(new DataValue.TextValue("receiving"),status.apply(state,source,LIMITS).reply().field("code"));
        final var waiting=state;
        assertSame(waiting,LibraryReadHead.prepareTick(waiting,source,LIMITS).next());
        assertEquals("stale_offset",assertThrows(LibraryBookAccess.Failure.class,()->chunk.apply(waiting,source,LIMITS,admission)).code());
        var hole=new LibraryDriveCommand(begin.target(),new DataValue.TextValue("hole"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(131),"bytes",bytes)));
        assertEquals("stale_offset",assertThrows(LibraryBookAccess.Failure.class,()->hole.apply(waiting,source,LIMITS,admission)).code());
        var second=new LibraryDriveCommand(begin.target(),new DataValue.TextValue("part2"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(130),"bytes",bytes)));
        fixture.grant=LibraryServiceConfiguration.Access.READ_ONLY;
        assertEquals("readonly",assertThrows(LibraryBookAccess.Failure.class,()->second.apply(waiting,source,LIMITS,admission)).code());
        fixture.grant=LibraryServiceConfiguration.Access.READ_WRITE;
        var finished=second.apply(waiting,source,LIMITS,admission).state();
        for(long tick=0;tick<LIMITS.readTicks(130);tick++) finished=LibraryReadHead.prepareTick(finished,source,LIMITS).next();
        assertEquals(finished,LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(finished,LIMITS),LIMITS));
        assertSame(finished,LibraryReadHead.prepareTick(finished,source,LIMITS).next(),"Complete upload still needs explicit commit");
        final var full=finished;
        var excess=new LibraryDriveCommand(begin.target(),new DataValue.TextValue("excess"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(260),"bytes",bytes)));
        assertEquals("length_mismatch",assertThrows(LibraryBookAccess.Failure.class,()->excess.apply(full,source,LIMITS,admission)).code());
        assertEquals(0,fixture.writes); assertTrue(ItemStack.matches(original,fixture.book));
    }
    @Test void segmentedAppendRejectsAChangedBookVersionWithoutReplacingItsStaging() {
        var fields = new LinkedHashMap<>(fixture.operation().mutation().value().fields());
        fields.remove("op"); fields.remove("value");
        fields.put("format",new DataValue.TextValue("typed_record_v1"));
        fields.put("bytes",new DataValue.IntegerValue(260));
        var begin=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("begin"),
            "write_begin",2,new DataValue.RecordValue(fields));
        var state=begin.apply(selected(),source,LIMITS,admission).state();
        var checkpoint=LibraryReadHeadCodec.encode(state,LIMITS);
        var chunk=new LibraryDriveCommand(begin.target(),new DataValue.TextValue("part"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(0),"bytes",
                new DataValue.NbtValue(7,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1)))))));
        var tight=new LibraryDriveLimits(2,1,2,3,128,1,LIMITS.archive());
        assertEquals("buffer_full",assertThrows(LibraryBookAccess.Failure.class,()->chunk.apply(state,source,tight,admission)).code());
        fixture.access.put(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF,2),"reading",
            new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.IntegerValue(77)));
        assertEquals("stale_version",assertThrows(LibraryBookAccess.Failure.class,()->chunk.apply(state,source,LIMITS,admission)).code());
        assertEquals(checkpoint,LibraryReadHeadCodec.encode(state,LIMITS));
        assertEquals(1,fixture.writes,"Only the independent edit wrote the medium");
        assertEquals(new DataValue.IntegerValue(77),owner.media.read(fixture.book).get("reading").value());
        var abort=new LibraryDriveCommand(begin.target(),new DataValue.TextValue("abort"),"write_abort",3,DataValue.NullValue.INSTANCE);
        assertFalse(LibraryReadHead.busy(abort.apply(state,source,LIMITS,admission).state()));
    }
    LibraryReadHead.State upload(DataValue value) {
        var bytes=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(
            new DataValue.RecordValue(Map.of("value",value)),
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(32768,32768,LIMITS.archive()));
        return uploadBytes(bytes);
    }
    LibraryReadHead.State uploadBytes(byte[] bytes) {
        var fields=new LinkedHashMap<>(fixture.operation().mutation().value().fields());
        fields.remove("op"); fields.remove("value"); fields.put("format",new DataValue.TextValue("typed_record_v1"));
        fields.put("bytes",new DataValue.IntegerValue(bytes.length));
        var state=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("begin"),
            "write_begin",2,new DataValue.RecordValue(fields)).apply(selected(),source,LIMITS,admission).state();
        var values=new ArrayList<DataValue>(); for(byte b:bytes) values.add(new DataValue.IntegerValue(b));
        state=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("chunk"),"write_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(0),"bytes",new DataValue.NbtValue(7,new DataValue.ListValue(values)))))
            .apply(state,source,LIMITS,admission).state();
        while(state.transfer().orElseThrow().remaining()>0) state=LibraryReadHead.prepareTick(state,source,LIMITS).next();
        return state;
    }
    @Test void segmentedCommitUsesBoundedDecodeAndTheExistingAtomicWriteRecovery() {
        DataValue value=new DataValue.RecordValue(Map.of("count",new DataValue.IntegerValue(7),"nbt",
            new DataValue.NbtValue(7,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-128),new DataValue.IntegerValue(127))))));
        var staged=upload(value);
        var commit=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("commit"),"write_commit",3,DataValue.NullValue.INSTANCE);
        var state=commit.apply(staged,source,LIMITS,admission).state();
        assertEquals(3,state.sequence()); assertEquals(0,fixture.writes);
        // One unit per activity tick makes both partial decoding and recovery observable.
        var slow=new LibraryDriveLimits(2,1,2,3,128,32768,LIMITS.archive(),1);
        var first=LibraryReadHead.prepareTick(state,source,slow).next();
        assertTrue(first.transfer().isPresent()); assertTrue(first.writing().isEmpty());
        assertEquals(0,fixture.writes);
        for(int i=0;i<30;i++) first=LibraryReadHead.prepareTick(first,source,slow).next();
        long paid=first.transfer().orElseThrow().commit().orElseThrow().steps();
        state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(first,slow),slow);
        assertTrue(state.transfer().orElseThrow().commit().orElseThrow().restoring());
        state=LibraryReadHead.prepareTick(state,source,slow).next();
        assertEquals(paid,state.transfer().orElseThrow().commit().orElseThrow().steps(),"Reconstruction must not replay all paid work in one tick");
        assertTrue(state.transfer().orElseThrow().commit().orElseThrow().restoring());
        int ticks=0;
        while(state.transfer().isPresent() && ++ticks<10000) state=LibraryReadHead.prepareTick(state,source,slow).next();
        assertTrue(state.writing().isPresent(),"A valid stream must become ordinary mechanical work, not disappear: "+state.result());
        assertTrue(ticks>10); assertEquals(0,fixture.writes);
        assertEquals(admission.batch(),state.writing().orElseThrow().admission().batch());
        assertEquals(3,state.writing().orElseThrow().operation().sequence());
        state=LibraryReadHead.withWrite(state,state.writing().orElseThrow().seal(false));
        LibraryReadHead.PreparedTick completion=null;
        while(state.result().isEmpty()) {
            completion=LibraryReadHead.prepareTick(state,source,LIMITS); state=completion.next();
            if(completion.recovery().isEmpty()) completion.commit().run();
        }
        assertEquals("written",state.result().orElseThrow().code());
        assertEquals(0,fixture.writes,"Neither decoder nor result preparation may publish the book");
        var recovery=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(completion.recovery().orElseThrow(),LIMITS),LIMITS);
        fixture.failAfterReplace=true; assertThrows(IllegalStateException.class,completion.commit()::run);
        assertEquals(1,fixture.writes); fixture.failAfterReplace=false;
        var replay=LibraryReadHead.prepareTick(recovery,source,LIMITS); replay.commit().run();
        assertEquals(state,replay.next()); assertEquals(1,fixture.writes);
        assertEquals(value,owner.media.read(fixture.book).get("reading").value());
    }
    @Test void segmentedCommitConflictAndInvalidStreamNeverPublishOrLoseTheFailureResult() {
        var commit=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("commit"),"write_commit",3,DataValue.NullValue.INSTANCE);
        var staged=upload(new DataValue.IntegerValue(99));
        var decoding=commit.apply(staged,source,LIMITS,admission).state();
        var conflicted=LibraryReadHead.sealTransfer(decoding,true);
        conflicted=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(conflicted,LIMITS),LIMITS);
        var rejected=LibraryReadHead.prepareTick(conflicted,source,LIMITS);
        assertEquals("conflict",rejected.next().result().orElseThrow().code());
        assertTrue(rejected.recovery().isEmpty()); rejected.commit().run(); assertEquals(0,fixture.writes);
        var invalid=commit.apply(uploadBytes(new byte[]{1,2,3}),source,LIMITS,admission).state();
        for(int tick=0;tick<1000 && invalid.result().isEmpty();tick++) invalid=LibraryReadHead.prepareTick(invalid,source,LIMITS).next();
        assertEquals("invalid_record",invalid.result().orElseThrow().code());
        assertEquals(invalid,LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(invalid,LIMITS),LIMITS));
        assertTrue(invalid.transfer().isEmpty()); assertTrue(invalid.writing().isEmpty()); assertEquals(0,fixture.writes);
        fixture.grant=LibraryServiceConfiguration.Access.READ_ONLY;
        var revoked=LibraryReadHead.sealTransfer(decoding,false);
        for(int tick=0;tick<1000 && revoked.result().isEmpty();tick++) revoked=LibraryReadHead.prepareTick(revoked,source,LIMITS).next();
        assertEquals("readonly",revoked.result().orElseThrow().code()); assertEquals(0,fixture.writes);
    }
    @Test void legacyUploadWorkspaceKeepsItsPartitionsAndPaidContinuationAfterReload() {
        var staged=upload(new DataValue.IntegerValue(99));
        var commit=new LibraryDriveCommand(new LibraryTrackAddress(0,0,0).value(),new DataValue.TextValue("commit"),"write_commit",3,DataValue.NullValue.INSTANCE);
        var state=commit.apply(staged,source,LIMITS,admission).state();
        var transfer=state.transfer().orElseThrow();
        var fields=new LinkedHashMap<>(transfer.commit().orElseThrow().value().fields());
        assertEquals(new DataValue.IntegerValue(2),fields.remove("workspace"));
        long scratch=((DataValue.IntegerValue)fields.get("scratch")).value()/2;
        fields.put("scratch",new DataValue.IntegerValue(scratch));
        var legacy=LibraryUploadCommit.decode(new DataValue.RecordValue(fields));
        assertEquals(2*scratch,legacy.reservedBytes());
        assertEquals(fields,legacy.value().fields(),"Loading must not silently upgrade an admitted legacy operation");
        var paid=legacy.advance(transfer.input(),30);
        var restored=LibraryUploadCommit.decode(paid.value());
        restored=restored.advance(transfer.input(),1);
        assertTrue(restored.restoring());assertEquals(paid.steps(),restored.steps());
        for(int ticks=0;!restored.done()&&ticks<20000;ticks++)restored=restored.advance(transfer.input(),1);
        assertTrue(restored.done());assertNull(restored.decoded().error());
        assertEquals(new DataValue.IntegerValue(99),((DataValue.RecordValue)restored.decoded().value()).field("value"));
        assertFalse(restored.value().fields().containsKey("workspace"));
        var unknown=new LinkedHashMap<>(fields);unknown.put("workspace",new DataValue.IntegerValue(3));
        assertEquals("invalid_checkpoint",assertThrows(LibraryBookAccess.Failure.class,
            ()->LibraryUploadCommit.decode(new DataValue.RecordValue(unknown))).code());
    }
    @Test void pooledUploadWorkspaceStillRejectsExhaustionWithoutChangingTheBook() {
        var original=fixture.book.copy();
        var staged=upload(new DataValue.TextValue("bounded".repeat(50)));
        var transfer=staged.transfer().orElseThrow();
        var bounded=LibraryUploadCommit.start(admission.batch(),Math.toIntExact(transfer.length()),4096,LIMITS.archive());
        assertEquals(4096,bounded.reservedBytes(),"The parser and live values share one finite reservation, not two free budgets");
        var state=new LibraryReadHead.State(staged.sequence(),staged.track(),staged.slot(),Optional.empty(),Optional.empty(),
            Optional.empty(),Optional.of(transfer.withCommit(bounded)));
        state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,LIMITS),LIMITS);
        for(int tick=0;state.result().isEmpty()&&tick<20000;tick++) {
            var next=LibraryReadHead.prepareTick(state,source,LIMITS);next.commit().run();state=next.next();
        }
        assertEquals("buffer_full",state.result().orElseThrow().code());
        assertTrue(state.transfer().isEmpty());assertTrue(state.writing().isEmpty());assertEquals(0,fixture.writes);
        assertTrue(ItemStack.matches(original,fixture.book));
        assertEquals(state,LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,LIMITS),LIMITS));
    }
    @Test void segmentedReadReturnsExplicitBoundedSlicesAndRestoresWithoutEditingTheBook() {
        var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.BiocomputingConfig(
            new net.neoforged.neoforge.common.ModConfigSpec.Builder()).libraryDrive(false);
        var value=new DataValue.TextValue("库存".repeat(1100));
        fixture.access.put(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF,2),"reading",new LibraryEntry(LibraryEntry.Kind.DATA,value));
        var original=fixture.book.copy(); int writes=fixture.writes;
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        var readSource=new LibraryPositionedReadSource(fixture.access,track->LibraryPositionedWriteSourceTest.SHELF,volume->allowed.get());
        var target=new LibraryTrackAddress(0,0,0).value();
        var begin=new LibraryDriveCommand(target,new DataValue.TextValue("begin"),"read_begin",2,new DataValue.TextValue("reading"));
        var state=begin.apply(selected(),readSource,limits).state();
        var info=new LibraryDriveCommand(target,new DataValue.TextValue("info"),"read_info",3,DataValue.NullValue.INSTANCE);
        var initial=state;
        assertEquals("busy",assertThrows(LibraryBookAccess.Failure.class,()->info.apply(initial,readSource,limits)).code());
        int ticks=0; boolean copyRestored=false;
        while(ticks++<20000) {
            long previousWork=state.reading().orElseThrow().steps();
            state=LibraryReadHead.prepareTick(state,readSource,limits).next();
            assertTrue(state.reading().isPresent(),"Reading failed: "+state.result());
            assertTrue(state.reading().orElseThrow().steps()-previousWork<=limits.codecWorkPerTick(),"Encoding, recovery and byte copy share one device budget");
            if(ticks==160) state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,limits),limits);
            if(!copyRestored && state.reading().filter(r -> !r.ready() && r.copiedBytes()>0).isPresent()) {
                state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,limits),limits); copyRestored=true;
                assertTrue(state.reading().orElseThrow().restoring());
            }
            var status=new LibraryDriveCommand(target,new DataValue.TextValue("status"),"status",0,DataValue.NullValue.INSTANCE).apply(state,readSource,limits);
            if(status.reply().field("code").equals(new DataValue.TextValue("read_ready"))) break;
            assertTrue(state.result().isEmpty(),"Reading failed: "+state.result());
        }
        assertTrue(ticks<20000); assertTrue(copyRestored,"The byte-copy phase must have its own resumable budget");
        state=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(state,limits),limits);
        var metadata=(DataValue.RecordValue)info.apply(state,readSource,limits).reply().field("value");
        long length=((DataValue.IntegerValue)metadata.field("bytes")).value();
        assertTrue(length>4096,"Keep an actually oversized value; do not shrink it to fit one frame");
        var buffer=new java.io.ByteArrayOutputStream();
        var wire=new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits(4096,131072,limits.archive());
        for(long offset=0;offset<length;) {
            int count=(int)Math.min(32,length-offset);
            var chunk=new LibraryDriveCommand(target,new DataValue.TextValue("part"),"read_chunk",3,
                new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(offset),"count",new DataValue.IntegerValue(count))));
            var reply=chunk.apply(state,readSource,limits);
            assertSame(state,reply.state(),"Explicit reading does not consume or silently advance a device cursor");
            assertEquals(reply.reply(),chunk.apply(state,readSource,limits).reply());
            assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(reply.reply(),wire).length<=4096);
            var part=(DataValue.NbtValue)((DataValue.RecordValue)reply.reply().field("value")).field("bytes");
            for(var b:((DataValue.ListValue)part.value()).values()) buffer.write((byte)((DataValue.IntegerValue)b).value());
            offset+=count;
        }
        var decoded=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.decode(buffer.toByteArray(),
            new com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.Limits((int)length,131072,limits.archive()));
        assertEquals(value,decoded.field("value"));
        final var ready=state;
        var firstChunk=new LibraryDriveCommand(target,new DataValue.TextValue("first"),"read_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(0),"count",new DataValue.IntegerValue(32))));
        assertEquals("payload_too_large",assertThrows(LibraryBookAccess.Failure.class,
            ()->firstChunk.apply(ready,readSource,limits,null,31)).code());
        var outside=new LibraryDriveCommand(target,new DataValue.TextValue("outside"),"read_chunk",3,
            new DataValue.RecordValue(Map.of("offset",new DataValue.IntegerValue(length),"count",new DataValue.IntegerValue(1))));
        assertEquals("invalid_range",assertThrows(LibraryBookAccess.Failure.class,()->outside.apply(ready,readSource,limits)).code());
        allowed.set(false);
        assertEquals("denied",assertThrows(LibraryBookAccess.Failure.class,()->info.apply(ready,readSource,limits)).code());
        assertEquals("denied",assertThrows(LibraryBookAccess.Failure.class,()->firstChunk.apply(ready,readSource,limits)).code());
        assertEquals(writes,fixture.writes); assertTrue(ItemStack.matches(original,fixture.book));
        allowed.set(true);
        fixture.access.put(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF,2),"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.IntegerValue(1)));
        assertEquals("changed",assertThrows(LibraryBookAccess.Failure.class,()->firstChunk.apply(ready,readSource,limits)).code());
        allowed.set(false);
        var close=new LibraryDriveCommand(target,new DataValue.TextValue("close"),"read_close",3,DataValue.NullValue.INSTANCE);
        assertFalse(LibraryReadHead.busy(close.apply(ready,readSource,limits).state()));
        assertEquals(writes+1,fixture.writes,"Only the explicit intervening edit changed the book");
    }
    @Test void serialWriteAdmissionOccupiesTheMechanismWithoutWritingBeforeTimeAndBatchCompletion() {
        var before = fixture.book.copy();
        var accepted = command().apply(selected(), source, LIMITS, admission);
        assertEquals(new DataValue.TextValue("accepted"), accepted.reply().field("code"));
        assertEquals(3, accepted.state().sequence());
        assertEquals(0, fixture.writes); assertTrue(ItemStack.matches(before, fixture.book));
        assertEquals("busy", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.read(accepted.state(), "reading", source, LIMITS)).code());
    }
    @Test void admittedWriteSurvivesCheckpointWithItsIdentityBatchAndRemainingWork() {
        var state = command().apply(selected(), source, LIMITS, admission).state();
        var raw = LibraryReadHeadCodec.encode(state, LIMITS);
        assertEquals(state, LibraryReadHeadCodec.decode(raw, LIMITS));
        assertEquals(0, fixture.writes);
    }
    @Test void activityTicksCannotCommitUntilBatchIsSealedAndBothCheckpointsFit() {
        var state = command().apply(selected(), source, LIMITS, admission).state();
        long total = state.writing().orElseThrow().total(); assertTrue(total > 3);
        for (long tick = 0; tick < total + 2; tick++) {
            var next = LibraryReadHead.prepareTick(state, source, LIMITS);
            assertTrue(next.recovery().isEmpty()); next.commit().run();
            state = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(next.next(), LIMITS), LIMITS);
        }
        assertEquals(0, state.writing().orElseThrow().remaining());
        assertTrue(state.result().isEmpty()); assertEquals(0, fixture.writes, "Elapsed time is not a conflict verdict");
        state = LibraryReadHead.withWrite(state, state.writing().orElseThrow().seal(false));
        var completion = LibraryReadHead.prepareTick(state, source, LIMITS);
        var recovery = completion.recovery().orElseThrow();
        assertEquals(recovery, LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(recovery, LIMITS), LIMITS));
        assertEquals(completion.next(), LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(completion.next(), LIMITS), LIMITS));
        assertEquals(0, fixture.writes, "Final reply preparation is still not the physical commit");
        completion.commit().run(); assertEquals(1, fixture.writes);
        assertEquals("written", completion.next().result().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(44), owner.media.read(fixture.book).get("reading").value());
        assertTrue(completion.next().writing().isPresent(), "Receipt cleanup context lasts until explicit ack");
    }
    LibraryReadHead.State elapsed(boolean conflict) {
        var state = command().apply(selected(), source, LIMITS, admission).state();
        state = LibraryReadHead.withWrite(state, state.writing().orElseThrow().seal(conflict));
        while (state.writing().orElseThrow().remaining() > 1) {
            var next = LibraryReadHead.prepareTick(state, source, LIMITS);
            assertTrue(next.recovery().isEmpty()); state = next.next();
        }
        return state;
    }
    @Test void conflictAndChangedPermissionsCompleteWithoutWritingAndUncertainCommitNeverTurnsIntoAFailureReply() {
        var failed = LibraryReadHead.prepareTick(elapsed(true), source, LIMITS);
        assertEquals("conflict", failed.next().result().orElseThrow().code()); assertTrue(failed.recovery().isEmpty());
        failed.commit().run(); assertEquals(0, fixture.writes);
        var ready = elapsed(false); fixture.grant = LibraryServiceConfiguration.Access.READ_ONLY;
        assertEquals("readonly", LibraryReadHead.prepareTick(ready, source, LIMITS).next().result().orElseThrow().code());
        fixture.grant = LibraryServiceConfiguration.Access.READ_WRITE;
        var prepared = LibraryReadHead.prepareTick(ready, source, LIMITS);
        var recovery = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(prepared.recovery().orElseThrow(), LIMITS), LIMITS);
        fixture.failAfterReplace = true; assertThrows(IllegalStateException.class, prepared.commit()::run); assertEquals(1, fixture.writes);
        fixture.grant = LibraryServiceConfiguration.Access.CLOSED;
        assertEquals("commit_recovery_denied", assertThrows(LibraryBookAccess.Failure.class,
            () -> LibraryReadHead.prepareTick(recovery, source, LIMITS)).code());
        fixture.grant = LibraryServiceConfiguration.Access.READ_WRITE; fixture.failAfterReplace = false;
        var replay = LibraryReadHead.prepareTick(recovery, source, LIMITS); replay.commit().run();
        assertEquals(prepared.next(), replay.next()); assertEquals(1, fixture.writes);
        assertTrue(LibraryReadHead.take(replay.next(), replay.next().sequence(), LIMITS).state().writing().isEmpty());
    }
    @Test void aLocalEditDuringAdmittedMechanicalWorkWinsItsOwnVersionAndIsNeverOverwrittenByTheOldRequest() {
        var admitted = command().apply(selected(), source, LIMITS, admission).state();
        admitted = LibraryReadHead.withWrite(admitted, admitted.writing().orElseThrow().seal(false));
        var edited = fixture.access.put(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF, 2), "reading",
            new LibraryEntry(LibraryEntry.Kind.DATA, new DataValue.IntegerValue(99)));
        var book = fixture.book.copy(); var version = fixture.access.stat(edited, "reading").field("version");
        var restored = LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(admitted, LIMITS), LIMITS);
        while (restored.result().isEmpty()) {
            var tick = LibraryReadHead.prepareTick(restored, source, LIMITS);
            assertTrue(tick.recovery().isEmpty(), "The stale request must never enter uncertain commit");
            tick.commit().run(); restored = tick.next();
        }
        assertEquals("stale_version", restored.result().orElseThrow().code());
        assertTrue(restored.writing().isEmpty()); assertEquals(1, fixture.writes, "Only the explicit local edit committed");
        assertTrue(ItemStack.matches(book, fixture.book));
        var acknowledged = LibraryReadHead.take(restored, restored.sequence(), LIMITS).state();
        assertEquals(version, fixture.access.stat(fixture.access.at(LibraryPositionedWriteSourceTest.SHELF, 2), "reading").field("version"));
        assertTrue(ItemStack.matches(book, fixture.book), "Acknowledging a failed old write cannot roll back the newer entry");
        assertTrue(acknowledged.result().isEmpty());
    }
    @Test void oversizedCombinedWorkAndReplyCannotCommitAndSchemaCannotBeDowngraded() {
        var ready = elapsed(false); var work = ready.writing().orElseThrow();
        var completion = LibraryReadHead.prepareTick(ready, source, LIMITS);
        var tag = LibraryReadHeadCodec.encode(ready, LIMITS);
        var value = (DataValue.RecordValue)com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.decode(tag, LIMITS.archive());
        var fields = new LinkedHashMap<>(value.fields()); fields.put("version", new DataValue.IntegerValue(2));
        assertThrows(RuntimeException.class, () -> LibraryReadHeadCodec.decode(
            com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(fields), LIMITS.archive()), LIMITS));
        // Bound exactly the work payload: its successful completion also retains receipt cleanup context.
        var output = new java.io.ByteArrayOutputStream();
        try { net.minecraft.nbt.NbtIo.write(com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(work.value(), LIMITS.archive()), new java.io.DataOutputStream(output)); }
        catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
        var tight = new LibraryDriveLimits(2, 1, 2, 3, 128, output.size(), LIMITS.archive());
        assertEquals("buffer_full", assertThrows(LibraryBookAccess.Failure.class, () -> LibraryReadHead.prepareTick(ready, source, tight)).code());
        assertEquals(0, fixture.writes); assertTrue(completion.recovery().isPresent());
    }
}
