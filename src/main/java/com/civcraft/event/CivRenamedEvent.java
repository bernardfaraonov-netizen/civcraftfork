package com.civcraft.event;

/** A civilization name or tag changed (/rename civ|tag). */
public final class CivRenamedEvent extends CivEvent {

    private final String civId;
    private final String oldName;
    private final String newName;
    private final String oldTag;
    private final String newTag;

    public CivRenamedEvent(String civId, String oldName, String newName, String oldTag, String newTag) {
        this.civId = civId;
        this.oldName = oldName;
        this.newName = newName;
        this.oldTag = oldTag;
        this.newTag = newTag;
    }

    public String civId() {
        return civId;
    }

    public String oldName() {
        return oldName;
    }

    public String newName() {
        return newName;
    }

    public String oldTag() {
        return oldTag;
    }

    public String newTag() {
        return newTag;
    }
}
