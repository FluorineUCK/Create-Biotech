package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;

/** Replies have already passed their physical menu's ownership, nonce and latest-intent fence. */
public interface RecentEventMenu {
    record Reply(String code, Optional<DataValue.RecordValue> status) {}
    void requestLog();
    long logResponseVersion();
    Optional<Reply> logResponse();
}
