package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Scalar catalog adapter for the shared native operation selector. */
public final class ComputerOperationScreen
    extends ComputerOperationChooserScreen<ScalarOperation, ScalarOperation.Category> {
    public ComputerOperationScreen(ComputerProgramMenu menu, Inventory inventory, Component title, ScalarOperation selected,
        Consumer<ScalarOperation> selection, Runnable close) {
        super(menu,inventory,title,selected,selection,close);
    }
    @Override protected ScalarOperation[] catalog(){return java.util.Arrays.stream(ScalarOperation.values()).filter(op -> !op.numericEdge()).toArray(ScalarOperation[]::new);}
    @Override protected ScalarOperation.Category[] categories(){return ScalarOperation.Category.values();}
    @Override protected ScalarOperation.Category category(ScalarOperation operation){return operation.category();}
    @Override protected String translationPrefix(){return "create_biotech.biocomputing.operation.";}
    @Override protected Component hint(){
        String key=switch(selected){
            case REQUIRE_VALUE->"require_value";case SELECT->"eager";case INTEGER_TO_DECIMAL->"integer_to_decimal";case DECIMAL_TO_INTEGER->"decimal_to_integer";
            case SQRT_DECIMAL->"sqrt";case POWER_DECIMAL->"power";case REMAINDER_DECIMAL->"remainder";
            case ROUND_DECIMAL_TO_INTEGER->"round";case CEIL_DECIMAL_TO_INTEGER->"ceil";case FLOOR_DECIMAL_TO_INTEGER->"floor";
            case DIVIDE_INTEGER,REMAINDER_INTEGER,DIVIDE_DECIMAL->"numeric";
            case IS_ERROR->"is_error";case ERROR_CODE,ERROR_MESSAGE->"error_extract";default->null;
        };
        return key==null?Component.empty():text("hint."+key);
    }
}
