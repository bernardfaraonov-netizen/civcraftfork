package com.civcraft.town;

import com.civcraft.model.Town;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Lets other modules provide {@code /t info <page>} pages (cottage, mine, bank, trade, temple,
 * religion, goodies, tradeship, disabled...). The first module returning non-null lines wins.
 */
public interface TownInfoExtension {

    /** Page names this module can render (for tab completion). */
    Set<String> townInfoPages();

    /** Lines of the page, or null if this module does not handle it. */
    List<Component> townInfo(String page, Player viewer, Town town);
}
