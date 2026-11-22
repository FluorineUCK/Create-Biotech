package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.lang.reflect.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;
class TextControlsTest {
    @Test void compactMatchChooserReallyPagesBackAndForwardAndSelectsAfterResize() throws Exception {
        var workspace=new ProgramWorkspace();var method=ScalarControlsTest.class.getDeclaredMethod("parent",ProgramWorkspace.class,int.class);method.setAccessible(true);
        var parent=(ComputerProgramScreen)method.invoke(null,workspace,320);set(parent,"textOperation",TextOperation.LENGTH);
        ComputerProgramScreenTest.addNode(parent, "text_operation");String id=workspace.root().nodes().getLast().id();
        assertNull(workspace.root().node(id).definition());
        
        var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(s->opened[0]=s));
        button(parent,"text_operation.choose").onClick(0,0);var chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
        button(chooser,"text_operation.category.match").onClick(0,0);
        assertTrue(find(chooser,"text_operation.name.equal").isPresent());assertTrue(find(chooser,"text_operation.name.ends_with").isEmpty());
        assertFalse(button(chooser,"text_operation.previous").active);assertTrue(button(chooser,"text_operation.next").active);
        button(chooser,"text_operation.next").onClick(0,0);
        assertEquals(1,get(chooser,"page"));assertTrue(find(chooser,"text_operation.name.ends_with").isPresent());assertLayout(chooser);
        assertTrue(button(chooser,"text_operation.previous").active);assertFalse(button(chooser,"text_operation.next").active);
        button(chooser,"text_operation.previous").onClick(0,0);
        assertEquals(0,get(chooser,"page"));assertTrue(find(chooser,"text_operation.name.equal").isPresent());
        button(chooser,"text_operation.next").onClick(0,0);
        button(chooser,"text_operation.name.ends_with").onClick(0,0);
        assertEquals(TextOperation.ENDS_WITH,((ComputationGraph.Text)workspace.root().node(id).definition()).operation());
        button(parent,"text_operation.choose").onClick(0,0);chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
        button(chooser,"text_operation.next").onClick(0,0);assertEquals(1,get(chooser,"page"));
        set(chooser,"width",426);set(chooser,"height",300);chooser.init();
        assertEquals(TextOperation.Category.MATCH,get(chooser,"category"));assertEquals(TextOperation.ENDS_WITH,get(chooser,"selected"));
        assertEquals(0,get(chooser,"page"));assertLayout(chooser);
        button(chooser,"text_operation.name.starts_with").onClick(0,0);
        assertEquals(TextOperation.STARTS_WITH,((ComputationGraph.Text)workspace.root().node(id).definition()).operation());
        assertFalse((boolean)get(parent,"requested"));
    }
    @Test void chooserCancelsWithoutChangingDefinitionAndRetainsOnlyCommonConnections() throws Exception {
        var workspace=new ProgramWorkspace();var method=ScalarControlsTest.class.getDeclaredMethod("parent",ProgramWorkspace.class,int.class);method.setAccessible(true);
        var parent=(ComputerProgramScreen)method.invoke(null,workspace,320);set(parent,"textOperation",TextOperation.LENGTH);
        ComputerProgramScreenTest.addNode(parent, "text_operation");String id=workspace.root().nodes().getLast().id();
        
        var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(s->opened[0]=s));
        var before=workspace.root().node(id);
        button(parent,"text_operation.choose").onClick(0,0);var chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
        button(chooser,"text_operation.back").onClick(0,0);assertEquals(before,workspace.root().node(id));
        button(parent,"text_operation.choose").onClick(0,0);chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
        chooser.keyPressed(256,0,0);assertEquals(before,workspace.root().node(id));
        button(parent,"text_operation.choose").onClick(0,0);chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
        select(chooser,TextOperation.CONTAINS);
        var source=workspace.root().add(new ComputationGraph.Constant(TextProgramFixtures.text("abc")),0,0);
        workspace.root().connect(id,"text",source);workspace.root().connect(id,"needle",source);
        button(parent,"text_operation.choose").onClick(0,0);chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);select(chooser,TextOperation.REPLACE);
        assertEquals(Map.of("text",source,"needle",source),workspace.root().node(id).connections());
        assertTrue(workspace.root().node(id).ports().contains("replacement"));assertFalse(workspace.root().node(id).connections().containsKey("replacement"));
        button(parent,"text_operation.choose").onClick(0,0);chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);select(chooser,TextOperation.LENGTH);
        assertEquals(Map.of("text",source),workspace.root().node(id).connections());assertFalse((boolean)get(parent,"requested"));
    }
    private static void select(ComputerTextOperationScreen screen,TextOperation op){
        button(screen,"text_operation.category."+op.category().name().toLowerCase(Locale.ROOT)).onClick(0,0);findOperation(screen,op).onClick(0,0);
    }
    private final ScalarControlsTest bootstrap=new ScalarControlsTest();
    @BeforeAll static void bootstrap() throws Exception { ScalarControlsTest.bootstrap(); }
    @BeforeEach void client() throws Exception { bootstrap.client(); }
    @AfterEach void restore() throws Exception { bootstrap.restore(); }
    @Test void newUnconfiguredPlayerNodeReachesEveryOperationAndWireHost() throws Exception {
        for(var example:TextProgramFixtures.examples()){
            var workspace=new ProgramWorkspace();
            var method=ScalarControlsTest.class.getDeclaredMethod("parent",ProgramWorkspace.class,int.class);method.setAccessible(true);
            var parent=(ComputerProgramScreen)method.invoke(null,workspace,320);
            set(parent,"textOperation",TextOperation.LENGTH);
            ComputerProgramScreenTest.addNode(parent, "text_operation");
            String id=workspace.root().nodes().getLast().id();
            assertNull(workspace.root().node(id).definition());
            
            var opened=new Screen[1];set(parent,"navigator",(java.util.function.Consumer<Screen>)(s->opened[0]=s));
            button(parent,"text_operation.choose").onClick(0,0);
            var chooser=(ComputerTextOperationScreen)opened[0];initialize(chooser,320,240);
            assertLayout(chooser);
            button(chooser,"text_operation.category."+example.operation().category().name().toLowerCase(Locale.ROOT)).onClick(0,0);
            set(chooser,"width",426);set(chooser,"height",300);chooser.init();
            assertLayout(chooser);
            var operation=findOperation(chooser,example.operation());operation.onClick(0,0);
            assertInstanceOf(ComputationGraph.Text.class,workspace.root().node(id).definition());
            example.arguments().forEach((name,value)->{
                var source=workspace.root().add(new ComputationGraph.Constant(value),0,0);
                workspace.root().connect(id,name,source);
            });
            workspace.root().output("result",id);
            var limits=new ArchiveLimits(10000,1000000,10000000);
            var received=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
            var hostLimits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(10000,1000000,1),limits));
            var host=new ComputerBlockHost(()->{});host.install(received.archive(),ComputerRuntime.MemoryPolicy.RESET,hostLimits,true);
            host.begin(new DataValue.RecordValue(Map.of()),hostLimits,true);
            for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++){
                assertTrue(i<2000);host.tick(hostLimits,true);
            }
            assertEquals(example.expected(),host.snapshot().orElseThrow().machine().output().orElseThrow().field("result"));
            assertFalse((boolean)get(parent,"requested"));
        }
    }
    private static CreateTextButton findOperation(ComputerTextOperationScreen screen,TextOperation operation){
        String key="text_operation.name."+operation.name().toLowerCase(Locale.ROOT);
        for(int i=0;i<20;i++){var found=find(screen,key);if(found.isPresent())return found.orElseThrow();var next=button(screen,"text_operation.next");assertTrue(next.active);next.onClick(0,0);}
        throw new AssertionError(operation);
    }
    private static void assertLayout(Screen screen)throws Exception{
        var widgets=screen.children().stream().filter(net.minecraft.client.gui.components.AbstractWidget.class::isInstance).map(net.minecraft.client.gui.components.AbstractWidget.class::cast).toList();
        int left=(int)get(screen,"leftPos"),top=(int)get(screen,"topPos"),width=(int)get(screen,"imageWidth"),height=(int)get(screen,"imageHeight");
        for(var w:widgets){assertTrue(w.getX()>=left&&w.getY()>=top);assertTrue(w.getX()+w.getWidth()<=left+width);assertTrue(w.getY()+w.getHeight()<=top+height);}
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY());}
    }
    private static Optional<CreateTextButton> find(Screen s,String suffix){return s.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b->b.getMessage().getContents() instanceof TranslatableContents t&&t.getKey().equals("create_biotech.biocomputing."+suffix)).findFirst();}
    private static CreateTextButton button(Screen s,String suffix){return find(s,suffix).orElseThrow(()->new AssertionError(suffix));}
    private static void initialize(ComputerTextOperationScreen s,int w,int h)throws Exception{
        set(s,"children",new ArrayList<>());set(s,"renderables",new ArrayList<>());set(s,"narratables",new ArrayList<>());
        set(s,"width",w);set(s,"height",h);set(s,"minecraft",Minecraft.getInstance());set(s,"font",Minecraft.getInstance().font);s.init();
    }
    private static Field field(Class<?> type,String name)throws Exception{for(var c=type;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static void set(Object o,String name,Object value)throws Exception{field(o.getClass(),name).set(o,value);}
    private static Object get(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
}
