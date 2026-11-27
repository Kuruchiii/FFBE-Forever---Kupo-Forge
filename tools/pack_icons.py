"""Packs a folder of game icons (unit_icon_*.png, item_*.png, global_*.png, ability_*.png) into assets/icons.bin.
Format: 'FFIC', u32 count, then per icon: u8 len, name (utf8), u32 offset, u32 size; then the image bytes (WebP).
Names: units -> u_<first 8 digits of the id> (highest star kept), everything else -> file name without .png.
usage: python3 pack_icons.py <folder> <out icons.bin>"""
import io, os, re, struct, sys
from PIL import Image
src, out = sys.argv[1:3]
units = {}
others = {}
for f in sorted(os.listdir(src)):
    m = re.fullmatch(r'unit_icon_(\d{9})\.png', f)
    if m:
        k = 'u_' + m.group(1)[:8]
        if k not in units or m.group(1) > units[k][0]:
            units[k] = (m.group(1), f)
    elif re.fullmatch(r'(item|global_equip|global_item|ability|global_ability|global_materia|limit)_\d+\.png', f):
        others[f[:-4]] = f
def enc(f, size):
    im = Image.open(os.path.join(src, f)).convert('RGBA')
    b = io.BytesIO()
    if size:
        im = im.resize((size, size), Image.LANCZOS)
        im.save(b, 'WEBP', quality=85, method=3)
    else:
        im.save(b, 'WEBP', lossless=True, method=3)
    return b.getvalue()
items, bad = [], []
for k, v in sorted(units.items()):
    try: items.append((k, enc(v[1], 0)))
    except Exception: bad.append(v[1])
for k, f in sorted(others.items()):
    try: items.append((k, enc(f, 64)))
    except Exception: bad.append(f)
print(len(bad), 'unreadable files skipped:', bad[:10])
index = b''
data = b''
base = 8 + sum(1 + len(k.encode()) + 8 for k, _ in items)
for k, d in items:
    kb = k.encode()
    index += bytes([len(kb)]) + kb + struct.pack('<II', base + len(data), len(d))
    data += d
open(out, 'wb').write(b'FFIC' + struct.pack('<I', len(items)) + index + data)
print(len(units), 'units,', len(others), 'others,', (len(index) + len(data)) // 1024, 'KB')
