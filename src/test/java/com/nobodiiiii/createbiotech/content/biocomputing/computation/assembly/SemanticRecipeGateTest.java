package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.TypeArchiveCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SemanticRecipeGateTest {
    @Test void nestedAuthoredSemanticDeclarationsRequireAdvancedIsaButNativeTransportDoesNot() {
        DataType type=new DataType.RecordType(Map.of("recipe",new DataType.OptionalType(new DataType.ListType(DataType.Semantic.RECIPE))));
        assertTrue(AssemblyTypeRequirements.dynamic(TypeArchiveCodec.decode(TypeArchiveCodec.encode(type))));
        assertFalse(AssemblyTypeRequirements.dynamic(DataType.Scalar.NBT));
        assertTrue(Arrays.stream(DataType.Scalar.values()).noneMatch(s->s.name().equals("RECIPE")));
        assertTrue(RegisterAssignment.permits(DataType.Semantic.RECIPE,DataType.Semantic.RECIPE));
        var image=new RegisterProgram(List.of(),List.of(type),List.of(),Map.of("x",0),Map.of("x",0),0);
        assertTrue(image.advancedTypeLocation().isPresent());
        var value=new DataValue.RecipeValue("recipe",1,FoundationRecipeTest.projection(false));
        var cells=Map.of("recipe",new ProgramDefinition.Cell(DataType.Semantic.RECIPE,value));
        var stored=new RegisterProgram(List.of(),List.of(),List.of(),Map.of(),Map.of(),0,List.of(),cells);
        assertTrue(stored.advancedTypeLocation().isPresent());
        var graph=new ComputationGraph(Map.of(),Map.of(),Map.of());
        var archive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(new ProgramDefinition(graph,cells,List.of()),Map.of());
        assertTrue(LegacyInstructionRequirements.firstSemanticDeclaration(archive).isPresent());
        var basic=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,1000000,1),FoundationRecipeTest.LIMITS);
        var master=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(basic.hardware(),basic.archive(),basic.mailbox(),basic.pins(),basic.serial(),basic.taskSlots(),true,true);
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable.class,()->com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validateLegacyInstructionSets(archive,master));
        var flow=new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("value",type),List.of()).withAssembly();
        assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.Unavailable.class,()->com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validate(flow,basic));
        assertDoesNotThrow(()->com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowPins.validate(flow,master));
        assertFalse(new RegisterProgram(List.of(),List.of(DataType.Scalar.ANY),List.of(),Map.of("opaque",0),Map.of("opaque",0),0).advancedTypeLocation().isPresent());
    }
}
