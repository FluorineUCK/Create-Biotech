package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterProtocol.*;

/** Scoped to the expressly authorized mounted list Filter; no arbitrary item/component interface. */
final class VirtualListFilterProtocol {
    private static final Set<String> FIELDS = Set.of("kind", "version", "target", "request", "operation", "arguments");
    private VirtualListFilterProtocol() {}
    record Plan(ItemStack before, Optional<ItemStack> after, DataValue.RecordValue reply) {}

    static Optional<Plan> plan(VirtualFilterTarget slot, String status, UUID session, UUID controller, UUID source,
            DataValue.RecordValue address, DataValue.RecordValue frame, int limit) {
        if (!text("virtual_list_filter_command").equals(frame.fields().get("kind")) || !address.equals(frame.fields().get("target"))
            || !(frame.fields().get("request") instanceof DataValue.TextValue request)
            || request.value().isEmpty() || request.value().length() > 128) return Optional.empty();
        boolean authorized = controller.equals(source);
        var before = slot == null ? ItemStack.EMPTY : slot.nativeStack();
        ItemStack after = null;
        DataValue.RecordValue state = null;
        String code;
        if (!authorized) code = "denied";
        else if (!frame.fields().keySet().equals(FIELDS)) code = "invalid_command";
        else if (!number(1).equals(frame.field("version"))) code = "unsupported_version";
        else if (!status.equals("ready")) code = status;
        else if (!(frame.field("operation") instanceof DataValue.TextValue operation)
            || !(frame.field("arguments") instanceof DataValue.RecordValue args)) code = "invalid_command";
        else try {
            var mount = slot.listBindingId();
            long current = VirtualListFilter.revision(before);
            state = token(mount, slot.identity(), session, current);
            if (operation.value().equals("status")) code = args.fields().isEmpty() ? "ok" : "invalid_command";
            else if (operation.value().equals("set") || operation.value().equals("clear")) {
                boolean set = operation.value().equals("set");
                if (!args.fields().keySet().equals(set ? Set.of("state", "slot", "rule") : Set.of("state", "slot"))
                    || !(args.field("slot") instanceof DataValue.IntegerValue index) || index.value() < 1 || index.value() > 18
                    || !(args.field("state") instanceof DataValue.RecordValue observed)
                    || !(observed.fields().get("revision") instanceof DataValue.IntegerValue rev)) code = "invalid_command";
                else if (!state.field("mount").equals(observed.fields().get("mount"))
                    || !state.field("target_id").equals(observed.fields().get("target_id"))) code = "target_changed";
                else if (!state.field("session").equals(observed.fields().get("session"))) code = "stale_session";
                else {
                    int nativeIndex = (int)index.value() - 1;
                    if (!VirtualListFilter.emptyAt(before, nativeIndex)) code = "filter_slot_occupied";
                    else if (rev.value() != current) code = "version_conflict";
                    else if (!slot.writeStatus().equals("ready")) code = slot.writeStatus();
                    else {
                        var edit = before.copy();
                        if (!set) code = VirtualListFilter.clear(edit, nativeIndex, current);
                        else if (!(args.field("rule") instanceof DataValue.RecordValue rule)) code = "invalid_filter_rule";
                        else if (!slot.acceptsListRule(rule, limit)) code = "filter_domain_mismatch";
                        else code = VirtualListFilter.set(edit, nativeIndex, current, rule, limit);
                        if (code.equals("applied") || code.equals("cleared")) {
                            state = token(mount, slot.identity(), session, VirtualListFilter.revision(edit));
                            after = edit;
                        }
                    }
                }
            } else code = "unsupported_operation";
        } catch (IllegalArgumentException invalid) { code = invalid.getMessage(); }
        var reply = new LinkedHashMap<String, DataValue>(Map.of("kind", text("virtual_list_filter_reply"), "version", number(1),
            "target", address, "request", request, "code", text(code)));
        if (state != null) reply.put("state", state);
        return Optional.of(new Plan(before, Optional.ofNullable(after), record(reply)));
    }
    private static DataValue.RecordValue token(UUID mount, UUID target, UUID session, long revision) {
        return record(Map.of("mount", text(mount.toString()), "target_id", text(target.toString()), "session", text(session.toString()),
            "revision", number(revision)));
    }
}
