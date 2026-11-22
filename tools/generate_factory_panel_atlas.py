#!/usr/bin/env python3
"""Deterministically generate the replaceable 512x512 Panel solid-color atlas.

No external dependencies, timestamps, palette quantization, or antialiasing.
Rectangles use inclusive-exclusive bounds and are applied in the listed order.
"""
from pathlib import Path
import struct
import zlib

SIZE = 512
REGIONS = (
    ('WINDOW', 0, 0, 256, 192, '20252b'),
    ('TITLE', 0, 192, 256, 24, '15191d'),
    ('CARD_NORMAL', 0, 216, 128, 32, '303943'),
    ('CARD_SELECTED', 128, 216, 128, 32, '3d5266'),
    ('CARD_WAIT', 0, 248, 128, 32, '665a35'),
    ('CARD_FAULT', 128, 248, 128, 32, '663b3b'),
    ('NODE_NORMAL', 0, 280, 128, 24, '34404a'),
    ('NODE_SELECTED', 128, 280, 128, 24, '46657a'),
    ('NAV_NORMAL', 256, 0, 32, 32, '3d5266'),
    ('NAV_SELECTED', 256, 32, 32, 32, '5b7890'),
    ('SCROLL_TRACK', 288, 0, 8, 128, '252b31'),
    ('SCROLL_THUMB', 296, 0, 8, 32, 'c49a55'),
    ('INPUT', 304, 0, 160, 20, '181c20'),
    ('BUTTON_NORMAL', 304, 20, 80, 20, '45535f'),
    ('BUTTON_PRESSED', 384, 20, 80, 20, '2d3942'),
    ('POPUP', 256, 160, 240, 160, '252b31'),
    ('STATE_IDLE', 256, 320, 120, 8, '52606c'),
    ('STATE_RUN', 256, 328, 120, 8, '5c9b63'),
    ('STATE_SLEEP', 256, 336, 120, 8, '6a71a5'),
    ('STATE_FAULT', 256, 344, 120, 8, 'ad5555'),
)
OUTPUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/create_biotech/textures/gui/factory_panel_monitor.png"


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def atlas_png():
    pixels = bytearray(bytes.fromhex("20252bff") * (SIZE * SIZE))
    for name, x, y, width, height, color in REGIONS:
        assert 0 <= x < x + width <= SIZE and 0 <= y < y + height <= SIZE, name
        row = bytes.fromhex(color + "ff") * width
        for yy in range(y, y + height):
            start = (yy * SIZE + x) * 4
            pixels[start:start + width * 4] = row
    raw = b"".join(b"\x00" + pixels[y * SIZE * 4:(y + 1) * SIZE * 4] for y in range(SIZE))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(atlas_png())
    print(OUTPUT)
