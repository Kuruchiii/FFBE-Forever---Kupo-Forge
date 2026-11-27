"""Encode a small AndroidManifest.xml into Android's binary XML (no aapt needed).
Attributes in the android namespace need their framework resource id (ATTR below)."""
import struct, sys
import xml.etree.ElementTree as ET

ANDROID = 'http://schemas.android.com/apk/res/android'
ATTR = {'theme': 0x01010000, 'label': 0x01010001, 'icon': 0x01010002, 'name': 0x01010003,
        'exported': 0x01010010, 'configChanges': 0x0101001f, 'minSdkVersion': 0x0101020c,
        'versionCode': 0x0101021b, 'versionName': 0x0101021c, 'windowSoftInputMode': 0x0101022b,
        'targetSdkVersion': 0x01010270, 'foregroundServiceType': 0x01010599,
        'value': 0x01010024, 'drawable': 0x01010199}
INT_ATTRS = {'versionCode', 'minSdkVersion', 'targetSdkVersion'}
HEX_ATTRS = {'configChanges', 'windowSoftInputMode', 'foregroundServiceType'}
BOOL_ATTRS = {'exported'}

def encode(src):
    root = ET.parse(src).getroot()
    # Collect attribute names: mapped (android:) first, in res-id order, then the rest.
    mapped, other = [], ['android', ANDROID]
    def walk(e):
        for k in e.attrib:
            if k.startswith('{' + ANDROID + '}'):
                n = k.split('}')[1]
                if n not in ATTR: raise SystemExit('no resource id for android:' + n)
                if n not in mapped: mapped.append(n)
            elif k not in other: other.append(k)
        for c in e: walk(c)
    walk(root)
    mapped.sort(key=lambda n: ATTR[n])
    strings = list(mapped) + other
    def sid(s):
        if s not in strings: strings.append(s)
        return strings.index(s)
    body = []   # chunks after the pool, built lazily since strings may grow
    events = []
    def emit(e):
        attrs = []
        for k, v in e.attrib.items():
            if k.startswith('{' + ANDROID + '}'):
                n = k.split('}')[1]; ns = ANDROID; rid = ATTR[n]
            else:
                n = k; ns = None; rid = None
            if v.startswith('@0x'):
                typ, data, raw = 0x01, int(v[3:], 16), None
            elif n in BOOL_ATTRS:
                typ, data, raw = 0x12, 0xffffffff if v == 'true' else 0, None
            elif n in INT_ATTRS:
                typ, data, raw = 0x10, int(v), None
            elif n in HEX_ATTRS:
                typ, data, raw = 0x11, int(v, 16), None
            else:
                typ, data, raw = 0x03, None, v
            attrs.append((rid if rid is not None else 0xffffffff, ns, n, typ, data, raw))
        attrs.sort(key=lambda a: a[0])
        events.append(('start', e.tag, attrs))
        for c in e: emit(c)
        events.append(('end', e.tag))
    emit(root)
    # Resolve all string ids now.
    for ev in events:
        sid(ev[1])
        if ev[0] == 'start':
            for a in ev[2]:
                sid(a[2])
                if a[5] is not None: sid(a[5])
    NONE = 0xffffffff
    out = []
    line = 1
    out.append(struct.pack('<HHIIIII', 0x0100, 16, 24, line, NONE, sid('android'), sid(ANDROID)))
    for ev in events:
        if ev[0] == 'start':
            attrs = ev[2]
            ab = b''
            for rid, ns, n, typ, data, raw in attrs:
                nsid = sid(ANDROID) if ns else NONE
                if typ == 0x03:
                    ab += struct.pack('<IIIHBBI', nsid, sid(n), sid(raw), 8, 0, 0x03, sid(raw))
                else:
                    ab += struct.pack('<IIIHBBI', nsid, sid(n), NONE, 8, 0, typ, data)
            size = 16 + 20 + len(ab)
            out.append(struct.pack('<HHIII', 0x0102, 16, size, line, NONE) +
                       struct.pack('<IIHHHHHH', NONE, sid(ev[1]), 20, 20, len(attrs), 0, 0, 0) + ab)
        else:
            out.append(struct.pack('<HHIIIII', 0x0103, 16, 24, line, NONE, NONE, sid(ev[1])))
        line += 1
    out.append(struct.pack('<HHIIIII', 0x0101, 16, 24, line, NONE, sid('android'), sid(ANDROID)))
    # String pool (UTF-16).
    offs, data = [], b''
    for s in strings:
        offs.append(len(data))
        u = s.encode('utf-16-le')
        data += struct.pack('<H', len(u) // 2) + u + b'\0\0'
    data += b'\0' * ((4 - len(data) % 4) % 4)
    hdr = 28
    start = hdr + 4 * len(strings)
    pool = struct.pack('<HHIIIIII', 0x0001, hdr, start + len(data), len(strings), 0, 0, start, 0)
    pool += b''.join(struct.pack('<I', o) for o in offs) + data
    resmap = struct.pack('<HHI', 0x0180, 8, 8 + 4 * len(mapped)) + b''.join(struct.pack('<I', ATTR[n]) for n in mapped)
    body = pool + resmap + b''.join(out)
    return struct.pack('<HHI', 0x0003, 8, 8 + len(body)) + body

if __name__ == '__main__':
    open(sys.argv[2], 'wb').write(encode(sys.argv[1]))
