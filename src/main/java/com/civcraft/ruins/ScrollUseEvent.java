package com.civcraft.ruins;

import com.civcraft.event.CivEvent;
import com.civcraft.model.Town;
import org.bukkit.entity.Player;

/**
 * A bonus scroll from a lucky block is used (spec 04 §15.3). Fired for the scrolls whose effect
 * belongs to another module: {@code scroll_bank_2/3} (instantly raise the Bank to that level),
 * {@code scroll_settler} ({@code value} = percent of settler training to add) and
 * {@code scroll_town_2/3} (town level). The owning module applies the effect and calls
 * {@link #handled()}; if nobody handles it the scroll is not consumed (town level scrolls fall back
 * to setting {@link Town#level} directly). Set a message key with {@link #fail} to refuse.
 */
public final class ScrollUseEvent extends CivEvent {

    private final String scroll;
    private final Player player;
    private final Town town;
    private final double value;
    private boolean handled;
    private String failKey;

    public ScrollUseEvent(String scroll, Player player, Town town, double value) {
        this.scroll = scroll;
        this.player = player;
        this.town = town;
        this.value = value;
    }

    public String scroll() {
        return scroll;
    }

    public Player player() {
        return player;
    }

    public Town town() {
        return town;
    }

    public double value() {
        return value;
    }

    public void handled() {
        this.handled = true;
    }

    public boolean isHandled() {
        return handled;
    }

    /** Refuses the scroll with a message key (the scroll is kept). */
    public void fail(String messageKey) {
        this.failKey = messageKey;
    }

    public String failKey() {
        return failKey;
    }
}
