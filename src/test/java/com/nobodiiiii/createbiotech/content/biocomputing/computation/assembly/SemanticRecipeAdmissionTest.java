package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticRecipeAdmissionTest {
    static final ComputerHostLimits BASIC=new ComputerHostLimits(new ResourceLimits(1000,1000000,1),FoundationRecipeTest.LIMITS);
    static final ComputerHostLimits MASTER=new ComputerHostLimits(BASIC.hardware(),BASIC.archive(),BASIC.mailbox(),BASIC.pins(),BASIC.serial(),1,true,true);
    static DataValue.RecipeValue recipe(){return new DataValue.RecipeValue("recipe",1,FoundationRecipeTest.projection(false));}
    static List<DataValue> embedded(){
        return List.of(recipe(),new DataValue.ListValue(List.of(recipe(),DataValue.NullValue.INSTANCE)),
            new DataValue.MapValue(Map.of("recipe",recipe(),"null",DataValue.NullValue.INSTANCE)));
    }
    @Test void heterogeneousListConstantRequiresMaster(){
        var value=embedded().get(1);
        assertTrue(new RegisterProgram(List.of(),List.of(),List.of(value),Map.of(),Map.of(),0).advancedTypeLocation().isPresent());
    }
    @Test void heterogeneousMapConstantRequiresMaster(){
        var value=embedded().get(2);
        assertTrue(new RegisterProgram(List.of(),List.of(),List.of(value),Map.of(),Map.of(),0).advancedTypeLocation().isPresent());
    }
    @Test void staticConstantsAndAnyCellInitialsCannotEraseSemanticPresenceThroughTypeInference() {
        for(var value:embedded()){
            var constants=new RegisterProgram(List.of(),List.of(),List.of(value),Map.of(),Map.of(),0);
            assertTrue(constants.advancedTypeLocation().isPresent(),"static constant "+value);
            var cells=Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.ANY,value));
            var cellImage=new RegisterProgram(List.of(),List.of(),List.of(),Map.of(),Map.of(),0,List.of(),cells);
            assertTrue(cellImage.advancedTypeLocation().isPresent(),"ANY initial "+value);
            for(var archive:List.of(
                    new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of("constant",new ComputationGraph.Constant(value)),Map.of()),Map.of(),List.of()),Map.of()),
                    new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),cells,List.of()),Map.of()))){
                var flow=new FlowProgram(Map.of(),List.of(),archive);
                for(var limits:List.of(BASIC,MASTER))assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(flow,limits));
            }
        }
    }
    @Test void legacyFlowInputsSignaturesAndReadSchemasRejectAtRealAdmission() {
        var nested=new DataType.RecordType(Map.of("recipe",new DataType.OptionalType(new DataType.ListType(DataType.Semantic.RECIPE))));
        var key=new ModuleDefinition.Key("legacy",1);
        for(DataType type:List.of(DataType.Semantic.RECIPE,nested)){
            var input=new FlowProgram(Map.of("r",type),List.of());
            var inProcedure=new FlowProgram(Map.of(),List.of(new FlowProgram.Procedure("proc",key,Map.of("r",type),Map.of(),Map.of(),FlowBlockRules.NONE),new FlowProgram.EndProcedure("end")));
            var outProcedure=new FlowProgram(Map.of(),List.of(new FlowProgram.Procedure("proc",key,Map.of(),Map.of("r",type),Map.of("r","r"),FlowBlockRules.NONE),new FlowProgram.EndProcedure("end")));
            for(var flow:List.of(input,inProcedure,outProcedure))for(var limits:List.of(BASIC,MASTER))
                assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(flow,limits));
        }
        var read=new FlowProgram(Map.of(),List.of(new FlowProgram.Read("read","x0","r",nested,false)));
        for(var limits:List.of(BASIC,MASTER))assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(read,limits));
    }
    @Test void legacyOwnerInstallCannotAcceptGenuineRecipeInput() {
        var flow=new FlowProgram(Map.of("r",DataType.Semantic.RECIPE),List.of());
        var inputs=new DataValue.RecordValue(Map.of("r",recipe()));
        for(var limits:List.of(BASIC,MASTER)){
            var owner=new ComputerBlockHost(()->{});var before=owner.write();
            assertThrows(FlowPins.Unavailable.class,()->owner.installFlow(flow,inputs,limits,true));
            assertEquals(before,owner.write());
        }
    }
    @Test void deepSharedStaticTreesAreStackSafeAndOpaqueRuntimeInputsAreNotAuthoredDeclarations() {
        DataValue deep=new DataValue.ListValue(List.of(recipe(),DataValue.NullValue.INSTANCE));
        for(int i=0;i<3000;i++)deep=new DataValue.ListValue(List.of(deep));
        deep=new DataValue.ListValue(List.of(deep,deep));
        assertTrue(new RegisterProgram(List.of(),List.of(),List.of(deep),Map.of(),Map.of(),0).advancedTypeLocation().isPresent());
        assertFalse(new RegisterProgram(List.of(),List.of(DataType.Scalar.ANY,DataType.Scalar.NBT),List.of(),Map.of("opaque",0,"native",1),Map.of("opaque",0,"native",1),0).advancedTypeLocation().isPresent());
        assertDoesNotThrow(()->FlowPins.validate(new FlowProgram(Map.of("runtime",DataType.Scalar.ANY),List.of()),BASIC));
    }
}
