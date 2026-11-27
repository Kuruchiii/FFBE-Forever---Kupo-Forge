"""Write a tiny resources.arsc (no aapt needed). It holds only the launcher icon:
  0x7f010000 mipmap/ic_launcher  nodpi -> res/mipmap-nodpi/ic_launcher.png (old launchers)
                                 anydpi-v26 -> res/mipmap-anydpi-v26/ic_launcher.xml (adaptive icon)
  0x7f010001 mipmap/ic_fg        nodpi -> res/mipmap-nodpi/ic_fg.png (adaptive foreground)
  0x7f020000 color/ic_bg         #ffffffff (adaptive background)
Layout follows frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h."""
import struct, sys

PKG_ID, PKG_NAME = 0x7f, 'com.ffbeforever.saveedit'
NODPI, ANYDPI = 0xffff, 0xfffe
TYPE_STRING, TYPE_ARGB8 = 0x03, 0x1c
CONFIG_DENSITY, CONFIG_VERSION = 0x0100, 0x0400

def pool(strings):
    offs, data = [], b''
    for s in strings:
        offs.append(len(data))
        u = s.encode('utf-16-le')
        data += struct.pack('<H', len(u) // 2) + u + b'\0\0'
    data += b'\0' * ((4 - len(data) % 4) % 4)
    start = 28 + 4 * len(strings)
    return (struct.pack('<HHIIIIII', 0x0001, 28, start + len(data), len(strings), 0, 0, start, 0)
            + b''.join(struct.pack('<I', o) for o in offs) + data)

def config(density=0, sdk=0):
    c = bytearray(64)
    struct.pack_into('<I', c, 0, 64)
    struct.pack_into('<H', c, 14, density)
    struct.pack_into('<H', c, 24, sdk)
    return bytes(c)

def type_chunk(tid, count, cfg, entries):
    """entries: {index: (keyIndex, dataType, data)}"""
    offs, body = [], b''
    for i in range(count):
        if i in entries:
            k, t, d = entries[i]
            offs.append(len(body))
            body += struct.pack('<HHI', 8, 0, k) + struct.pack('<HBBI', 8, 0, t, d)
        else:
            offs.append(0xffffffff)
    hsize = 20 + len(cfg)
    start = hsize + 4 * count
    return (struct.pack('<HHIBBHII', 0x0201, hsize, start + len(body), tid, 0, 0, count, start) + cfg
            + b''.join(struct.pack('<I', o) for o in offs) + body)

def spec_chunk(tid, flags, ntypes):
    return struct.pack('<HHIBBHI', 0x0202, 16, 16 + 4 * len(flags), tid, 0, ntypes, len(flags)) + \
        b''.join(struct.pack('<I', f) for f in flags)

def build():
    values = ['res/mipmap-nodpi/ic_launcher.png', 'res/mipmap-anydpi-v26/ic_launcher.xml', 'res/mipmap-nodpi/ic_fg.png']
    types = ['mipmap', 'color']
    keys = ['ic_launcher', 'ic_fg', 'ic_bg']
    chunks = b''
    # type 1: mipmap (2 entries)
    chunks += spec_chunk(1, [CONFIG_DENSITY | CONFIG_VERSION, 0], 0)
    chunks += type_chunk(1, 2, config(NODPI), {0: (0, TYPE_STRING, 0), 1: (1, TYPE_STRING, 2)})
    chunks += type_chunk(1, 2, config(ANYDPI, 26), {0: (0, TYPE_STRING, 1)})
    # type 2: color (1 entry)
    chunks += spec_chunk(2, [0], 0)
    chunks += type_chunk(2, 1, config(), {0: (2, TYPE_ARGB8, 0xffffffff)})
    tpool, kpool = pool(types), pool(keys)
    name = PKG_NAME.encode('utf-16-le').ljust(256, b'\0')
    hsize = 288
    pkg_size = hsize + len(tpool) + len(kpool) + len(chunks)
    pkg = (struct.pack('<HHII', 0x0200, hsize, pkg_size, PKG_ID) + name
           + struct.pack('<IIIII', hsize, len(types), hsize + len(tpool), len(keys), 0)
           + tpool + kpool + chunks)
    vpool = pool(values)
    body = vpool + pkg
    return struct.pack('<HHII', 0x0002, 12, 12 + len(body), 1) + body

if __name__ == '__main__':
    open(sys.argv[1], 'wb').write(build())
