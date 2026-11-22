package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import java.util.function.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.StringTag;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins;

/** Device-owned physical interfaces, never program RAM or an automatically opened legacy endpoint. */
public final class SerialDevice implements SerialReceiveAccess {
    public record Status(String transmit, String connection, String failure, SerialReceiveBuffer.Status receive) {}
    /** Metadata only: never exposes device identity, pending bytes or received payloads. */
    public record PortView(Optional<Direction> side, boolean enabled, Status status, Optional<SerialGatewayBinding> gateway) {
        public PortView(Optional<Direction> side, boolean enabled, Status status) { this(side, enabled, status, Optional.empty()); }
    }
    public record View(Optional<String> diagnostic, Map<String, PortView> ports) {
        public View { ports = Collections.unmodifiableMap(new TreeMap<>(ports)); }
    }
    private static final Set<String> FAILURES = Set.of("", "configuration_changed", "hardware_changed", "reloaded", "unloaded",
        "topology_changed", "topology_unloaded", "topology_limit", "endpoint_limit", "access_denied", "disconnected",
        "duplicate_identity", "port_limit", "port_busy", "unknown_port", "payload_too_large", "empty_payload", "collision",
        "incompatible_media", "duplex_topology", "duplex_hardware",
        "gateway_unavailable", "gateway_unloaded", "gateway_changed", "gateway_unformed", "gateway_not_member", "gateway_conflict");
    public record Attachment(SerialDevice device, String name, UUID identity, Direction side,
                             SerialHardware.Port hardware, SerialReceiveBuffer receiver, Optional<SerialGatewayBinding> gateway) {
        public SharedSerialChannel.Port key() { return new SharedSerialChannel.Port(identity, name); }
    }
    private static final class Port {
        UUID interfaceIdentity=UUID.randomUUID();
        long configurationEpoch,hardwareEpoch;
        Direction side;
        boolean enabled;
        Optional<SerialGatewayBinding> gateway = Optional.empty();
        SerialHardware.Port hardware;
        SerialReceiveBuffer receiver;
        final ArrayDeque<byte[]> pending = new ArrayDeque<>();
        long waitingBytes;
        int activeBytes;
        boolean full() { return pending.size() + (activeBytes>0?1:0) >= hardware.transmitFrames(); }
        String fullStatus() { return hardware.transmitFrames() == 1 ? "busy" : "tx_buffer_full"; }
        boolean fits(int bytes) { return bytes <= hardware.transmitBytes()-activeBytes-waitingBytes; }
        void enqueue(byte[] bytes) {
            pending.addLast(bytes); waitingBytes+=bytes.length;
            if(activeBytes==0) transmit="queued";
            failure="";
        }
        String transmit = "idle", connection = "disconnected", failure = "";
        Port(Direction side, boolean enabled, SerialHardware.Port hardware) {
            this.side = side; this.enabled = enabled; this.hardware = hardware;
            receiver = new SerialReceiveBuffer(hardware.receiveLimits());
        }
    }
    /** Optional archive fallback for the one physically distinct Computer radio port. */
    public record RadioSlot(String name, BooleanSupplier equipped, BooleanSupplier owned, BooleanSupplier armed,
                            Supplier<SerialHardware.Port> retainedSpec) {
        public RadioSlot {
            new HardwarePins(List.of(name));
            Objects.requireNonNull(equipped); Objects.requireNonNull(owned);
            Objects.requireNonNull(armed); Objects.requireNonNull(retainedSpec);
        }
    }
    private java.util.function.BooleanSupplier receiveGate=()->true;
    /** Pure observation used by mutually exclusive receive circuits. No decoding or polling. */
    public boolean hasWiredInput() {
        for(var entry:ports.entrySet())if(!radioName(entry.getKey()) && entry.getValue().receiver.head().isPresent())return true;
        return false;
    }
    public void receiveGate(java.util.function.BooleanSupplier gate) { this.receiveGate=Objects.requireNonNull(gate); }
    private final RadioSlot radio;
    private final Supplier<SerialHardware> hardware;
    private final BooleanSupplier available;
    private final Supplier<SerialRecordCodec.Limits> codec;
    private final Runnable changed;
    private final Map<String, Port> ports = new LinkedHashMap<>();
    private UUID identity;
    private byte[] receiveKey;
    private Tag retained;
    private boolean decoded = true;
    private String diagnostic;
    private Object token = new Object();

    public SerialDevice(Supplier<SerialHardware> hardware, BooleanSupplier available,
                        Supplier<SerialRecordCodec.Limits> codec, Runnable changed) {
        this(hardware, available, codec, changed, null);
    }

