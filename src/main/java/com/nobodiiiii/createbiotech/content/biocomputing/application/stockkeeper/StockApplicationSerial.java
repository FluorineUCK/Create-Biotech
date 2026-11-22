package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import java.util.function.Function;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;

/** Thread-confined publication owner for a future host. No new transport queue or automatic work.
 * Host must mark dirty after successful confirm/bind/revoke/publication; verification callbacks must be read-only. */
public final class StockApplicationSerial {
    private State state;
    public StockApplicationSerial(State state) { StockApplicationCodec.validate(state); this.state=state; }
    /** Trusted host data, not a player/serial permission bypass. */
    public State state() { return state; }
    public Update confirm(Access access, UUID player, Request request, Limits limits) {
        return confirm(access, player, request, limits, (state, input) -> true);
    }
    public Update confirm(Access access, UUID player, Request request, Limits limits,
                          java.util.function.BiPredicate<State, Input> fitsRead) {
        var update = StockApplication.confirm(state, access, player, request, limits);
        if (update.code().equals("input_queued") && !fitsRead.test(update.state(), update.input().orElseThrow()))
            return new Update("input_too_large", state, Optional.empty());
        state = update.state();
        return update;
    }
    /** Explicit local administrator operation. A new session invalidates captured menus and commands. */
    void discardInputs(UUID session) {
        var before = state;
        if (session.equals(before.session())) throw new IllegalArgumentException("session_reused");
        state = new State(before.device(), session, before.revision(), before.application(), before.open(),
            before.nextSequence(), before.catalog(), List.of());
    }
    public Update bind(Application application, UUID session) {
        var update=StockApplication.bind(state,application,session);state=update.state();return update;
    }
    void refreshSession(UUID session) {
        var before = state;
        if (session.equals(before.session())) throw new IllegalArgumentException("session_reused");
        state = new State(before.device(), session, before.revision(), before.application(), before.open(),
            before.nextSequence(), before.catalog(), before.inputs());
    }
    public void revoke(UUID session) { state=StockApplication.revoke(state,session); }
    /** Verifier proves hardware source + CURRENT installed program and host availability, not frame fields.
     * Return Optional.empty() for unresolvable proof. Unrelated/native frames never invoke the verifier. */
    public Optional<Exchange> prepare(SerialDevice.Prepared receive, Function<SharedSerialChannel.Port,Optional<Access>> verifier,
                                     DataValue.RecordValue target, Limits limits) {
        return prepare(receive, verifier, target, limits, false);
    }
    Optional<Exchange> prepareOutput(SerialDevice.Prepared receive, Function<SharedSerialChannel.Port,Optional<Access>> verifier,
                                     DataValue.RecordValue target, Limits limits) {
        return prepare(receive, verifier, target, limits, true);
    }
    private Optional<Exchange> prepare(SerialDevice.Prepared receive, Function<SharedSerialChannel.Port,Optional<Access>> verifier,
                                      DataValue.RecordValue target, Limits limits, boolean output) {
        if (!new DataValue.TextValue("received").equals(receive.result().fields().get("status")) || receive.source().isEmpty()) return Optional.empty();
        if (!StockApplicationProtocol.addressed(target,receive.result().field("value"))) return Optional.empty();
        var source=receive.source().orElseThrow();var peer=Objects.requireNonNull(verifier.apply(source));
        var plan = output ? StockApplicationProtocol.planOutput(state, peer, target, receive.result().field("value"), limits)
            : StockApplicationProtocol.plan(state, peer, target, receive.result().field("value"), limits);
        if (plan.isEmpty()) return Optional.empty();
        var value=plan.orElseThrow();
        // Reply encoding/busy checks happen before any application mutation.
        return Optional.of(new Exchange(state,peer,source,verifier,value,receive.replying(value.reply())));
    }
    public final class Exchange {
        private final State before;
        private final Optional<Access> peer;
        private final SharedSerialChannel.Port source;
        private final Function<SharedSerialChannel.Port,Optional<Access>> verifier;
        private final StockApplicationProtocol.Plan plan;
        private final SerialDevice.Prepared publication;
        private Exchange(State before, Optional<Access> peer, SharedSerialChannel.Port source, Function<SharedSerialChannel.Port,Optional<Access>> verifier,
                         StockApplicationProtocol.Plan plan, SerialDevice.Prepared publication) {
            this.before=before;this.peer=peer;this.source=source;this.verifier=verifier;this.plan=plan;this.publication=publication;
        }
        public DataValue.RecordValue reply() { return plan.reply(); }
        public boolean publish() {
            if (state != before || !peer.equals(verifier.apply(source)) || !publication.publish()) return false;
            state=plan.next();return true;
        }
    }
}
