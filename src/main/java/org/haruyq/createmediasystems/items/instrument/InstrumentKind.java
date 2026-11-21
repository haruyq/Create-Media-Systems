package org.haruyq.createmediasystems.items.instrument;

public enum InstrumentKind {
    STRATOCASTER("stratocaster"),
    FLUTE("yfl_212"),
    TRUMPET("ytr_2330");

    public final String model;

    InstrumentKind(String model) {
        this.model = model;
    }
}
