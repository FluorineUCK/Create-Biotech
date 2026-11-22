package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchExtensionDependencyBrowserTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    private static final Map<String, Map<Object, Object>> savedRegistry = new LinkedHashMap<>();
    private static boolean savedFrozen;
    @BeforeAll static void bootstrap() throws Exception {
        ComputerProgramScreenTest.bootstrap();
        // The production registry freezes on first catalog read. Isolate test-only add-ons,
        // preserving the exact prior registry so other screen tests see only real extensions.
        synchronized (ScratchExtensions.class) {
            var frozen = registryField("frozen");
            savedFrozen = frozen.getBoolean(null);
            for (String name : List.of("DEFINITIONS", "BINDINGS"))
                savedRegistry.put(name, new LinkedHashMap<>(registry(name)));
            frozen.setBoolean(null, false);
            try {
                registerFixture("scope_meter", 1, Map.of());
                registerFixture("scope_meter", 2, Map.of());
                registerFixture("scope_workflow", 3, Map.of(ResourceLocation.parse("addon:scope_meter"), 2L));
                registerFixture("factory_terminal", 5, Map.of(ResourceLocation.parse("addon:scope_workflow"), 3L));
                registerFixture("cycle_a", 1, Map.of(ResourceLocation.parse("addon:cycle_b"), 1L));
                registerFixture("cycle_b", 1, Map.of(ResourceLocation.parse("addon:cycle_a"), 1L));
                registerFixture("scope_missing_workflow", 4, Map.of(ResourceLocation.parse("addon:scope_missing"), 7L));
            } catch (RuntimeException failure) { restoreRegistry(); throw failure; }
        }
    }
    private static void registerFixture(String name, long version, Map<ResourceLocation, Long> needs) {
        var command = new ScratchExtension.Command("test", "test", "test", Map.of(), Map.of(), Map.of(), Map.of(),
            new AssemblySource("RET"));
        ScratchExtensions.register(new ScratchExtension(ResourceLocation.parse("addon:" + name), version,
            name, name, needs, List.of(command)));
    }
    private static java.lang.reflect.Field registryField(String name) throws Exception {
        var field = ScratchExtensions.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    @SuppressWarnings("unchecked") private static Map<Object, Object> registry(String name) throws Exception {
        return (Map<Object, Object>)registryField(name).get(null);
    }
    @AfterAll static void restoreRegistry() throws Exception {
        synchronized (ScratchExtensions.class) {
            for (var entry : savedRegistry.entrySet()) {
                var target = registry(entry.getKey()); target.clear(); target.putAll(entry.getValue());
            }
            registryField("frozen").setBoolean(null, savedFrozen);
        }
    }
    @BeforeEach void start() throws Exception { environment.client(); }
    @AfterEach void stop() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 640})
    void followAnExactRequiredVersionThenExplicitlyEnableIt(int width) throws Exception {
        var fixture = open(width);
        var library = fixture.screen();
        var workspace = fixture.workspace();
        var before = workspace.snapshot();
        var query = "ADDON:scope_workflow @ 3";
        boxes(library).getFirst().setValue(query);
        bounds(library);

        assertFalse(button(library, "extension_enable").active, "Blocked until the exact required version is enabled");
        click(library, "extension_dependencies");
        bounds(library);
        assertEquals(before, workspace.snapshot());
        assertTrue(visibleButton(library, "extension_open_requirement").isPresent());
        click(library, "extension_open_requirement");
        bounds(library);
        assertEquals(before, workspace.snapshot(), "Navigation must not change the draft");
        assertFalse(button(library, "palette_next").active, "Only the exact required version is selected");
        click(library, "extension_enable");
        assertEquals(Map.of("addon:scope_meter", 2L), workspace.flow().orElseThrow().canvas().extensions(),
            "The sibling revision is not silently enabled");
        click(library, "extension_return_origin");
        assertEquals(query, boxes(library).getFirst().getValue());
        assertTrue(button(library, "extension_enable").active);
        click(library, "extension_enable");
        assertEquals(Map.of("addon:scope_meter", 2L, "addon:scope_workflow", 3L), workspace.flow().orElseThrow().canvas().extensions());
        assertTrue(workspace.flow().orElseThrow().canvas().detached().isEmpty(), "No script or definition is installed");
    }

    @Test void aMissingExactDependencyIsInspectableEvenWithoutADraftReference() throws Exception {
        var fixture = open(426);
        var library = fixture.screen();
        var before = fixture.workspace().snapshot();
        boxes(library).getFirst().setValue("addon:scope_missing_workflow @ 4");

        click(library, "extension_dependencies");
        click(library, "extension_open_requirement");
        assertEquals("addon:scope_missing @ 7", boxes(library).getFirst().getValue());
        assertTrue(visibleButton(library, "extension_enable").isPresent());
        assertFalse(button(library, "extension_enable").active);
        click(library, "extension_return_origin");
        assertEquals("addon:scope_missing_workflow @ 4", boxes(library).getFirst().getValue());
        assertFalse(button(library, "extension_enable").active);
        assertEquals(before, fixture.workspace().snapshot());
    }

    @Test void anEnabledSiblingRevisionIsNeverReplacedByFollowingADependency() throws Exception {
        var fixture = open(426);
        var library = fixture.screen();
        boxes(library).getFirst().setValue("addon:scope_meter @ 1");
        click(library, "extension_enable");
        var siblingActive = fixture.workspace().snapshot();

        boxes(library).getFirst().setValue("addon:scope_workflow @ 3");
        assertFalse(button(library, "extension_enable").active);
        click(library, "extension_dependencies");
        click(library, "extension_open_requirement");
        assertEquals("addon:scope_meter @ 2", boxes(library).getFirst().getValue());
        assertFalse(button(library, "extension_enable").active);
        assertTrue(visibleButton(library, "extension_disable").isEmpty(), "Only the enabled version can be disabled");
        library.onClose();
        assertEquals("addon:scope_workflow @ 3", boxes(library).getFirst().getValue());
        assertEquals(siblingActive, fixture.workspace().snapshot());
    }
    @ParameterizedTest @ValueSource(ints = {320, 640})
    void nestedRequirementsReturnOneLevelAtATimeAndEnableOnlyOnExplicitChoice(int width) throws Exception {
        var fixture = open(width);
        var library = fixture.screen();
        var workspace = fixture.workspace();
        var original = workspace.snapshot();
        var initialQuery = "ADDON:factory_terminal @ 5";
        boxes(library).getFirst().setValue(initialQuery);
        assertFalse(button(library, "extension_enable").active);
        click(library, "extension_dependencies");
        click(library, "extension_open_requirement");
        assertEquals("addon:scope_workflow @ 3", boxes(library).getFirst().getValue());
        click(library, "extension_dependencies");
        click(library, "extension_open_requirement");
        assertEquals("addon:scope_meter @ 2", boxes(library).getFirst().getValue());
        assertEquals(original, workspace.snapshot(), "Following the chain does not enable anything");
        click(library, "extension_enable");
        click(library, "extension_return_origin");
        assertEquals("addon:scope_workflow @ 3", boxes(library).getFirst().getValue(), "Return to the immediate caller");
        assertEquals(Map.of("addon:scope_meter", 2L), workspace.flow().orElseThrow().canvas().extensions());
        assertTrue(button(library, "extension_enable").active);
        click(library, "extension_enable");
        click(library, "extension_return_origin");
        assertEquals(initialQuery, boxes(library).getFirst().getValue());
        assertEquals(Map.of("addon:scope_meter", 2L, "addon:scope_workflow", 3L),
            workspace.flow().orElseThrow().canvas().extensions());
        click(library, "extension_enable");
        assertEquals(Map.of("addon:scope_meter", 2L, "addon:scope_workflow", 3L, "addon:factory_terminal", 5L),
            workspace.flow().orElseThrow().canvas().extensions());
        assertTrue(workspace.flow().orElseThrow().canvas().detached().isEmpty());
    }

    @Test void navigatingADependencyCycleDoesNotCreateAnUnboundedReturnPath() throws Exception {
        var fixture = open(426);
        var library = fixture.screen();
        var before = fixture.workspace().snapshot();
        boxes(library).getFirst().setValue("addon:cycle_a @ 1");
        click(library, "extension_dependencies");
        click(library, "extension_open_requirement");
        assertEquals("addon:cycle_b @ 1", boxes(library).getFirst().getValue());
        click(library, "extension_dependencies");
        assertFalse(button(library, "extension_open_requirement").active);
        library.onClose();
        click(library, "extension_return_origin");
        assertEquals("addon:cycle_a @ 1", boxes(library).getFirst().getValue());
        assertFalse(button(library, "extension_enable").active);
        assertEquals(before, fixture.workspace().snapshot());
    }

    private static Fixture open(int width) throws Exception {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor = FlowCallScreenTest.editor(workspace, width);
        var opened = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>)opened::set);
        click(editor, "category_extensions");
        click(editor, "extension_library");
        var library = opened.get();
        FlowProcedureScreenTest.initializeForm(library, width);
        return new Fixture(workspace, library);
    }

    private static Optional<net.minecraft.client.gui.components.AbstractWidget> visibleButton(Screen screen, String key) {
        return screen.children().stream()
            .filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance)
            .map(net.minecraft.client.gui.components.AbstractWidget.class::cast)
            .filter(widget -> widget.visible && widget.getMessage().getString().equals(
                net.minecraft.network.chat.Component.translatable("create_biotech.biocomputing.flow_editor." + key).getString()))
            .findFirst();
    }
    private record Fixture(ProgramWorkspace workspace, Screen screen) {}
}
