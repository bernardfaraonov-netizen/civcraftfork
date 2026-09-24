package com.civcraft.structure.template;

import com.civcraft.structure.type.StructureType;
import com.civcraft.template.Template;
import com.civcraft.template.TemplateService;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.block.structure.StructureRotation;

/**
 * Chooses the template of a structure type for a theme: an admin {@code templates/<theme>/<type id>.schem} in the
 * plugin folder, then the bundled legacy template named in the balance file ({@code template:}), then a procedural
 * building. Rotated copies and solid-cell masks are cached per theme, template and rotation.
 */
public final class StructureTemplates {

    /** Result of resolving a type: which template was used and whether it was generated. */
    public record Resolved(String templateId, boolean procedural, Template template) {
    }

    private final TemplateService service;
    private final Logger logger;
    private final Map<String, Resolved> resolved = new HashMap<>();
    private final Map<String, Template> rotated = new HashMap<>();
    private final Map<String, BitSet> masks = new HashMap<>();

    public StructureTemplates(TemplateService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    public void clear() {
        resolved.clear();
        rotated.clear();
        masks.clear();
        service.clearCache();
    }

    public Resolved resolve(StructureType type, String theme) {
        String key = theme + "|" + type.id();
        Resolved r = resolved.get(key);
        if (r != null) return r;
        Template t = service.get(theme, type.id());
        String id = type.id();
        if (t == null && type.template() != null && !type.template().equals(type.id())) {
            t = service.get(theme, type.template());
            id = type.template();
        }
        if (t != null) {
            r = new Resolved(id, false, t);
        } else {
            r = new Resolved(type.id(), true, procedural(type, theme));
        }
        resolved.put(key, r);
        return r;
    }

    /** The template a persisted structure was built from (same theme / id / generator). */
    public Template load(StructureType type, String theme, String templateId, boolean procedural) {
        if (procedural) return procedural(type, theme);
        Template t = service.get(theme, templateId);
        if (t == null) {
            logger.warning("Template " + theme + "/" + templateId + " is missing; using a generated one for " + type.id());
            return procedural(type, theme);
        }
        return t;
    }

    private Template procedural(StructureType type, String theme) {
        String key = "proc_" + type.id();
        Template cached = rotated.get(theme + "|" + key + "|base");
        if (cached != null) return cached;
        Template t = ProceduralTemplates.generate(type, theme);
        rotated.put(theme + "|" + key + "|base", t);
        service.put(theme, key, t);
        return t;
    }

    /** Rotated copy (cached). */
    public Template rotate(Template template, StructureRotation rotation) {
        if (rotation == StructureRotation.NONE) return template;
        String key = template.id() + "|" + rotation + "|" + System.identityHashCode(template);
        return rotated.computeIfAbsent(key, k -> template.rotate(rotation));
    }

    /** Cells that are part of the building: non-air blocks and markers. */
    public BitSet solidMask(Template t) {
        String key = t.id() + "|" + System.identityHashCode(t);
        return masks.computeIfAbsent(key, k -> {
            BitSet bits = new BitSet(t.blockCount());
            for (int y = 0; y < t.sizeY(); y++) {
                for (int z = 0; z < t.sizeZ(); z++) {
                    for (int x = 0; x < t.sizeX(); x++) {
                        if (!t.block(x, y, z).getMaterial().isAir()) bits.set(t.index(x, y, z));
                    }
                }
            }
            for (Template.Marker m : t.markers()) bits.set(t.index(m.x(), m.y(), m.z()));
            return bits;
        });
    }
}
