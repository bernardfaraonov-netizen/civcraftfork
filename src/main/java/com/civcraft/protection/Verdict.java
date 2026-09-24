package com.civcraft.protection;

/** Guard answer. {@link #PASS} lets the next guard decide; if every guard passes, the action is allowed. */
public record Verdict(Kind kind, String messageKey) {

    public enum Kind { ALLOW, DENY, PASS }

    public static final Verdict PASS = new Verdict(Kind.PASS, null);
    public static final Verdict ALLOW = new Verdict(Kind.ALLOW, null);

    public static Verdict deny(String messageKey) {
        return new Verdict(Kind.DENY, messageKey);
    }

    public boolean denied() {
        return kind == Kind.DENY;
    }
}
