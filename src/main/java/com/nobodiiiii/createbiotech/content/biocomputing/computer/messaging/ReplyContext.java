package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Map;
import java.util.Objects;

/** Frozen local routing selected from a matching committed pending request. */
public record ReplyContext(String slot, RpcState.Pending pending) {
	public ReplyContext {
		RpcState.requireName(slot);
		Objects.requireNonNull(pending);
	}

	public DataValue.RecordValue value() {
		return new DataValue.RecordValue(Map.of("slot", new DataValue.TextValue(slot), "pending", pending.value()));
	}
}
