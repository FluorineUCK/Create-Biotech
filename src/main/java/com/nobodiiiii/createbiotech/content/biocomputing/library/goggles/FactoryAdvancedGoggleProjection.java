package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;
import java.util.*;
/** Immutable exact-target display data; no executable components, catalogs, or global queues. */
public record FactoryAdvancedGoggleProjection(FactoryGoggleTarget target,long sampleTick,int intervalTicks,int rowLimit,
 List<String> slots,List<String> routes,List<String> listeners,List<String> logistics,List<String> library,List<String> agents,List<String> formation,List<String> errors) {
 public static final int MAX_ROWS=512, MAX_TEXT=512;
 public FactoryAdvancedGoggleProjection {
  Objects.requireNonNull(target);if(sampleTick<0||intervalTicks<1||intervalTicks>100||rowLimit<1||rowLimit>MAX_ROWS)throw new IllegalArgumentException("invalid advanced bounds");
  if((long)slots.size()+routes.size()+listeners.size()+logistics.size()+library.size()+agents.size()+formation.size()+errors.size()>rowLimit)throw new IllegalArgumentException("aggregate advanced budget exceeded");
  slots=checked(slots,rowLimit);routes=checked(routes,rowLimit);listeners=checked(listeners,rowLimit);logistics=checked(logistics,rowLimit);library=checked(library,rowLimit);agents=checked(agents,rowLimit);formation=checked(formation,rowLimit);errors=checked(errors,rowLimit);
 }
 private static List<String> checked(List<String> list,int limit) { if(list.size()>limit)throw new IllegalArgumentException("advanced collection exceeds budget");for(var s:list)if(s.length()>MAX_TEXT||s.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("invalid advanced text");return List.copyOf(list); }
 public List<List<String>> collections() { return List.of(slots,routes,listeners,logistics,library,agents,formation,errors); }
 public static String text(Object value) { String s=String.valueOf(value).codePoints().filter(cp->!Character.isISOControl(cp)).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();return s.length()>MAX_TEXT?s.substring(0,MAX_TEXT):s; }
}
