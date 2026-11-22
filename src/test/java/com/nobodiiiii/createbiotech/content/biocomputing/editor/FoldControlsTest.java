package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;

class FoldControlsTest {
	private Object oldClient;
	private Language oldLanguage;
	@BeforeAll static void bootstrap() throws Exception {
		Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		field(net.minecraft.server.Bootstrap.class, "isBootstrapped").setBoolean(null, true);
	}
	@BeforeEach void client() throws Exception {
		oldClient=field(Minecraft.class,"instance").get(null); oldLanguage=Language.getInstance();
		var client=allocate(Minecraft.class); set(client,"font",new ComputerProgramScreenTest.Metrics());
		field(Minecraft.class,"instance").set(null,client);
	}
	@AfterEach void restore() throws Exception { Language.inject(oldLanguage);field(Minecraft.class,"instance").set(null,oldClient); }


    @Test void realCreateCallbacksConfigureFoldAndWireToActualHost() throws Exception {
        var parent = ComputerProgramScreenTest.screen();
        var workspace = (ProgramWorkspace) get(parent, "workspace");
        var key = module(workspace, "sum");
        var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(parent, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) (screen -> opened[0] = screen));
        ComputerProgramScreenTest.addNode(parent, "fold");
        assertNull(workspace.root().nodes().getLast().definition());
        
