package com.nobodiiiii.createbiotech.content.biocomputing.editor;

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

class BranchControlsTest {
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

	@Test void branchPortsNamespaceConditionApartFromArgumentsAndRebindingPreservesNames() {
		var branch = new ComputationGraph.Branch("conditionSource", BranchProgramFixtures.DIVIDE,
			BranchProgramFixtures.ZERO, Map.of("condition", "argumentCondition", "x", "x", "d", "d"));
		assertEquals(Map.of("condition", "conditionSource", "argument:condition", "argumentCondition",
			"argument:x", "x", "argument:d", "d"), NodePorts.bindings(branch));
		var rebound = assertInstanceOf(ComputationGraph.Branch.class, NodePorts.bind(branch, Map.of(
			"condition", "newCondition", "argument:condition", "newArgumentCondition",
			"argument:x", "newX", "argument:d", "newD")));
		assertEquals("newCondition", rebound.condition());
		assertEquals(Map.of("condition", "newArgumentCondition", "x", "newX", "d", "newD"), rebound.arguments());
	}

	@Test void changingEitherBranchKeepsSameNamedArgumentConnectionsInDraft() {
		var workspace = new ProgramWorkspace();
		var root = workspace.root();
		var first = workspace.createModule("first");
		var second = workspace.createModule("second");
		var third = workspace.createModule("third");
		String id = root.add(new ComputationGraph.Branch("condition", first, second, Map.of("value", "value")), 0, 0);
		String condition = root.add(new ComputationGraph.Constant(new DataValue.BooleanValue(true)), 0, 0);
		String value = root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 0, 0);
		root.connect(id, "condition", condition); root.connect(id, "argument:value", value);
		root.configure(id, new ComputationGraph.Branch("condition", third, second, Map.of("value", "value")));
		assertEquals(Map.of("condition", condition, "argument:value", value), root.node(id).connections());
	}

	@Test void realCreateCallbacksBuildSelectOpenBothSidesAndReturnAtCompactSize() throws Exception {
		var parent=ComputerProgramScreenTest.screen(); var workspace=(ProgramWorkspace)get(parent,"workspace");
		var first=module(workspace,"divide"); var second=module(workspace,"zero");
		ComputerProgramScreenTest.addNode(parent, "branch");
		
		assertEquals(17,get(parent,"kind"));
		var opened=new net.minecraft.client.gui.screens.Screen[1];
		set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(screen->opened[0]=screen));
		assertTrue(keys(parent).contains("create_biotech.biocomputing.branch.title"),keys(parent).toString());
		click(parent,"create_biotech.biocomputing.branch.title");
		var chooser=assertInstanceOf(ComputerBranchScreen.class,opened[0]); initialize(chooser,320,240);
		click(chooser,"create_biotech.biocomputing.branch.when_false");
		click(chooser,"create_biotech.biocomputing.branch.apply");
		var branch=assertInstanceOf(ComputationGraph.Branch.class,workspace.root().nodes().getLast().definition());
		assertEquals(first,branch.whenTrue()); assertEquals(second,branch.whenFalse());
		assertEquals(Map.of("x","x","d","d"),branch.arguments());
		assertEquals(java.util.Set.of("condition","argument:x","argument:d"),workspace.root().nodes().getLast().ports());
		assertFalse((boolean)get(parent,"requested"));
		var root=workspace.root();String branchId=root.nodes().getLast().id();
		String condition=root.add(new ComputationGraph.Constant(new DataValue.BooleanValue(true)),0,0);
		String x=root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(24)),0,0);
		String d=root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(3)),0,0);
		root.connect(branchId,"condition",condition);root.connect(branchId,"argument:x",x);root.connect(branchId,"argument:d",d);root.output("result",branchId);
		var archiveLimits=new ArchiveLimits(100_000,1_000_000,10_000_000);
		var wire=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),archiveLimits),archiveLimits).archive();
		var hostLimits=new ComputerHostLimits(new ResourceLimits(1_000,100_000,1),archiveLimits);var host=new ComputerBlockHost(()->{});
		host.install(wire,ComputerRuntime.MemoryPolicy.RESET,hostLimits,true);host.begin(new DataValue.RecordValue(Map.of()),hostLimits,true);
		for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<100);host.tick(hostLimits,true);}
		assertEquals(new DataValue.IntegerValue(8),((DataValue.RecordValue)host.snapshot().orElseThrow().machine().output().orElseThrow().field("result")).field("value"));

		// Reopen the node, then exercise each real subgraph-navigation callback.
		invoke(parent,"openNode",branchId);
		click(parent,"create_biotech.biocomputing.branch.title"); chooser=assertInstanceOf(ComputerBranchScreen.class,opened[0]); initialize(chooser,320,240);
		click(chooser,"create_biotech.biocomputing.branch.open_true"); assertSame(workspace.graph(first),get(parent,"draft"));
		click(parent,"create_biotech.biocomputing.editor.modules");click(parent,"create_biotech.biocomputing.editor.root_graph");
		invoke(parent,"openNode",branchId);
		click(parent,"create_biotech.biocomputing.branch.title"); chooser=assertInstanceOf(ComputerBranchScreen.class,opened[0]); initialize(chooser,426,240);
		click(chooser,"create_biotech.biocomputing.branch.open_false"); assertSame(workspace.graph(second),get(parent,"draft"));
	}

	@Test void mismatchedModuleInputsRemainARepairableDraftAfterRealApplyClick() throws Exception {
		var parent=ComputerProgramScreenTest.screen();var workspace=(ProgramWorkspace)get(parent,"workspace");var root=workspace.root();
		var first=workspace.createModule("first");workspace.graph(first).putInput("common",DataType.Scalar.INTEGER);workspace.graph(first).putInput("old",DataType.Scalar.INTEGER);
		String firstOut=workspace.graph(first).add(new ComputationGraph.Constant(new DataValue.IntegerValue(1)),0,0);workspace.graph(first).output("value",firstOut);
		var second=workspace.createModule("second");workspace.graph(second).putInput("common",DataType.Scalar.INTEGER);workspace.graph(second).putInput("new",DataType.Scalar.INTEGER);
		String secondOut=workspace.graph(second).add(new ComputationGraph.Constant(new DataValue.IntegerValue(2)),0,0);workspace.graph(second).output("value",secondOut);
		String branchId=root.add(new ComputationGraph.Branch("condition",first,first,Map.of("common","common","old","old")),0,0);
		String condition=root.add(new ComputationGraph.Constant(new DataValue.BooleanValue(true)),0,0),common=root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(3)),0,0),old=root.add(new ComputationGraph.Constant(new DataValue.IntegerValue(4)),0,0);
		root.connect(branchId,"condition",condition);root.connect(branchId,"argument:common",common);root.connect(branchId,"argument:old",old);root.output("result",branchId);
		invoke(parent,"openNode",branchId);var opened=new net.minecraft.client.gui.screens.Screen[1];set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(screen->opened[0]=screen));
		click(parent,"create_biotech.biocomputing.branch.title");var chooser=initialize(assertInstanceOf(ComputerBranchScreen.class,opened[0]),320,240);
		click(chooser,"create_biotech.biocomputing.branch.when_false");
		assertDoesNotThrow(()->click(chooser,"create_biotech.biocomputing.branch.apply"));
		var branch=assertInstanceOf(ComputationGraph.Branch.class,root.node(branchId).definition());
		assertEquals(second,branch.whenFalse());assertEquals(java.util.Set.of("common","old","new"),branch.arguments().keySet());
		assertEquals(Map.of("condition",condition,"argument:common",common,"argument:old",old),root.node(branchId).connections());
		assertFalse(root.node(branchId).connections().containsKey("argument:new"));
		assertThrows(IllegalStateException.class,workspace::snapshot,"unmatched port remains visible for repair");
	}

	private static ModuleDefinition.Key module(ProgramWorkspace workspace,String name) {
		var key=workspace.createModule(name);var graph=workspace.graph(key);
		graph.putInput("x",DataType.Scalar.INTEGER);graph.putInput("d",DataType.Scalar.INTEGER);
		String value;
		if(name.equals("divide")){
			String x=graph.add(new ComputationGraph.Input("x"),0,0),d=graph.add(new ComputationGraph.Input("d"),0,40);
			value=graph.add(new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER,Map.of("left","left","right","right")),80,0);
			graph.connect(value,"left",x);graph.connect(value,"right",d);
		}else value=graph.add(new ComputationGraph.Constant(new DataValue.IntegerValue(0)),0,0);
		graph.output("value",value);return key;
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
