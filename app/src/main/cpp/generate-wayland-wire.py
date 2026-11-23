#!/usr/bin/env python3
"""Generate C++ protocol ordinals from the vendored Wayland XML descriptions."""

import sys
from pathlib import Path
from xml.etree import ElementTree


def main():
    output = Path(sys.argv[1])
    lines = [
        "// Generated from Wayland protocol XML. Do not edit.",
        "#pragma once",
        "#include <cstdint>",
        "namespace wayland_wire {",
    ]
    for path in sorted(sys.argv[2:]):
        for interface in ElementTree.parse(path).getroot().findall("interface"):
            lines.append(f"namespace {interface.attrib['name']} {{")
            for direction in ("request", "event"):
                lines.append(f"namespace {direction} {{")
                for opcode, item in enumerate(interface.findall(direction)):
                    lines.append(f"inline constexpr uint16_t {item.attrib['name']} = {opcode};")
                lines.append("}")
            lines.append("}")
    lines.append("}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