    public SerialDevice(Supplier<SerialHardware> hardware, BooleanSupplier available,
                        Supplier<SerialRecordCodec.Limits> codec, Runnable changed, RadioSlot radio) {
        this.hardware = Objects.requireNonNull(hardware); this.available = Objects.requireNonNull(available);
        this.radio = radio;
        this.codec = Objects.requireNonNull(codec); Objects.requireNonNull(changed);
        this.changed = () -> { token = new Object(); changed.run(); };
    }
    /** Thread-confined preparation. The owning block must mark dirty after publishing BOTH local states. */
    public final class Prepared {
        private final Object before = token;
        private final SerialHardware selected = hardware.get();
        private final SerialRecordCodec.Limits encoding = codec.get();
        private final boolean wasAvailable = available.getAsBoolean();
        private final Port port;
        private final Object receiveToken;
        private final Optional<SharedSerialChannel.Port> source;
        private final DataValue.RecordValue result;
        private final Runnable mutation;
        private boolean published;
        private boolean headOnly;
        private UUID boundHead;
        private Prepared(Port port, DataValue.RecordValue result, Runnable mutation) {
            this.port = port; this.receiveToken = port == null ? null : port.receiver.token();
            this.source = port == null ? Optional.empty() : port.receiver.source();
            this.result = result; this.mutation = mutation;
        }
        public DataValue.RecordValue result() { return result; }
        /** Trusted device adapter only; not exposed by public receive/status records. */
        public Optional<SharedSerialChannel.Port> source() { return source; }
        /** Admission preflight for device adapters; final encoding still checks the exact framed reply. */
        public int maximumReplyBytes() { return port==null?0:Math.min(encoding.maxEncodedBytes(),port.hardware.maxFrameBytes()); }
        /** Device adapters consume one command and queue ONE finite reply together, without callbacks.
         * A too-large/busy reply leaves the command intact; no fragmentation or automatic retry. */
        public Prepared replying(DataValue.RecordValue reply) {
            if (!(result.fields().get("value") instanceof DataValue.RecordValue value) || !SerialResult.receive("received", value).equals(result)
                || !current()) throw new IllegalArgumentException("changed");
            if (!isEnabled(port)) throw new IllegalArgumentException("closed");
            if (port.full()) throw new IllegalArgumentException(port.fullStatus());
            var bounds = codec.get();
            byte[] bytes = SerialRecordCodec.encode(reply, new SerialRecordCodec.Limits(
                Math.min(bounds.maxEncodedBytes(), port.hardware.maxFrameBytes()), bounds.maxDecodeHeapBytes(), bounds.values()));
            if (!port.fits(bytes.length)) throw new IllegalArgumentException("tx_buffer_full");
            return new Prepared(port, result, () -> { mutation.run(); port.enqueue(bytes); });
        }
        private boolean current() {
            synchronize();
            return !published && before == token && selected.equals(hardware.get()) && encoding.equals(codec.get())
                && wasAvailable == available.getAsBoolean() && (port == null || (headOnly
                    ? port.receiver.head().map(h->h.identity().equals(boundHead)).orElse(false) : receiveToken == port.receiver.token()));
        }
        /** No observer callbacks; a false result changes neither queue nor caller continuation. */
        public boolean publish() {
            if (!current()) return false;
            mutation.run(); token = new Object(); published = true; return true;
        }
    }
    public DataValue.RecordValue encodingConfiguration(String name) {
        synchronize();return encodingConfiguration(ports.get(name));
    }
    private static DataValue.RecordValue encodingConfiguration(Port port) {
        return port==null?new DataValue.RecordValue(Map.of("configured",new DataValue.BooleanValue(false))):
            new DataValue.RecordValue(Map.of("configured",new DataValue.BooleanValue(true),"side",new DataValue.IntegerValue(port.side.ordinal()),
                "enabled",new DataValue.BooleanValue(port.enabled),"gateway",new DataValue.TextValue(port.gateway.map(Object::toString).orElse(""))));
    }
    public SerialRecordCodec.Limits encodingLimits(String name) {
        var bounds=codec.get();var spec=hardware.get().ports().get(name);
        return new SerialRecordCodec.Limits(spec==null?bounds.maxEncodedBytes():Math.min(bounds.maxEncodedBytes(),spec.maxFrameBytes()),bounds.maxDecodeHeapBytes(),bounds.values());
    }
    /** Private durable witness. This is host metadata, never a public wire protocol or player UUID. */
    public static final class Receive {
        private final UUID device,face;
        private final String pin,error;
        private final SerialReceiveBuffer.Head head;
        private final SerialRecordCodec.Limits limits;
        private final DataValue.RecordValue configuration;
        private Receive(UUID device,UUID face,String pin,String error,SerialReceiveBuffer.Head head,SerialRecordCodec.Limits limits,DataValue.RecordValue configuration){this.device=device;this.face=face;this.pin=pin;this.error=error;this.head=head;this.limits=limits;this.configuration=configuration;}
        public UUID device(){return device;} public UUID face(){return face;} public String pin(){return pin;} public String error(){return error;}
        public SerialReceiveBuffer.Head head(){return head;} public SerialRecordCodec.Limits limits(){return limits;} public DataValue.RecordValue configuration(){return configuration;}
        public boolean sameHead(Receive other){return other!=null&&Objects.equals(device,other.device)&&Objects.equals(face,other.face)&&pin.equals(other.pin)&&head!=null&&other.head!=null&&head.identity().equals(other.head.identity());}
    }
    public Receive beginReceive(String name){
        String error=operationError(name);var port=ports.get(name);
        if(error==null&&port==null)error="closed";
        var head=port==null?null:port.receiver.head().orElse(null);
        if(error==null&&head==null)error=port.enabled?"empty":"closed";
        return new Receive(identity,port==null?null:port.interfaceIdentity,name,error,head,codec.get(),receiveConfiguration(name));
    }
    private DataValue.RecordValue receiveConfiguration(String name){
        synchronize();return receiveConfiguration(ports.get(name),hardware.get().ports().get(name));
    }
    private static DataValue.RecordValue receiveConfiguration(Port port,SerialHardware.Port spec){
        var configuration=encodingConfiguration(port);
        if(spec==null||!((DataValue.BooleanValue)configuration.field("configured")).value())return configuration;
        var fields=new LinkedHashMap<>(configuration.fields());fields.put("epoch",new DataValue.IntegerValue(port.configurationEpoch));fields.put("hardware",new DataValue.RecordValue(Map.of("epoch",new DataValue.IntegerValue(port.hardwareEpoch),"bits",new DataValue.IntegerValue(spec.bitsPerTick()),"frame",new DataValue.IntegerValue(spec.maxFrameBytes()),"frames",new DataValue.IntegerValue(spec.receiveFrames()),"bytes",new DataValue.IntegerValue(spec.receiveBytes()))));return new DataValue.RecordValue(fields);
    }
    /** Bounded metadata only. In particular, observing an opaque device must not restore it. */
    private record ReceiveVersion(Object device, SerialHardware hardware, SerialRecordCodec.Limits codec,
                                  boolean available, boolean decoded, String diagnostic, Map<String,Object> queues) {}
    public Object receiveObservationVersion() {
        var specs=hardware.get();var bounds=codec.get();boolean ready=available.getAsBoolean();
        var queues=new LinkedHashMap<String,Object>();ports.forEach((name,port)->queues.put(name,port.receiver.token()));
        return new ReceiveVersion(token,specs,bounds,ready,decoded,diagnostic,Map.copyOf(queues));
    }
    /** No synchronization or queue copying. Normal device lifecycle must restore opaque hardware first. */
    public Optional<ReceiveInspection> inspectReceive() {
        var version=(ReceiveVersion)receiveObservationVersion();
        if(!version.decoded()||version.diagnostic()!=null||identity==null||receiveKey==null) return Optional.empty();
        for(var entry:ports.entrySet())
            if(!entry.getValue().hardware.equals(version.hardware().ports().get(entry.getKey())))return Optional.empty();
        var snapshot=new LinkedHashMap<String,Receive>();
        for(var name:version.hardware().ports().keySet()) {
            var port=ports.get(name);var head=port==null?null:port.receiver.head().orElse(null);
            String error=!version.available()?"unavailable":port==null?"closed":head==null?(port.enabled?"empty":"closed"):null;
            snapshot.put(name,new Receive(identity,port==null?null:port.interfaceIdentity,name,error,head,version.codec(),
                receiveConfiguration(port,version.hardware().ports().get(name))));
        }
        var inspection=new ReceiveInspection(version,identity,receiveKey.clone(),Map.copyOf(snapshot));
        return inspection.current()?Optional.of(inspection):Optional.empty();
    }
    /** Borrows immutable frame heads only for this validation attempt, never a second device buffer. */
    public final class ReceiveInspection implements SerialReceiveAccess {
        private final ReceiveVersion version;
        private final UUID device;
        private final byte[] key;
        private final Map<String,Receive> snapshot;
        private ReceiveInspection(ReceiveVersion version,UUID device,byte[] key,Map<String,Receive> snapshot){
            this.version=version;this.device=device;this.key=key;this.snapshot=snapshot;
        }
        public boolean current(){return version.equals(receiveObservationVersion());}
        public Receive beginReceive(String pin){return snapshot.getOrDefault(pin,new Receive(device,null,pin,
            version.available()?"missing_port":"unavailable",null,version.codec(),encodingConfiguration((Port)null)));}
        public boolean verifyReceiveCursor(SerialReceiveProof.Cursor cursor,String certificate){
            return device.equals(cursor.device())&&SerialReceiveProof.verify(key,cursor,certificate);
        }
        public String signReceiveCursor(SerialReceiveProof.Cursor cursor){
            if(!device.equals(cursor.device()))throw new IllegalArgumentException("Wrong receive device");
            return SerialReceiveProof.sign(key,cursor);
        }
        public String receiveFailure(SerialReceiveProof.Cursor cursor){
            if(!version.available())return "unavailable";
            if(!version.hardware().ports().containsKey(cursor.pin()))return "missing_port";
            return SerialDevice.receiveFailure(device,beginReceive(cursor.pin()),cursor);
        }
    }
    public Prepared prepareReceiveOutcome(Receive receive){if(receive.error==null)throw new IllegalArgumentException("Frame requires incremental decode");return new Prepared(ports.get(receive.pin),SerialResult.receive(receive.error,null),()->{});}
    public String signReceiveCursor(SerialReceiveProof.Cursor cursor){byte[] key=receiveProofKey(cursor.device());if(key==null)throw new IllegalArgumentException("Wrong receive device");return SerialReceiveProof.sign(key,cursor);}
    private byte[] receiveProofKey(UUID expected){
        synchronize();byte[] key=receiveKey;UUID device=identity;
        // A current hardware downgrade may leave the device opaque. Authentication only reads
        // fixed trusted metadata from its retained save, not queue data or application values.
        if(!decoded&&retained instanceof CompoundTag root&&root.getInt("Version")==6&&root.hasUUID("Identity")&&root.get("ReceiveKey") instanceof ByteArrayTag stored&&stored.size()==32){key=stored.getAsByteArray();device=root.getUUID("Identity");}
        return Objects.equals(device,expected)?key:null;
    }
    public boolean verifyReceiveCursor(SerialReceiveProof.Cursor cursor,String certificate){byte[] key=receiveProofKey(cursor.device());return key!=null&&SerialReceiveProof.verify(key,cursor,certificate);}
    public String receiveFailure(SerialReceiveProof.Cursor cursor){
        String error=operationError(cursor.pin());if(error!=null)return error;
        return receiveFailure(identity,beginReceive(cursor.pin()),cursor);
    }
    private static String receiveFailure(UUID identity,Receive current,SerialReceiveProof.Cursor cursor){
        if(!Objects.equals(identity,cursor.device())||!Objects.equals(current.face(),cursor.face()))return "stale";
        if(!Objects.equals(current.configuration().fields().get("hardware"),cursor.configuration().fields().get("hardware"))||!current.limits().equals(cursor.limits()))return "hardware_changed";
        if(!current.configuration().equals(cursor.configuration()))return "configuration_changed";
        return current.head()==null||!current.head().identity().equals(cursor.head())||current.head().size()!=cursor.length()?"stale":null;
    }
    /** Already authenticated operation failure; no queue effect, retry or reroute. */
    public Prepared prepareReceiveFailure(SerialReceiveProof.Cursor cursor,String certificate,String failure){if(!verifyReceiveCursor(cursor,certificate)||failure==null||failure.isEmpty())throw new IllegalArgumentException("Invalid receive failure proof");return new Prepared(ports.get(cursor.pin()),SerialResult.receive(failure,null),()->{});}
    /** Completed incremental parser output only. No whole-frame decode, retry, reroute or early claim. */
    public Prepared prepareDecodedReceive(Receive bound,SerialDecoding.Decoded decoded){
        String error=operationError(bound.pin);var port=ports.get(bound.pin);
        if(error==null&&!Objects.equals(bound.configuration.fields().get("hardware"),receiveConfiguration(bound.pin).fields().get("hardware")))error="hardware_changed";
        if(error==null&&!bound.configuration.equals(receiveConfiguration(bound.pin)))error="configuration_changed";
        if(error==null&&!bound.limits.equals(codec.get()))error="hardware_changed";
        if(error==null&&!bound.sameHead(beginReceive(bound.pin)))error="stale";
        if(error==null)error=decoded.error();
        if(error!=null)return new Prepared(port,SerialResult.receive(error,null),()->{});
        var prepared=new Prepared(port,SerialResult.receive("received",decoded.value()),()->port.receiver.consume(bound.head));
        prepared.headOnly=true;prepared.boundHead=bound.head.identity();return prepared;
    }
    /** Only completed verified encoder results can reach this boundary, never program bytes. */
    public Prepared prepareEncodedSend(String name,SerialEncoding.Encoded encoded,DataValue.RecordValue configuration) {
        Objects.requireNonNull(encoded);
        String error=operationError(name);var port=ports.get(name);
        if(error==null && (port==null || !isEnabled(port)))error="closed";
        if(error==null && port.full())error=port.fullStatus();
        if(error==null && !encoded.limits().equals(encodingLimits(name)))error="hardware_changed";
        if(error==null && !configuration.equals(encodingConfiguration(name)))error="configuration_changed";
        if(error==null)error=encoded.error();
        if(error!=null)return new Prepared(port,SerialResult.send(error),()->{});
        byte[] bytes=encoded.bytes();
        if(!port.fits(bytes.length))return new Prepared(port,SerialResult.send("tx_buffer_full"),()->{});
        return new Prepared(port,SerialResult.send("queued"),()->port.enqueue(bytes));
    }
    public Prepared prepareSend(String name, DataValue value) {
        String error = operationError(name); var port = ports.get(name);
        if (error == null && (port == null || !isEnabled(port))) error = "closed";
        if (error == null && port.full()) error = port.fullStatus();
        if (error == null && !(value instanceof DataValue.RecordValue) && !(value instanceof DataValue.NbtValue)) error = "invalid_record";
        if (error != null) return new Prepared(port, SerialResult.send(error), () -> {});
        var limits = codec.get(); final byte[] encoded;
        try { encoded = value instanceof DataValue.NbtValue nbt
            ? NativeNbtCodec.encode(nbt, nativeLimits(Math.min(limits.maxEncodedBytes(), port.hardware.maxFrameBytes())))
            : SerialRecordCodec.encode((DataValue.RecordValue)value, new SerialRecordCodec.Limits(
                Math.min(limits.maxEncodedBytes(), port.hardware.maxFrameBytes()), limits.maxDecodeHeapBytes(), limits.values())); }
        catch (SerialRecordCodec.Failure invalid) { return new Prepared(port, SerialResult.send(invalid.code().name().toLowerCase(Locale.ROOT)), () -> {}); }
        catch (NativeNbtCodec.Failure invalid) { return new Prepared(port, SerialResult.send(invalid.code().name().toLowerCase(Locale.ROOT)), () -> {}); }
        if(!port.fits(encoded.length))return new Prepared(port,SerialResult.send("tx_buffer_full"),()->{});
        return new Prepared(port, SerialResult.send("queued"), () -> port.enqueue(encoded));
    }
    /* Discard is a device-host policy for unsupported/unreadable frames, not a public ISA operation. */
    public Prepared prepareDiscard(String name) {
        String error = operationError(name); var port = ports.get(name);
        if (error != null || port == null || port.receiver.peek().isEmpty())
            return new Prepared(port, SerialResult.receive("empty", null), () -> {});
        return new Prepared(port, SerialResult.receive("discarded", null), () -> port.receiver.poll());
    }
    public Prepared prepareReceive(String name) {
        return prepareReceive(name, false);
    }
    /** Explicit decoding: a native compound may legitimately resemble a typed archive. Never guess. */
    public Prepared prepareReceive(String name, boolean nativeNbt) {
        String error = operationError(name); var port = ports.get(name);
        if (error == null && port == null) error = "closed";
        if (error != null) return new Prepared(port, SerialResult.receive(error, null), () -> {});
        var bytes = port.receiver.peek();
        if (bytes.isEmpty()) return new Prepared(port, SerialResult.receive(port.enabled ? "empty" : "closed", null), () -> {});
        final DataValue value;
        try { value = nativeNbt ? NativeNbtCodec.decode(bytes.orElseThrow(), nativeLimits(codec.get().maxEncodedBytes()))
            : SerialRecordCodec.decode(bytes.orElseThrow(), codec.get()); }
        catch (SerialRecordCodec.Failure invalid) { return new Prepared(port, SerialResult.receive(invalid.code().name().toLowerCase(Locale.ROOT), null), () -> {}); }
        catch (NativeNbtCodec.Failure invalid) { return new Prepared(port, SerialResult.receive(invalid.code().name().toLowerCase(Locale.ROOT), null), () -> {}); }
        return new Prepared(port, SerialResult.receive("received", value), () -> port.receiver.poll());
    }
    private NativeNbtCodec.Limits nativeLimits(int maxBytes) {
        var bounds = codec.get();
        return new NativeNbtCodec.Limits(maxBytes, bounds.maxDecodeHeapBytes(), bounds.values());
    }
    public Prepared prepareStatus(String name) {
        return prepareStatus(name, false);
    }
    /** Explicit local fault receipt. Preparing never clears; the owner publishes receipt and flags together. */
    public Prepared prepareStatus(String name, boolean claimReceiveFaults) {
        String error = operationError(name);
        var status = status(name);
        var port = ports.get(name);
        boolean canClaim = claimReceiveFaults && error == null && port != null;
        return new Prepared(port, SerialResult.status(status), canClaim ? port.receiver::clearFaults : () -> {});
    }
    private String operationError(String name) {
        if (!synchronize()) return diagnostic;
        if (!available.getAsBoolean()) return "unavailable";
        return livePort(name) ? null : "missing_port";
    }
    private boolean livePort(String name) {
        return hardware.get().ports().containsKey(name) && (!radioName(name) || radio.equipped().getAsBoolean());
    }
    private boolean radioName(String name) {
        return radio != null && radio.name().equals(name)
            && (radio.owned().getAsBoolean() || radio.equipped().getAsBoolean());
    }
    private boolean isEnabled(Port port) {
        return port.enabled && (radio == null || port != ports.get(radio.name()) || !radioName(radio.name())
            || radio.owned().getAsBoolean() && radio.armed().getAsBoolean() && radio.equipped().getAsBoolean());
    }
    private SerialHardware.Port archivedPort(SerialHardware active, String name) {
        if (radio != null && radio.name().equals(name) && radio.owned().getAsBoolean() && !radio.equipped().getAsBoolean())
            return radio.retainedSpec().get();
        return active.ports().get(name);
    }
    public void configure(String name, Direction side, boolean enabled) {
        if (radioName(name)) throw new IllegalArgumentException("radio_interface");
        configurePort(name, side, enabled, false);
    }

