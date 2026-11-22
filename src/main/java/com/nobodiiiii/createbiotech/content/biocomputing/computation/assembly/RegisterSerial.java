package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
/** Closed serial-TX extension v1. No authored parser surface and no arbitrary byte injection. */
public final class RegisterSerial {
    private RegisterSerial() {}
    public enum Kind { SEND, STATUS, RECEIVE }
    public record Instruction(Kind kind,String pin,int input,int output,boolean claimFaults,boolean nativeNbt) implements RegisterProgram.Instruction {
        public Instruction(Kind kind,String pin,int input,int output,boolean claimFaults){this(kind,pin,input,output,claimFaults,false);}
        public Instruction {Objects.requireNonNull(kind);Objects.requireNonNull(pin);if(pin.isBlank() || output<0 || (kind==Kind.SEND)!=(input>=0) || kind!=Kind.STATUS&&claimFaults || kind!=Kind.RECEIVE&&nativeNbt)throw new IllegalArgumentException("Serial operands");}
        public int words(){return Math.addExact(6,pin.length());}
        public String extension(){return kind==Kind.RECEIVE?"serial-rx":"serial-tx";} public int version(){return 1;}
    }
    /** Saved paid cursor, reconstructed once on restore; live candidates share a private bound witness. */
    public static final class Progress {
        private final long paid;
        private final SerialRecordCodec.Limits limits;
        private final DataValue.RecordValue configuration;
        private SerialEncoding.State live;
        private DataValue input; private String image;private int pc;
        private Rx rx;
        public Progress(long paid,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration){this.configuration=Objects.requireNonNull(configuration);if(paid<1)throw new IllegalArgumentException("Invalid serial paid cursor");this.paid=paid;this.limits=Objects.requireNonNull(limits);}
        public void requireEnvelope(long memoryUnits) {
            if((rx==null?limits.maxEncodedBytes():ownedUnits())>memoryUnits)throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"assembly","Serial temporary buffer exceeds current owner RAM");
        }
        public void requireEnvelope(SerialRecordCodec.Limits current) {
            if(limits.maxEncodedBytes()>current.maxEncodedBytes() || limits.values().maxNodes()>current.values().maxNodes()
                || limits.values().maxStringChars()>current.values().maxStringChars() || limits.values().maxValueUnits()>current.values().maxValueUnits()
                || limits.maxDecodeHeapBytes()>current.maxDecodeHeapBytes())invalid();
        }
        public DataValue.RecordValue configuration(){return configuration;}
        public long paidWork(){return paid;}
        public long ownedUnits(){if(rx!=null)return rx.live==null?0:Math.addExact(rx.length,rx.live.storageUnits());if(live==null)throw new IllegalStateException("Unvalidated serial state");return live.storageUnits();}
        /** An absent TX witness is genuinely unallocated; RX keeps its existing accounting. */
        public OptionalLong materializedUnits(){return rx!=null?OptionalLong.of(ownedUnits()):live==null?OptionalLong.empty():OptionalLong.of(live.storageUnits());}
        void validate(RegisterState state,Instruction op,java.util.function.LongConsumer reserve){
            if(rx!=null){if(op.kind()!=Kind.RECEIVE||rx.nativeNbt!=op.nativeNbt()||!rx.pin.equals(op.pin())||rx.live==null&&rx.failure.isEmpty())invalid();if(rx.authImage!=null&&(!rx.authImage.equals(state.image())||rx.authPc!=state.pc()))invalid();if(image!=null&&(!image.equals(state.image())||pc!=state.pc()))invalid();image=state.image();pc=state.pc();return;}
            DataValue frozen=state.registers().get(op.input());
            if(live!=null){if(input!=frozen || !image.equals(state.image()) || pc!=state.pc())invalid();return;}
            if(frozen==null || paid>Math.multiplyExact(256L,Math.addExact(frozen.storageUnits(),limits.maxEncodedBytes())))invalid();
            var rebuilt=SerialEncoding.begin(frozen,limits,reserve);
            for(long i=1;i<paid;i++){if(rebuilt.done())invalid();rebuilt=rebuilt.advance(reserve);}
            bind(state,frozen,rebuilt);
        }
        private void bind(RegisterState state,DataValue frozen,SerialEncoding.State value){input=frozen;image=state.image();pc=state.pc();live=value;}
        static Progress start(RegisterState state,Instruction op,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration){var p=new Progress(1,limits,configuration);p.bind(state,state.registers().get(op.input()),SerialEncoding.begin(state.registers().get(op.input()),limits));return p;}
        Progress advance(RegisterState state){var next=new Progress(Math.incrementExact(paid),limits,configuration);if(rx!=null){next.rx=rx.advance();next.image=state.image();next.pc=state.pc();}else next.bind(state,input,live.advance());return next;}
        public boolean done(){return rx!=null?!rx.failure.isEmpty()||rx.live!=null&&rx.live.done():live!=null&&live.done();}
        public boolean receiving(){return rx!=null;}
        public UUID flow(){return rx.flow;}
        public SerialDevice.Receive receive(){return rx.bound;}
        public SerialDecoding.Decoded decoded(){return rx.live.decoded();}
        public String failure(){return rx.failure;}
        public SerialReceiveProof.Cursor proof(){return rx.proof(paid,limits,configuration,rx.authImage==null?image:rx.authImage,rx.authImage==null?pc:rx.authPc);}
        public String certificate(){return rx.certificate;}
        public void sealReceive(SerialDevice device){if(rx==null)return;rx.authImage=image;rx.authPc=pc;rx.certificate=device.signReceiveCursor(proof());}
        public void checkReceiveWork(long work){if(rx!=null&&(work<rx.origin||work-rx.origin!=paid))invalid();}
        public void restoreReceive(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialReceiveAccess device,SerialDevice.Receive head,UUID flow){
            if(rx==null)return;
            if(!rx.flow.equals(flow))invalid();
            if(rx.certificate!=null){
                if(!device.verifyReceiveCursor(proof(),rx.certificate))invalid();
                String failed=rx.failure.isEmpty()?device.receiveFailure(proof()):rx.failure;
                if(failed!=null&&!failed.isEmpty()){rx.failure=failed;rx.live=null;rx.bound=null;image=rx.authImage;pc=rx.authPc;rx.certificate=device.signReceiveCursor(proof());return;}
            }
            if(!rx.flow.equals(flow)||head.error()!=null||!rx.device.equals(head.device())||!rx.face.equals(head.face())||!rx.head.equals(head.head().identity())||rx.length!=head.head().size()||!rx.pin.equals(head.pin())||!configuration.equals(head.configuration())||!limits.equals(head.limits()))invalid();
            if(rx.live!=null)return;
            if(paid>Math.multiplyExact(4096L,Math.addExact(head.head().size(),limits.values().maxNodes())))invalid();
            var rebuilt=SerialDecoding.begin(head.head(),rx.nativeNbt,limits);for(long i=1;i<paid;i++){if(rebuilt.done())invalid();rebuilt=rebuilt.advance();}if(rx.consumed!=rebuilt.consumedBytes()||rx.witness!=rebuilt.wireWitness())invalid();rx.live=rebuilt;rx.bound=head;
        }
        public static Progress savedReceive(long paid,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration,UUID flow,UUID device,UUID face,UUID head,String pin,boolean nativeNbt,long origin,int length,int consumed,long witness){var p=new Progress(paid,limits,configuration);p.rx=new Rx(flow,device,face,head,pin,nativeNbt,origin,length,consumed,witness,null,null);return p;}
        public static Progress savedCertifiedReceive(long paid,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration,UUID flow,UUID device,UUID face,UUID head,String pin,boolean nativeNbt,long origin,int length,int consumed,long witness,String image,int pc,String certificate,String failure){
            var p=savedReceive(paid,limits,configuration,flow,device,face,head,pin,nativeNbt,origin,length,consumed,witness);p.rx.authImage=image;p.rx.authPc=pc;p.rx.certificate=certificate;p.rx.failure=failure;
            if(certificate==null||!certificate.matches("[0-9a-f]{64}"))invalid();p.proof();return p;
        }
        static Progress startReceive(RegisterState state,SerialDevice.Receive head,UUID flow,boolean nativeNbt,long origin){var p=savedReceive(1,head.limits(),head.configuration(),flow,head.device(),head.face(),head.head().identity(),head.pin(),nativeNbt,origin,head.head().size(),0,0);p.rx.bound=head;p.rx.live=SerialDecoding.begin(head.head(),nativeNbt,head.limits());p.image=state.image();p.pc=state.pc();return p;}
        private static final class Rx {
            final UUID flow,device,face,head;final String pin;final boolean nativeNbt;SerialDevice.Receive bound;SerialDecoding.State live;
            final long origin,witness;final int length,consumed;
            String authImage,certificate,failure="";int authPc;
            Rx(UUID flow,UUID device,UUID face,UUID head,String pin,boolean nativeNbt,long origin,int length,int consumed,long witness,SerialDevice.Receive bound,SerialDecoding.State live){this.flow=Objects.requireNonNull(flow);this.device=Objects.requireNonNull(device);this.face=Objects.requireNonNull(face);this.head=Objects.requireNonNull(head);this.pin=Objects.requireNonNull(pin);this.nativeNbt=nativeNbt;this.origin=origin;this.length=length;this.consumed=consumed;this.witness=witness;this.bound=bound;this.live=live;if(origin<0||length<1||consumed<0||consumed>length)throw new IllegalArgumentException("Invalid receive witness");}
            Rx advance(){var next=live.advance();return new Rx(flow,device,face,head,pin,nativeNbt,origin,length,next.consumedBytes(),next.wireWitness(),bound,next);}
            SerialReceiveProof.Cursor proof(long paid,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration,String image,int pc){return new SerialReceiveProof.Cursor(device,face,head,flow,image,pc,origin,paid,length,consumed,witness,nativeNbt,pin,limits,configuration,failure);}
            DataValue.RecordValue value(){var fields=new LinkedHashMap<String,DataValue>(Map.of("flow",new DataValue.TextValue(flow.toString()),"device",new DataValue.TextValue(device.toString()),"face",new DataValue.TextValue(face.toString()),"head",new DataValue.TextValue(head.toString()),"pin",new DataValue.TextValue(pin),"native",new DataValue.BooleanValue(nativeNbt),"origin",new DataValue.IntegerValue(origin),"length",new DataValue.IntegerValue(length),"consumed",new DataValue.IntegerValue(consumed),"witness",new DataValue.IntegerValue(witness)));if(certificate!=null){fields.put("image",new DataValue.TextValue(authImage));fields.put("pc",new DataValue.IntegerValue(authPc));fields.put("certificate",new DataValue.TextValue(certificate));fields.put("failure",new DataValue.TextValue(failure));}return new DataValue.RecordValue(fields);}
        }
        public SerialEncoding.Encoded encoded(){if(live==null)throw new IllegalStateException("Unvalidated serial state");return live.encoded();}
        public DataValue.RecordValue value(){var fields=new LinkedHashMap<String,DataValue>(Map.of("configuration",configuration,"version",new DataValue.IntegerValue(rx==null?1:rx.certificate==null?2:3),"paid",new DataValue.IntegerValue(paid),
            "bytes",new DataValue.IntegerValue(limits.maxEncodedBytes()),"heap",new DataValue.IntegerValue(limits.maxDecodeHeapBytes()),
            "nodes",new DataValue.IntegerValue(limits.values().maxNodes()),"chars",new DataValue.IntegerValue(limits.values().maxStringChars()),"units",new DataValue.IntegerValue(limits.values().maxValueUnits())));if(rx!=null)fields.put("receive",rx.value());return new DataValue.RecordValue(fields);}
        @Override public boolean equals(Object o){return o instanceof Progress p&&paid==p.paid&&limits.equals(p.limits)&&configuration.equals(p.configuration)&&Objects.equals(rx==null?null:rx.value(),p.rx==null?null:p.rx.value());}
        @Override public int hashCode(){return Objects.hash(paid,limits,configuration);}
        private static void invalid(){throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT,"assembly","Serial encoding cursor differs from installed frozen source");}
    }
}
