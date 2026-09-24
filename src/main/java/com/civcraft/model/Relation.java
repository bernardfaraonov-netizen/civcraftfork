package com.civcraft.model;

import com.civcraft.storage.Stored;
import java.time.Instant;

/**
 * Diplomatic state between two civilizations. Stored once per unordered pair; {@code aggressor} is
 * the side that declared the war (it pays the war upkeep).
 */
public final class Relation implements Stored {

    private String civA;
    private String civB;
    private RelationType type;
    private Instant since;
    private Instant expires;
    private String aggressor;

    private Relation() {
    }

    public Relation(String civA, String civB, RelationType type, String aggressor) {
        boolean ordered = civA.compareTo(civB) < 0;
        this.civA = ordered ? civA : civB;
        this.civB = ordered ? civB : civA;
        this.type = type;
        this.aggressor = aggressor;
        this.since = Instant.now();
    }

    public static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    @Override
    public String storageId() {
        return key(civA, civB);
    }

    public String civA() {
        return civA;
    }

    public String civB() {
        return civB;
    }

    public String other(String civId) {
        return civA.equals(civId) ? civB : civA;
    }

    public boolean involves(String civId) {
        return civA.equals(civId) || civB.equals(civId);
    }

    public RelationType type() {
        return type;
    }

    public void type(RelationType type, String aggressor) {
        this.type = type;
        this.aggressor = aggressor;
        this.since = Instant.now();
        this.expires = null;
    }

    public Instant since() {
        return since;
    }

    public Instant expires() {
        return expires;
    }

    public void expires(Instant expires) {
        this.expires = expires;
    }

    public String aggressor() {
        return aggressor;
    }
}
