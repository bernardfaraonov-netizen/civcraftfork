package com.civcraft.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for CivCraft's own events. Modules communicate through these instead of calling each
 * other directly (e.g. the camp module destroys camps swallowed by culture on {@link CultureChangedEvent}).
 */
public abstract class CivEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Fires the event and returns it for chaining. */
    public <T extends CivEvent> T call() {
        org.bukkit.Bukkit.getPluginManager().callEvent(this);
        @SuppressWarnings("unchecked")
        T self = (T) this;
        return self;
    }
}