    /** Tune only the held radio port; refuse to re-use an old wired port with the same configured name. */
    public void configureRadio(boolean enabled) {
        requireState();
        if (radio == null || !radio.equipped().getAsBoolean()) throw new IllegalArgumentException("missing_port");
        if (ports.containsKey(radio.name()) && !radio.owned().getAsBoolean())
            throw new IllegalArgumentException("radio_port_conflict");
        configurePort(radio.name(), Direction.UP, enabled, true);
    }

    /** Physical removal is scoped to the radio and never clears a completed receive queue. */
    public void radioRemoved() {
        if (radio == null || !radio.owned().getAsBoolean() || !synchronize()) return;
        var port = ports.get(radio.name());
        if (port == null) return;
        port.enabled = false;
        interrupt(port, "hardware_changed");
        port.connection = "disconnected";
        changed.run();
    }

    private void configurePort(String name, Direction side, boolean enabled, boolean retune) {
        requireState();
        if (!available.getAsBoolean()) throw new IllegalArgumentException("unavailable");
        var spec = hardware.get().ports().get(name);
        if (spec == null) throw new IllegalArgumentException("missing_port");
        Objects.requireNonNull(side);
        var port = ports.get(name);
        if (port == null) ports.put(name, new Port(side, enabled, spec));
        else {
            if (port.gateway.isPresent() && port.gateway.orElseThrow().side() != side) throw new IllegalArgumentException("gateway_face_locked");
            if (!retune && port.side == side && port.enabled == enabled) return;
            port.configurationEpoch=nextEpoch(port.configurationEpoch);
            interrupt(port, "configuration_changed"); port.side = side; port.enabled = enabled; port.connection = "disconnected";
        }
        if (identity == null) identity = UUID.randomUUID();
        if(receiveKey==null)receiveKey=newKey();
        changed.run();
    }
    /** Explicit routing change always closes the interface; complete received frames remain owned here. */
    public void configureGateway(String name, Optional<SerialGatewayBinding> gateway) {
        Objects.requireNonNull(gateway);
        if (radioName(name)) throw new IllegalArgumentException("radio_interface");
        requireState();
        if (!available.getAsBoolean()) throw new IllegalArgumentException("unavailable");
        var spec = hardware.get().ports().get(name);
        if (spec == null) throw new IllegalArgumentException("missing_port");
        var port = ports.get(name);
        if (port == null && gateway.isEmpty()) return;
        if (port != null && port.gateway.equals(gateway)) return;
        if (port == null) { port = new Port(gateway.orElseThrow().side(), false, spec); ports.put(name, port); }
        port.configurationEpoch=nextEpoch(port.configurationEpoch);
        interrupt(port, "configuration_changed"); port.gateway = gateway; port.enabled = false; port.connection = "disconnected";
        if (gateway.isPresent()) port.side = gateway.orElseThrow().side();
        if (identity == null) identity = UUID.randomUUID();
        if(receiveKey==null)receiveKey=newKey();
        changed.run();
    }
    public String send(String name, DataValue.RecordValue record) {
        Objects.requireNonNull(record);
        if (!synchronize()) return diagnostic;
        if (!available.getAsBoolean()) return "unavailable";
        if (!livePort(name)) return "missing_port";
        var port = ports.get(name);
        if (port == null || !isEnabled(port)) return "closed";
        if (port.full()) return port.fullStatus();
        var limits = codec.get();
        try {
            var bytes = SerialRecordCodec.encode(record, new SerialRecordCodec.Limits(
                Math.min(limits.maxEncodedBytes(), port.hardware.maxFrameBytes()), limits.maxDecodeHeapBytes(), limits.values()));
            if (!port.fits(bytes.length)) return "tx_buffer_full";
            port.enqueue(bytes);
        } catch (SerialRecordCodec.Failure invalid) { return invalid.code().name().toLowerCase(Locale.ROOT); }
        changed.run(); return "queued";
    }
    public Optional<DataValue.RecordValue> poll(String name) {
        requireState();
        if (!available.getAsBoolean()) throw new IllegalArgumentException("unavailable");
        if (!livePort(name)) throw new IllegalArgumentException("missing_port");
        var port = ports.get(name);
        if (port == null) throw new IllegalArgumentException("closed");
        return port.receiver.peek().map(bytes -> {
            var result = SerialRecordCodec.decode(bytes, codec.get());
            port.receiver.poll(); changed.run(); return result;
        });
    }
    public Status status(String name) {
        if (!synchronize()) return new Status("unavailable", diagnostic, diagnostic, new SerialReceiveBuffer.Status(0, 0, Set.of()));
        if (!livePort(name)) return new Status("unavailable", "missing_port", "", new SerialReceiveBuffer.Status(0, 0, Set.of()));
        var port = ports.get(name);
        if (port == null) return new Status("idle", "closed", "", new SerialReceiveBuffer.Status(0, 0, Set.of()));
        return new Status(port.transmit, !available.getAsBoolean() ? "unavailable" : isEnabled(port) ? port.connection : "closed", port.failure, port.receiver.status());
    }
    public View view() {
        synchronize();
        var result = new TreeMap<String, PortView>();
        for (String name : hardware.get().ports().keySet()) {
            if (radioName(name) && !livePort(name)) continue;
            var port = ports.get(name);
            result.put(name, new PortView(port == null ? Optional.empty() : Optional.of(port.side),
                port != null && isEnabled(port), status(name), port == null ? Optional.empty() : port.gateway));
        }
        return new View(Optional.ofNullable(diagnostic), result);
    }
    /** Last published device metadata for cosmetic observers, never a live hardware/access decision.
     * Does not restore a retained save, query providers, interrupt frames, or invoke dirty callbacks.
     * Undeclared ports are omitted: observing a log must not manufacture a physical interface. */
    public View observedView() {
        var result = new TreeMap<String, PortView>();
        ports.forEach((name, port) -> result.put(name, new PortView(Optional.of(port.side), port.enabled,
            new Status(port.transmit, diagnostic != null ? diagnostic : port.enabled ? port.connection : "closed",
                port.failure, port.receiver.status()), port.gateway)));
        return new View(Optional.ofNullable(diagnostic), result);
    }
    /** Local attachment version for a medium; does not inspect or persist receive data. */
    record PhysicalSession(UUID face, long configuration, long hardware) {}
    PhysicalSession physicalSession(Attachment attachment) {
        if (attachment.device() != this) throw new IllegalArgumentException("Foreign serial attachment");
        var current = ports.get(attachment.name());
        return new PhysicalSession(current.interfaceIdentity, current.configurationEpoch, current.hardwareEpoch);
    }

