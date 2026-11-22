package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.google.gson.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** Client resources containing ordinary documents, never an alternate execution or installation path. */
public final class ProgramExampleCatalog {
    /** Local resource index, not a network packet. Still read with a fixed bounded allocation. */
    public static final int MAX_CATALOG_BYTES = 128 * 1024;
    public static final ResourceLocation INDEX = ResourceLocation.fromNamespaceAndPath(
        "create_biotech", "biocomputing/examples/catalog.json");
    private ProgramExampleCatalog() {}

    public record Example(String id, ResourceLocation document, String title, List<String> instructions, Set<String> tags) {
        public Example(String id, ResourceLocation document, String title, List<String> instructions) {
            this(id, document, title, instructions, Set.of());
        }
        public Example { instructions = List.copyOf(instructions); tags = Set.copyOf(tags); }
    }

    public static List<Example> load(Function<ResourceLocation, InputStream> resources) {
        var root = object(JsonParser.parseString(read(resources, INDEX, MAX_CATALOG_BYTES)));
        if (!root.has("version") || !root.get("version").toString().equals("1")) throw new IllegalArgumentException("Unsupported example catalog version");
        var examples = new ArrayList<Example>(); var ids = new HashSet<String>();
        for (var element : array(root, "examples")) {
            var item = object(element); String id = string(item, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate example: " + id);
            var document = ResourceLocation.tryParse(string(item, "document"));
            if (document == null || !document.getPath().startsWith("biocomputing/examples/") || !document.getPath().endsWith(".bcp"))
                throw new IllegalArgumentException("Example document must be an example resource");
            var instructions = new ArrayList<String>();
            for (var instruction : array(item, "instructions")) instructions.add(string(instruction));
            if (instructions.isEmpty()) throw new IllegalArgumentException("Missing example instructions");
            var tags = new HashSet<String>();
            if (item.has("tags")) for (var tag : array(item, "tags")) tags.add(string(tag));
            examples.add(new Example(id, document, string(item, "title"), instructions, tags));
        }
        return List.copyOf(examples);
    }

    public static ProgramDocument preview(Example example, Function<ResourceLocation, InputStream> resources, ArchiveLimits limits) {
        var text = read(resources, example.document(), ComputerProgramDocumentScreen.MAX_TEXT).strip();
        return ProgramDocumentWire.decode(Base64.getDecoder().decode(text), limits);
    }

    private static String string(JsonObject object, String name) { return string(object.get(name)); }
    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Expected an example object");
        return element.getAsJsonObject();
    }
    private static JsonArray array(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw new IllegalArgumentException("Expected example array: " + name);
        return object.getAsJsonArray(name);
    }
    private static String string(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString() || element.getAsString().isBlank())
            throw new IllegalArgumentException("Missing or invalid example field");
        return element.getAsString();
    }
    private static String read(Function<ResourceLocation, InputStream> resources, ResourceLocation id, int limit) {
        try (var stream = resources.apply(id)) {
            if (stream == null) throw new IllegalArgumentException("Missing example resource: " + id);
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException("Example resource exceeds read limit: " + id);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
