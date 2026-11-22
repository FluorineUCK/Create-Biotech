package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import java.util.*;
import java.util.function.Function;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationValues.*;

/** One finite wire owner, with explicit typed output extensions. */
final class StockApplicationHost {
    static final String PORT = "s0";
    @FunctionalInterface
    interface ProductionExtension {
        Optional<DataValue.RecordValue> respond(Optional<SharedSerialChannel.Port> source,
                                               DataValue.RecordValue target, DataValue.RecordValue frame);
    }
    private StockApplicationHost() {}

    static DataValue.RecordValue address(net.minecraft.core.BlockPos pos) { return BlockSnapshotReader.position(pos); }

    static String receive(StockApplicationSerial owner, SerialDevice device, Function<SharedSerialChannel.Port, Optional<Access>> proof,
                          DataValue.RecordValue target, Limits limits, Runnable changed) {
        return receive(owner, device, proof, target, limits, changed, (source, address, frame) -> Optional.empty());
    }
    static String receive(StockApplicationSerial owner, SerialDevice device, Function<SharedSerialChannel.Port, Optional<Access>> proof,
                          DataValue.RecordValue target, Limits limits, Runnable changed, ProductionExtension production) {
        if (Set.of("queued", "transmitting").contains(device.status(PORT).transmit())) return "busy";
        if (device.status(PORT).receive().frames() == 0) return "idle";
        var receive = device.prepareReceive(PORT);
        try {
            var frame = receive.result().fields().get("value");
            if (frame instanceof DataValue.RecordValue record && text("production_command").equals(record.fields().get("kind"))) {
                var response = production.respond(receive.source(), target, record);
                if (response.isPresent()) {
                    var reply = response.orElseThrow();
                    if (!receive.replying(reply).publish()) return "changed";
                    changed.run();
                    return StockApplicationCodec.string(reply.field("code"));
                }
            }
            var exchange = frame instanceof DataValue.RecordValue record && text("stock_application_command").equals(record.fields().get("kind"))
                ? owner.prepareOutput(receive, proof, target, limits)
                : Optional.<StockApplicationSerial.Exchange>empty();
            if (exchange.isPresent()) {
                var prepared = exchange.orElseThrow();
                if (!prepared.publish()) return "changed";
                changed.run();
                return StockApplicationCodec.string(prepared.reply().field("code"));
            }
            if (device.prepareDiscard(PORT).publish()) changed.run();
            return "ignored";
        } catch (SerialRecordCodec.Failure failure) {
            if (device.prepareDiscard(PORT).publish()) changed.run();
            return failure.code().name().toLowerCase(Locale.ROOT);
        }
    }

    static DataValue.RecordValue publicInput(State state, Optional<Input> head) {
        // This is a public read contract, not a dump of the private confirmation archive.
        // The current ACK fence is in state. Frozen entries include exact icons, payload and quantity.
        var fields = new LinkedHashMap<>(fence(state.fence()).fields());
        fields.put("inputs",number(state.inputs().size()));
        var publicState = new DataValue.RecordValue(fields);
        return head.<DataValue.RecordValue>map(value -> record("state", publicState, "head",
            publicHead(value)))
            .orElseGet(() -> record("state", publicState));
    }
    private static DataValue.RecordValue publicHead(Input value) {
        var common = new LinkedHashMap<String, DataValue>();
        common.put("sequence", number(value.sequence())); common.put("player", text(value.player().toString()));
        common.put("address", text(value.request().address())); common.put("kind", text(value.request().kind()));
        if (value.request().nativeOrder().isPresent()) common.put("order", value.request().nativeOrder().orElseThrow());
        else common.put("items", new DataValue.ListValue(value.items().stream().<DataValue>map(item ->
            record("entry",entry(item.entry()),"quantity",number(item.quantity()))).toList()));
        return new DataValue.RecordValue(common);
    }
    static boolean fitsReader(State next, Input input, ReaderSerialBehaviour.ApplicationPath reader) {
        try { return readerReply(next,input,reader).filter(reply -> fits(reply,reader.sender(),reader.receiver())).isPresent(); }
        catch (SnapshotLimits.LimitExceeded tooLarge) { return false; }
    }
    static Optional<DataValue.RecordValue> readerReply(State next, Input input, ReaderSerialBehaviour.ApplicationPath reader) {
        var component = CreateReadComponents.STOCK_APPLICATION;
        var values = record(component,publicInput(next,Optional.of(input)));
        SnapshotLimits.validate(values,reader.snapshot());
        var snapshot = record("components",values,"game_time",number(Long.MAX_VALUE),"target",address(reader.target()));
        var protocol = reader.protocol();
        var request = record("kind",text("reader_command"),"version",number(1),"target",protocol.address(),
            "request",worstRequest(ReaderSamplingProtocol.MAX_REQUEST_CHARS), "operation",text("sample"), "arguments",record(
                "device",text(protocol.device().toString()),"session",text(protocol.session().toString()),
                "components",new DataValue.ListValue(List.of(text(component)))));
        return protocol.respond(true,request, ids -> snapshot);
    }
    static boolean fitsOutput(State next, DataValue.RecordValue target, SerialRecordCodec.Limits sender, SerialRecordCodec.Limits receiver) {
        // Longest current normal mutation/control reply; all counters have fixed wire integer widths.
        return fits(StockApplicationProtocol.reply(next,target,worstRequest(StockApplicationProtocol.MAX_REQUEST_CHARS),"acknowledged",true,Optional.empty()),sender,receiver);
    }
    private static DataValue.TextValue worstRequest(int chars) { return text("\u0800".repeat(chars)); }
    private static boolean fits(DataValue.RecordValue reply, SerialRecordCodec.Limits sender, SerialRecordCodec.Limits receiver) {
        try { return reply.equals(SerialRecordCodec.decode(SerialRecordCodec.encode(reply,sender),receiver)); }
        catch (SerialRecordCodec.Failure failure) { return false; }
    }
}