    public List<Attachment> attachments() {
        if (!synchronize() || !available.getAsBoolean()) return List.of();
        return ports.entrySet().stream().filter(e -> !radioName(e.getKey()) && e.getValue().enabled)
            .map(e -> attachment(e.getKey(), e.getValue())).toList();
    }
    Optional<Attachment> radioAttachment(String name) {
        if (!synchronize() || !available.getAsBoolean()) return Optional.empty();
        if (radio != null && (!radioName(name) || !livePort(name))) return Optional.empty();
        var port = ports.get(name);
        return port == null || !isEnabled(port) || port.gateway.isPresent() ? Optional.empty()
            : Optional.of(attachment(name, port));
    }
    private Attachment attachment(String name, Port port) {
        port.receiver.admitWhile(receiveGate);
        return new Attachment(this, name, identity, port.side, port.hardware, port.receiver, port.gateway);
    }
    public Tag write() {
        if (!decoded) return retained == null ? null : retained.copy();
        if (identity == null) return null;
        var tag = new CompoundTag(); tag.putInt("Version", 6); tag.putUUID("Identity", identity);tag.putByteArray("ReceiveKey",receiveKey.clone());
        var entries = new CompoundTag();
        ports.forEach((name, port) -> {
            var entry = new CompoundTag(); entry.putString("Side", port.side.getName()); entry.putBoolean("Enabled", port.enabled);
            entry.putUUID("Interface",port.interfaceIdentity);
            entry.putLong("ConfigurationEpoch",port.configurationEpoch);entry.putLong("HardwareEpoch",port.hardwareEpoch);
            entry.putString("Transmit", !port.pending.isEmpty() && port.activeBytes==0 ? "queued" : port.transmit);
            entry.putString("Failure", port.failure);
            port.gateway.ifPresent(gateway -> entry.put("Gateway", gateway.write()));
            var frames = new ListTag(); port.receiver.snapshotFrames().forEach(frame -> {
                var stored = new CompoundTag(); stored.putByteArray("Payload", frame.payload());
                stored.putUUID("Frame",frame.identity());
                frame.source().ifPresent(source -> {
                    stored.putUUID("Source", source.device()); stored.putString("Port", source.name());
                });
                frames.add(stored);
            });
            var faults = new ListTag(); port.receiver.status().faults().stream().sorted().forEach(fault -> faults.add(StringTag.valueOf(fault.name())));
            entry.put("Frames", frames); entry.put("Faults", faults); entries.put(name, entry);
        });
        tag.put("Ports", entries); return tag;
    }
    public void read(Tag tag) {
        token = new Object();
        retained = tag == null ? null : tag.copy(); decoded = tag == null;
        identity = null;receiveKey=null; ports.clear(); diagnostic = null;
    }
    public Optional<String> diagnostic() { synchronize(); return Optional.ofNullable(diagnostic); }
    void connect(String name) { var port = ports.get(name); if (!port.connection.equals("ready")) token = new Object(); port.connection = "ready"; }
    void disconnect(String name, String code) {
        var port = ports.get(name); if (port == null) return;
        if (!port.connection.equals(code)) token = new Object();
        port.connection = code;
        boolean interrupted = !port.pending.isEmpty() || port.activeBytes!=0;
        if (interrupted) interrupt(port, code);
        if (interrupted) changed.run();
    }
    void start(Attachment attachment, SerialChannel channel) {
        start(attachment, channel::transmit);
    }
    void start(Attachment attachment,
               java.util.function.BiFunction<SharedSerialChannel.Port, byte[], SharedSerialChannel.Admission> admit) {
        var port = ports.get(attachment.name());
        if (port.activeBytes!=0 || port.pending.isEmpty()) return;
        var bytes=port.pending.getFirst();
        var admission = admit.apply(attachment.key(), bytes);
        port.pending.removeFirst();port.waitingBytes-=bytes.length;
        port.activeBytes = admission==SharedSerialChannel.Admission.STARTED ? bytes.length : 0;
        port.transmit = admission == SharedSerialChannel.Admission.STARTED ? "transmitting" : "interrupted";
        if (admission != SharedSerialChannel.Admission.STARTED) port.failure = admission.name().toLowerCase(Locale.ROOT);
        changed.run();
    }
    void complete(String name, SharedSerialChannel.Outcome outcome) {
        var port = ports.get(name); port.activeBytes=0; port.transmit = outcome.name().toLowerCase(Locale.ROOT);
        port.failure = outcome == SharedSerialChannel.Outcome.COLLISION ? "collision" : ""; changed.run();
    }
    void received() { changed.run(); }
    /** Unload never replays a pending frame; already received complete frames remain device-owned. */
    public void unload() { List.copyOf(ports.keySet()).forEach(name -> disconnect(name, "unloaded")); }
    private void requireState() { if (!synchronize()) throw new IllegalArgumentException(diagnostic); }
    private boolean synchronize() {
        try {
            var specs = hardware.get();
            if (!decoded) {
                var root = retained instanceof CompoundTag raw&&raw.getInt("Version")==6?exact(retained,"Version","Identity","Ports","ReceiveKey"):exact(retained, "Version", "Identity", "Ports");
                require(root, "Version", Tag.TAG_INT);
                int version = root.getInt("Version");
                if (version < 1 || version > 6) throw new IllegalArgumentException("unsupported_version");
                if(version==6&&(!(root.get("ReceiveKey") instanceof ByteArrayTag key)||key.size()!=32))throw new IllegalArgumentException("invalid_save");
                if (!root.hasUUID("Identity")) throw new IllegalArgumentException("invalid_save");
                require(root, "Ports", Tag.TAG_COMPOUND); var entries = root.getCompound("Ports");
                if (entries.size() > HardwarePins.MAX_PINS) throw new IllegalArgumentException("invalid_save");
                var restored = new LinkedHashMap<String, Port>();
                for (String name : entries.getAllKeys().stream().sorted().toList()) {
                    var spec = archivedPort(specs, name);
                    if (spec == null) throw new IllegalArgumentException("missing_port");
                    boolean hasGateway = version >= 2 && entries.get(name) instanceof CompoundTag candidate && candidate.contains("Gateway");
                    var fields=new ArrayList<>(List.of("Side", "Enabled", "Transmit", "Failure", "Frames", "Faults"));
                    if(hasGateway)fields.add("Gateway");if(version>=5)fields.addAll(List.of("Interface","ConfigurationEpoch","HardwareEpoch"));
                    var entry = exact(entries.get(name),fields.toArray(String[]::new));
                    if(version>=5&&!entry.hasUUID("Interface"))throw new IllegalArgumentException("invalid_save");
                    if(version>=5){require(entry,"ConfigurationEpoch",Tag.TAG_LONG);require(entry,"HardwareEpoch",Tag.TAG_LONG);if(entry.getLong("ConfigurationEpoch")<0||entry.getLong("HardwareEpoch")<0)throw new IllegalArgumentException("invalid_save");}
                    require(entry, "Side", Tag.TAG_STRING); require(entry, "Enabled", Tag.TAG_BYTE); require(entry, "Transmit", Tag.TAG_STRING);
                    require(entry, "Failure", Tag.TAG_STRING);
                    if (!FAILURES.contains(entry.getString("Failure"))) throw new IllegalArgumentException("invalid_save");
                    var side = Direction.byName(entry.getString("Side")); var enabled = entry.getByte("Enabled");
                    if (side == null || enabled < 0 || enabled > 1) throw new IllegalArgumentException("invalid_save");
                    String tx = entry.getString("Transmit");
                    if (!Set.of("idle", "queued", "transmitting", "transmitted", "collision", "interrupted").contains(tx))
                        throw new IllegalArgumentException("invalid_save");
                    if (!(entry.get("Frames") instanceof ListTag frames) || !frames.isEmpty() && frames.getElementType() != (version >= 3 ? Tag.TAG_COMPOUND : Tag.TAG_BYTE_ARRAY)
                        || !(entry.get("Faults") instanceof ListTag faults) || !faults.isEmpty() && faults.getElementType() != Tag.TAG_STRING)
                        throw new IllegalArgumentException("invalid_save");
                    if (frames.size() > spec.receiveFrames()) throw new IllegalArgumentException("resource_limit");
                    var bytes = new ArrayList<SerialReceiveBuffer.Frame>(); long total = 0;
                    for (var frame : frames) {
                        byte[] data; Optional<SharedSerialChannel.Port> source = Optional.empty();
                        if (version >= 3) {
                            var stored = (CompoundTag)frame;
                            boolean known = stored.contains("Source");
                            var frameKeys=new ArrayList<>(List.of("Payload"));if(known)frameKeys.addAll(List.of("Source","Port"));if(version>=5)frameKeys.add("Frame");
                            exact(stored,frameKeys.toArray(String[]::new));if(version>=5&&!stored.hasUUID("Frame"))throw new IllegalArgumentException("invalid_save");
                            require(stored, "Payload", Tag.TAG_BYTE_ARRAY); data = stored.getByteArray("Payload");
                            if (known) {
                                require(stored, "Port", Tag.TAG_STRING);
                                if (!stored.hasUUID("Source")) throw new IllegalArgumentException("invalid_save");
                                new HardwarePins(List.of(stored.getString("Port")));
                                source = Optional.of(new SharedSerialChannel.Port(stored.getUUID("Source"), stored.getString("Port")));
                            }
                        } else data = ((ByteArrayTag)frame).getAsByteArray();
                        if (data.length > spec.maxFrameBytes() || data.length > spec.receiveBytes() - total)
                            throw new IllegalArgumentException("resource_limit");
                        // Buffers own finite raw bytes. Application decoding happens only on an explicit read;
                        // one foreign/malformed native frame must not invalidate the entire device on reload.
                        total += data.length;
                        if (version < 4) SerialRecordCodec.decode(data, codec.get());
                        bytes.add(version>=5?new SerialReceiveBuffer.Frame(data,source,((CompoundTag)frame).getUUID("Frame")):new SerialReceiveBuffer.Frame(data, source));
                    }
                    var faultSet = EnumSet.noneOf(SerialReceiveBuffer.Fault.class);
                    for (var fault : faults)
                        if (!faultSet.add(SerialReceiveBuffer.Fault.valueOf(fault.getAsString()))) throw new IllegalArgumentException("invalid_save");
                    var port = new Port(side, enabled == 1, spec);
                    if(version>=5)port.interfaceIdentity=entry.getUUID("Interface");
                    if(version>=5){port.configurationEpoch=entry.getLong("ConfigurationEpoch");port.hardwareEpoch=entry.getLong("HardwareEpoch");}
                    if (hasGateway) {
                        port.gateway = Optional.of(SerialGatewayBinding.read(entry.get("Gateway")));
                        if (port.gateway.orElseThrow().side() != side) throw new IllegalArgumentException("invalid_save");
                    }
                    port.receiver = SerialReceiveBuffer.restoreFrames(spec.receiveLimits(), bytes, faultSet);
                    port.transmit = tx.equals("queued") || tx.equals("transmitting") ? "interrupted" : tx;
                    port.failure = port.transmit.equals(tx) ? entry.getString("Failure") : "reloaded";
                    restored.put(name, port);
                }
                ports.putAll(restored); identity = root.getUUID("Identity");receiveKey=version==6?root.getByteArray("ReceiveKey").clone():newKey(); decoded = true; retained = null;
            }
            var replacements = new LinkedHashMap<String, SerialReceiveBuffer>();
            for (var entry : ports.entrySet()) {
                var spec = archivedPort(specs, entry.getKey());
                if (spec == null) throw new IllegalArgumentException("missing_port");
                if (!spec.equals(entry.getValue().hardware)) replacements.put(entry.getKey(), SerialReceiveBuffer.restoreFrames(
                    spec.receiveLimits(), entry.getValue().receiver.snapshotFrames(), entry.getValue().receiver.status().faults()));
            }
            replacements.forEach((name, buffer) -> {
                var port = ports.get(name);port.hardwareEpoch=nextEpoch(port.hardwareEpoch); interrupt(port, "hardware_changed"); port.receiver = buffer; port.hardware = archivedPort(specs, name);
            });
            diagnostic = null;
            if (!replacements.isEmpty()) changed.run();
            return true;
        } catch (IllegalArgumentException invalid) {
            diagnostic = switch (String.valueOf(invalid.getMessage())) {
                case "resource_limit", "missing_port", "unsupported_version" -> invalid.getMessage();
                default -> "invalid_save";
            };
            return false;
        }
    }
    private static CompoundTag exact(Tag raw, String... keys) {
        if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of(keys))) throw new IllegalArgumentException("invalid_save");
        return tag;
    }
    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("invalid_save");
    }
    private static void interrupt(Port port, String reason) {
        if (!port.pending.isEmpty() || port.activeBytes!=0) { port.transmit = "interrupted"; port.failure = reason; }
        port.pending.clear();port.waitingBytes=0;port.activeBytes=0;
    }
    private static long nextEpoch(long value){if(value==Long.MAX_VALUE)throw new IllegalArgumentException("invalid_save");return value+1;}
    private static byte[] newKey(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
}
