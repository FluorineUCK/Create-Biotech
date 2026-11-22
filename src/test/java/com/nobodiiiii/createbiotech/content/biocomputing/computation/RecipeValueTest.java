package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeValueTest {
    @Test void recipeHasNominalIdentityRatherThanARecordFieldConvention() throws Exception {
        assertTrue(DataType.class.isAssignableFrom(Class.forName(DataType.class.getName() + "$Semantic")));
    }
}
