"""Draws the mod's logo (the in-game Mods list and CurseForge): a controller wired to four connectors, in the
blocks' own colors, as 40x40 pixel art scaled to 400x400. Needs Pillow. Run: python tools/logo.py [out.png]"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/reticulum.png"
N = 40
im = Image.new("RGBA", (N, N), (20, 22, 28, 255))
d = ImageDraw.Draw(im)
def box(x0, y0, x1, y1, c): d.rectangle([x0, y0, x1, y1], fill=c)
# faint background grid: a net
for i in range(1, N, 4):
    box(i, 0, i, N - 1, (27, 30, 38, 255)); box(0, i, N - 1, i, (27, 30, 38, 255))
DARK, GRAY, BLUE = (14, 16, 20, 255), (92, 98, 110, 255), (120, 190, 220, 255)
A, B, M = 7, 31, 19  # cable cores at 7-8, 31-32, 19-20
H = [(A, B + 1, A), (A, B + 1, B), (A, B + 1, M)]
V = [(A, A, B + 1), (B, A, B + 1), (M, A, B + 1)]
# each layer for every cable before the next, so crossings join instead of cutting each other
for w, c in [(2, DARK), (1, GRAY), (0, BLUE)]:
    for x0, x1, y in H: box(x0, y - w, x1, y + 1 + w, c)
    for x, y0, y1 in V: box(x - w, y0, x + 1 + w, y1, c)
def connector(cx, cy):  # 10x10 centred on a core
    x0, y0 = cx - 4, cy - 4
    box(x0 - 1, y0 - 1, x0 + 10, y0 + 10, DARK)
    box(x0, y0, x0 + 9, y0 + 9, (40, 64, 92, 255)); box(x0 + 1, y0 + 1, x0 + 8, y0 + 8, (70, 110, 150, 255))
    box(x0 + 3, y0 + 3, x0 + 6, y0 + 6, (150, 220, 255, 255)); box(x0 + 4, y0 + 4, x0 + 5, y0 + 5, (70, 110, 150, 255))
for c in [(A, A), (B, A), (A, B), (B, B)]:
    connector(*c)
# controller in the middle
x0 = M - 6
box(x0 - 1, x0 - 1, x0 + 14, x0 + 14, DARK)
box(x0, x0, x0 + 13, x0 + 13, (90, 100, 120, 255))
box(x0 + 2, x0 + 2, x0 + 11, x0 + 11, (40, 44, 54, 255))
for i, c in enumerate([(80, 220, 120, 255), (120, 190, 240, 255), (240, 200, 80, 255)]):
    bx = x0 + 3 + i * 3
    box(bx, x0 + 4, bx + 1, x0 + 9, c)
im.resize((400, 400), Image.NEAREST).save(sys.argv[1] if len(sys.argv) > 1 else OUT)
