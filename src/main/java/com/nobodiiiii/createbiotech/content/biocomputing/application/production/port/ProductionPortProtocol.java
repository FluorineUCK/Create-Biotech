package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public opt-in production service records; correlation/dedup is independent of wire acknowledgement. */
public final class ProductionPortProtocol {
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private final UUID device, session;
    private final long revision;
    private final DataValue.RecordValue target;
    private final ProductionOrderService service;
    private final Optional<String> address;
    /** Compatibility seam: no configured route fact is available from an older adapter. */
    public ProductionPortProtocol(UUID device, UUID session, long revision, DataValue.RecordValue target, ProductionOrderService service) {
        this(device, session, revision, target, Optional.empty(), service);
    }
    /** Destination is host-owned configuration, never a field supplied by a command. */
    public ProductionPortProtocol(UUID device, UUID session, long revision, DataValue.RecordValue target,
            String address, ProductionOrderService service) {
        this(device, session, revision, target,
            Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules.requireNormalized(address)), service);
    }
    private ProductionPortProtocol(UUID device, UUID session, long revision, DataValue.RecordValue target,
            Optional<String> address, ProductionOrderService service) {
        this.device = Objects.requireNonNull(device); this.session = Objects.requireNonNull(session);
        this.revision = revision; this.target = Objects.requireNonNull(target); this.service = Objects.requireNonNull(service);
        this.address = Objects.requireNonNull(address);
        if (revision < 0) throw new IllegalArgumentException("invalid_revision");
    }
    /** Only the host supplies a verified physical sender; null means ungranted, never a payload UUID. */
    public Optional<DataValue.RecordValue> respond(UUID source, DataValue.RecordValue frame) {
        if (!text("production_command").equals(frame.fields().get("kind")) || !target.equals(frame.fields().get("target"))) return Optional.empty();
        if (!(frame.fields().get("request") instanceof DataValue.TextValue request) || !validId(request.value())) return Optional.empty();
        var response = new LinkedHashMap<String, DataValue>();
        response.put("kind", text("production_reply")); response.put("version", integer(1));
        response.put("target", target); response.put("request", request);
        String code = "ok";
        if (source == null) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!integer(1).equals(frame.field("version"))) code = "unsupported_version";
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation) || !(frame.field("arguments") instanceof DataValue.RecordValue arguments)) code = "invalid_command";
        else {
            switch (operation.value()) {
                case "status" -> {
                    boolean scoped = arguments.equals(new DataValue.RecordValue(Map.of("scope", new DataValue.BooleanValue(true))));
                    if (!arguments.fields().isEmpty() && !scoped) code = "invalid_command";
                    else if (scoped && address.isEmpty()) code = "scope_unavailable";
                    else {
                        var state = new LinkedHashMap<String, DataValue>();
                        state.put("device", text(device.toString())); state.put("session", text(session.toString()));
                        state.put("revision", integer(revision)); address.ifPresent(route -> state.put("address", text(route)));
                        // Existing typed status records retain their exact shape unless explicitly requested.
                        if (scoped) scope(source).ifPresent(value -> state.put("scope", value));
                        response.put("state", new DataValue.RecordValue(state));
                    }
                }
                case "submit" -> {
                    boolean fenced = arguments.fields().containsKey("scope");
                    if (!arguments.fields().keySet().equals(fenced ? Set.of("device", "session", "order", "data", "scope") : Set.of("device", "session", "order", "data"))
                        || !(arguments.field("order") instanceof DataValue.TextValue order) || !validId(order.value())
                        || !(arguments.field("data") instanceof DataValue.RecordValue data)
                        || fenced && !(arguments.field("scope") instanceof DataValue.RecordValue)) code = "invalid_command";
                    else if (!text(device.toString()).equals(arguments.field("device")) || !text(session.toString()).equals(arguments.field("session"))) code = "session_changed";
                    else if (fenced && !matchesScope(source, arguments.field("scope"))) code = "scope_changed";
                    else code = addOrder(response, order.value(), service.submit(key(source, order.value()), data));
                }
                case "order" -> {
                    boolean fenced = arguments.fields().containsKey("scope");
                    if (!arguments.fields().keySet().equals(fenced ? Set.of("order", "scope") : Set.of("order"))
                        || !(arguments.field("order") instanceof DataValue.TextValue order) || !validId(order.value())
                        || fenced && !(arguments.field("scope") instanceof DataValue.RecordValue)) code = "invalid_command";
                    else if (fenced && !matchesScope(source, arguments.field("scope"))) code = "scope_changed";
                    else code = addOrder(response, order.value(), service.status(key(source, order.value())));
                }
                default -> code = "unsupported_operation";
            }
        }
        response.put("code", text(code)); return Optional.of(new DataValue.RecordValue(response));
    }
    /** Public immutable route facts, not credentials. A restored application must keep its OLD scope.
     * The host still proves the current sender and live grant; payloads cannot replace that proof.
     * Sessions are intentionally absent so an unchanged route survives ordinary save/reload. */
    private Optional<DataValue.RecordValue> scope(UUID source) {
        return address.map(route -> new DataValue.RecordValue(Map.of("source", text(source.toString()),
            "device", text(device.toString()), "revision", integer(revision), "target", target, "address", text(route))));
    }
    private boolean matchesScope(UUID source, DataValue expected) {
        return scope(source).filter(expected::equals).isPresent();
    }
    /** A failed device archive is not an empty order book and must not swallow the request. */
    public static Optional<DataValue.RecordValue> unavailable(UUID source, DataValue.RecordValue target,
            DataValue.RecordValue frame, String failure) {
        if (!text("production_command").equals(frame.fields().get("kind")) || !target.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request) || !validId(request.value())) return Optional.empty();
        return Optional.of(new DataValue.RecordValue(Map.of("kind", text("production_reply"), "version", integer(1),
            "target", target, "request", request, "code", text(source == null ? "denied" : failure))));
    }
    private ProductionOrderService.Key key(UUID source, String request) {
        // Device identity survives reload/movement; session only prevents stale *new* commands.
        return new ProductionOrderService.Key(source, UUID.nameUUIDFromBytes((device + "/" + request).getBytes(StandardCharsets.UTF_8)));
    }
    private static String addOrder(Map<String, DataValue> response, String name, ProductionOrderService.Reply reply) {
        response.put("order", text(name));
        reply.order().ifPresent(order -> {
            response.put("phase", text(order.phase().name().toLowerCase(Locale.ROOT)));
            response.put("diagnostic", text(order.diagnostic()));
            response.put("step", integer(order.step()));
            response.put("address", frozenAddress(order));
            if (order.plan().extension().equals("create.packaging") && order.plan().version() == 1)
                response.put("milestone", text(order.phase() == ProductionOrderService.Phase.COMPLETE ? "materials_delivered"
                    : order.phase() == ProductionOrderService.Phase.WAITING ? (order.result() == DataValue.NullValue.INSTANCE
                        ? "dispatch_accepted" : "materials_partially_delivered") : "not_confirmed"));
        });
        return reply.code();
    }
    /** Only the recognized frozen v1 command may publish a route; never substitute current host configuration. */
    private static DataValue frozenAddress(ProductionOrderService.Order order) {
        if (!order.plan().extension().equals("create.packaging") || order.plan().version() != 1
                || order.plan().steps().size() != 1 || order.step() != 0) return DataValue.NullValue.INSTANCE;
        var command = order.plan().steps().getFirst();
        if (!command.fields().keySet().equals(Set.of("network", "address", "revision", "data"))
                || !(command.field("network") instanceof DataValue.TextValue network)
                || !(command.field("address") instanceof DataValue.TextValue route)
                || !(command.field("revision") instanceof DataValue.IntegerValue revision) || revision.value() < 0
                || !(command.field("data") instanceof DataValue.RecordValue data)
                || !data.fields().keySet().equals(Set.of("items"))
                || !(data.field("items") instanceof DataValue.ListValue)) return DataValue.NullValue.INSTANCE;
        try {
            if (!UUID.fromString(network.value()).toString().equals(network.value())
                    || !com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules
                        .requireNormalized(route.value()).equals(route.value())) return DataValue.NullValue.INSTANCE;
        } catch (IllegalArgumentException invalid) { return DataValue.NullValue.INSTANCE; }
        return route;
    }
    private static boolean validId(String id) {
        // Malformed UTF-16 would be replaced during UTF-8 key encoding and alias distinct job names.
        return !id.isEmpty() && id.length() <= 128 && id.codePoints()
            .noneMatch(c -> Character.isISOControl(c) || c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE);
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
