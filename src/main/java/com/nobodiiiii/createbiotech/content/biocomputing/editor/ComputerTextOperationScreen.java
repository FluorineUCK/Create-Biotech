package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.TextOperation;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Literal-text catalog adapter for the shared native operation selector. */
public final class ComputerTextOperationScreen
    extends ComputerOperationChooserScreen<TextOperation, TextOperation.Category> {
    public ComputerTextOperationScreen(ComputerProgramMenu menu, Inventory inventory, Component title, TextOperation selected,
        Consumer<TextOperation> selection, Runnable close) {
        super(menu,inventory,title,selected,selection,close);
    }
    @Override protected TextOperation[] catalog(){return TextOperation.values();}
    @Override protected TextOperation.Category[] categories(){return TextOperation.Category.values();}
    @Override protected TextOperation.Category category(TextOperation operation){return operation.category();}
    @Override protected String translationPrefix(){return "create_biotech.biocomputing.text_operation.";}
    @Override protected Component hint(){return text("hint."+switch(selected){
        case CONTAINS_IGNORE_CASE->"ignore_case";
        case LENGTH,CHARACTER_AT,SUBSTRING,INDEX_OF->"indices";
        default->"literal";
    });}
}
