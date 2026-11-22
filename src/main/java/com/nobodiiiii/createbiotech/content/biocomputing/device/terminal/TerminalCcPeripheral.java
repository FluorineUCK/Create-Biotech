package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.peripheral.NotAttachedException;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import java.util.Map;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Loaded only with CC:Tweaked. Authority is local to this terminal, not serial or Computer RAM. */
public final class TerminalCcPeripheral implements IPeripheral {
    private final TerminalUiBlockEntity terminal;

    private TerminalCcPeripheral(TerminalUiBlockEntity terminal) { this.terminal = terminal; }

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), CBBlockEntityTypes.TERMINAL_UI.get(), (terminal, side) ->
            side != null && side != terminal.getBlockState().getValue(TerminalUiBlock.FACING).getOpposite()
                ? new TerminalCcPeripheral(terminal) : null);
    }

    @Override public String getType() { return "biocomputing_terminal"; }

    @Override public boolean equals(IPeripheral other) {
        return other instanceof TerminalCcPeripheral peer && terminal == peer.terminal;
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> readFrame(IComputerAccess computer, int width, int height, int offset) throws LuaException {
        int caller = caller(computer);
        try {
            var frame = terminal.ccFrame(caller, width, height, offset);
            return Map.of("revision", Long.toString(terminal.state().revision()), "rows", frame.rows(),
                "totalRows", frame.totalRows(), "clipped", frame.clipped(), "substituted", frame.substituted());
        } catch (IllegalArgumentException denied) {
            throw new LuaException(denied.getMessage());
        }
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> publisherStatus(IComputerAccess computer) throws LuaException {
        int caller = caller(computer);
        try { return TerminalCcProtocol.status(terminal.state(), caller); }
        catch (IllegalArgumentException denied) { throw new LuaException(denied.getMessage()); }
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> publishPage(IComputerAccess computer, String device, String session, String revision,
            String layoutJson, String rootsJson) throws LuaException {
        int caller = caller(computer);
        var before = terminal.state();
        try {
            var result = TerminalCcProtocol.publish(before, caller, device, session, revision, layoutJson, rootsJson,
                TerminalUiSerialBehaviour.limits());
            if (before != result.state() && !terminal.publish(before, result.state())) throw new LuaException("changed");
            return Map.of("code", result.code(), "state", TerminalCcProtocol.status(result.state(), caller));
        } catch (IllegalArgumentException denied) { throw new LuaException(denied.getMessage()); }
    }

    @LuaFunction(mainThread = true)
    public final Map<String, Object> takeEvent(IComputerAccess computer, String device, String session, String revision,
            String sequence) throws LuaException {
        int caller = caller(computer);
        var before = terminal.state();
        try {
            var result = TerminalCcProtocol.take(before, caller, device, session, revision, sequence);
            if (!terminal.publish(before, result.next())) throw new LuaException("changed");
            return result.event();
        } catch (IllegalArgumentException denied) { throw new LuaException(denied.getMessage()); }
    }

    private int caller(IComputerAccess computer) throws LuaException {
        // Delayed Lua calls must still have a live attachment when they reach the server thread.
        try {
            if (terminal.available() && equals(computer.getAvailablePeripheral(computer.getAttachmentName())))
                return computer.getID();
        } catch (NotAttachedException detached) { /* The caller disconnected while waiting for the main thread. */ }
        throw new LuaException("unavailable");
    }
}
