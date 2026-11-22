package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.*;

/** Player-facing scalar coverage through the real Create button callbacks. */
class ScalarControlsTest {
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
        oldClient=field(Minecraft.class,"instance").get(null);
        oldLanguage=Language.getInstance();
        var client=allocate(Minecraft.class);set(client,"font",new ComputerProgramScreenTest.Metrics());
        field(Minecraft.class,"instance").set(null,client);
    }

    @AfterEach void restore() throws Exception { Language.inject(oldLanguage);field(Minecraft.class,"instance").set(null,oldClient); }

    @Test void chooserOffersEveryOperationAcrossDerivedCategoriesAndPages() throws Exception {
        var selected=EnumSet.noneOf(ScalarOperation.class);
        var screen=chooser(320,ScalarOperation.ADD_INTEGER,selected::add,()->{});
        for(var category:ScalarOperation.Category.values()) {
            clickCategory(screen,category);
            for(var operation:ScalarOperation.values()) if(operation.category()==category) {
                findOperation(screen,operation).onClick(0,0);
                assertTrue(selected.remove(operation),"selection callback for "+operation);
                screen=chooser(320,operation,selected::add,()->{});
                clickCategory(screen,category);
            }
        }
        assertTrue(selected.isEmpty());
    }

    @Test void chooserStartsAtSelectedCategoryFitsBothSizesAndPropagatesCallbacks() throws Exception {
        for(int width:List.of(320,426)) {
            var closed=new int[1];var chosen=new ScalarOperation[1];
            var screen=chooser(width,ScalarOperation.SELECT,operation->chosen[0]=operation,()->closed[0]++);
            assertEquals(ScalarOperation.Category.VALUE,get(screen,"category"));
            assertLayout(screen);
            findOperation(screen,ScalarOperation.IS_NULL).onClick(0,0);
            assertEquals(ScalarOperation.IS_NULL,chosen[0]);assertEquals(1,closed[0]);
        }
        var failure=new IllegalStateException("selection failed");
        var screen=chooser(320,ScalarOperation.ADD_INTEGER,ignored->{throw failure;},()->fail("must not close"));
        assertSame(failure,assertThrows(IllegalStateException.class,()->findOperation(screen,ScalarOperation.ADD_INTEGER).onClick(0,0)));
    }

    @Test void changingScalarOperationKeepsCommonPortsDropsObsoleteOnesAndNeverSubmits() throws Exception {
        var parent=ComputerProgramScreenTest.screen();var draft=(ProgramDraft)get(parent,"draft");
        String left=draft.add(ProgramDraftTest.number(2),0,0),right=draft.add(ProgramDraftTest.number(8),0,40);
        String scalar=draft.add(new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","left","right","right")),80,0);
        draft.connect(scalar,"left",left);draft.connect(scalar,"right",right);
        invoke(parent,"openNode",scalar);set(parent,"scalarOperation",ScalarOperation.ADD_INTEGER);
        var english=loadLanguage("en_us");Language.inject(new TestLanguage(english));parent.init();
        assertEquals(english.get("create_biotech.biocomputing.operation.choose").formatted(
            english.get("create_biotech.biocomputing.operation.name.add_integer")),
            button(parent,"create_biotech.biocomputing.operation.choose").getMessage().getString());
        var opened=new net.minecraft.client.gui.screens.Screen[1];set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(s->opened[0]=s));
        button(parent,"create_biotech.biocomputing.operation.choose").onClick(0,0);
        var chooser=initialize((ComputerOperationScreen)opened[0],320);findOperation(chooser,ScalarOperation.SUBTRACT_INTEGER).onClick(0,0);
        assertEquals(Map.of("left",left,"right",right),draft.node(scalar).connections());
        button(parent,"create_biotech.biocomputing.operation.choose").onClick(0,0);
        chooser=initialize((ComputerOperationScreen)opened[0],320);findOperation(chooser,ScalarOperation.NOT_BOOLEAN).onClick(0,0);
        assertEquals(new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN,Map.of("value","value")),draft.node(scalar).definition());
        assertTrue(draft.node(scalar).connections().isEmpty());
        assertFalse((boolean)get(parent,"requested"));
        assertThrows(IllegalStateException.class,draft::snapshot,"partial node remains editable rather than being discarded");
    }

    @Test void importedScalarRetainsIdentifierOperationAndBindingsAcrossOpenAndResize() throws Exception {
        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var imported=ProgramDocumentWire.decode(ProgramDocumentWire.encode(ProgramDocument.of(ScalarProgramFixtures.intervalArchive()),limits),limits);
        var workspace=new ProgramWorkspace(imported);var parent=parent(workspace,426);var draft=workspace.root();
        String id="selected",condition="accepted",yes="yes",no="no";
        invoke(parent,"openNode",id);assertEquals(ScalarOperation.SELECT,get(parent,"scalarOperation"));
        set(parent,"width",320);set(parent,"height",240);parent.init();
        assertEquals(id,get(parent,"editing"));assertEquals(ScalarOperation.SELECT,get(parent,"scalarOperation"));
        assertEquals(Map.of("condition",condition,"when_true",yes,"when_false",no),draft.node(id).connections());
        set(parent,"width",426);parent.init();
        assertEquals(id,get(parent,"editing"));assertEquals(ScalarOperation.SELECT,get(parent,"scalarOperation"));
    }

    @Test void localizedSemanticHintsWrapIntoReservedSpaceAboveFooterAtCompactWidth() throws Exception {
        var font=new WideMetrics();
        for(String locale:List.of("en_us","zh_cn")) {
            var json=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang/"+locale+".json"))).getAsJsonObject();
            var translations=new HashMap<String,String>();json.entrySet().forEach(entry->translations.put(entry.getKey(),entry.getValue().getAsString()));Language.inject(new TestLanguage(translations));
            var hinted=new ArrayList<>(NumericProgramFixtures.OPERATIONS);
            hinted.addAll(List.of(ScalarOperation.SELECT,ScalarOperation.DIVIDE_INTEGER,ScalarOperation.INTEGER_TO_DECIMAL,ScalarOperation.DECIMAL_TO_INTEGER,
                ScalarOperation.IS_ERROR,ScalarOperation.ERROR_CODE,ScalarOperation.ERROR_MESSAGE));
            for(var operation:hinted) {
                var screen=chooser(320,operation,ignored->{},()->{});set(screen,"font",font);screen.init();
                var key=switch(operation){case SELECT->"eager";case INTEGER_TO_DECIMAL->"integer_to_decimal";case DECIMAL_TO_INTEGER->"decimal_to_integer";
                    case SQRT_DECIMAL->"sqrt";case POWER_DECIMAL->"power";case REMAINDER_DECIMAL->"remainder";
                    case ROUND_DECIMAL_TO_INTEGER->"round";case CEIL_DECIMAL_TO_INTEGER->"ceil";case FLOOR_DECIMAL_TO_INTEGER->"floor";
                    case IS_ERROR->"is_error";case ERROR_CODE,ERROR_MESSAGE->"error_extract";default->"numeric";};
                String value=json.get("create_biotech.biocomputing.operation.hint."+key).getAsString();
                assertEquals(value,screen.hint().getString(),"The actual chooser exposes this semantic hint");
                int footerY=operationButton(screen,"create_biotech.biocomputing.operation.back").getY();
                int operationBottom=screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                    .filter(button->button.getMessage().getContents() instanceof TranslatableContents text&&text.getKey().startsWith("create_biotech.biocomputing.operation.name."))
                    .mapToInt(button->button.getY()+button.getHeight()).max().orElseThrow();
                int lineHeight=9,lines=wrappedLines(value,(int)get(screen,"imageWidth")-16);
                assertTrue(operationBottom+lines*lineHeight<=footerY,"full "+locale+" "+key+" hint must fit without truncation");
            }
        }
    }

    @Test void playerOperationCallbacksBuildIntervalApplicationThatSurvivesWireAndRunsOnHost() throws Exception {
        var archive=ScalarProgramFixtures.intervalArchive();var workspace=new ProgramWorkspace();var draft=workspace.root();
        archive.program().graph().inputs().forEach(draft::putInput);
        archive.program().cells().forEach((name,cell)->draft.putCell(name,cell.type(),cell.initial()));
        archive.program().writes().forEach(write->draft.setWrite(write.cell(),write.output()));
        var parent=parent(workspace,426);
        var built=new LinkedHashMap<String,String>();
        for(var entry:archive.program().graph().nodes().entrySet()) {
            String id;
            if(entry.getValue() instanceof ComputationGraph.Scalar scalar) {
                ComputerProgramScreenTest.addNode(parent, "scalar");
                
                id=draft.nodes().getLast().id();
                var opened=new net.minecraft.client.gui.screens.Screen[1];set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(s->opened[0]=s));
                button(parent,"create_biotech.biocomputing.operation.choose").onClick(0,0);
                findOperation(initialize((ComputerOperationScreen)opened[0],426),scalar.operation()).onClick(0,0);
                assertEquals(scalar.operation(),((ComputationGraph.Scalar)draft.node(id).definition()).operation());
                button(parent,"create_biotech.biocomputing.editor.back").onClick(0,0);
            } else id=draft.add(entry.getValue(),0,0);
            built.put(entry.getKey(),id);
        }
        for(var entry:archive.program().graph().nodes().entrySet()) {
            String target=built.get(entry.getKey());
            NodePorts.bindings(entry.getValue()).forEach((port,reference)->draft.connect(target,port,built.get(reference)));
        }
        draft.output("accepted",built.get("accepted"));draft.output("selected",built.get("selected"));
        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var received=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
        var hostLimits=new ComputerHostLimits(new ResourceLimits(10_000,1_000_000,1_000),limits);
        var host=new ComputerBlockHost(()->{});host.install(received.archive(),ComputerRuntime.MemoryPolicy.RESET,hostLimits,true);
        assertInterval(host,hostLimits,5,true,"in_range");assertInterval(host,hostLimits,2,true,"in_range");assertInterval(host,hostLimits,8,false,"out_of_range");
    }

    @Test void newNumericOperationsUsePlayerSelectionBindingsDocumentAndHostWithoutSpecialExecution() throws Exception {
        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var hostLimits=new ComputerHostLimits(new ResourceLimits(100,100_000,1),limits);
        for(var operation:NumericProgramFixtures.OPERATIONS) {
            var workspace=new ProgramWorkspace();var draft=workspace.root();var parent=parent(workspace,320);
            var bindings=new LinkedHashMap<String,String>();
            operation.inputs().forEach((name,type)->{draft.putInput(name,type);bindings.put(name,draft.add(new ComputationGraph.Input(name),0,0));});
            ComputerProgramScreenTest.addNode(parent,"scalar");String id=draft.nodes().getLast().id();
            var opened=new net.minecraft.client.gui.screens.Screen[1];
            set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(s->opened[0]=s));
            button(parent,"create_biotech.biocomputing.operation.choose").onClick(0,0);
            findOperation(initialize((ComputerOperationScreen)opened[0],320),operation).onClick(0,0);
            bindings.forEach((name,source)->draft.connect(id,name,source));draft.output("result",id);
            draft.putCell("saved",DataType.Scalar.ANY,DataValue.NullValue.INSTANCE);draft.setWrite("saved","result");
            var received=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
            var host=new ComputerBlockHost(()->{});host.install(received.archive(),ComputerRuntime.MemoryPolicy.RESET,hostLimits,true);
            host.begin(NumericProgramFixtures.inputs(operation),hostLimits,true);
            for(int i=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;i++) {
                assertTrue(i<20);host.tick(hostLimits,true);
            }
            var state=host.snapshot().orElseThrow().machine();
            assertEquals(ComputationMachine.Status.SUCCEEDED,state.status(),operation.name());
            assertEquals(NumericProgramFixtures.expected(operation),state.memory().field("saved"));
        }
    }

    private static void assertInterval(ComputerBlockHost host,ComputerHostLimits limits,long value,boolean accepted,String state){
        host.begin(ScalarProgramFixtures.intervalInputs(value,2,8),limits,true);
        for(int guard=0;host.snapshot().orElseThrow().machine().status()==ComputationMachine.Status.RUNNING;guard++){assertTrue(guard<100);host.tick(limits,true);}
        var snapshot=host.snapshot().orElseThrow();assertEquals(new DataValue.BooleanValue(accepted),snapshot.machine().output().orElseThrow().field("accepted"));
        assertEquals(new DataValue.TextValue(state),((DataValue.RecordValue)snapshot.machine().output().orElseThrow().field("selected")).field("state"));
        assertEquals(new DataValue.BooleanValue(accepted),snapshot.machine().memory().field("accepted"));
        assertEquals(new DataValue.TextValue(state),((DataValue.RecordValue)snapshot.machine().memory().field("selected")).field("state"));
    }

    private static ComputerOperationScreen chooser(int width,ScalarOperation selected,
        java.util.function.Consumer<ScalarOperation> selection,Runnable close)throws Exception{
        var screen=allocate(ComputerOperationScreen.class);set(screen,"menu",allocate(ComputerProgramMenu.class));
        set(screen,"selected",selected);set(screen,"selection",selection);set(screen,"close",close);set(screen,"category",selected.category());
        set(screen,"title",Component.literal("Operations"));set(screen,"playerInventoryTitle",Component.empty());
        set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());
        set(screen,"width",width);set(screen,"height",240);set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);
        screen.init();return screen;
    }
    @Test void errorOperationsUsePublicChooserBindingsAndDocumentWithoutInstallingTheDraft() throws Exception {
        var limits=new ArchiveLimits(10_000,1_000_000,10_000_000);
        var error=new DataValue.ErrorValue("unavailable","source is unloaded");
        for(var operation:List.of(ScalarOperation.IS_ERROR,ScalarOperation.ERROR_CODE,ScalarOperation.ERROR_MESSAGE)) {
            var workspace=new ProgramWorkspace();var draft=workspace.root();var parent=parent(workspace,320);
            draft.putInput("value",DataType.Scalar.ANY);String source=draft.add(new ComputationGraph.Input("value"),0,0);
            ComputerProgramScreenTest.addNode(parent,"scalar");String id=draft.nodes().getLast().id();
            var opened=new net.minecraft.client.gui.screens.Screen[1];
            set(parent,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(s->opened[0]=s));
            button(parent,"create_biotech.biocomputing.operation.choose").onClick(0,0);
            findOperation(initialize((ComputerOperationScreen)opened[0],320),operation).onClick(0,0);
            assertFalse((boolean)get(parent,"requested"));
            assertEquals(operation,((ComputationGraph.Scalar)draft.node(id).definition()).operation());
            draft.connect(id,"value",source);draft.output("result",id);
            var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits),limits);
            var machine=new ComputationMachine(document.archive().compile(new ResourceLimits(100,100_000,1)));
            machine.begin(new DataValue.RecordValue(Map.of("value",error)));
            for(int guard=0;machine.report().status()==ComputationMachine.Status.RUNNING;guard++){assertTrue(guard<30);machine.step();}
            DataValue expected=operation==ScalarOperation.IS_ERROR?new DataValue.BooleanValue(true)
                :new DataValue.TextValue(operation==ScalarOperation.ERROR_CODE?error.code():error.message());
            assertEquals(expected,machine.report().output().orElseThrow().field("result"));
        }
    }

    private static ComputerOperationScreen initialize(ComputerOperationScreen screen,int width)throws Exception{
        set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());
        set(screen,"width",width);set(screen,"height",240);set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);screen.init();return screen;
    }
    private static ComputerProgramScreen parent(ProgramWorkspace workspace,int width)throws Exception{
        var screen=allocate(ComputerProgramScreen.class);var menu=allocate(ComputerProgramMenu.class);set(menu,"response",Optional.empty());
        set(screen,"menu",menu);set(screen,"workspace",workspace);set(screen,"draft",workspace.root());set(screen,"editorInventory",allocate(net.minecraft.world.entity.player.Inventory.class));
        var viewport=new com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport();set(screen,"viewport",viewport);set(screen,"interaction",new com.nobodiiiii.createbiotech.foundation.gui.CanvasInteraction<String>(viewport));
        set(screen,"scalarOperation",ScalarOperation.ADD_INTEGER);set(screen,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(ignored->{}));
        set(screen,"title",Component.literal("Computer"));set(screen,"playerInventoryTitle",Component.empty());set(screen,"children",new ArrayList<>());set(screen,"renderables",new ArrayList<>());set(screen,"narratables",new ArrayList<>());
        set(screen,"width",width);set(screen,"height",240);set(screen,"minecraft",Minecraft.getInstance());set(screen,"font",Minecraft.getInstance().font);screen.init();return screen;
    }

    private static void clickCategory(ComputerOperationScreen screen,ScalarOperation.Category category){
        operationButton(screen,"create_biotech.biocomputing.operation.category."+category.name().toLowerCase(Locale.ROOT)).onClick(0,0);
    }
    private static CreateTextButton findOperation(ComputerOperationScreen screen,ScalarOperation operation)throws Exception{
        clickCategory(screen,operation.category());
        String key="create_biotech.biocomputing.operation.name."+operation.name().toLowerCase(Locale.ROOT);
        for(int guard=0;guard<20;guard++){
            var found=findButton(screen,key);if(found.isPresent())return found.orElseThrow();
            var next=findButton(screen,"create_biotech.biocomputing.operation.next").orElseThrow();
            if(!next.active)break;next.onClick(0,0);
        }
        throw new NoSuchElementException(operation.name());
    }
    private static CreateTextButton operationButton(ComputerOperationScreen screen,String key){return findButton(screen,key).orElseThrow();}
    private static Optional<CreateTextButton> findButton(ComputerOperationScreen screen,String key){
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button->button.getMessage().getContents() instanceof TranslatableContents text&&text.getKey().equals(key)).findFirst();
    }
    private static CreateTextButton button(ComputerProgramScreen screen,String key){
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button->button.getMessage().getContents() instanceof TranslatableContents text&&text.getKey().equals(key)).findFirst().orElseThrow();
    }
    private static void assertLayout(ComputerOperationScreen screen)throws Exception{
        var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
        int left=(int)get(screen,"leftPos"),top=(int)get(screen,"topPos"),width=(int)get(screen,"imageWidth"),height=(int)get(screen,"imageHeight");
        for(var widget:widgets){assertTrue(widget.getX()>=left&&widget.getY()>=top);assertTrue(widget.getX()+widget.getWidth()<=left+width);assertTrue(widget.getY()+widget.getHeight()<=top+height);}
        for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);assertFalse(a.getX()<b.getX()+b.getWidth()&&a.getX()+a.getWidth()>b.getX()&&a.getY()<b.getY()+b.getHeight()&&a.getY()+a.getHeight()>b.getY(),a.getMessage().getString()+" overlaps "+b.getMessage().getString());}
    }
    private static Object invoke(Object target,String name,Object...args)throws Exception{var method=target.getClass().getDeclaredMethod(name,String.class);method.setAccessible(true);return method.invoke(target,args);}
    private static Object get(Object target,String name)throws Exception{return field(target.getClass(),name).get(target);}
    private static void set(Object target,String name,Object value)throws Exception{field(target.getClass(),name).set(target,value);}
    private static Field field(Class<?> type,String name)throws Exception{for(var current=type;current!=null;current=current.getSuperclass())try{var field=current.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static<T>T allocate(Class<T> type)throws Exception{return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null)).allocateInstance(type));}
    private static Map<String,String> loadLanguage(String locale)throws Exception{var json=com.google.gson.JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/create_biotech/lang/"+locale+".json"))).getAsJsonObject();var result=new HashMap<String,String>();json.entrySet().forEach(entry->result.put(entry.getKey(),entry.getValue().getAsString()));return result;}
    private static int wrappedLines(String value,int width){int columns=Math.max(1,width/6),lines=0,current=0;for(String word:value.split(" ", -1)){int needed=word.length()+(current==0?0:1);if(current>0&&current+needed>columns){lines++;current=word.length();}else current+=needed;while(current>columns){lines++;current-=columns;}}return lines+(current>0?1:0);}
    private static final class TestLanguage extends Language {
        private final Map<String,String> text;TestLanguage(Map<String,String> text){this.text=text;}
        @Override public String getOrDefault(String key,String fallback){return text.getOrDefault(key,fallback);}
        @Override public boolean has(String key){return text.containsKey(key);}
        @Override public boolean isDefaultRightToLeft(){return false;}
        @Override public FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText text){return FormattedCharSequence.forward(text.getString(),net.minecraft.network.chat.Style.EMPTY);}
    }
    private static final class WideMetrics extends net.minecraft.client.gui.Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor {
        WideMetrics(){super(ignored->null,false);}
        @Override public java.util.function.Function<net.minecraft.resources.ResourceLocation,net.minecraft.client.gui.font.FontSet> create$getFonts(){return ignored->null;}
        @Override public int width(String value){return value.length()*6;}
        @Override public int width(net.minecraft.network.chat.FormattedText value){return value.getString().length()*6;}
        @Override public String plainSubstrByWidth(String value,int width){return value.substring(0,Math.min(value.length(),Math.max(0,width/6)));}
        @Override public String plainSubstrByWidth(String value,int width,boolean reverse){int count=Math.min(value.length(),Math.max(0,width/6));return reverse?value.substring(value.length()-count):value.substring(0,count);}
    }
}
