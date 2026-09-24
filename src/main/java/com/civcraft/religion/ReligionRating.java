package com.civcraft.religion;

import com.civcraft.storage.Stored;
import java.util.ArrayList;
import java.util.List;

/** Ordered followers of one religion (collection {@code religion_rating}); index 0 is the 1st place. */
public final class ReligionRating implements Stored {

    private String religionId;
    private List<String> order = new ArrayList<>();

    private ReligionRating() {
    }

    public ReligionRating(String religionId) {
        this.religionId = religionId;
    }

    @Override
    public String storageId() {
        return religionId;
    }

    void repair() {
        if (order == null) order = new ArrayList<>();
    }

    public String religionId() {
        return religionId;
    }

    public List<String> order() {
        return order;
    }
}
