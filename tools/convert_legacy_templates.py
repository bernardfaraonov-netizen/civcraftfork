#!/usr/bin/env python3
"""Converts legacy CivCraft 1.7.10 ``.def`` templates into Sponge Schematic v3 (``.schem``).

Legacy format::

    sizeX;sizeY;sizeZ
    x:y:z,id:data[,sign line 1,line 2,line 3,line 4]

Block ids are resolved through ``legacy_blocks.tsv`` (generated from Minecraft's own
``BlockStateData`` flattening table) and validated against the 1.21.11 block report.
Signs are kept as sign block entities, because the plugin reads "command signs"
(first line starting with ``/``) as functional markers. That keeps templates
editable in WorldEdit: an admin just places a sign with ``/control`` on it.

Only the ``_south`` orientation is converted; the plugin rotates templates itself.

Usage: convert_legacy_templates.py <legacy templates dir> <output dir> [blocks.json]
"""
import gzip
import json
import os
import re
import sys

import nbtlib
from nbtlib import tag

DATA_VERSION = 4671  # Minecraft 1.21.11
HERE = os.path.dirname(os.path.abspath(__file__))

# Blocks renamed after the 1.13 flattening that BlockStateData still reports by old name.
RENAMES = {
    "minecraft:grass": "minecraft:short_grass",
    "minecraft:melon_block": "minecraft:melon",
    "minecraft:sign": "minecraft:oak_sign",
    "minecraft:wall_sign": "minecraft:oak_wall_sign",
}
SIGN_BLOCKS = {"minecraft:oak_sign", "minecraft:oak_wall_sign"}


def parse_dynamic(value):
    """Parses the toString() of a Dynamic tag: {Name:"x",Properties:{a:"b",...}}."""
    name = re.search(r'Name:"([^"]+)"', value).group(1)
    props = dict(re.findall(r'(\w+):"([^"]*)"', value.split("Properties:", 1)[1])) if "Properties:" in value else {}
    return name, props


def load_legacy_map(blocks_report):
    known = json.load(open(blocks_report)) if blocks_report else None
    result = {}
    for line in open(os.path.join(HERE, "legacy_blocks.tsv"), encoding="utf-8"):
        if "\t" not in line:
            continue
        key, value = line.rstrip("\n").split("\t", 1)
        name, props = parse_dynamic(value)
        name = RENAMES.get(name, name)
        if known is not None:
            if name not in known:
                name, props = "minecraft:air", {}
            else:
                valid = {p for s in known[name]["states"] for p in s.get("properties", {})}
                props = {k: v for k, v in props.items() if k in valid}
        state = name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
        result[key] = state
    return result


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return out


def convert(path, legacy_map):
    with open(path, encoding="utf-8", errors="replace") as fh:
        header = fh.readline().strip()
        sx, sy, sz = (int(v) for v in header.split(";"))
        blocks = ["minecraft:air"] * (sx * sy * sz)
        signs = []
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split(",")
            x, y, z = (int(v) for v in parts[0].split(":"))
            state = legacy_map.get(parts[1], "minecraft:air")
            idx = x + z * sx + y * sx * sz
            blocks[idx] = state
            if len(parts) > 2 and state.split("[")[0] in SIGN_BLOCKS:
                lines = (parts[2:] + ["", "", "", ""])[:4]
                signs.append(((x, y, z), [l.strip() for l in lines]))

    palette = {}
    data = bytearray()
    for state in blocks:
        data += varint(palette.setdefault(state, len(palette)))

    entities = tag.List[tag.Compound]([
        tag.Compound({
            "Pos": tag.IntArray(list(pos)),
            "Id": tag.String("minecraft:sign"),
            "Data": tag.Compound({
                "front_text": tag.Compound({
                    "messages": tag.List[tag.String]([tag.String(l) for l in lines]),
                }),
                "is_waxed": tag.Byte(1),
            }),
        })
        for pos, lines in signs
    ])

    schematic = tag.Compound({
        "Version": tag.Int(3),
        "DataVersion": tag.Int(DATA_VERSION),
        "Width": tag.Short(sx),
        "Height": tag.Short(sy),
        "Length": tag.Short(sz),
        "Offset": tag.IntArray([0, 0, 0]),
        "Metadata": tag.Compound({"Source": tag.String("legacy:" + os.path.basename(path))}),
        "Blocks": tag.Compound({
            "Palette": tag.Compound({k: tag.Int(v) for k, v in palette.items()}),
            "Data": tag.ByteArray([b - 256 if b > 127 else b for b in data]),
            "BlockEntities": entities,
        }),
    })
    return nbtlib.File({"Schematic": schematic}, root_name="")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    report = sys.argv[3] if len(sys.argv) > 3 else None
    legacy_map = load_legacy_map(report)
    count = 0
    for root, _, files in os.walk(src):
        for name in files:
            if not name.endswith(".def"):
                continue
            rel = os.path.relpath(root, src)
            stem = name[:-4]
            if stem.endswith(("_north", "_east", "_west")):
                continue
            stem = stem.removesuffix("_south")
            # themes/<theme>/<structures|wonders>/<id>/<id>.def -> <theme>/<id>.schem
            parts = rel.split(os.sep)
            theme = parts[1] if len(parts) > 1 and parts[0] == "themes" else "default"
            out_dir = os.path.join(dst, theme)
            os.makedirs(out_dir, exist_ok=True)
            convert(os.path.join(root, name), legacy_map).save(
                os.path.join(out_dir, stem + ".schem"), gzipped=True)
            count += 1
    print(f"converted {count} templates")


if __name__ == "__main__":
    main()
