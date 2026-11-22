package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

/** Explicit production-stop request plus independent evidence of quiescence; never stops general computation. */
public interface ProductionReformControl extends ProductionQuiescence {
    boolean stopAllAndClear();
}
