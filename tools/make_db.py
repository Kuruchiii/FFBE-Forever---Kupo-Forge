"""Build assets/db.json for the editor.
Units/items: public GL datamine (aEnigmatic/ffbe). Banners: the game's own gacha_community.json
(FFBE Forever's 369 historical banners). Seasons follow the game's summon screen
(gacha_archive.py): Season 1 until 2017-11-15, 2 from 2017-11-16, 3 from 2020-01-30, 4 from 2021-11-18.
usage: python3 make_db.py <data dir> <out db.json>"""
import json, sys
src, out = sys.argv[1:3]
units = json.load(open(f'{src}/units.json', encoding='utf-8'))
lbs = json.load(open(f'{src}/limitbursts.json', encoding='utf-8'))
equip = json.load(open(f'{src}/equipment.json', encoding='utf-8'))
mat = json.load(open(f'{src}/materia.json', encoding='utf-8'))
community = json.load(open(f'{src}/gacha_community.json', encoding='utf-8'))
KIND = {'EQUIP': 21, 'MATERIA': 22}
STARTS = ('2017-11-16', '2020-01-30', '2021-11-18')

# id of any star -> base id
to_base = {}
for base, u in units.items():
    for eid in (u.get('entries') or {}):
        to_base[eid] = base

banners = sorted(community['banners'], key=lambda b: str(b.get('release') or '9999'))
db_banners, first = [], {}
for i, b in enumerate(banners):
    rel = str(b.get('release') or '')[:10]
    season = 1 + sum(rel >= s for s in STARTS)
    db_banners.append([rel, b.get('name') or b.get('id'), season])
    pool = b.get('pool') or []
    ids = []
    if isinstance(pool, dict):
        for t in pool.get('tiers') or []:
            ids += [str(x) for x in (t.get('units') or [])]
    else:
        ids += [str(p.get('unit_id')) for p in pool if isinstance(p, dict)]
    ids += [str(r.get('id')) for r in (b.get('reward_pool') or []) if isinstance(r, dict) and r.get('kind') == 10]
    ids += [str(x) for x in ((b.get('community') or {}).get('unit_ids') or [])]
    for uid in ids:
        base = to_base.get(uid)
        if base and base not in first:
            first[base] = i

db_units = []
for base, u in units.items():
    ents = u.get('entries') or {}
    stars = {}
    for eid, e in ents.items():
        r = e.get('rarity')
        if not isinstance(r, int) or eid[:-1] != base[:-1] or eid[-1] != str(r):
            continue          # Neo Vision / Brave Shift rows use other ids
        lb = lbs.get(str(e.get('limitburst_id'))) if e.get('limitburst_id') else None
        lbmax = len(lb.get('levels') or []) if lb else 0
        stars[str(r)] = [eid, lbmax, e.get('exp_pattern') or 0]
    if not stars:
        continue
    tmr = u.get('TMR') or None
    t = [KIND[tmr[0]], int(tmr[1])] if tmr and tmr[0] in KIND else [0, 0]
    st = u.get('sTMR') or None
    s2 = [KIND[st[0]], int(st[1])] if st and st[0] in KIND else [0, 0]
    db_units.append({'b': base, 'n': u.get('name') or base, 's': stars, 't': t, 'st': s2,
                     'fb': first.get(base, -1)})
# Units with no banner in the data: estimate a banner from release order (compendium id).
# Place the unit between its nearest units that do have a banner and take the banner nearest to the mid date.
import bisect, datetime
def cid(b):
    return min(int(v.get('compendium_id') or 0) for v in units[b]['entries'].values()) or 0
def day(i):
    return datetime.date.fromisoformat(db_banners[i][0]).toordinal()
known = sorted((cid(u['b']), day(u['fb'])) for u in db_units if u['fb'] >= 0 and cid(u['b']))
kc = [k[0] for k in known]
bdays = [day(i) for i in range(len(db_banners))]
est = 0
for u in db_units:
    if u['fb'] >= 0 or not cid(u['b']):
        continue
    j = bisect.bisect(kc, cid(u['b']))
    if j == 0 or j == len(known):
        continue                     # older or newer than every dated unit: leave as no banner
    mid = (known[j - 1][1] + known[j][1]) // 2
    u['fb'] = min(range(len(bdays)), key=lambda i: abs(bdays[i] - mid))
    u['fe'] = 1
    est += 1
print(est, 'units with an estimated banner')
eq = {k: [v['name'], v.get('slot') or '', v.get('type') or '', v.get('icon') or ''] for k, v in equip.items() if v.get('name')}
mi = {k: v['icon'] for k, v in mat.items() if v.get('name') and v.get('icon')}
ma = {k: v['name'] for k, v in mat.items() if v.get('name')}
db = {'units': db_units, 'eq': eq, 'ma': ma, 'mi': mi, 'banners': db_banners}
json.dump(db, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print(len(db_units), 'units', len(eq), 'equipment', len(ma), 'materia', len(db_banners), 'banners',
      len(first), 'units found on a banner')
