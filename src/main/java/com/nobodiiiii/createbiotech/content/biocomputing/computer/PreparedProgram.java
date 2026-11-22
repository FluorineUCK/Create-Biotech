package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView;
import java.util.Objects;
import java.util.Optional;

/**
 * Validated, linked installation source, not a portable machine image or checkpoint.
 * The existing owner remains responsible for final compilation and lifecycle admission.
 * Transitional control IR is intentionally confined to this package's installation seam.
 */
public final class PreparedProgram {
    public enum InstallationPolicy { RESET_PAUSED, LEGACY_EVENT }
    private final ProgramDocument document;
    private final FlowProgram control;

    private PreparedProgram(ProgramDocument document) {
        this.document = Objects.requireNonNull(document);
        control = document.flow().map(body -> body.program(document.archive())).orElse(null);
    }

    /** Pure preparation: no owner, identity allocation, RAM mutation, or execution. */
    public static PreparedProgram prepare(ProgramDocument document, ComputerHostLimits limits) {
        var prepared = new PreparedProgram(document);
        prepared.validateTarget(limits);
        return prepared;
    }

    public InstallationPolicy installationPolicy() {
        return control == null ? InstallationPolicy.LEGACY_EVENT : InstallationPolicy.RESET_PAUSED;
    }

    // No public document/image pair or public Flow IR accessor can bypass source association.
    ProgramDocument document() { return document; }
    FlowProgram control() { return control; }
    void validateTarget(ComputerHostLimits limits) {
        Objects.requireNonNull(limits);
        document.validateTerminalTargets();
        document.validateExtensions();
        if (control == null) {
            document.archive().compile(limits.hardware());
            FlowPins.validateLegacyInstructionSets(document.archive(), limits);
        }
        else {
            var target=control.forBroadcastHardware(limits.isa1a());
            target.validate(limits.hardware());
            FlowPins.validate(target, limits);
        }
        ProgramDocumentWire.encode(document, limits.archive());
        // Document compression does not enlarge the terminal observation transport.
        if (document.terminal().isPresent()) ProgramEditorWire.encodeValue(
            TerminalStatusView.of(document.terminal(), Optional.empty()), limits.archive());
        ValueNbtCodec.encode(ProgramDocumentCodec.encode(document), limits.archive());
    }
}
