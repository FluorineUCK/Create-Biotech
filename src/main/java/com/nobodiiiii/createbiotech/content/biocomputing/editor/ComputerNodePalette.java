package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Editor catalog only: choosing a node never compiles, installs or executes a program. */
final class ComputerNodePalette {
    enum Category {
        ALL, VALUES, OPERATIONS, DATA, STATE, MODULES;
        Component label() { return text("category." + name().toLowerCase(Locale.ROOT)); }
    }

    enum Kind {
        INTEGER, DECIMAL, TEXT, BOOLEAN, ADD, LESS, EQUAL, FIELD, RECORD, INPUT, MEMORY,
        CALL, MAP, FILTER, JSON, INDEX, SCALAR, BRANCH, FOLD, TEXT_OPERATION, SORT, LIST, CONCAT_LISTS, DYNAMIC_INDEX, LIST_REPLACE, LIST_INSERT, LIST_REMOVE, LIST_SLICE, DISTINCT, VALUE_EQUALS, DYNAMIC_FIELD, HAS_FIELD, KEYS, NBT, LIST_APPEND;

        Component label() {
            return Component.translatable("create_biotech.biocomputing.editor.kind." + id());
        }
        String id() { return name().toLowerCase(Locale.ROOT); }
        Category category() {
            return switch (this) {
                case INTEGER, DECIMAL, TEXT, BOOLEAN, JSON, NBT -> Category.VALUES;
                case ADD, LESS, EQUAL, SCALAR, TEXT_OPERATION -> Category.OPERATIONS;
                case FIELD, RECORD, MAP, FILTER, INDEX, FOLD, SORT, LIST, CONCAT_LISTS, DYNAMIC_INDEX, LIST_REPLACE, LIST_INSERT, LIST_REMOVE, LIST_SLICE, DISTINCT, VALUE_EQUALS, DYNAMIC_FIELD, HAS_FIELD, KEYS, LIST_APPEND -> Category.DATA;
                case INPUT, MEMORY -> Category.STATE;
                case CALL, BRANCH -> Category.MODULES;
            };
        }
        com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation listOperation() {
            return switch (this) {
                case LIST_REPLACE -> com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation.REPLACE;
                case LIST_INSERT -> com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation.INSERT;
                case LIST_REMOVE -> com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation.REMOVE;
                case LIST_APPEND -> com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation.APPEND;
                case LIST_SLICE -> com.nobodiiiii.createbiotech.content.biocomputing.computation.ListOperation.SLICE;
                default -> null;
            };
        }
        String initialParameter() {
            return switch (this) {
                case INTEGER, DECIMAL, INDEX -> "0";
                case LIST -> "2";
                case BOOLEAN -> "false";
                case NBT -> "{}";
                default -> "";
            };
        }
    }

    static List<Kind> search(Category category, String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(Kind.values())
            .filter(kind -> category == Category.ALL || kind.category() == category)
            .filter(kind -> kind.id().contains(needle) || kind.label().getString().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    }

    static Component text(String key) { return Component.translatable("create_biotech.biocomputing.palette." + key); }
}
