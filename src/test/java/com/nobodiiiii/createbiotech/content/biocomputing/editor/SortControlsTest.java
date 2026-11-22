package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class SortControlsTest {
    private Object oldClient;
    private Language oldLanguage;
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
            .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();field(net.minecraft.server.Bootstrap.class,"isBootstrapped").setBoolean(null,true);
    }
    @BeforeEach void client() throws Exception {
        oldClient=field(Minecraft.class,"instance").get(null);oldLanguage=Language.getInstance();
        var client=allocate(Minecraft.class);set(client,"font",new ComputerProgramScreenTest.Metrics());field(Minecraft.class,"instance").set(null,client);
    }
    @AfterEach void restore() throws Exception {Language.inject(oldLanguage);field(Minecraft.class,"instance").set(null,oldClient);}

    @Test void publicCreateConfigureExpandReopenAndWireRunBothDirections() throws Exception {
        var parent=ComputerProgramScreenTest.screen();var workspace=(ProgramWorkspace)get(parent,"workspace");
        var key=module(workspace,"rank_key");var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(screen->opened[0]=screen));
        ComputerProgramScreenTest.addNode(parent, "sort");assertNull(workspace.root().nodes().getLast().definition());
        
        assertEquals(20,get(parent,"kind"),"SORT is appended without shifting existing palette indices");
        click(parent,"sort.title");var chooser=initialize(opened[0],320,240);assertLayout(chooser,320,240);
        assertEquals("ComputerSortScreen",chooser.getClass().getSimpleName());assertTrue(button(chooser,"sort.apply").active);
        click(chooser,"sort.item");assertEquals("offset",get(chooser,"item"));click(chooser,"sort.item");
        click(chooser,"sort.result");assertEquals("raw",get(chooser,"result"));click(chooser,"sort.result");
        click(chooser,"sort.apply");var root=workspace.root();var node=root.nodes().getLast();String id=node.id();
        var sort=assertInstanceOf(ComputationGraph.Sort.class,node.definition());
        assertEquals(key,sort.module());assertEquals("item",sort.itemPort());assertEquals("key",sort.resultPort());assertFalse(sort.descending());
        assertEquals(Set.of("source","capture:offset"),node.ports());
        var source=items();String sourceId=root.add(new ComputationGraph.Constant(source),0,0);
        String offset=root.add(new ComputationGraph.Constant(integer(10)),0,40);
        root.connect(id,"source",sourceId);root.connect(id,"capture:offset",offset);root.output("sorted",id);
        invoke(parent,"openNode",id);click(parent,"sort.title");chooser=initialize(opened[0],426,300);assertLayout(chooser,426,300);
        click(chooser,"sort.open");assertSame(workspace.graph(key),get(parent,"draft"));
        click(parent,"editor.modules");click(parent,"editor.root_graph");assertSame(root,get(parent,"draft"));
        invoke(parent,"openNode",id);click(parent,"sort.title");chooser=initialize(opened[0],320,240);
        click(chooser,"sort.direction");click(chooser,"sort.apply");
        assertTrue(assertInstanceOf(ComputationGraph.Sort.class,root.node(id).definition()).descending());
        assertEquals(Map.of("source",sourceId,"capture:offset",offset),root.node(id).connections());
        assertEquals(new DataValue.ListValue(List.of(source.values().get(0),source.values().get(2),source.values().get(1))),run(workspace));
        invoke(parent,"openNode",id);click(parent,"sort.title");chooser=initialize(opened[0],320,240);
        assertTrue((boolean)get(chooser,"descending"));click(chooser,"sort.direction");click(chooser,"sort.apply");
        assertEquals(new DataValue.ListValue(List.of(source.values().get(1),source.values().get(0),source.values().get(2))),run(workspace));
        assertEquals(items(),source);
    }

    @Test void missingAndIncompleteModulesDisableApplyButBackEscapeAndRepairRemainAvailable() throws Exception {
        var parent=ComputerProgramScreenTest.screen();var workspace=(ProgramWorkspace)get(parent,"workspace");
        var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(screen->opened[0]=screen));
        ComputerProgramScreenTest.addNode(parent, "sort");
        var retained=workspace.root().nodes().getLast().definition();
        click(parent,"sort.title");var chooser=initialize(opened[0],320,240);
        assertFalse(button(chooser,"sort.apply").active);assertFalse(button(chooser,"sort.open").active);
        click(chooser,"sort.back");assertSame(parent,opened[0]);
        var key=workspace.createModule("unfinished");workspace.graph(key).putInput("item",DataType.Scalar.ANY);
        click(parent,"sort.title");chooser=initialize(opened[0],320,240);
        assertFalse(button(chooser,"sort.apply").active);assertTrue(button(chooser,"sort.open").active);
        assertTrue(chooser.keyPressed(256,0,0));assertSame(parent,opened[0]);
        assertEquals(retained,workspace.root().nodes().getLast().definition());
        click(parent,"sort.title");chooser=initialize(opened[0],320,240);click(chooser,"sort.open");
        assertSame(workspace.graph(key),get(parent,"draft"));
    }

    @Test void changingModulePreservesSameNamedConnectionsAndCancelDoesNotApplyDirection() throws Exception {
        var parent=ComputerProgramScreenTest.screen();var workspace=(ProgramWorkspace)get(parent,"workspace");
        var first=module(workspace,"first");var second=module(workspace,"second");
        workspace.graph(first).putInput("removed",DataType.Scalar.INTEGER);workspace.graph(second).putInput("new",DataType.Scalar.INTEGER);
        var root=workspace.root();String value=root.add(new ComputationGraph.Constant(integer(5)),0,0);
        String source=root.add(new ComputationGraph.Constant(items()),0,40);
        String id=root.add(new ComputationGraph.Sort("source",first,"item",Map.of("offset","offset","removed","removed"),"key",true),80,0);
        root.connect(id,"source",source);root.connect(id,"capture:offset",value);root.connect(id,"capture:removed",value);
        var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(screen->opened[0]=screen));
        var before=root.node(id);invoke(parent,"openNode",id);click(parent,"sort.title");var chooser=initialize(opened[0],320,240);
        click(chooser,"sort.direction");click(chooser,"sort.back");assertEquals(before,root.node(id));
        click(parent,"sort.title");chooser=initialize(opened[0],320,240);
        click(chooser,"sort.module");click(chooser,"sort.apply");
        var after=assertInstanceOf(ComputationGraph.Sort.class,root.node(id).definition());
        assertEquals(second,after.module());assertTrue(after.descending());assertEquals("item",after.itemPort());
        assertEquals(Map.of("source",source,"capture:offset",value),root.node(id).connections());
        assertTrue(root.node(id).ports().contains("capture:new"));assertFalse(root.node(id).connections().containsKey("capture:new"));
    }

    @Test void importedExactRevisionAndArbitraryPortNamesSurviveReconfiguration() throws Exception {
        var parent=ComputerProgramScreenTest.screen();var workspace=(ProgramWorkspace)get(parent,"workspace");
        var key=new ModuleDefinition.Key("shared",7);
        var module=new ComputationGraph(Map.of("record",DataType.Scalar.ANY,"source",DataType.Scalar.INTEGER),
            Map.of("value",new ComputationGraph.Input("source")),Map.of("rank","value"));
        var limits=new ArchiveLimits(10000,100000,1000000);
        workspace.importSubgraph(new SubgraphDocument(key,Map.of(key,new ModuleDefinition(key,module)),Map.of()),limits);
        var root=workspace.root();String id=root.add(new ComputationGraph.Sort("items",key,"record",Map.of("source","capture"),"rank",true),0,0);
        var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(screen->opened[0]=screen));
        invoke(parent,"openNode",id);click(parent,"sort.title");var chooser=initialize(opened[0],426,300);
        assertEquals(key,get(chooser,"module"));assertEquals("record",get(chooser,"item"));assertEquals("rank",get(chooser,"result"));
        var label=(TranslatableContents)button(chooser,"sort.module").getMessage().getContents();
        assertTrue(Arrays.asList(label.getArgs()).contains(7L),"Player sees the pinned revision, not just a potentially duplicate id");
        click(chooser,"sort.apply");
        var node=assertInstanceOf(ComputationGraph.Sort.class,root.node(id).definition());
        assertEquals(key,node.module());assertTrue(node.descending());assertEquals(Set.of("source","capture:source"),root.node(id).ports());
    }

    private static ModuleDefinition.Key module(ProgramWorkspace workspace,String name) {
        var key=workspace.createModule(name);var graph=workspace.graph(key);graph.putInput("item",DataType.Scalar.ANY);graph.putInput("offset",DataType.Scalar.INTEGER);
        String item=graph.add(new ComputationGraph.Input("item"),0,0),offset=graph.add(new ComputationGraph.Input("offset"),0,40);
        String rank=graph.add(new ComputationGraph.Field(item,"rank"),80,0);
        String result=graph.add(new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left",rank,"right",offset)),160,0);
        graph.connect(rank,"source",item);graph.connect(result,"left",rank);graph.connect(result,"right",offset);
        graph.output("key",result);graph.output("raw",rank);return key;
    }
    private static DataValue.ListValue items() {
        return new DataValue.ListValue(List.of(row("a",2),row("b",1),row("c",2)));
    }
    private static DataValue.RecordValue row(String name,long rank){return new DataValue.RecordValue(Map.of("name",new DataValue.TextValue(name),"rank",integer(rank)));}
    private static DataValue.IntegerValue integer(long value){return new DataValue.IntegerValue(value);}
    private static DataValue run(ProgramWorkspace workspace) {
        var limits=new ArchiveLimits(10000,100000,1000000);var hostLimits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000,100000,1),limits));
        var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
        var host=new ComputerBlockHost(()->{});host.install(document.archive(),ComputerRuntime.MemoryPolicy.RESET,hostLimits,true);
        host.begin(new DataValue.RecordValue(Map.of()),hostLimits,true);
        for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<300);host.step(hostLimits,true);}
        assertEquals(ComputationMachine.Status.SUCCEEDED,host.snapshot().orElseThrow().machine().status());
        return host.snapshot().orElseThrow().machine().output().orElseThrow().field("sorted");
    }
    private static void assertLayout(Screen screen,int width,int height) {
        var buttons=screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).toList();
        for(var b:buttons){assertTrue(b.getX()>=0&&b.getY()>=0&&b.getX()+b.getWidth()<=width&&b.getY()+b.getHeight()<=height);
            for(var c:buttons)if(b!=c)assertFalse(b.getX()<c.getX()+c.getWidth()&&c.getX()<b.getX()+b.getWidth()&&b.getY()<c.getY()+c.getHeight()&&c.getY()<b.getY()+b.getHeight());}
    }
    private static CreateTextButton button(Screen screen,String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b->b.getMessage().getContents() instanceof TranslatableContents t&&t.getKey().equals("create_biotech.biocomputing."+key)).findFirst().orElseThrow();
    }
    private static void click(Screen screen,String key){var button=button(screen,key);assertTrue(button.active);button.onClick(0,0);}
    private static Screen initialize(Screen screen,int width,int height)throws Exception {
        assertNotNull(screen);set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);set(screen,"width",width);set(screen,"height",height);
        set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());
        var init=screen.getClass().getDeclaredMethod("init");init.setAccessible(true);init.invoke(screen);return screen;
    }
    private static void invoke(Object target,String name,String arg)throws Exception {var method=target.getClass().getDeclaredMethod(name,String.class);method.setAccessible(true);method.invoke(target,arg);}
    private static Object get(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}
    private static Field field(Class<?> type,String name)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static <T>T allocate(Class<T> type)throws Exception{return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null)).allocateInstance(type));}
}
