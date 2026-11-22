package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationValues.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationCodec.*;

/** Opt-in ordinary typed serial records. The peer proof is supplied separately by the host. */
public final class StockApplicationProtocol {
    private StockApplicationProtocol() {}
    public static final int VERSION = 1, MAX_REQUEST_CHARS = 128;
    public record Plan(State next, DataValue.RecordValue reply) {}
    static boolean addressed(DataValue.RecordValue target, DataValue payload) {
        return payload instanceof DataValue.RecordValue frame && text("stock_application_command").equals(frame.fields().get("kind"))
            && target.equals(frame.fields().get("target"));
    }
    public static Optional<Plan> plan(State before, Access peer, DataValue.RecordValue target, DataValue payload, Limits limits) {
        return plan(before,Optional.of(peer),target,payload,limits);
    }
    /** Empty proof is a normal addressed permission denial, not an invented application identity. */
    public static Optional<Plan> plan(State before, Optional<Access> verifiedPeer, DataValue.RecordValue target, DataValue payload, Limits limits) {
        return plan(before, verifiedPeer, target, payload, limits, true);
    }
    static Optional<Plan> planOutput(State before, Optional<Access> verifiedPeer, DataValue.RecordValue target, DataValue payload, Limits limits) {
        return plan(before, verifiedPeer, target, payload, limits, false);
    }
    private static Optional<Plan> plan(State before, Optional<Access> verifiedPeer, DataValue.RecordValue target, DataValue payload, Limits limits, boolean allowPoll) {
        Objects.requireNonNull(verifiedPeer);
        if (!addressed(target,payload)) return Optional.empty();
        var frame=(DataValue.RecordValue)payload;
        if (!(frame.fields().get("request") instanceof DataValue.TextValue request) || request.value().isEmpty() || request.value().length() > MAX_REQUEST_CHARS) return Optional.empty();
        String code = verifiedPeer.isPresent() ? accessError(before,verifiedPeer.orElseThrow()) : "permission";
        boolean authorized = code == null;
        State next = before; Optional<Input> input = Optional.empty();
        if (authorized) try {
            var peer=verifiedPeer.orElseThrow();
            fields(frame,"kind","version","target","request","operation","arguments");
            if (!number(VERSION).equals(frame.field("version"))) code = "unsupported_version";
            else {
                String operation = string(frame.field("operation"));
                if (!(frame.field("arguments") instanceof DataValue.RecordValue args)) throw new IllegalArgumentException();
                Update update = null;
                if (operation.equals("poll") && !allowPoll) code = "unsupported_operation";
                else switch (operation) {
                    case "status" -> { fields(args); code = "ok"; }
                    case "replace", "clear", "poll", "ack" -> {
                        if (operation.equals("replace")) fields(args,"device","session","revision","entries");
                        else if (operation.equals("ack")) fields(args,"device","session","revision","sequence");
                        else fields(args,"device","session","revision");
                        var fence = new Fence(uuid(args.field("device")),uuid(args.field("session")),integer(args.field("revision")));
                        update = switch(operation) {
                            case "replace" -> planReplace(before, peer, fence, args.field("entries"), limits);
                            case "clear" -> replace(before,peer,fence,List.of(),limits);
                            case "poll" -> poll(before,peer,fence);
                            default -> ack(before,peer,fence,integer(args.field("sequence")));
                        };
                    }
                    default -> code = "unsupported_operation";
                }
                if (update != null) { code=update.code();next=update.state();input=update.input(); }
            }
        } catch (IllegalArgumentException | com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException invalid) { code="invalid_command"; }
        return Optional.of(new Plan(next, reply(next, target, request, code, authorized, input)));
    }

    private static Update planReplace(State before, Access peer, Fence fence, DataValue entries, Limits limits) {
        if (entries instanceof DataValue.ListValue list && list.values().size() > MAX_MEMBERS) {
            // Validate the shape even beyond the representation ceiling, without publishing or retaining it.
            validateEntries(list.values().stream().map(StockApplicationCodec::readEntry).toList(), Integer.MAX_VALUE);
            var stale = fenceError(before, peer, fence);
            return new Update(stale == null ? "capacity" : stale, before, Optional.empty());
        }
        return replace(before, peer, fence, readEntries(entries), limits);
    }

    static DataValue.RecordValue reply(State next, DataValue.RecordValue target, DataValue.TextValue request,
                                               String code, boolean authorized, Optional<Input> input) {
        var reply = new LinkedHashMap<String,DataValue>();
        reply.put("kind",text("stock_application_reply"));reply.put("version",number(VERSION));reply.put("target",target);
        reply.put("request",request);reply.put("code",text(code));
        if (authorized) {
            reply.put("state",summary(next)); input.ifPresent(value -> reply.put("input",StockApplicationValues.input(value)));
        }
        return new DataValue.RecordValue(reply);
    }
    /** Token/count summary only; no private program memory or unrelated queued players. */
    public static DataValue.RecordValue summary(State state) {
        var fields = new LinkedHashMap<>(fence(state.fence()).fields());
        fields.put("next_sequence",number(state.nextSequence()));fields.put("entries",number(state.catalog().size()));
        fields.put("inputs",number(state.inputs().size()));fields.put("retained_units",number(retainedUnits(state)));
        return new DataValue.RecordValue(fields);
    }
}
