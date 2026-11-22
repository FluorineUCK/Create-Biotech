package com.nobodiiiii.createbiotech.content.biocomputing.extension;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Public declaration of a fixed Scratch extension revision. Registration never exposes a world capability. */
public record ScratchExtension(ResourceLocation id, long revision, String titleKey, String descriptionKey,
                               Map<ResourceLocation, Long> requires, List<Command> commands) {
    public ScratchExtension {
        Objects.requireNonNull(id);
        if (revision < 1 || titleKey.isBlank() || descriptionKey.isBlank()) throw new IllegalArgumentException("Invalid extension identity");
        requires = Map.copyOf(requires);
        if (requires.containsKey(id) || requires.values().stream().anyMatch(version -> version < 1))
            throw new IllegalArgumentException("Invalid extension dependencies");
        commands = List.copyOf(commands);
        if (commands.isEmpty() || commands.stream().map(Command::opcode).distinct().count() != commands.size())
            throw new IllegalArgumentException("Extension opcodes must be nonempty and unique");
    }
    /** Labels are presentation only; the chosen value becomes an ordinary literal in the argument slot. */
    public record MenuOption(String labelKey, DataValue value) {
        public MenuOption {
            if (labelKey.isBlank()) throw new IllegalArgumentException("Missing menu label");
            LocalReferences.portable(value);
        }
    }
    public enum Shape {
        COMMAND, REPORTER, BOOLEAN, CONDITIONAL, CONDITIONAL_ELSE, LOOP, HAT;

        public boolean conditional() { return this == CONDITIONAL || this == CONDITIONAL_ELSE; }
        public int bodyCount() { return this == CONDITIONAL_ELSE ? 2 : this == CONDITIONAL || this == LOOP ? 1 : 0; }
    }
    /** Optional catalog placement. This never grants a hardware or world capability. */
    public enum SensingRole { NONE, INPUT, LINK, OUTPUT }
    public static final int MAX_MENU_OPTIONS = 16;

    /** A menu can suggest component IDs from a captured reader at three named integer inputs.
     *  The capture is editor data, not a serial session or permission grant. */
    public record CapturedReaderComponents(String x, String y, String z) {
        public CapturedReaderComponents {
            if (Set.of(x, y, z).size() != 3 || x.isBlank() || y.isBlank() || z.isBlank())
                throw new IllegalArgumentException("A reader menu needs three distinct coordinate inputs");
        }
    }

    /** All shapes lower to ordinary, resource-accounted ISA source. */
    public record Command(String opcode, String labelKey, String helpKey, Map<String, DataType> inputs,
                          Map<String, DataValue> defaults, Map<String, DataType> outputs, Map<String, String> returns,
                          AssemblySource source, Map<String, List<MenuOption>> menus, Shape shape, SensingRole sensingRole,
                          Map<String, CapturedReaderComponents> capturedMenus) {
        public Command(String opcode, String labelKey, String helpKey, Map<String, DataType> inputs,
                       Map<String, DataValue> defaults, Map<String, DataType> outputs, Map<String, String> returns,
                       AssemblySource source) {
            this(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, Map.of(), Shape.COMMAND);
        }
        public Command(String opcode, String labelKey, String helpKey, Map<String, DataType> inputs,
                       Map<String, DataValue> defaults, Map<String, DataType> outputs, Map<String, String> returns,
                       AssemblySource source, Map<String, List<MenuOption>> menus) {
            this(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, menus, Shape.COMMAND);
        }
        public Command(String opcode, String labelKey, String helpKey, Map<String, DataType> inputs,
                       Map<String, DataValue> defaults, Map<String, DataType> outputs, Map<String, String> returns,
                       AssemblySource source, Map<String, List<MenuOption>> menus, Shape shape) {
            this(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, menus, shape, SensingRole.NONE);
        }
        public Command(String opcode, String labelKey, String helpKey, Map<String, DataType> inputs,
                       Map<String, DataValue> defaults, Map<String, DataType> outputs, Map<String, String> returns,
                       AssemblySource source, Map<String, List<MenuOption>> menus, Shape shape, SensingRole role) {
            this(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, menus, shape, role, Map.of());
        }
        public Command inSensing(SensingRole role) {
            return new Command(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, menus, shape, role, capturedMenus);
        }
        public Command withCapturedReaderMenu(String argument, CapturedReaderComponents provider) {
            var added = new LinkedHashMap<>(capturedMenus);
            added.put(argument, provider);
            return new Command(opcode, labelKey, helpKey, inputs, defaults, outputs, returns, source, menus, shape, sensingRole, added);
        }
        public Command {
            if (!opcode.matches("[a-z][a-z0-9_]*") || labelKey.isBlank() || helpKey.isBlank())
                throw new IllegalArgumentException("Invalid Scratch command description");
            inputs = DataValue.immutableMap(inputs);
            outputs = DataValue.immutableMap(outputs);
            defaults = DataValue.immutableMap(defaults);
            returns = DataValue.immutableMap(returns);
            Objects.requireNonNull(source);
            Objects.requireNonNull(shape);
            Objects.requireNonNull(sensingRole);
            if (!inputs.keySet().equals(defaults.keySet()) || !outputs.keySet().equals(returns.keySet()))
                throw new IllegalArgumentException("Command signature and defaults/returns differ");
            if (shape != Shape.COMMAND) {
                var result = outputs.size() == 1 ? outputs.values().iterator().next() : null;
                boolean matches = shape == Shape.REPORTER
                    ? result == DataType.Scalar.INTEGER || result == DataType.Scalar.DECIMAL || result == DataType.Scalar.TEXT
                    : result == DataType.Scalar.BOOLEAN;
                if (!matches) throw new IllegalArgumentException("A reporter needs one matching concrete scalar result");
                if (!capturedMenus.isEmpty())
                    throw new IllegalArgumentException("Only commands can declare captured reader menus");
                if (inputs.values().stream().anyMatch(t -> !RegisterProgram.scalar(t)
                        && !(shape == Shape.REPORTER && t == DataType.Scalar.TEXT)))
                    throw new IllegalArgumentException("A reporter requires concrete scalar inputs");
            }
            for (var input : inputs.entrySet()) {
                var value = defaults.get(input.getKey());
                if (!input.getValue().accepts(value)) throw new IllegalArgumentException("Wrong command default: " + input.getKey());
                LocalReferences.portable(value);
            }
            var checked = new LinkedHashMap<String, List<MenuOption>>();
            for (var entry : menus.entrySet()) {
                var type = inputs.get(entry.getKey());
                var fallback = defaults.get(entry.getKey());
                var options = List.copyOf(entry.getValue());
                if (type == null || options.isEmpty() || options.size() > MAX_MENU_OPTIONS
                    || options.stream().anyMatch(option -> !type.accepts(option.value()))
                    || options.stream().map(MenuOption::value).distinct().count() != options.size()
                    || options.stream().noneMatch(option -> option.value().equals(fallback)))
                    throw new IllegalArgumentException("Invalid Scratch menu: " + entry.getKey());
                checked.put(entry.getKey(), options);
            }
            menus = Map.copyOf(checked);
            capturedMenus = Map.copyOf(capturedMenus);
            for (var entry : capturedMenus.entrySet()) {
                var provider = entry.getValue();
                if (inputs.get(entry.getKey()) != DataType.Scalar.TEXT || !menus.containsKey(entry.getKey())
                    || inputs.get(provider.x()) != DataType.Scalar.INTEGER || inputs.get(provider.y()) != DataType.Scalar.INTEGER
                    || inputs.get(provider.z()) != DataType.Scalar.INTEGER)
                    throw new IllegalArgumentException("Invalid captured reader menu: " + entry.getKey());
            }
        }
    }
}
