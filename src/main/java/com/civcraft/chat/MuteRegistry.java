package com.civcraft.chat;

import com.civcraft.model.Civilization;
import java.util.UUID;

/** Answers whether a player is muted in a civilization's chats ({@code /civ mute}, spec §19.1). */
public interface MuteRegistry {

    boolean isMuted(Civilization civ, UUID player);
}
