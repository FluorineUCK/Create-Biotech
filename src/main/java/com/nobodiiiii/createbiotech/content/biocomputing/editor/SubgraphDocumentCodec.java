package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.GraphLayouts.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;

/** Versioned typed value format; graph/node encoding remains shared with program archives. */
public final class SubgraphDocumentCodec {
    private SubgraphDocumentCodec() {}
    public static DataValue.RecordValue encode(SubgraphDocument document) {
        Objects.requireNonNull(document);
        var modules = new ArrayList<DataValue>();
        document.modules().forEach((key, module) -> modules.add(new DataValue.RecordValue(Map.of(
            "key", GraphArchiveCodec.key(key), "graph", GraphArchiveCodec.encode(module.graph())))));
        return new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(1),
            "graph_version", new DataValue.IntegerValue(GraphArchiveCodec.encodingVersion(
                document.modules().values().stream().map(ModuleDefinition::graph).toList())), "root", GraphArchiveCodec.key(document.root()),
            "modules", new DataValue.ListValue(modules), "layouts", GraphLayouts.encode(document.positions())));
    }
    public static SubgraphDocument decode(DataValue value) {
        try {
            var fields = exactRecord(value, Set.of("version", "graph_version", "root", "modules", "layouts"));
            long version = integer(fields.get("version")), graphVersion = integer(fields.get("graph_version"));
            if (version != 1 || graphVersion < 1 || graphVersion > GraphArchiveCodec.VERSION)
                throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported subgraph or graph schema version");
            var modules = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
            for (var entry : list(fields.get("modules"))) {
                var module = exactRecord(entry, Set.of("key", "graph")); var key = readKey(module.get("key"));
                if (modules.putIfAbsent(key, new ModuleDefinition(key, GraphArchiveCodec.decode(module.get("graph"), graphVersion))) != null)
                    throw invalid("Duplicate module revision");
            }
            return new SubgraphDocument(readKey(fields.get("root")), modules, GraphLayouts.decode(fields.get("layouts")));
        } catch (ArchiveException failure) { throw failure; }
        catch (ComputationException | IllegalArgumentException | NullPointerException failure) { throw invalid("Invalid subgraph package: " + failure.getMessage()); }
    }
    private static ModuleDefinition.Key readKey(DataValue value) {
        exactRecord(value, Set.of("id", "revision")); return GraphArchiveCodec.readKey(value);
    }
}
