package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import org.junit.jupiter.api.*;

class LibraryMediaWorkspaceTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

    @Test void ordinaryMediaRecordCommitsWithinDefaultDeviceBufferInEveryFieldOrderAndRestores() {
        var archive = new ArchiveLimits(262144,2097152L,16777216L);
        var codec = new SerialRecordCodec.Limits(8192,524288,archive);
        var limits = new LibraryDriveLimits(20,10,4,20,32,131072,archive,32);
        var payload = new DataValue.TextValue(Base64.getEncoder().encodeToString(new byte[1829]));
        for (int position=0; position<4; position++) {
            var names = new ArrayList<>(List.of("schema","name","format"));
            names.add(position,"payload");
            var fields = new LinkedHashMap<String,DataValue>();
            for (String name:names) fields.put(name, switch(name) {
                case "payload" -> payload;
                case "schema" -> new DataValue.TextValue("speaker_file_v1");
                case "name" -> new DataValue.TextValue("sample.mp3");
                default -> new DataValue.TextValue("mp3");
            });
            var packet = new DataValue.RecordValue(Map.of("value",new DataValue.RecordValue(fields)));
            byte[] wire = SerialRecordCodec.encode(packet,codec);
            var parts = new ArrayList<LibraryWriteTransfer.Segment>();
            for (int offset=0;offset<wire.length;offset+=64)
                parts.add(new LibraryWriteTransfer.Segment(HexFormat.of().formatHex(wire,offset,Math.min(wire.length,offset+64))));
            var device = new UUID(1,2);
            var transfer = new LibraryWriteTransfer(device,33,"Tracks","sample.mp3",
                new LibraryMutation.Version(new UUID(3,4),"sample.mp3",0),LibraryEntry.Kind.DATA,
                LibraryWriteTransfer.FORMAT,wire.length,parts,wire.length,0,Optional.empty());
            var head = new LibraryReadHead.State(33,Optional.of(new LibraryTrackAddress(-1,0,0)),2,
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(transfer));
            var source = new LibraryReadHead.Source() {
                public void validateTrack(LibraryTrackAddress track) {}
                public void verifyTransfer(LibraryTrackAddress track,int slot,LibraryWriteTransfer t) {}
                public void verify(LibraryTrackAddress track,int slot,String entry,DataValue.RecordValue fence) {fail("Not a read");}
                public LibraryReadHead.Sample read(LibraryTrackAddress track,int slot,String entry) {throw new AssertionError("Not a read");}
            };
            var accepted = LibraryReadHead.commitTransfer(head,device,new LibraryWriteBatch(new UUID(5,6),200),source,limits)
                .transfer().orElseThrow();
            var operation = accepted.commit().orElseThrow();
            var input = accepted.input();
            var state = SerialDecoding.begin(input,false,codec);
            long peak=0;
            while (!state.done()) {
                state=state.advance();
                peak=Math.max(peak,state.parserAllocatedBytes()+8L*state.bufferStorageUnits());
            }
            assertNull(state.decoded().error());
            assertEquals(packet,state.decoded().value());
            assertTrue(peak<=operation.reservedBytes(),"position="+position+" peak="+peak+" reserved="+operation.reservedBytes());
            System.out.println("LIBRARY_MEDIA_WORKSPACE position="+position+" peak="+peak+" reserved="+operation.reservedBytes());
            var tooSmall=LibraryUploadCommit.start(new LibraryWriteBatch(new UUID(7,8),201),wire.length,16000,archive);
            var deniedCheckpoint=tooSmall.value();
            assertEquals("buffer_full",assertThrows(LibraryBookAccess.Failure.class,()->{
                var pending=tooSmall;
                while(!pending.done()) pending=pending.advance(input,32);
            }).code());
            assertEquals(deniedCheckpoint,tooSmall.value(),"Failure cannot mutate the original operation");
            // Historical two-partition operations still use logical units, not the new pooled borrowing ledger.
            var legacyFields=new LinkedHashMap<>(operation.value().fields());
            legacyFields.remove("workspace");
            legacyFields.put("scratch",new DataValue.IntegerValue(524288));
            var legacy=LibraryUploadCommit.decode(new DataValue.RecordValue(legacyFields));
            while(!legacy.done()) legacy=legacy.advance(input,32);
            assertEquals(packet,legacy.decoded().value());
            assertEquals(state.steps(),legacy.steps());
            boolean restored=false;
            for(int ticks=0;!operation.done();ticks++) {
                assertTrue(ticks<3000);
                operation=operation.advance(input,32);
                if (!restored && operation.steps()>16000) {
                    operation=LibraryUploadCommit.decode(operation.value());
                    assertTrue(operation.restoring());
                    restored=true;
                }
            }
            assertTrue(restored);
            assertNull(operation.decoded().error());
            assertEquals(packet,operation.decoded().value());
            assertEquals(state.steps(),operation.steps());
        }
    }
}
