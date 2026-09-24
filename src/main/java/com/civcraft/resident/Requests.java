package com.civcraft.resident;

import com.civcraft.CivCraft;
import com.civcraft.core.CivException;
import com.civcraft.core.task.Tasks;
import com.civcraft.core.text.Messages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Pending questions answered with {@code /accept} / {@code /deny} or the chat buttons: town and camp
 * invitations, settler and town-change approvals, diplomatic requests. Everything runs on the main
 * thread and the accept action re-validates the whole situation at answer time (legacy answered on
 * async threads without re-checking, audit C-7, C-16).
 */
public final class Requests {

    /** Runs when a target accepts; may throw a rule violation that is shown to the accepting player. */
    @FunctionalInterface
    public interface Action {
        void run(Player responder) throws CivException;
    }

    public static final class Request {
        private final long id;
        private final String kind;
        private final String key;
        private final Set<UUID> targets;
        private final UUID requester;
        private final Component text;
        private final Instant expires;
        private final Action onAccept;
        private final Action onDeny;

        private Request(long id, String kind, String key, Set<UUID> targets, UUID requester, Component text,
                        Instant expires, Action onAccept, Action onDeny) {
            this.id = id;
            this.kind = kind;
            this.key = key;
            this.targets = targets;
            this.requester = requester;
            this.text = text;
            this.expires = expires;
            this.onAccept = onAccept;
            this.onDeny = onDeny;
        }

        public long id() {
            return id;
        }

        public String kind() {
            return kind;
        }

        public String key() {
            return key;
        }

        public Set<UUID> targets() {
            return targets;
        }

        public UUID requester() {
            return requester;
        }

        public Component text() {
            return text;
        }

        public Instant expires() {
            return expires;
        }
    }

    private final Map<Long, Request> requests = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Asks one or more players (e.g. all leaders of a civilization). The first target to answer
     * decides. A request with the same {@code key} replaces the previous one (one pending invitation
     * per player and town, one settler request per founder...).
     */
    public Request ask(String kind, String key, Collection<UUID> targets, UUID requester, Component text,
                       Duration ttl, Action onAccept, Action onDeny) {
        Tasks.checkMain();
        if (key != null) requests.values().removeIf(r -> key.equals(r.key));
        Request r = new Request(nextId++, kind, key, new HashSet<>(targets), requester, text,
                Instant.now().plus(ttl), onAccept, onDeny);
        requests.put(r.id, r);
        Messages m = Messages.get();
        Component buttons = Component.text(" ")
                .append(m.component("request.accept-button").clickEvent(ClickEvent.runCommand("/accept " + r.id))
                        .hoverEvent(HoverEvent.showText(m.component("request.accept-hover"))))
                .append(Component.text(" "))
                .append(m.component("request.deny-button").clickEvent(ClickEvent.runCommand("/deny " + r.id))
                        .hoverEvent(HoverEvent.showText(m.component("request.deny-hover"))));
        for (UUID target : targets) {
            Player p = Bukkit.getPlayer(target);
            if (p != null) {
                p.sendMessage(m.prefix().append(text));
                p.sendMessage(buttons.append(Component.space())
                        .append(m.component("request.expires", Messages.arg("seconds", ttl.toSeconds()))));
            }
        }
        return r;
    }

    public Request ask(String kind, String key, UUID target, UUID requester, Component text, Duration ttl,
                       Action onAccept) {
        return ask(kind, key, List.of(target), requester, text, ttl, onAccept, null);
    }

    /** Pending requests addressed to the player, oldest first. */
    public List<Request> pendingFor(UUID player) {
        purge();
        List<Request> result = new ArrayList<>();
        for (Request r : requests.values()) if (r.targets.contains(player)) result.add(r);
        result.sort(Comparator.comparingLong(Request::id));
        return result;
    }

    public List<Request> byKind(String kind) {
        purge();
        List<Request> result = new ArrayList<>();
        for (Request r : requests.values()) if (r.kind.equals(kind)) result.add(r);
        return result;
    }

    public boolean has(String key) {
        purge();
        for (Request r : requests.values()) if (Objects.equals(r.key, key)) return true;
        return false;
    }

    public void cancel(String key) {
        requests.values().removeIf(r -> Objects.equals(r.key, key));
    }

    /** Answers a request; {@code id < 0} picks the newest request addressed to the player. */
    public void answer(Player player, long id, boolean accept) throws CivException {
        purge();
        Request r;
        if (id < 0) {
            List<Request> mine = pendingFor(player.getUniqueId());
            if (mine.isEmpty()) throw new CivException("request.none");
            r = mine.getLast();
        } else {
            r = requests.get(id);
            if (r == null || !r.targets.contains(player.getUniqueId())) throw new CivException("request.none");
        }
        requests.remove(r.id);
        if (accept) {
            r.onAccept.run(player);
        } else {
            if (r.onDeny != null) r.onDeny.run(player);
            Messages.get().send(player, "request.denied");
            Player requester = r.requester == null ? null : Bukkit.getPlayer(r.requester);
            if (requester != null && !requester.equals(player)) {
                Messages.get().send(requester, "request.denied-by", Messages.arg("name", player.getName()));
            }
        }
    }

    /** Drops expired requests and tells requesters. */
    public void purge() {
        Instant now = Instant.now();
        for (Iterator<Request> it = requests.values().iterator(); it.hasNext(); ) {
            Request r = it.next();
            if (r.expires.isBefore(now)) {
                it.remove();
                Player requester = r.requester == null ? null : Bukkit.getPlayer(r.requester);
                if (requester != null) Messages.get().send(requester, "request.expired");
            }
        }
    }

    static Requests get() {
        return CivCraft.get().module(ResidentModule.class).requests();
    }
}
