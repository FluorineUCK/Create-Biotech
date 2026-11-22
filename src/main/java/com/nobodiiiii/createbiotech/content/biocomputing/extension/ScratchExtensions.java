package com.nobodiiiii.createbiotech.content.biocomputing.extension;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.BuiltinReadComponents;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.RedstoneReadComponent;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.WorldReadComponents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Trusted common-setup registry, frozen on first catalog access. No custom VM or device dispatch. */
public final class ScratchExtensions {
    private static final int MAX_SOURCE_BYTES = 32 * 1024;
    private static final String PREFIX = "cb_extension:";
    private record Revision(ResourceLocation id, long revision) {}
    private static final Map<Revision, ScratchExtension> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<ModuleDefinition.Key, Binding> BINDINGS = new LinkedHashMap<>();
    private static boolean frozen;

    // Remaining control mouths/event hats are linked with their respective compiler support.
    static { register(reader()); register(signals()); register(textTools()); register(narratorOutput()); register(textOutput()); register(noteOutput()); register(chatOutput()); register(speakerMedia()); }

    private ScratchExtensions() {}

    /** Add-ons register the same definitions on both sides during mod initialization. */
    public static synchronized void register(ScratchExtension definition) {
        if (frozen) throw new IllegalStateException("Scratch extensions are frozen");
        var revision = new Revision(definition.id(), definition.revision());
        if (DEFINITIONS.containsKey(revision)) throw new IllegalArgumentException("Duplicate Scratch extension: " + revision);
        var bindings = new LinkedHashMap<ModuleDefinition.Key, Binding>();
        for (var command : definition.commands()) {
            var binding = new Binding(definition, command);
            if (command.source().text().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
                throw new IllegalArgumentException("Extension source exceeds limit");
            var image = ProcedureAssembler.assemble(command.source(), command.inputs(), command.outputs(), command.returns(),
                MAX_SOURCE_BYTES, Long.MAX_VALUE);
            if (command.shape() != ScratchExtension.Shape.COMMAND && (image.loops()
                    || image.program().code().stream().anyMatch(code -> !reporterInstruction(command.shape(), code.instruction()))))
                throw new IllegalArgumentException("Extension reporters cannot perform effects, wait, or loop: " + command.opcode());
            bindings.put(binding.key(), binding);
        }
        DEFINITIONS.put(revision, definition);
        BINDINGS.putAll(bindings);
    }

    private static boolean reporterInstruction(ScratchExtension.Shape shape, RegisterProgram.Instruction op) {
        return switch (op) {
            case RegisterProgram.Constant ignored -> true;
            case RegisterProgram.Move ignored -> true;
            case RegisterProgram.Alu ignored -> true;
            case RegisterProgram.Jump ignored -> true;
            case RegisterProgram.JumpFalse ignored -> true;
            case RegisterProgram.Return ignored -> true;
            case RegisterProgram.TextConcat ignored -> shape == ScratchExtension.Shape.REPORTER;
            case RegisterDevices.Instruction device -> device.kind() != RegisterDevices.Kind.SIO_WRITE;
            default -> false;
        };
    }

    public record Binding(ScratchExtension extension, ScratchExtension.Command command) {
        public ModuleDefinition.Key key() {
            return new ModuleDefinition.Key(PREFIX + extension.id() + ":" + command.opcode(), extension.revision());
        }
        public FlowProgram.Procedure template(String source) {
            return new FlowProgram.Procedure(source, key(), command.inputs(), command.outputs(), command.returns(),
                FlowBlockRules.NONE, Optional.of(command.source()));
        }
    }

    public static synchronized List<ScratchExtension> all() {
        frozen = true;
        return DEFINITIONS.values().stream().sorted(Comparator.comparing((ScratchExtension d) -> d.id().toString())
            .thenComparingLong(ScratchExtension::revision)).toList();
    }

    public static synchronized Optional<Binding> binding(ModuleDefinition.Key key) {
        frozen = true;
        return Optional.ofNullable(BINDINGS.get(key));
    }

    public static boolean reserved(ModuleDefinition.Key key) { return key.id().startsWith(PREFIX); }

    /** Decode the owner of a persisted block key even when its add-on is absent. */
    public static Optional<ResourceLocation> extensionId(ModuleDefinition.Key key) {
        var value = key.id();
        if (!reserved(key)) return Optional.empty();
        int separator = value.lastIndexOf(':');
        if (separator <= PREFIX.length() || separator == value.length() - 1) return Optional.empty();
        String owner = value.substring(PREFIX.length(), separator);
        return owner.indexOf(':') > 0 ? Optional.ofNullable(ResourceLocation.tryParse(owner)) : Optional.empty();
    }

    public static void requireEnabled(ScratchExtension definition, Map<String, Long> enabled) {
        if (!Objects.equals(enabled.get(definition.id().toString()), definition.revision()))
            throw new IllegalArgumentException("Scratch extension not enabled: " + definition.id() + " @ " + definition.revision());
        var checked = new HashSet<Revision>();
        var pending = new ArrayDeque<ScratchExtension>();
        pending.add(definition);
        var known = all();
        while (!pending.isEmpty()) {
            var next = pending.removeFirst();
            if (!checked.add(new Revision(next.id(), next.revision()))) continue;
            next.requires().forEach((id, version) -> {
                if (!Objects.equals(enabled.get(id.toString()), version))
                    throw new IllegalArgumentException("Required Scratch extension is not enabled: " + id + " @ " + version);
                var dependency = known.stream().filter(item -> item.id().equals(id) && item.revision() == version).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension: " + id + " @ " + version));
                pending.add(dependency);
            });
        }
    }

    /** Validate copied source at installation, not at archive decoding or installed continuation restore. */
    public static void validate(Map<String, Long> enabled, List<FlowProgram.Step> steps) {
        for (var step : steps) if (step instanceof FlowProgram.Procedure procedure && reserved(procedure.key())) {
            var binding = binding(procedure.key()).orElseThrow(
                () -> new IllegalArgumentException("Missing Scratch extension command: " + procedure.key().id() + " @ " + procedure.key().revision()));
            requireEnabled(binding.extension(), enabled);
            if (!binding.template(procedure.sourceId()).equals(procedure))
                throw new IllegalArgumentException("Scratch extension command source differs from its registered revision: " + procedure.key().id());
        }
    }
    /** Find the predicate beneath the single negation used to lower a while loop to Until. */
    public static String loopPredicate(ComputationGraph graph, String root) {
        if (root == null || !(graph.nodes().get(root) instanceof ComputationGraph.Scalar scalar)
            || scalar.operation() != ScalarOperation.NOT_BOOLEAN) return null;
        return scalar.arguments().get("value");
    }

    public static String hatPredicate(ComputationGraph graph, String root) {
        if (root == null || !(graph.nodes().get(root) instanceof ComputationGraph.Scalar edge)
            || edge.operation() != ScalarOperation.SAMPLE_RISING_INTEGER
            || !integerConstant(graph, edge.arguments().get("threshold"), 0)
            || !(graph.nodes().get(edge.arguments().get("current")) instanceof ComputationGraph.Scalar bit)
            || bit.operation() != ScalarOperation.SELECT
            || !integerConstant(graph, bit.arguments().get("when_true"), 1)
            || !integerConstant(graph, bit.arguments().get("when_false"), 0)) return null;
        return bit.arguments().get("condition");
    }
    private static boolean integerConstant(ComputationGraph graph, String node, long value) {
        return graph.nodes().get(node) instanceof ComputationGraph.Constant literal
            && literal.value() instanceof DataValue.IntegerValue integer && integer.value() == value;
    }
    public static void validateReporter(ModuleDefinition.Key key, Set<ModuleDefinition.Key> linked, ScratchExtension.Shape placement) {
        var binding = binding(key).orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension reporter: " + key.id()));
        boolean validPlacement = switch (binding.command().shape()) {
            case COMMAND -> false;
            case REPORTER, BOOLEAN -> true;
            case CONDITIONAL, CONDITIONAL_ELSE, LOOP, HAT -> binding.command().shape() == placement;
        };
        if (!linked.contains(key) || !validPlacement)
            throw new IllegalArgumentException("Invalid fixed Scratch reporter binding: " + key.id());
    }

    public static void validateDirectCall(ModuleDefinition.Key key, Set<ModuleDefinition.Key> linked) {
        if (!reserved(key)) return;
        var binding = binding(key).orElseThrow(() -> new IllegalArgumentException("Missing Scratch extension command: " + key.id()));
        if (!linked.contains(key) || binding.command().shape() != ScratchExtension.Shape.COMMAND)
            throw new IllegalArgumentException("Only a fixed Scratch command can be called directly: " + key.id());
    }

    private static ScratchExtension signals() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "signals");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var level = new ScratchExtension.Command("simple_level", lang + "extension_simple_level", lang + "extension_simple_level_help",
            Map.of(), Map.of(), Map.of("level", integer), Map.of("level", "sample"),
            new AssemblySource("REG sample INTEGER\nSIO.READ sample p0\nRET"), Map.of(), ScratchExtension.Shape.REPORTER);
        var busy = new ScratchExtension.Command("xbus_busy", lang + "extension_xbus_busy", lang + "extension_xbus_busy_help",
            Map.of(), Map.of(), Map.of("busy", DataType.Scalar.BOOLEAN), Map.of("busy", "answer"),
            new AssemblySource("REG status INTEGER\nREG high INTEGER\nREG answer BOOLEAN\nCONST high 15\nXBUS.STATUS status x0\nEQUAL_INTEGER answer status high\nRET"),
            Map.of(), ScratchExtension.Shape.BOOLEAN);
        var conditional = new ScratchExtension.Command("if_signal", lang + "extension_if_signal", lang + "extension_if_signal_help",
            Map.of("threshold", integer), Map.of("threshold", new DataValue.IntegerValue(1)),
            Map.of("matches", DataType.Scalar.BOOLEAN), Map.of("matches", "matches"),
            new AssemblySource("REG level INTEGER\nREG matches BOOLEAN\nSIO.READ level p0\nLESS_EQUAL_INTEGER matches threshold level\nRET"),
            Map.of(), ScratchExtension.Shape.CONDITIONAL);
        var alternate = new ScratchExtension.Command("if_signal_else", lang + "extension_if_signal_else", lang + "extension_if_signal_else_help",
            conditional.inputs(), conditional.defaults(), conditional.outputs(), conditional.returns(), conditional.source(),
            Map.of(), ScratchExtension.Shape.CONDITIONAL_ELSE);
        var loop = new ScratchExtension.Command("while_signal", lang + "extension_while_signal", lang + "extension_while_signal_help",
            conditional.inputs(), conditional.defaults(), conditional.outputs(), conditional.returns(), conditional.source(),
            Map.of(), ScratchExtension.Shape.LOOP);
        var hat = new ScratchExtension.Command("when_signal", lang + "extension_when_signal", lang + "extension_when_signal_help",
            conditional.inputs(), conditional.defaults(), conditional.outputs(), conditional.returns(), conditional.source(),
            Map.of(), ScratchExtension.Shape.HAT);
        var input = ScratchExtension.SensingRole.INPUT;
        return new ScratchExtension(id, 1, lang + "extension_signals", lang + "extension_signals_description",
            Map.of(), List.of(level.inSensing(input), busy.inSensing(ScratchExtension.SensingRole.LINK),
                conditional.inSensing(input), alternate.inSensing(input), loop.inSensing(input), hat.inSensing(input)));
    }
    private static ScratchExtension textTools() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "text");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var text = DataType.Scalar.TEXT;
        var label = new ScratchExtension.Command("text_label", lang + "extension_text_label", lang + "extension_text_label_help",
            Map.of("label", text, "value", text),
            Map.of("label", new DataValue.TextValue("Status"), "value", new DataValue.TextValue("ready")),
            Map.of("text", text), Map.of("text", "formatted"),
            new AssemblySource(bundledSource("text_label")), Map.of(), ScratchExtension.Shape.REPORTER);
        return new ScratchExtension(id, 1, lang + "extension_text", lang + "extension_text_description", Map.of(), List.of(label));
    }

    private static ScratchExtension textOutput() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "text_output");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var text = DataType.Scalar.TEXT;
        var zero = new DataValue.IntegerValue(0);
        var empty = new DataValue.TextValue("");
        var coordinates = Map.<String, DataType>of("x", integer, "y", integer, "z", integer);
        var defaults = Map.<String, DataValue>of("x", zero, "y", zero, "z", zero);
        var outputs = Map.<String, DataType>of("queued", SerialResult.SEND, "request", text);
        var returns = Map.of("queued", "queued", "request", "request");
        var status = new ScratchExtension.Command("status", lang + "extension_text_output_status",
            lang + "extension_text_output_status_help", coordinates, defaults, outputs, returns,
            new AssemblySource(bundledSource("text_output_status")));
        var inputs = new LinkedHashMap<String, DataType>(coordinates);
        inputs.putAll(Map.of("device", text, "session", text, "revision", integer, "line", text));
        var initial = new LinkedHashMap<String, DataValue>(defaults);
        initial.putAll(Map.of("device", empty, "session", empty, "revision", zero, "line", empty));
        var replace = new ScratchExtension.Command("replace_line", lang + "extension_text_output_replace_line",
            lang + "extension_text_output_replace_line_help", inputs, initial, outputs, returns,
            new AssemblySource(bundledSource("text_output_replace_line")));
        return new ScratchExtension(id, 1, lang + "extension_text_output", lang + "extension_text_output_description",
            Map.of(), List.of(status.inSensing(ScratchExtension.SensingRole.OUTPUT), replace.inSensing(ScratchExtension.SensingRole.OUTPUT)));
    }

    private static ScratchExtension noteOutput() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "note_output");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var text = DataType.Scalar.TEXT;
        var zero = new DataValue.IntegerValue(0);
        var empty = new DataValue.TextValue("");
        var coordinates = Map.<String, DataType>of("x", integer, "y", integer, "z", integer);
        var defaults = Map.<String, DataValue>of("x", zero, "y", zero, "z", zero);
        var outcomes = Map.<String, DataType>of("queued", SerialResult.SEND, "request", text);
        var returns = Map.of("queued", "queued", "request", "request");
        var status = new ScratchExtension.Command("status", lang + "extension_note_output_status",
            lang + "extension_note_output_status_help", coordinates, defaults, outcomes, returns,
            new AssemblySource(bundledSource("note_output_status")));
        var inputs = new LinkedHashMap<String, DataType>(coordinates);
        inputs.putAll(Map.of("device", text, "session", text, "revision", integer, "instrument", text, "note", integer));
        var initial = new LinkedHashMap<String, DataValue>(defaults);
        initial.putAll(Map.of("device", empty, "session", empty, "revision", zero, "instrument", new DataValue.TextValue("harp"), "note", zero));
        var play = new ScratchExtension.Command("play", lang + "extension_note_output_play", lang + "extension_note_output_play_help",
            inputs, initial, outcomes, returns, new AssemblySource(bundledSource("note_output_play")));
        return new ScratchExtension(id, 1, lang + "extension_note_output", lang + "extension_note_output_description",
            Map.of(), List.of(status.inSensing(ScratchExtension.SensingRole.OUTPUT), play.inSensing(ScratchExtension.SensingRole.OUTPUT)));
    }

    private static ScratchExtension chatOutput() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "chat_output");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var text = DataType.Scalar.TEXT;
        var zero = new DataValue.IntegerValue(0);
        var empty = new DataValue.TextValue("");
        var coordinates = Map.<String, DataType>of("x", integer, "y", integer, "z", integer);
        var defaults = Map.<String, DataValue>of("x", zero, "y", zero, "z", zero);
        var outcomes = Map.<String, DataType>of("queued", SerialResult.SEND, "request", text);
        var returns = Map.of("queued", "queued", "request", "request");
        var status = new ScratchExtension.Command("status", lang + "extension_chat_output_status",
            lang + "extension_chat_output_status_help", coordinates, defaults, outcomes, returns,
            new AssemblySource(bundledSource("chat_output_status")));
        var inputs = new LinkedHashMap<String, DataType>(coordinates);
        inputs.putAll(Map.of("device", text, "session", text, "revision", integer, "text", text));
        var initial = new LinkedHashMap<String, DataValue>(defaults);
        initial.putAll(Map.of("device", empty, "session", empty, "revision", zero, "text", empty));
        var send = new ScratchExtension.Command("send", lang + "extension_chat_output_send",
            lang + "extension_chat_output_send_help", inputs, initial, outcomes, returns,
            new AssemblySource(bundledSource("chat_output_send")));
        return new ScratchExtension(id, 1, lang + "extension_chat_output", lang + "extension_chat_output_description",
            Map.of(), List.of(status.inSensing(ScratchExtension.SensingRole.OUTPUT), send.inSensing(ScratchExtension.SensingRole.OUTPUT)));
    }

    private static ScratchExtension speakerMedia() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "speaker_media");
        var integer = DataType.Scalar.INTEGER;
        var text = DataType.Scalar.TEXT;
        var zero = new DataValue.IntegerValue(0);
        var empty = new DataValue.TextValue("");
        var coordinates = Map.<String, DataType>of("x", integer, "y", integer, "z", integer);
        var defaults = Map.<String, DataValue>of("x", zero, "y", zero, "z", zero);
        var token = new LinkedHashMap<String, DataType>(coordinates);
        token.putAll(Map.of("device", text, "session", text, "revision", integer));
        var tokenDefaults = new LinkedHashMap<String, DataValue>(defaults);
        tokenDefaults.putAll(Map.of("device", empty, "session", empty, "revision", zero));
        var version = new DataType.RecordType(Map.of("medium", text, "entry", text, "revision", integer));
        var reference = new DataType.RecordType(Map.of("library", new DataType.RecordType(coordinates),
            "device", text, "sequence", integer, "version", version));
        var playInputs = new LinkedHashMap<String, DataType>(token);
        playInputs.put("reference", reference);
        var playDefaults = new LinkedHashMap<String, DataValue>(tokenDefaults);
        // A well-typed, intentionally unusable default. The library sequence must be a real completed read.
        playDefaults.put("reference", new DataValue.RecordValue(Map.of("library", new DataValue.RecordValue(defaults),
            "device", empty, "sequence", zero,
            "version", new DataValue.RecordValue(Map.of("medium", empty, "entry", empty, "revision", zero)))));
        String lang = "create_biotech.biocomputing.flow_editor.extension_speaker_media";
        return new ScratchExtension(id, 1, lang, lang + "_description", Map.of(), List.of(
            speakerMediaCommand(lang, "status", coordinates, defaults),
            speakerMediaCommand(lang, "play", playInputs, playDefaults),
            speakerMediaCommand(lang, "stop", token, tokenDefaults)));
    }

    private static ScratchExtension.Command speakerMediaCommand(String lang, String operation,
            Map<String, DataType> inputs, Map<String, DataValue> defaults) {
        String key = lang + "_" + operation;
        return new ScratchExtension.Command(operation, key, key + "_help", inputs, defaults,
            Map.of("queued", SerialResult.SEND, "request", DataType.Scalar.TEXT),
            Map.of("queued", "queued", "request", "request"),
            new AssemblySource(bundledSource("speaker_media_" + operation))).inSensing(ScratchExtension.SensingRole.OUTPUT);
    }

    private static ScratchExtension reader() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "reader");
        String namespace = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var zero = new DataValue.IntegerValue(0);
        var redstone = new DataValue.TextValue(RedstoneReadComponent.ID);
        var outputs = Map.<String, DataType>of("queued", SerialResult.SEND, "request", DataType.Scalar.TEXT);
        var returns = Map.of("queued", "queued", "request", "request");
        var status = new ScratchExtension.Command("status", namespace + "extension_reader_status", namespace + "extension_reader_status_help",
            Map.of("x", integer, "y", integer, "z", integer), Map.of("x", zero, "y", zero, "z", zero),
            outputs, returns, new AssemblySource(bundledSource("reader_status")));
        var sample = new ScratchExtension.Command("sample", namespace + "extension_reader_sample", namespace + "extension_reader_sample_help",
            Map.of("x", integer, "y", integer, "z", integer, "device", DataType.Scalar.TEXT, "session", DataType.Scalar.TEXT,
                "component", DataType.Scalar.TEXT),
            Map.of("x", zero, "y", zero, "z", zero, "device", new DataValue.TextValue(""), "session", new DataValue.TextValue(""),
                "component", redstone),
            outputs, returns, new AssemblySource(bundledSource("reader_sample")), Map.of("component", List.of(
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_redstone", redstone),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_inventory", new DataValue.TextValue(BuiltinReadComponents.INVENTORY)),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_block", new DataValue.TextValue(BuiltinReadComponents.BLOCK)),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_fluid", new DataValue.TextValue(BuiltinReadComponents.FLUID)),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_energy", new DataValue.TextValue(BuiltinReadComponents.ENERGY)),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_world", new DataValue.TextValue(WorldReadComponents.WORLD)),
                new ScratchExtension.MenuOption(namespace + "extension_reader_component_environment", new DataValue.TextValue(WorldReadComponents.ENVIRONMENT)))))
            .withCapturedReaderMenu("component", new ScratchExtension.CapturedReaderComponents("x", "y", "z"));
        // Adding an opcode leaves the existing status command's fixed key and implementation intact.
        return new ScratchExtension(id, 1, namespace + "extension_reader", namespace + "extension_reader_description", Map.of(),
            List.of(status.inSensing(ScratchExtension.SensingRole.INPUT), sample.inSensing(ScratchExtension.SensingRole.INPUT)));
    }
    private static ScratchExtension narratorOutput() {
        var id = ResourceLocation.fromNamespaceAndPath("create_biotech", "narrator_output");
        String lang = "create_biotech.biocomputing.flow_editor.";
        var integer = DataType.Scalar.INTEGER;
        var text = DataType.Scalar.TEXT;
        var zero = new DataValue.IntegerValue(0);
        var empty = new DataValue.TextValue("");
        var coordinates = Map.<String, DataType>of("x", integer, "y", integer, "z", integer);
        var defaults = Map.<String, DataValue>of("x", zero, "y", zero, "z", zero);
        var outcomes = Map.<String, DataType>of("queued", SerialResult.SEND, "request", text);
        var returns = Map.of("queued", "queued", "request", "request");
        var status = new ScratchExtension.Command("status", lang + "extension_narrator_output_status",
            lang + "extension_narrator_output_status_help", coordinates, defaults, outcomes, returns,
            new AssemblySource(bundledSource("narrator_output_status")));
        var inputs = new LinkedHashMap<String, DataType>(coordinates);
        inputs.putAll(Map.of("device", text, "session", text, "revision", integer, "text", text));
        var initial = new LinkedHashMap<String, DataValue>(defaults);
        initial.putAll(Map.of("device", empty, "session", empty, "revision", zero, "text", empty));
        var speak = new ScratchExtension.Command("speak", lang + "extension_narrator_output_speak",
            lang + "extension_narrator_output_speak_help", inputs, initial, outcomes, returns,
            new AssemblySource(bundledSource("narrator_output_speak")));
        return new ScratchExtension(id, 1, lang + "extension_narrator_output", lang + "extension_narrator_output_description",
            Map.of(), List.of(status.inSensing(ScratchExtension.SensingRole.OUTPUT), speak.inSensing(ScratchExtension.SensingRole.OUTPUT)));
    }

    private static String bundledSource(String name) {
        String path = "/assets/create_biotech/biocomputing/extensions/" + name + ".asm";
        String source;
        try (var stream = ScratchExtensions.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing built-in Scratch extension source: " + name);
            source = new String(stream.readNBytes(MAX_SOURCE_BYTES + 1), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return source;
    }
}