        assertEquals(18, get(parent, "kind"));
        assertFalse(workspace.root().nodes().getLast().definition() instanceof ComputationGraph.Fold);
        click(parent, "create_biotech.biocomputing.fold.title");
        var chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        // Input order is deliberately item,total; cycling each twice preserves the selected interface.
        assertLayout(chooser, 320, 240);
        click(chooser, "create_biotech.biocomputing.fold.item");
        assertFalse(button(chooser, "apply").active, "Equal item and accumulator inputs cannot apply");
        click(chooser, "create_biotech.biocomputing.fold.item");
        click(chooser, "create_biotech.biocomputing.fold.accumulator");
        click(chooser, "create_biotech.biocomputing.fold.accumulator");
        click(chooser, "create_biotech.biocomputing.fold.result");
        initialize(chooser, 426, 300);
        assertLayout(chooser, 426, 300);
        click(chooser, "create_biotech.biocomputing.fold.apply");
        var root = workspace.root();
        var node = root.nodes().getLast();
        var fold = assertInstanceOf(ComputationGraph.Fold.class, node.definition());
        assertEquals(key, fold.module());
        assertEquals("item", fold.itemPort()); assertEquals("total", fold.accumulatorPort());
        assertEquals(java.util.Set.of("source", "initial"), node.ports());
        String id = node.id();
        String items = root.add(new ComputationGraph.Constant(FoldProgramFixtures.numbers(1,2,3)), 0, 0);
        String seed = root.add(new ComputationGraph.Constant(FoldProgramFixtures.integer(0)), 0, 40);
        root.connect(id, "source", items); root.connect(id, "initial", seed); root.output("result", id);
        invoke(parent, "openNode", id);
        click(parent, "create_biotech.biocomputing.fold.title");
        chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        click(chooser, "create_biotech.biocomputing.fold.open");
        assertSame(workspace.graph(key), get(parent, "draft"));
        click(parent, "create_biotech.biocomputing.editor.modules");
        click(parent, "create_biotech.biocomputing.editor.root_graph");
        invoke(parent, "openNode", id);
        click(parent, "create_biotech.biocomputing.fold.title");
        chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        click(chooser, "create_biotech.biocomputing.fold.apply");
        assertEquals(Map.of("source", items, "initial", seed), root.node(id).connections());
        var limits = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
        var archive = ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), limits), limits).archive();
        var hostLimits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1_000, 100_000, 1), limits));
        var host = new ComputerBlockHost(() -> {});
        host.install(archive, ComputerRuntime.MemoryPolicy.RESET, hostLimits, true);
        host.begin(new DataValue.RecordValue(Map.of()), hostLimits, true);
        for (int i = 0; host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) {
            assertTrue(i < 100); host.step(hostLimits, true);
        }
        assertEquals(FoldProgramFixtures.integer(6), host.snapshot().orElseThrow().machine().output().orElseThrow().field("result"));
    }

    @Test void missingOrIncompleteModuleCannotApplyButCanGoBackEscapeAndOpenForRepair() throws Exception {
        var parent = ComputerProgramScreenTest.screen();
        var workspace = (ProgramWorkspace) get(parent, "workspace");
        var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(parent, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) (screen -> opened[0] = screen));
        ComputerProgramScreenTest.addNode(parent, "fold");
        assertNull(workspace.root().nodes().getLast().definition());
        
        var retained = workspace.root().nodes().getLast().definition();
        click(parent, "create_biotech.biocomputing.fold.title");
        var chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        assertFalse(button(chooser, "apply").active);
        assertFalse(button(chooser, "open").active);
        click(chooser, "create_biotech.biocomputing.fold.back"); assertSame(parent, opened[0]);
        var key = workspace.createModule("incomplete");
        workspace.graph(key).putInput("only", DataType.Scalar.INTEGER);
        click(parent, "create_biotech.biocomputing.fold.title");
        chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        assertFalse(button(chooser, "apply").active); assertTrue(button(chooser, "open").active);
        assertTrue(chooser.keyPressed(256, 0, 0)); assertSame(parent, opened[0]);
        assertEquals(retained, workspace.root().nodes().getLast().definition());
        click(parent, "create_biotech.biocomputing.fold.title");
        chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        click(chooser, "create_biotech.biocomputing.fold.open");
        assertSame(workspace.graph(key), get(parent, "draft"));
    }

    @Test void namespacedPortsAndChangedModulePreserveOnlySameNamedConnections() throws Exception {
        var fold = new ComputationGraph.Fold("a", "b", FoldProgramFixtures.SUM, "item", "total",
            Map.of("source", "c", "initial", "d"), "next");
        var bindings = Map.of("source", "a", "initial", "b", "capture:source", "c", "capture:initial", "d");
        assertEquals(bindings, NodePorts.bindings(fold)); assertEquals(fold, NodePorts.bind(fold, bindings));
        var parent = ComputerProgramScreenTest.screen();
        var workspace = (ProgramWorkspace) get(parent, "workspace");
        var first = module(workspace, "first"); var second = module(workspace, "second");
        workspace.graph(first).putInput("common", DataType.Scalar.INTEGER);
        workspace.graph(second).putInput("common", DataType.Scalar.INTEGER);
        workspace.graph(second).putInput("new", DataType.Scalar.INTEGER);
        var root = workspace.root();
        String id = root.add(new ComputationGraph.Fold("source", "initial", first, "item", "total", Map.of("common", "common"), "next"), 0, 0);
        String source = root.add(new ComputationGraph.Constant(FoldProgramFixtures.numbers(1)), 0, 0);
        String seed = root.add(new ComputationGraph.Constant(FoldProgramFixtures.integer(0)), 0, 0);
        root.connect(id, "source", source); root.connect(id, "initial", seed); root.connect(id, "capture:common", seed);
        var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(parent, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>) (screen -> opened[0] = screen));
        invoke(parent, "openNode", id); click(parent, "create_biotech.biocomputing.fold.title");
        var chooser = initialize(assertInstanceOf(ComputerFoldScreen.class, opened[0]), 320, 240);
        click(chooser, "create_biotech.biocomputing.fold.module"); click(chooser, "create_biotech.biocomputing.fold.apply");
        assertEquals(second, assertInstanceOf(ComputationGraph.Fold.class, root.node(id).definition()).module());
        assertEquals(Map.of("source", source, "initial", seed, "capture:common", seed), root.node(id).connections());
        assertTrue(root.node(id).ports().contains("capture:new")); assertFalse(root.node(id).connections().containsKey("capture:new"));
    }

    private static void assertLayout(net.minecraft.client.gui.screens.Screen screen, int width, int height) {
        var buttons = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).toList();
        for (var button : buttons) {
            assertTrue(button.getX() >= 0 && button.getY() >= 0);
            assertTrue(button.getX() + button.getWidth() <= width && button.getY() + button.getHeight() <= height);
            for (var other : buttons) {
                if (button == other) continue;
                assertFalse(button.getX() < other.getX() + other.getWidth() && other.getX() < button.getX() + button.getWidth()
                    && button.getY() < other.getY() + other.getHeight() && other.getY() < button.getY() + button.getHeight());
            }
        }
    }
    private static CreateTextButton button(net.minecraft.client.gui.screens.Screen screen, String suffix) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.fold." + suffix)).findFirst().orElseThrow();
    }
    private static ModuleDefinition.Key module(ProgramWorkspace workspace, String name) {
        var key = workspace.createModule(name); var graph = workspace.graph(key);
        graph.putInput("item", DataType.Scalar.INTEGER); graph.putInput("total", DataType.Scalar.INTEGER);
        String item = graph.add(new ComputationGraph.Input("item"), 0, 0);
        String total = graph.add(new ComputationGraph.Input("total"), 0, 40);
        String next = graph.add(new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "left", "right", "right")), 80, 0);
        graph.connect(next, "left", total); graph.connect(next, "right", item); graph.output("next", next); return key;
    }
	private static <T extends net.minecraft.client.gui.screens.Screen> T initialize(T screen,int width,int height) throws Exception {
		set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);set(screen,"width",width);set(screen,"height",height);
		set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());
		var init=screen.getClass().getDeclaredMethod("init");init.setAccessible(true);init.invoke(screen);return screen;
	}
	private static void click(Object screen,String key) {
		((net.minecraft.client.gui.screens.Screen)screen).children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
			.filter(button->button.getMessage().getContents() instanceof TranslatableContents text&&text.getKey().equals(key)).findFirst().orElseThrow().onClick(0,0);
	}
	private static java.util.Set<String> keys(Object screen){var result=new java.util.LinkedHashSet<String>();((net.minecraft.client.gui.screens.Screen)screen).children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).forEach(button->{if(button.getMessage().getContents() instanceof TranslatableContents text)result.add(text.getKey());});return result;}
	private static void invoke(Object target,String name,Object...args) throws Exception {
		var method=target.getClass().getDeclaredMethod(name,String.class);method.setAccessible(true);method.invoke(target,args);
	}
	private static Object get(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
	private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}
	private static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
	private static <T>T allocate(Class<T> type)throws Exception{return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null)).allocateInstance(type));}
}
