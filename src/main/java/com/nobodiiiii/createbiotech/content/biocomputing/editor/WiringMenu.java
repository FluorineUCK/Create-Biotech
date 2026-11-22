package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** Small client-side view of a menu-owned, server-authorized wiring session. */
public interface WiringMenu {
    enum Action { READ, ADD, REMOVE, SET_ENABLED }
    record Reply(long intent, String code, String detail, Optional<DataValue.RecordValue> status) {}
    void wiringControl(Action action, DataValue.RecordValue inputs);
    long wiringResponseVersion();
    Optional<Reply> wiringResponse();
    default Optional<Component> wiringContext() { return Optional.empty(); }
    void leaveWiring();
}
