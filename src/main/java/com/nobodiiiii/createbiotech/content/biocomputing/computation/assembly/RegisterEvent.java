package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import java.util.*;

/** Owner-only dispatch. The installed linker fixes receiver identities; no run-time world lookup. */
public record RegisterEvent(String channel, boolean await, List<ModuleDefinition.Key> receivers)
        implements RegisterProgram.Instruction {
    public RegisterEvent {
        requireChannel(channel);
        receivers = List.copyOf(receivers);
        if (new HashSet<>(receivers).size() != receivers.size()) throw new IllegalArgumentException("Duplicate event receiver");
    }
    public static void requireChannel(String channel) {
        Objects.requireNonNull(channel);
        if (channel.isEmpty() || channel.length() > 64 || channel.codePoints().anyMatch(c ->
                Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c) || c == ';'))
            throw new IllegalArgumentException("Event name must be a single token of at most 64 characters");
    }
    @Override public int words() {
        int words = Math.addExact(2, channel.length());
        for (var key : receivers) words = Math.addExact(words, 2 + key.id().length());
        return words;
    }
}
