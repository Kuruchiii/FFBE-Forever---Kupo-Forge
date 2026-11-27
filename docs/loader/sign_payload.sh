#!/bin/sh
# usage: sh sign_payload.sh script.js out.bin ["Tool name"] [days valid, default 90, max 120] [game version or *] [keep: 1 = ask the newer game build to save the script]
# Needs private/kupo_sign_private.pem (keep secret, sign OFFLINE at build time, never ship it).
set -e
D=$(cd "$(dirname "$0")" && pwd)
TOOL=${3:-FFBE Kupo Forge}; DAYS=${4:-90}; GAME=${5:-*}; KEEP=${6:-}
EXP=$(( $(date +%s) + DAYS * 86400 ))
python3 - "$1" /tmp/ks_body.js "$TOOL" "$GAME" "$EXP" "$KEEP" <<'PY'
import sys
b=open(sys.argv[1],'rb').read()
k=' keep=1' if len(sys.argv)>6 and sys.argv[6]=='1' else ''
head=('//KUPO tool=%s game=%s exp=%s%s\n'%(sys.argv[3],sys.argv[4],sys.argv[5],k)).encode()
open(sys.argv[2],'wb').write(head+b)
PY
openssl dgst -sha256 -sign "$D/private/kupo_sign_private.pem" -out /tmp/ks.sig /tmp/ks_body.js
python3 - /tmp/ks_body.js "$2" <<'PY'
import sys,struct
s=open('/tmp/ks.sig','rb').read(); b=open(sys.argv[1],'rb').read()
assert len(s)==256
open(sys.argv[2],'wb').write(struct.pack('>I',len(s))+s+b)
PY
echo "wrote $2 (tool $TOOL, expires in $DAYS days)"
