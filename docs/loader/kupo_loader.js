// ---- Optional hook loader (paste at the END of source/exvius/android_test.js) ----
// Lets an allowed add-on tool send extra Frida script text to the running game, so the game APK
// does not need to be re-signed. Listens on 127.0.0.1 (this phone only), port 27334.
// LOCK: a script runs ONLY if (1) it has a valid RSA-2048 / SHA-256 signature from an allowed tool,
// (2) its signed first line names that same tool, and (3) it has not expired (and expires within about 400 days).
// This file holds PUBLIC keys only (safe to publish).
// To allow a tool: add one line to ALLOWED. To remove a tool: delete its line.
// Keep the game's Frida Gadget config at "type": "script" (never "listen"): listen mode skips this lock.
// As a safety net the loader also refuses to start if something answers on Frida's default port 27042.
// Remove these lines at any time; nothing else depends on them.
;(function () {
  var TAG = 'KUPO_LOADER', alw = null;
  function say(m) {   // visible in logcat (tag KUPO_LOADER) and the Frida console
    try { console.log(TAG + ' ' + m); } catch (e) { }
    try {
      var ll = Process.findModuleByName('liblog.so'), f = ll && ll.findExportByName('__android_log_write');
      if (f) { if (!alw) alw = new NativeFunction(f, 'int', ['int', 'pointer', 'pointer']); alw(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(String(m))); }
    } catch (e) { }
  }
  try {
    var PORT = 27334, MAX = 1024 * 1024, TIMEOUT_MS = 3000, MAX_CONN = 2, KEY_BITS = 2048, MAX_EXP_DAYS = 400, open = 0, loaded = 0;
    var GAME_VERSION = null;   // optional: set to the game version text (e.g. '9.0.0-offline.24'); scripts that name another game version are refused
    var ALLOWED = [
      ['FFBE Kupo Forge', 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwgj2jRpsRXr9wdKA5ZW3yf+T2oDdd/fCeU7kQVxNGhBjATL/WbfXHMNuMzwl+ZSjMjI9H0xaFyq8mj8eKrBuCz3Ijj6k51249GTEojvn7r4IaTBj2ubOj/aLt+iJQx4hfq1ZHeBEAa/7F0nVTX6oSijVlfwgwi3PgRLhRt9EVhy5dHkLt+At98SN9UFFEeJ6LzICjLrVMud1l3dnwcQAiiOIA4OVmtfD5wJKOGUNwV04la7Zmhib4bhoZutQEviPlBn+hazTu3l3+je05FY2FTYWtfl4/E49vg601PodEiWIl9OOKAv6cm79CYIBfTnxZkyo35aE+55oyKXmCZAY2wIDAQAB']
      // ['Other tool name', 'its public key (base64 of the DER file)'],
    ];
    var game = Process.findModuleByName('libgame.so');
    function ex(n) { var a = game && game.findExportByName(n); if (!a) throw new Error('missing ' + n); return a; }
    var d2i = new NativeFunction(ex('d2i_PUBKEY'), 'pointer', ['pointer', 'pointer', 'long']);
    var keyBits = new NativeFunction(ex('EVP_PKEY_bits'), 'int', ['pointer']);
    var mdNew = new NativeFunction(ex('EVP_MD_CTX_new'), 'pointer', []);
    var mdFree = new NativeFunction(ex('EVP_MD_CTX_free'), 'void', ['pointer']);
    var sha256 = new NativeFunction(ex('EVP_sha256'), 'pointer', []);
    var vInit = new NativeFunction(ex('EVP_DigestVerifyInit'), 'int', ['pointer', 'pointer', 'pointer', 'pointer', 'pointer']);
    var vUpd = new NativeFunction((game.findExportByName('EVP_DigestVerifyUpdate') || ex('EVP_DigestUpdate')), 'int', ['pointer', 'pointer', 'size_t']);
    var vFin = new NativeFunction(ex('EVP_DigestVerifyFinal'), 'int', ['pointer', 'pointer', 'size_t']);
    var digest = null;   // only used for the log line, so it is optional
    try { digest = new NativeFunction(ex('EVP_Digest'), 'int', ['pointer', 'size_t', 'pointer', 'pointer', 'pointer', 'pointer']); } catch (e) { say('EVP_Digest missing: logs will not show the script hash'); }
    function b64(s) {
      var k = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', o = [], b = 0, n = 0;
      for (var i = 0; i < s.length; i++) { var v = k.indexOf(s.charAt(i)); if (v < 0) continue; b = (b << 6) | v; n += 6; if (n >= 8) { n -= 8; o.push((b >> n) & 255); } }
      return new Uint8Array(o);
    }
    function bytesToMem(u8) { var m = Memory.alloc(u8.length || 1); m.writeByteArray(u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.length)); return m; }
    // parse every key ONCE here; weak or broken keys are skipped (and said so)
    var KEYS = [];
    ALLOWED.forEach(function (a) {
      try {
        var der = b64(a[1]), kb = bytesToMem(der), pp = Memory.alloc(8);
        pp.writePointer(kb);
        var k = d2i(NULL, pp, der.length);
        if (k.isNull()) { say('key for "' + a[0] + '" rejected: cannot parse'); return; }
        var bits = keyBits(k);
        if (bits !== KEY_BITS) { say('key for "' + a[0] + '" rejected: ' + bits + ' bits (RSA 2048 only)'); return; }
        KEYS.push({ name: a[0], key: k });
      } catch (e) { say('key for "' + a[0] + '" rejected: ' + e); }
    });
    say('ready, allowed tools: ' + KEYS.map(function (k) { return k.name; }).join(', '));
    // returns the tool name whose key signed data, or null
    function verify(data, sig) {
      var dbuf = bytesToMem(data), sbuf = bytesToMem(sig);
      for (var i = 0; i < KEYS.length; i++) {
        var ctx = NULL;
        try {
          ctx = mdNew();
          if (vInit(ctx, NULL, sha256(), NULL, KEYS[i].key) === 1 && vUpd(ctx, dbuf, data.length) === 1 && vFin(ctx, sbuf, sig.length) === 1) return KEYS[i].name;
        } catch (e) { } finally { if (!ctx.isNull()) mdFree(ctx); }
      }
      return null;
    }
    function sha256hex(data) {
      try {
        if (!digest) return '?';
        var out = Memory.alloc(64), len = Memory.alloc(4);
        if (digest(bytesToMem(data), data.length, out, len, sha256(), NULL) !== 1) return '?';
        var h = '', n = len.readU32();
        for (var i = 0; i < n; i++) h += ('0' + out.add(i).readU8().toString(16)).slice(-2);
        return h;
      } catch (e) { return '?'; }
    }
    function utf8(u8) { var m = bytesToMem(u8); return m.readUtf8String(u8.length); }
    // wire format: 4 bytes big-endian signature length (must be 256), signature, script text (UTF-8).
    // The script's FIRST LINE is signed with it:  //KUPO tool=<tool name> game=<version or *> exp=<unix seconds>
    function check(body, tool) {
      var text = utf8(body), nl = text.indexOf('\n'), head = nl < 0 ? text : text.substr(0, nl);
      var m = /^\/\/KUPO tool=(.+?) game=(\S+) exp=(\d+)\s*$/.exec(head);
      if (!m) throw new Error('missing signed header');
      if (m[1] !== tool) throw new Error('header tool does not match the signing key');
      var now = Date.now() / 1000, exp = parseInt(m[3], 10);
      if (now > exp) throw new Error('script expired');
      if (exp - now > MAX_EXP_DAYS * 86400) throw new Error('expiry too far ahead (max ' + MAX_EXP_DAYS + ' days)');
      if (GAME_VERSION !== null && m[2] !== '*' && m[2] !== GAME_VERSION) throw new Error('script is for game ' + m[2]);
      return text;
    }
    function handle(conn) {
      var chunks = [], total = 0, done = false;
      function finish(reply) {
        if (done) return; done = true; open--;
        try { conn.output.writeAll(new TextEncoder().encode(reply).buffer).catch(function () { }).then(function () { try { conn.close(); } catch (e) { } }); } catch (e) { try { conn.close(); } catch (x) { } }
      }
      var timer = setTimeout(function () { finish('refused timeout\n'); }, TIMEOUT_MS);
      function more() {
        return conn.input.read(65536).then(function (b) {
          if (done || b.byteLength === 0) return;
          total += b.byteLength;
          if (total > MAX) throw new Error('too large');
          chunks.push(b);
          return more();
        });
      }
      more().then(function () {
        if (done) return;
        clearTimeout(timer);
        var all = new Uint8Array(total), off = 0, text, tool;
        chunks.forEach(function (c) { all.set(new Uint8Array(c), off); off += c.byteLength; });
        try {
          if (total < 5 + 256) throw new Error('short');
          var sl = ((all[0] << 24) | (all[1] << 16) | (all[2] << 8) | all[3]) >>> 0;
          if (sl !== 256) throw new Error('bad signature length');
          var sig = all.subarray(4, 260), body = all.subarray(260);
          tool = verify(body, sig);
          if (!tool) throw new Error('signature rejected');
          text = check(body, tool);
        } catch (e) { say('refused: ' + e); return finish('refused ' + e + '\n'); }
        var hash = sha256hex(all.subarray(260));
        try { (0, eval)(text); loaded++; say('ran script from "' + tool + '" sha256 ' + hash); finish('ok loaded ' + loaded + '\n'); }
        catch (e) { say('script from "' + tool + '" sha256 ' + hash + ' threw: ' + e); finish('script error ' + e + '\n'); }
      }).catch(function (e) { say('refused: ' + e); finish('refused ' + e + '\n'); });
    }
    function loop(l) {
      l.accept().then(function (c) {
        if (open >= MAX_CONN) { try { c.close(); } catch (e) { } } else { open++; handle(c); }
        loop(l);
      }).catch(function () { setTimeout(function () { loop(l); }, 1000); });
    }
    function start() {
      Socket.listen({ family: 'ipv4', host: '127.0.0.1', port: PORT, backlog: 4 }).then(function (l) { say('listening on 127.0.0.1:' + PORT); loop(l); })
        .catch(function (e) { say('CANNOT LISTEN on port ' + PORT + ' (another app may hold it): ' + e); });
    }
    // safety net: if Frida's default listen port answers, the gadget is in listen mode and this lock would be bypassed
    Socket.connect({ family: 'ipv4', host: '127.0.0.1', port: 27042 })
      .then(function (c) { try { c.close(); } catch (e) { } say('REFUSING TO START: something answers on 27042 (gadget in listen mode?). Fix the gadget config to "type": "script".'); })
      .catch(start);
  } catch (e) { say('loader off: ' + e); }
})();
