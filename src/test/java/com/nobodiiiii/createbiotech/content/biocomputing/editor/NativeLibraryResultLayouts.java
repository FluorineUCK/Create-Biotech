package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;

/** Maintainer-only defaults: ordinary editable bindings, no execution controls. */
final class NativeLibraryResultLayouts {
 private NativeLibraryResultLayouts() {}
 static TerminalDefinition fields(String... names) {
  var nodes=new LinkedHashMap<String,TerminalDefinition.Node>();
  var rows=new ArrayList<String>();
  for(String name:names) {
   String row=name+"_row",label=name+"_label";
   rows.add(row);
   nodes.put(row,new TerminalDefinition.Row(List.of(label,name)));
   nodes.put(label,new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue(name))));
   nodes.put(name,new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("output",List.of()).key(name))));
  }
  nodes.put("results",new TerminalDefinition.Column(rows));
  return new TerminalDefinition("results",nodes);
 }
}
