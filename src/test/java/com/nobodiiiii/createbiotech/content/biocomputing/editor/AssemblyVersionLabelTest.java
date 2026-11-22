package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyVersionLabelTest {
    @Test void latestAndExplicitUpgradeActionsDescribeCurrentVersionWithoutLosingOldVersions() throws Exception {
        int latest=com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION;
        String prefix = "create_biotech.biocomputing.flow_editor.";
        for (String language : new String[]{"en_us", "zh_cn"}) {
            try (var stream = getClass().getResourceAsStream("/assets/create_biotech/lang/" + language + ".json")) {
                assertNotNull(stream);
                var labels = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                assertTrue(labels.get(prefix + "implementation_assembly").getAsString().contains("v"+latest), language);
                for (int old = 1; old < latest; old++) {
                    if (!com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.supported(old)) continue;
                    String key = prefix + "implementation_assembly_v" + old;
                    assertTrue(labels.has(key), language + ": " + key);
                    String label = labels.get(key).getAsString();
                    assertTrue(label.contains("v" + old) && label.contains("v"+latest), language + ": " + label);
                }
                String help = labels.get(prefix + "assembly_syntax_hint").getAsString();
                assertTrue(help.contains("TEXT.NEXT") && help.contains("TEXT_NEXT") && help.contains("LIST.APPEND") && help.contains("FMT.CHECK") && help.contains("FMT.PROJECT") && help.contains("FORMAT recipe 1"), language);
            }
        }
    }
}
