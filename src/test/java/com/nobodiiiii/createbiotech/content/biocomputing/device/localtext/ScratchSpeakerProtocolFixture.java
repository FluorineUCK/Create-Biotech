package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ScratchSpeakerProtocolFixture {
    private ScratchSpeakerProtocolFixture() {}

    public static DataValue.RecordValue reference() {
        return new DataValue.RecordValue(Map.of(
            "library",new DataValue.RecordValue(Map.of("x",number(12),"y",number(68),"z",number(-4))),
            "device",text("00000000-0000-0000-0000-000000000061"),"sequence",number(17),
            "version",new LibraryMutation.Version(UUID.fromString("00000000-0000-0000-0000-000000000062"),"workshop.mid",5).value()));
    }

    public record Grant(UUID sender, LocalTextOutputState.State state, Map<String,DataValue> token) {}

    public static Grant inspect(UUID sender, DataValue.RecordValue target, DataValue.RecordValue frame) {
        var before = LocalTextOutputState.closed(UUID.randomUUID(),UUID.randomUUID());
        assertEquals("closed",decode(before,sender,target,frame).code());
        var open = LocalTextOutputState.grant(before,Optional.of(sender),UUID.randomUUID());
        var status = decode(open,sender,target,frame);
        assertEquals("ok",status.code()); assertEquals(SpeakerPlaybackProtocol.Action.NONE,status.action());
        var reply = status.reply(target,open,"ok","idle");
        assertEquals(text("speaker_media_reply"),reply.field("kind"));
        assertEquals(frame.field("request"), reply.field("request"));
        var state = assertInstanceOf(DataValue.RecordValue.class,reply.field("state"));
        return new Grant(sender,open,Map.of("device",state.field("device"),"session",state.field("session"),"revision",state.field("revision")));
    }

    public static void verify(Grant grant, DataValue.RecordValue target, DataValue.RecordValue play, DataValue.RecordValue stop) {
        assertEquals("denied",decode(grant.state(),UUID.randomUUID(),target,play).code());
        var command = decode(grant.state(),grant.sender(),target,play);
        assertEquals("accepted",command.code());
        assertEquals(SpeakerPlaybackProtocol.Action.PLAY,command.action());
        assertEquals(SpeakerMediaPlaybackSource.Reference.decode(reference()),command.reference());
        var stopArgs = assertInstanceOf(DataValue.RecordValue.class,stop.field("arguments"));
        assertEquals(Set.of("device","session","revision"),stopArgs.fields().keySet(),"Stopping must not access a Library reference");
        var stopCommand = decode(grant.state(),grant.sender(),target,stop);
        assertEquals("accepted",stopCommand.code());
        assertEquals(SpeakerPlaybackProtocol.Action.STOP,stopCommand.action());
        assertNull(stopCommand.reference());
        assertEquals("stale_session",decode(LocalTextOutputState.restart(grant.state(),UUID.randomUUID()),grant.sender(),target,play).code());
        assertEquals("changed",decode(LocalTextOutputState.advanceControl(grant.state()),grant.sender(),target,play).code());
        var invalid = new LinkedHashMap<>(play.fields());
        var invalidArgs = new LinkedHashMap<>(((DataValue.RecordValue)play.field("arguments")).fields());
        invalidArgs.put("reference",new DataValue.RecordValue(Map.of()));
        invalid.put("arguments",new DataValue.RecordValue(invalidArgs));
        assertEquals("invalid_media_reference",decode(grant.state(),grant.sender(),target,new DataValue.RecordValue(invalid)).code());
    }

    private static SpeakerPlaybackProtocol.Command decode(LocalTextOutputState.State state, UUID sender, DataValue.RecordValue target, DataValue.RecordValue frame) {
        var result = SpeakerPlaybackProtocol.decode(frame,target,state,sender);
        assertNotNull(result);
        return result;
    }

    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    private static DataValue.IntegerValue number(long n) { return new DataValue.IntegerValue(n); }
}
