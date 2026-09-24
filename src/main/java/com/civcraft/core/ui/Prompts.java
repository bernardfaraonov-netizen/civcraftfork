package com.civcraft.core.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

/**
 * Native client dialogs (1.21.6+) for confirmations and text input. They replace the legacy
 * "type the name in chat" flow, which leaked into public chat and could be spoofed by other plugins.
 */
public final class Prompts {

    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
            .uses(1).lifetime(Duration.ofMinutes(5)).build();

    private Prompts() {
    }

    /** Yes/No confirmation. {@code onYes} runs on the main thread with the clicking player. */
    public static void confirm(Player player, Component title, List<Component> body, Component yes, Component no,
                               Consumer<Player> onYes) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component line : body) bodies.add(DialogBody.plainMessage(line, 300));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title).body(bodies).canCloseWithEscape(true).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(yes).action(DialogAction.customClick((view, audience) -> {
                            if (audience instanceof Player p) onYes.accept(p);
                        }, ONCE)).build(),
                        ActionButton.builder(no).build())));
        player.showDialog(dialog);
    }

    /** One-line text input with an OK button. */
    public static void text(Player player, Component title, List<Component> body, Component label, String initial,
                            int maxLength, Component ok, BiConsumer<Player, String> onSubmit) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component line : body) bodies.add(DialogBody.plainMessage(line, 300));
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title).body(bodies)
                        .inputs(List.of(DialogInput.text("value", label).initial(initial == null ? "" : initial)
                                .maxLength(maxLength).build()))
                        .canCloseWithEscape(true).build())
                .type(DialogType.notice(ActionButton.builder(ok).action(DialogAction.customClick((view, audience) -> {
                    String value = view.getText("value");
                    if (audience instanceof Player p && value != null) onSubmit.accept(p, value.trim());
                }, ONCE)).build())));
        player.showDialog(dialog);
    }
}
