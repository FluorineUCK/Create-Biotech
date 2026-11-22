package com.nobodiiiii.createbiotech.content.biocomputing.library.drive;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LibraryRecipePageCommandTest {
    static final LibraryTrackAddress TRACK = new LibraryTrackAddress(0, 0, 0);
    static final LibraryDriveLimits LIMITS = new LibraryDriveLimits(1, 0, 1, 1, 64, 65536, new ArchiveLimits(4096,65536,65536));
    static class Source implements LibraryReadHead.Source {
        int reads;
        public void validateTrack(LibraryTrackAddress track) {}
        public LibraryReadHead.Sample read(LibraryTrackAddress track, int slot, String entry) { throw new AssertionError("generic read"); }
        public LibraryReadHead.Sample recipePage(LibraryTrackAddress track, int slot, int page) {
            reads++; assertEquals(0, page); assertEquals(2, slot);
            return new LibraryReadHead.Sample(new DataValue.RecordValue(Map.of("witness", new DataValue.TextValue("owned"))),
                new DataValue.RecordValue(Map.of("page", new DataValue.IntegerValue(page))));
        }
        public void verify(LibraryTrackAddress track,int slot,String entry,DataValue.RecordValue fence) {}
    }
    static LibraryDriveCommand command(String op,long sequence,DataValue data) {
        return new LibraryDriveCommand(TRACK.value(),new DataValue.TextValue("rom"),op,sequence,data);
    }
    static LibraryReadHead.State finish(LibraryReadHead.State state, Source source) {
        for(int i=0;i<10000 && LibraryReadHead.busy(state);i++) state=LibraryReadHead.advance(state,source,LIMITS);
        assertFalse(LibraryReadHead.busy(state)); return state;
    }
    @Test void malformedPageRequestsFailBeforeReading() {
        var source=new Source();var selected=new LibraryReadHead.State(2,Optional.of(TRACK),2,Optional.empty(),Optional.empty());
        for(var data:List.of(new DataValue.IntegerValue(-1),new DataValue.IntegerValue(Long.MAX_VALUE),
            new DataValue.TextValue("0"),DataValue.NullValue.INSTANCE)) {
            assertEquals("invalid_page",assertThrows(LibraryBookAccess.Failure.class,()->command("recipe_page",2,data).apply(selected,source,LIMITS)).code());
        }
        assertEquals(0,source.reads);
    }
    @Test void positionedRecipePageUsesMechanicalBufferCheckpointAndExplicitAck() {
        var source=new Source();
        var state=new LibraryReadHead.State(2,Optional.of(TRACK),2,Optional.empty(),Optional.empty());
        var admitted=command("recipe_page",2,new DataValue.IntegerValue(0)).apply(state,source,LIMITS).state();
        assertEquals(1,source.reads); assertTrue(LibraryReadHead.busy(admitted));
        var restored=LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(admitted,LIMITS),LIMITS);
        assertEquals(admitted,restored);
        var done=finish(restored,source);
        assertEquals("recipe_page",done.result().orElseThrow().code());
        assertEquals(command("result",done.sequence(),DataValue.NullValue.INSTANCE).apply(done,source,LIMITS).reply(),
            command("result",done.sequence(),DataValue.NullValue.INSTANCE).apply(done,source,LIMITS).reply());
        assertEquals(done,command("result",done.sequence(),DataValue.NullValue.INSTANCE).apply(done,source,LIMITS).state());
        assertTrue(command("ack",done.sequence(),DataValue.NullValue.INSTANCE).apply(done,source,LIMITS).state().result().isEmpty());
        for(var pendingOrDone:List.of(admitted,done)) {
            var value=(DataValue.RecordValue)ValueNbtCodec.decode(LibraryReadHeadCodec.encode(pendingOrDone,LIMITS),LIMITS.archive());
            assertEquals(new DataValue.IntegerValue(5),value.field("version"));
            for(int old=1;old<=4;old++) {
                var fields=new LinkedHashMap<>(value.fields()); fields.put("version",new DataValue.IntegerValue(old));
                assertThrows(IllegalArgumentException.class,()->LibraryReadHeadCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(fields),LIMITS.archive()),LIMITS));
            }
        }
    }
}
