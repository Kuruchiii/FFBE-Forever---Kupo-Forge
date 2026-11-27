
/*FFBE-SPEED-PATCH v1*/
;(function () {
  // Game speed for the FFBE Forever save editor's floating button.
  // cocos2d::Scheduler::update(float) scales every frame by this->_timeScale (at +0x24 in the
  // pinned GL9 libgame.so, the same field the game's own TEST 10x build writes).
  // A command arrives on 127.0.0.1:27333 as text, e.g. "3" or "1.5". Reply: "OK <speed> <state> <fg>".
  var PORT = 27333;
  var speed = 1;
  var reset = false;
  globalThis.FFBE_SETSPEED = function (v) { if (v >= 0.1 && v <= 20) { if (v === 1 && speed !== 1) reset = true; speed = v; } };
  globalThis.FFBE_GETSPEED = function () { return speed; };
  var state = 'waiting';
  var lastFrame = 0;   // time of the newest game frame; frames stop when the game is not on screen
  var fg = 1;          // 1 while the game is on screen; the editor app shows its button only then
  function log(m) { try { console.log('[KUPOFORGE] FFBE_SPEED ' + m); } catch (e) {} }
  function hook() {
    var game = Process.findModuleByName('libgame.so');
    if (!game) return false;
    try {
      var update = game.findExportByName('_ZN7cocos2d9Scheduler6updateEf');
      var ctor = game.findExportByName('_ZN7cocos2d9SchedulerC2Ev');
      if (!update || !ctor || ctor.add(0x1c).readU32() !== 0x52a7f008 || ctor.add(0x20).readU32() !== 0xb9002668) {
        state = 'layout-changed';
        log('scheduler layout not recognised; speed disabled');
        return true;
      }
      // Foreground flag: AppDelegate gets these calls when the game goes to or comes back from the background.
      try {
        var bg = game.findExportByName('_ZN11AppDelegate29applicationDidEnterBackgroundEv');
        var fgf = game.findExportByName('_ZN11AppDelegate30applicationWillEnterForegroundEv');
        if (bg) Interceptor.attach(bg, { onEnter: function () { fg = 0; } });
        if (fgf) Interceptor.attach(fgf, { onEnter: function () { fg = 1; } });
      } catch (e2) { log('foreground hook failed: ' + e2); }
      Interceptor.attach(update, {
        onEnter: function (args) {
          lastFrame = Date.now();
          if (speed !== 1) {
            args[0].add(0x24).writeFloat(speed);
          } else if (reset) {
            args[0].add(0x24).writeFloat(1);
            reset = false;
          }
        }
      });
      state = 'ready';
      log('hook ready');
    } catch (e) {
      state = 'error';
      log('hook failed: ' + e);
    }
    return true;
  }
  function waitForGame() { if (!hook()) setTimeout(waitForGame, 500); }
  waitForGame();

  function text(buf) {
    var b = new Uint8Array(buf), s = '';
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return s;
  }
  function bytes(s) {
    var a = [];
    for (var i = 0; i < s.length; i++) a.push(s.charCodeAt(i) & 0xff);
    return a;
  }

  // Read-only file survey for the editor's "List game image files" button (command "ls").
  // Walks the game's own folders with libc and returns a short text report. Nothing is written or changed.
  function survey() {
    var out = [];
    try {
      var libc = Process.getModuleByName('libc.so');
      var opendir = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
      var readdir = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
      var closedir = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
      var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
      var sbuf = Memory.alloc(256);
      var pkg = '';
      try {
        var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
        var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
        var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
        var fh = fopen(Memory.allocUtf8String('/proc/self/cmdline'), Memory.allocUtf8String('r'));
        if (!fh.isNull()) {
          var cb = Memory.alloc(512);
          fread(cb, 1, 511, fh);
          fclose(fh);
          pkg = cb.readUtf8String().split(':')[0];
        }
      } catch (e) { out.push('package name lookup failed: ' + e); }
      out.push('game package: ' + (pkg || '(unknown)'));
      var roots = [];
      if (pkg) roots.push('/data/data/' + pkg, '/storage/emulated/0/Android/data/' + pkg, '/storage/emulated/0/Android/obb/' + pkg,
        '/storage/emulated/0/' + pkg);
      var big = [];
      var IMG = /\.(png|jpg|jpeg|webp|pvr|ccz|astc|etc|etc1|ktx|atlas|plist|dds|tga|bmp|gif)$/i;
      var exts = {}, dirs = {}, seen = 0, found = [];
      function walk(dir, depth) {
        if (depth > 9 || seen > 80000) return;
        var d = opendir(Memory.allocUtf8String(dir));
        if (d.isNull()) return;
        var subs = [];
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var name = e.add(19).readUtf8String();
          if (name === '.' || name === '..') continue;
          var path = dir + '/' + name;
          if (e.add(18).readU8() === 4) { subs.push(path); continue; }
          seen++;
          var size = 0;
          if (stat(Memory.allocUtf8String(path), sbuf) === 0) size = sbuf.add(48).readS64().valueOf();
          if (size > 200000) { big.push([size, path]); if (big.length > 400) { big.sort(function (a, b) { return b[0] - a[0]; }); big.length = 20; } }
          var m = /\.([A-Za-z0-9]+)$/.exec(name);
          var x = m ? m[1].toLowerCase() : '(none)';
          var o = exts[x] || (exts[x] = [0, 0]);
          o[0]++; o[1] += size;
          if (IMG.test(name)) {
            var r = dirs[dir] || (dirs[dir] = { n: 0, b: 0, ex: [] });
            r.n++; r.b += size;
            if (r.ex.length < 3) r.ex.push(name + ' ' + Math.round(size / 1024) + 'KB');
          }
        }
        closedir(d);
        for (var i = 0; i < subs.length; i++) walk(subs[i], depth + 1);
      }
      for (var i = 0; i < roots.length; i++) {
        var before = seen;
        walk(roots[i], 0);
        out.push('root ' + roots[i] + ': ' + (seen - before) + ' files' + (seen === before ? ' (empty or not readable)' : ''));
      }
      var el = Object.keys(exts).sort(function (a, b) { return exts[b][1] - exts[a][1]; }).slice(0, 14);
      out.push('types by size:');
      for (var k = 0; k < el.length; k++) out.push('  .' + el[k] + ' x' + exts[el[k]][0] + ' ' + Math.round(exts[el[k]][1] / 1024) + 'KB');
      var dl = Object.keys(dirs).sort(function (a, b) { return dirs[b].n - dirs[a].n; }).slice(0, 22);
      out.push('folders with image-like files:');
      for (var j = 0; j < dl.length; j++) out.push('  ' + dl[j].replace('/data/data/' + pkg, '~') + ': ' + dirs[dl[j]].n + ' files, '
        + Math.round(dirs[dl[j]].b / 1024) + 'KB; e.g. ' + dirs[dl[j]].ex.join(', '));
      if (!dl.length) out.push('  none found');
      big.sort(function (a, b) { return b[0] - a[0]; });
      out.push('largest files:');
      for (var q = 0; q < Math.min(12, big.length); q++) out.push('  ' + Math.round(big[q][0] / 1048576 * 10) / 10 + 'MB ' + big[q][1].replace('/data/data/' + pkg, '~'));
    } catch (e) {
      out.push('survey failed: ' + e);
    }
    return out.join('\n');
  }

  // Read-only deeper survey ("ls2"): sizes per package folder, file names found inside a few unit/item .cpk files
  // (name text near the start of each package) and the file list of game-assets.apk. Writes nothing.
  function survey2() {
    var out = [];
    try {
      var libc = Process.getModuleByName('libc.so');
      var opendir = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
      var readdir = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
      var closedir = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
      var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
      var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fseek = new NativeFunction(libc.getExportByName('fseek'), 'int', ['pointer', 'long', 'int']);
      var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var sbuf = Memory.alloc(256);
      var pkg = 'com.exvius.offline.forever';
      var base = '/data/data/' + pkg + '/files';
      function list(dir) {
        var r = { dirs: [], files: [] };
        var d = opendir(Memory.allocUtf8String(dir));
        if (d.isNull()) return r;
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var name = e.add(19).readUtf8String();
          if (name === '.' || name === '..') continue;
          var path = dir + '/' + name;
          if (e.add(18).readU8() === 4) { r.dirs.push(path); continue; }
          var size = 0;
          if (stat(Memory.allocUtf8String(path), sbuf) === 0) size = sbuf.add(48).readS64().valueOf();
          r.files.push([path, size]);
        }
        closedir(d);
        return r;
      }
      function sumDir(dir, depth) {
        var l = list(dir), n = l.files.length, b = 0, i;
        for (i = 0; i < l.files.length; i++) b += l.files[i][1];
        if (depth < 6) for (i = 0; i < l.dirs.length; i++) { var t = sumDir(l.dirs[i], depth + 1); n += t[0]; b += t[1]; }
        return [n, b];
      }
      function readBytes(path, off, len) {
        var f = fopen(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
        if (f.isNull()) return null;
        var buf = Memory.alloc(len);
        if (off > 0) fseek(f, off, 0);
        var n = fread(buf, 1, len, f);
        fclose(f);
        return n > 0 ? buf.readByteArray(n) : null;
      }
      function ascii(ab) {
        var u = new Uint8Array(ab), s = '', i;
        for (i = 0; i < u.length; i += 8192) s += String.fromCharCode.apply(null, u.subarray(i, Math.min(u.length, i + 8192)));
        return s;
      }
      var kb = function (v) { return Math.round(v / 1024) + 'KB'; };
      out.push('== package folders (files, size) ==');
      var tops = ['common_lang/sd', 'en/sd', 'sd', 'mst'];
      var want = [];
      for (var t = 0; t < tops.length; t++) {
        var l = list(base + '/' + tops[t]);
        for (var i = 0; i < l.dirs.length; i++) {
          var sd = sumDir(l.dirs[i], 0);
          var nm = l.dirs[i].replace(base + '/', '');
          out.push('  ' + nm + ': ' + sd[0] + ' files, ' + Math.round(sd[1] / 1048576) + 'MB');
          if (/unit|item|equip|icon|thum|chara|face|portrait|materia|ability/i.test(nm)) want.push(l.dirs[i]);
        }
      }
      out.push('== names inside packages ==');
      var shownPk = 0;
      for (var w = 0; w < want.length && shownPk < 8; w++) {
        var fl = list(want[w]).files.filter(function (x) { return /\.cpk$/i.test(x[0]) && x[1] > 8192; });
        fl.sort(function (a, b) { return a[1] - b[1]; });
        for (var k = 0; k < Math.min(2, fl.length); k++) {
          var ab = readBytes(fl[k][0], 0, 262144);
          if (!ab) continue;
          var txt = ascii(ab), re = /[A-Za-z0-9_\-\.\/]{3,80}\.(png|jpg|jpeg|pvr|ccz|ktx|astc|bmb|i|pma|atlas|plist|dat|tsv|json)/gi, m, pat = {}, total = 0;
          while ((m = re.exec(txt)) !== null) { var key = m[0].replace(/[0-9]+/g, '#'); pat[key] = (pat[key] || 0) + 1; total++; }
          var keys = Object.keys(pat).sort(function (a, b) { return pat[b] - pat[a]; }).slice(0, 8);
          out.push('  ' + fl[k][0].replace(base + '/', '') + ' (' + kb(fl[k][1]) + ', magic ' + txt.substr(0, 4) + '): '
            + total + ' names; ' + keys.map(function (x) { return x + ' x' + pat[x]; }).join(', '));
          shownPk++;
        }
      }
      if (!shownPk) out.push('  no matching package found');
      out.push('== game-assets.apk ==');
      var apk = base + '/exvius/data/static/client/game-assets.apk';
      if (stat(Memory.allocUtf8String(apk), sbuf) === 0) {
        var asz = sbuf.add(48).readS64().valueOf();
        var tail = readBytes(apk, Math.max(0, asz - 65536), 65536);
        var tu = new Uint8Array(tail), eo = -1;
        for (var z = tu.length - 22; z >= 0; z--) if (tu[z] === 0x50 && tu[z + 1] === 0x4b && tu[z + 2] === 5 && tu[z + 3] === 6) { eo = z; break; }
        if (eo >= 0) {
          var dv = new DataView(tail);
          var cnt = dv.getUint16(eo + 10, true), cdSize = dv.getUint32(eo + 12, true), cdOff = dv.getUint32(eo + 16, true);
          var cd = readBytes(apk, cdOff, cdSize);
          if (cd) {
            var cdu = new Uint8Array(cd), cdv = new DataView(cd), p = 0, groups = {}, ex = {};
            for (var e2 = 0; e2 < cnt && p + 46 <= cdu.length; e2++) {
              var nl = cdv.getUint16(p + 28, true), xl = cdv.getUint16(p + 30, true), cl = cdv.getUint16(p + 32, true);
              var name = ascii(cd.slice(p + 46, p + 46 + nl));
              var g = name.split('/').slice(0, 3).join('/');
              groups[g] = (groups[g] || 0) + 1;
              if (!ex[g]) ex[g] = name;
              p += 46 + nl + xl + cl;
            }
            out.push('  ' + cnt + ' entries');
            Object.keys(groups).sort(function (a, b) { return groups[b] - groups[a]; }).slice(0, 18)
              .forEach(function (g2) { out.push('  ' + g2 + ': ' + groups[g2] + ' e.g. ' + ex[g2]); });
          }
        } else out.push('  zip directory not found');
      } else out.push('  not found');
    } catch (e) {
      out.push('survey2 failed: ' + e);
    }
    return out.join('\n');
  }

  // File name patterns inside the game's .cpk packages ("names"): digits are replaced by # and equal names are counted,
  // so the naming of summon and monster images shows up in a short list. Writes nothing.
  function surveyNames() {
    var out = [];
    try {
      var libc = Process.getModuleByName('libc.so');
      var opendir = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
      var readdir = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
      var closedir = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
      var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fseek = new NativeFunction(libc.getExportByName('fseek'), 'int', ['pointer', 'long', 'int']);
      var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var base = '/data/data/com.exvius.offline.forever/files/';
      function list(dir, wantDir) {
        var r = [], d = opendir(Memory.allocUtf8String(base + dir));
        if (d.isNull()) return r;
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var n = e.add(19).readUtf8String(), isDir = e.add(18).readU8() === 4;
          if (wantDir ? (isDir && n !== '.' && n !== '..') : (!isDir && /\.cpk$/i.test(n))) r.push(dir + '/' + n);
        }
        closedir(d);
        return r;
      }
      function rd(path, off, len) {
        var f = fopen(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
        if (f.isNull()) return null;
        var buf = Memory.alloc(len);
        if (off > 0) fseek(f, off, 0);
        var n = fread(buf, 1, len, f);
        fclose(f);
        return n === len ? buf : null;
      }
      var files = [];
      ['common_lang/sd', 'en/sd'].forEach(function (root) { list(root, true).forEach(function (d) { list(d, false).forEach(function (f) { files.push(f); }); }); });
      var pat = {}, nc = 0, bad = 0;
      files.forEach(function (rel) {
        try {
          var path = base + rel, head = rd(path, 0, 0x800);
          if (!head) { bad++; return; }
          var hv = new DataView(head.readByteArray(0x800)), hsz = hv.getUint32(8, true);
          var hrows = cpkUtf(new Uint8Array(head.add(16).readByteArray(Math.min(hsz, 0x7e0)))), toc = 0;
          if (hrows && hrows.length) toc = hrows[0].TocOffset || 0;
          if (!toc) { bad++; return; }
          var th = rd(path, toc, 16);
          if (!th) { bad++; return; }
          var tsz = new DataView(th.readByteArray(16)).getUint32(8, true), tb = rd(path, toc + 16, tsz);
          if (!tb) { bad++; return; }
          var rows = cpkUtf(new Uint8Array(tb.readByteArray(tsz)));
          if (!rows) { bad++; return; }
          nc++;
          rows.forEach(function (r) {
            var n = r.FileName;
            if (typeof n !== 'string') return;
            var k = n.replace(/\d+/g, '#'), e = pat[k];
            if (!e) e = pat[k] = { n: 0, ex: [] };
            e.n++;
            if (e.ex.length < 2) e.ex.push(n);
          });
        } catch (e) { bad++; }
      });
      var keys = Object.keys(pat).sort(function (a, b) { return pat[b].n - pat[a].n; });
      out.push('packages read: ' + nc + ' of ' + files.length + ' (failed ' + bad + '), name patterns: ' + keys.length);
      keys.slice(0, 600).forEach(function (k) { out.push(pat[k].n + '  ' + k + '  e.g. ' + pat[k].ex.join(', ')); });
    } catch (e) { out.push('error ' + e); }
    return out.join('\n');
  }

  // Read-only package name scan ("ls3"): opens the file table of a few .cpk packages (unscrambling it if needed)
  // and lists the file names inside. Writes nothing.
  function survey3() {
    var out = [];
    try {
      var libc = Process.getModuleByName('libc.so');
      var opendir = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
      var readdir = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
      var closedir = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
      var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
      var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fseek = new NativeFunction(libc.getExportByName('fseek'), 'int', ['pointer', 'long', 'int']);
      var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var sbuf = Memory.alloc(256);
      var base = '/data/data/com.exvius.offline.forever/files/';
      function files(dir) {
        var r = [], d = opendir(Memory.allocUtf8String(base + dir));
        if (d.isNull()) return r;
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var n = e.add(19).readUtf8String();
          if (e.add(18).readU8() === 4 || !/\.cpk$/i.test(n)) continue;
          var p = base + dir + '/' + n, sz = 0;
          if (stat(Memory.allocUtf8String(p), sbuf) === 0) sz = sbuf.add(48).readS64().valueOf();
          r.push([p, sz]);
        }
        closedir(d);
        r.sort(function (a, b) { return a[1] - b[1]; });
        return r;
      }
      function rd(path, off, len) {
        var f = fopen(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
        if (f.isNull()) return null;
        var buf = Memory.alloc(len);
        if (off > 0) fseek(f, off, 0);
        var n = fread(buf, 1, len, f);
        fclose(f);
        return n > 0 ? buf.readByteArray(n) : null;
      }
      function str(u, a, b) {
        var s = '';
        for (var i = a; i < b; i += 4096) s += String.fromCharCode.apply(null, u.subarray(i, Math.min(b, i + 4096)));
        return s;
      }
      function decrypt(u) {
        var m = 0x655f, t = 0x4115, o = new Uint8Array(u.length);
        for (var i = 0; i < u.length; i++) { o[i] = u[i] ^ (m & 0xff); m = (Math.imul(m, t)) >>> 0; }
        return o;
      }
      function scan(path, sz) {
        var line = path.replace(base, '') + ' (' + Math.round(sz / 1024) + 'KB): ';
        var ab = rd(path, 0, 262144);
        if (!ab) return line + 'cannot read';
        var h = new Uint8Array(ab), toc = -1;
        for (var o = 0; o + 4 < h.length; o += 0x100) if (h[o] === 0x54 && h[o + 1] === 0x4f && h[o + 2] === 0x43 && h[o + 3] === 0x20) { toc = o; break; }
        if (toc < 0) return line + 'no TOC chunk in the first 256KB; head=' + str(h, 0, 4);
        var dv = new DataView(ab), usz = dv.getUint32(toc + 8, true);
        if (usz < 32 || usz > 16777216) return line + 'odd TOC size ' + usz;
        var tab = rd(path, toc + 16, usz);
        if (!tab) return line + 'cannot read TOC';
        var u = new Uint8Array(tab), mode = 'plain';
        if (str(u, 0, 4) !== '@UTF') { u = decrypt(u); mode = 'scrambled'; }
        if (str(u, 0, 4) !== '@UTF') return line + 'TOC not readable (head ' + u[0] + ',' + u[1] + ',' + u[2] + ',' + u[3] + ')';
        var tv = new DataView(u.buffer, u.byteOffset, u.length);
        var strOff = tv.getUint32(12, false) + 8, datOff = tv.getUint32(16, false) + 8, rows = tv.getUint32(28, false);
        var pool = str(u, strOff, Math.min(datOff, u.length)).split('\0');
        var pat = {}, k, names = 0;
        for (k = 0; k < pool.length; k++) {
          if (pool[k].length < 3) continue;
          names++;
          var key = pool[k].replace(/[0-9]+/g, '#');
          pat[key] = (pat[key] || 0) + 1;
        }
        var keys = Object.keys(pat).sort(function (a, b) { return pat[b] - pat[a]; }).slice(0, 14);
        return line + mode + ', ' + rows + ' files; ' + names + ' strings; ' + keys.map(function (x) { return x + ' x' + pat[x]; }).join(' | ')
          + '; first: ' + pool.filter(function (x) { return x.length > 2; }).slice(0, 10).join(', ');
      }
      var plan = [['common_lang/sd/character', 0], ['common_lang/sd/unit', 0], ['common_lang/sd/unit', 1], ['common_lang/sd/unit', 3],
        ['common_lang/sd/item', 0], ['common_lang/sd/item', 9], ['en/sd/unit', 0], ['en/sd/item', 0], ['common_lang/sd/visioncard', 0],
        ['common_lang/sd/skill', 0], ['common_lang/sd/series', 0]];
      for (var i = 0; i < plan.length; i++) {
        var fl = files(plan[i][0]);
        if (!fl.length) { out.push(plan[i][0] + ': none'); continue; }
        var idx = plan[i][1] === 9 ? fl.length - 1 : Math.min(plan[i][1], fl.length - 1);
        if (plan[i][1] === 3) idx = Math.floor(fl.length / 2);
        try { out.push(scan(fl[idx][0], fl[idx][1])); } catch (e) { out.push(plan[i][0] + ': scan failed ' + e); }
      }
    } catch (e) {
      out.push('survey3 failed: ' + e);
    }
    return out.join('\n');
  }
  // ---- CRI .cpk file table reading (pure functions) ----
  function cpkDecrypt(u) {
    var m = 0x655f, t = 0x4115, o = new Uint8Array(u.length);
    for (var i = 0; i < u.length; i++) { o[i] = u[i] ^ (m & 0xff); m = (Math.imul(m, t)) >>> 0; }
    return o;
  }
  function cpkAscii(u, a, b) {
    var s = '';
    for (var i = a; i < b; i += 4096) s += String.fromCharCode.apply(null, u.subarray(i, Math.min(b, i + 4096)));
    return s;
  }
  /** Reads an "@UTF" table into an array of row objects (column name -> value). u may start scrambled. */
  function cpkUtf(u0) {
    var u = u0;
    if (cpkAscii(u, 0, 4) !== '@UTF') u = cpkDecrypt(u0);
    if (cpkAscii(u, 0, 4) !== '@UTF') return null;
    var v = new DataView(u.buffer, u.byteOffset, u.length);
    var rowsOff = v.getUint16(10, false) + 8, strOff = v.getUint32(12, false) + 8, datOff = v.getUint32(16, false) + 8;
    var ncol = v.getUint16(24, false), rowW = v.getUint16(26, false), nrow = v.getUint32(28, false);
    function cstr(off) {
      var a = strOff + off, b = a;
      while (b < u.length && u[b] !== 0) b++;
      return cpkAscii(u, a, b);
    }
    function val(type, p) {
      switch (type) {
        case 0: return [v.getUint8(p), 1];
        case 1: return [v.getInt8(p), 1];
        case 2: return [v.getUint16(p, false), 2];
        case 3: return [v.getInt16(p, false), 2];
        case 4: return [v.getUint32(p, false), 4];
        case 5: return [v.getInt32(p, false), 4];
        case 6: case 7: return [v.getUint32(p, false) * 4294967296 + v.getUint32(p + 4, false), 8];
        case 8: return [v.getFloat32(p, false), 4];
        case 9: return [v.getFloat64(p, false), 8];
        case 10: return [cstr(v.getUint32(p, false)), 4];
        case 11: return [[v.getUint32(p, false), v.getUint32(p + 4, false)], 8];
      }
      return [null, 0];
    }
    var cols = [], p = 32, i;
    for (i = 0; i < ncol; i++) {
      var fl = u[p++], c = { type: fl & 15, name: null, def: null, row: (fl & 0x40) !== 0 };
      if (fl & 0x10) { c.name = cstr(v.getUint32(p, false)); p += 4; }
      if (fl & 0x20) { var d = val(c.type, p); c.def = d[0]; p += d[1]; }
      cols.push(c);
    }
    var rows = [];
    for (var r = 0; r < nrow; r++) {
      var q = rowsOff + r * rowW, o = {};
      for (i = 0; i < cols.length; i++) {
        var cc = cols[i], x = cc.def;
        if (cc.row) { var g = val(cc.type, q); x = g[0]; q += g[1]; }
        if (cc.name) o[cc.name] = x;
      }
      rows.push(o);
    }
    return rows;
  }

  /** CRILAYLA ("compressed") entry -> plain bytes (Uint8Array), or null if it does not look right. */
  var layWhy = '';
  function crilayla(src, want) {
    layWhy = '';
    if (src.length < 0x120) { layWhy = 'short' + src.length; return null; }
    if (cpkAscii(src, 0, 8) !== 'CRILAYLA') { layWhy = 'nolayla:' + src[0] + ',' + src[1] + ',' + src[2] + ',' + src[3]; return null; }
    var dv = new DataView(src.buffer, src.byteOffset, src.length);
    var usz = dv.getUint32(8, true), hdr = dv.getUint32(12, true);
    if (usz + 0x100 !== want && usz !== want) { /* some tools store the size with or without the 0x100 header */ }
    var total = usz + 0x100, dst = new Uint8Array(total), i;
    if (0x10 + hdr + 0x100 > src.length) { layWhy = 'hdr' + hdr + '/' + src.length; return null; }
    for (i = 0; i < 0x100; i++) dst[i] = src[0x10 + hdr + i];
    var sp = 0x10 + hdr - 1, dp = total - 1, pool = 0, left = 0;
    function bits(n) {
      var val = 0, got = 0;
      while (got < n) {
        if (left === 0) { if (sp < 0x10) throw 0; pool = src[sp--]; left = 8; }
        var take = Math.min(left, n - got);
        val = (val << take) | ((pool >> (left - take)) & ((1 << take) - 1));
        left -= take; got += take;
      }
      return val;
    }
    var lens = [2, 3, 5, 8];
    try {
      while (dp >= 0x100) {
        if (bits(1)) {
          var off = bits(13) + 3, len = 3, lv = 0, k;
          for (k = 0; k < 4; k++) { lv = bits(lens[k]); len += lv; if (lv !== (1 << lens[k]) - 1) break; }
          if (k === 4) { do { lv = bits(8); len += lv; } while (lv === 255); }
          for (k = 0; k < len; k++) { if (dp < 0x100) break; dst[dp] = dst[dp + off]; dp--; }
        } else {
          dst[dp--] = bits(8);
        }
      }
    } catch (e) { layWhy = 'bits-ran-out@' + dp; return null; }
    return dst;
  }
  // One-time icon export ("icons"): copies unit and item icon PNGs out of the game's own .cpk packages into
  // Download/ffbe_icons so the editor app can import them. Only reads game files; writes only into that folder.
  function exportIcons() {
    var out = [];
    try {
      var libc = Process.getModuleByName('libc.so');
      var opendir = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
      var readdir = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
      var closedir = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
      var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
      var mkdir = new NativeFunction(libc.getExportByName('mkdir'), 'int', ['pointer', 'int']);
      var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fwrite = new NativeFunction(libc.getExportByName('fwrite'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fseek = new NativeFunction(libc.getExportByName('fseek'), 'int', ['pointer', 'long', 'int']);
      var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var sbuf = Memory.alloc(256);
      var base = '/data/data/com.exvius.offline.forever/files/';
      var dest = '/storage/emulated/0/Download/ffbe_icons';
      function cpks(dir) {
        var r = [], d = opendir(Memory.allocUtf8String(base + dir));
        if (d.isNull()) return r;
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var n = e.add(19).readUtf8String();
          if (e.add(18).readU8() !== 4 && /\.cpk$/i.test(n)) r.push(base + dir + '/' + n);
        }
        closedir(d);
        return r;
      }
      function rd(path, off, len) {
        var f = fopen(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
        if (f.isNull()) return null;
        var buf = Memory.alloc(len);
        if (off > 0) fseek(f, off, 0);
        var n = fread(buf, 1, len, f);
        fclose(f);
        return n === len ? buf : null;
      }
      function u8(ptr, n) { return new Uint8Array(ptr.readByteArray(n)); }
      var files = [];
      function subdirs(dir) {
        var r = [], d = opendir(Memory.allocUtf8String(base + dir));
        if (d.isNull()) return r;
        for (;;) {
          var e = readdir(d);
          if (e.isNull()) break;
          var n = e.add(19).readUtf8String();
          if (e.add(18).readU8() === 4 && n !== '.' && n !== '..') r.push(dir + '/' + n);
        }
        closedir(d);
        return r;
      }
      ['common_lang/sd', 'en/sd'].forEach(function (root) { subdirs(root).forEach(function (d) { files = files.concat(cpks(d)); }); });
      var units = {}, items = {}, nc = 0, bad = 0;
      files.forEach(function (path) {
        try {
          var head = rd(path, 0, 0x800);
          if (!head) { bad++; return; }
          var hv = new DataView(head.readByteArray(0x800));
          var hsz = hv.getUint32(8, true);
          var hu = u8(head.add(16), Math.min(hsz, 0x7e0));
          var hrows = cpkUtf(hu), toc = 0, content = 0;
          if (hrows && hrows.length) { toc = hrows[0].TocOffset || 0; content = hrows[0].ContentOffset || 0; }
          if (!toc) { bad++; return; }
          var th = rd(path, toc, 16);
          if (!th) { bad++; return; }
          var tsz = new DataView(th.readByteArray(16)).getUint32(8, true);
          var tb = rd(path, toc + 16, tsz);
          if (!tb) { bad++; return; }
          var rows = cpkUtf(u8(tb, tsz));
          if (!rows) { bad++; return; }
          nc++;
          rows.forEach(function (r) {
            var n = r.FileName, m;
            if (typeof n !== 'string') return;
            var ent = { path: path, name: n, size: r.FileSize, ext: r.ExtractSize, off: r.FileOffset, toc: toc, content: content };
            if ((m = /^unit_icon_(\d{9})\.png$/.exec(n))) {
              var fam = m[1].substr(0, 8), cur = units[fam];
              if (!cur || m[1] > cur.id) { ent.id = m[1]; units[fam] = ent; }
            } else if (/^(item|global_equip|global_item|ability|global_ability|global_materia)_\d+\.png$/.test(n)) {
              if (!items[n]) items[n] = ent;
            }
          });
        } catch (e) { bad++; }
      });
      var list = [];
      Object.keys(units).forEach(function (k) { list.push(units[k]); });
      Object.keys(items).forEach(function (k) { list.push(items[k]); });
      out.push('packages read: ' + nc + ' of ' + files.length + ' (failed ' + bad + ')');
      out.push('unit icons chosen (highest star per unit): ' + Object.keys(units).length + ', item icons: ' + Object.keys(items).length);
      mkdir(Memory.allocUtf8String('/storage/emulated/0/Download'), 511);
      mkdir(Memory.allocUtf8String(dest), 511);
      var compOk = 0, compBad = 0, whyCount = {}, whyEx = [], written = 0, total = 0, compressed = 0, notpng = 0, failed = 0, baseUsed = {}, index = '', dim = [], dimSeen = {};
      list.forEach(function (e) {
        try {
          var bases = [e.content, e.toc], buf = null, used = -1, i, outLen = e.size;
          var isComp = e.ext !== e.size;
          for (i = 0; i < bases.length; i++) {
            var b = rd(e.path, bases[i] + e.off, e.size);
            if (!b) continue;
            if (isComp) {
              var dec = crilayla(u8(b, e.size), e.ext);
              if (dec && dec.length >= 8 && dec[0] === 0x89 && dec[1] === 0x50 && dec[2] === 0x4e && dec[3] === 0x47) {
                var cut = dec.length;
                if (cut > e.ext) cut = e.ext;
                var nb = Memory.alloc(cut);
                nb.writeByteArray(dec.subarray(0, cut).buffer.slice(dec.byteOffset, dec.byteOffset + cut));
                buf = nb; outLen = cut; used = i; compOk++;
                break;
              }
            } else if (b.readU8() === 0x89 && b.add(1).readU8() === 0x50) { buf = b; used = i; break; }
          }
          if (!buf) {
            if (isComp) { compBad++; var wk = layWhy.replace(/[0-9]+/g, '#'); whyCount[wk] = (whyCount[wk] || 0) + 1; if (whyEx.length < 6) whyEx.push(e.name + ' size ' + e.size + '/' + e.ext + ' ' + layWhy); }
            else notpng++;
            return;
          }
          baseUsed[used] = (baseUsed[used] || 0) + 1;
          var f = fopen(Memory.allocUtf8String(dest + '/' + e.name), Memory.allocUtf8String('w'));
          if (f.isNull()) { failed++; return; }
          fwrite(buf, 1, outLen, f);
          fclose(f);
          written++; total += outLen;
          index += e.name + ' ' + outLen + '\n';
          var kind = e.name.indexOf('unit_') === 0 ? 'u' : 'i';
          if (!dimSeen[kind]) {
            dimSeen[kind] = 1;
            var h = new DataView(buf.add(16).readByteArray(8));
            dim.push(e.name + ' ' + h.getUint32(0, false) + 'x' + h.getUint32(4, false));
          }
        } catch (er) { failed++; }
      });
      var fi = fopen(Memory.allocUtf8String(dest + '/index.txt'), Memory.allocUtf8String('w'));
      if (!fi.isNull()) { var ib = Memory.allocUtf8String(index); fwrite(ib, 1, index.length, fi); fclose(fi); }
      out.push('written: ' + written + ' files, ' + Math.round(total / 1024) + 'KB into Download/ffbe_icons');
      out.push('compressed entries unpacked: ' + compOk + ', could not unpack: ' + compBad + ', not PNG at offset: ' + notpng + ', write failed: ' + failed);
      out.push('unpack failures: ' + JSON.stringify(whyCount) + ' e.g. ' + whyEx.join(' ; '));
      out.push('data offset base used (0=content,1=toc): ' + JSON.stringify(baseUsed));
      out.push('sizes: ' + dim.join(' | '));
    } catch (e) {
      out.push('icon export failed: ' + e);
    }
    return out.join('\n');
  }
  function serve(conn) {
    var acc = '';
    function more(buf) {
      acc += text(buf);
      // a staged save piece (sput) can be longer than one read: keep reading until the end of the line
      if ((acc.indexOf('sput ') === 0 || acc.indexOf('gam ') === 0) && acc.indexOf('\n') < 0 && acc.length < 4000000 && buf.byteLength > 0) return conn.input.read(65536).then(more);
      return handle(acc.trim());
    }
    function handle(t) {
      if (t === 'icons') return conn.output.writeAll(bytes(exportIcons() + '\n')).then(function () {});
      if (t === 'names') return conn.output.writeAll(bytes(surveyNames() + '\n')).then(function () {});
      if (t === 'ls3') return conn.output.writeAll(bytes(survey3() + '\n')).then(function () {});
      if (t === 'ls2') return conn.output.writeAll(bytes(survey2() + '\n')).then(function () {});
      if (t === 'ls') return conn.output.writeAll(bytes(survey() + '\n')).then(function () {});
      var xc = globalThis.FFBE_CMD && globalThis.FFBE_CMD[t.split(' ')[0]];
      if (xc) {
        var xr;
        try { xr = String(xc(t)); } catch (xe) { xr = 'error ' + xe; }
        return conn.output.writeAll(bytes(xr + '\n')).then(function () {});
      }
      var v = parseFloat(t);
      if (t.length && !isNaN(v) && v >= 0.1 && v <= 20) {
        if (v === 1 && speed !== 1) reset = true;
        speed = v;
      }
      return conn.output.writeAll(bytes('OK ' + speed + ' ' + state + ' ' + ((Date.now() - lastFrame) < 3000 ? 1 : 0)
        + (globalThis.FFBE_XTRA ? ' ' + globalThis.FFBE_XTRA() : '') + '\n'));
    }
    conn.input.read(65536).then(more).catch(function () {}).then(function () {
      conn.close().catch(function () {});
    });
  }
  function accept(listener) {
    listener.accept().then(function (conn) {
      serve(conn);
      accept(listener);
    }).catch(function (e) {
      log('accept failed: ' + e);
      setTimeout(function () { accept(listener); }, 1000);
    });
  }
  function listen() {
    Socket.listen({ family: 'ipv4', host: '127.0.0.1', port: PORT }).then(function (l) {
      log('listening on ' + PORT);
      accept(l);
    }).catch(function (e) {
      log('listen failed: ' + e);
      setTimeout(listen, 3000);
    });
  }
  listen();
})();
