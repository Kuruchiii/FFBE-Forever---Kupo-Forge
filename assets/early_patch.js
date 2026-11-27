// Runs the moment the script loads, before the game has rewritten its save: remembers when the save file was last written.
globalThis.__kupoAge = function () {   // seconds since the game process started, or 99999 if unknown
  try {
    var libc = Process.getModuleByName('libc.so');
    var fo = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
    var fr = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
    var fc = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
    function rd(p) { var f = fo(Memory.allocUtf8String(p), Memory.allocUtf8String('r')); if (f.isNull()) return ''; var m = Memory.alloc(2048), n = fr(m, 1, 2000, f); fc(f); return n > 0 ? m.readUtf8String(n) : ''; }
    var st = rd('/proc/self/stat'), up = parseFloat(rd('/proc/uptime'));
    var f = st.substring(st.lastIndexOf(')') + 2).split(' ');   // field 3 onward; starttime is field 22
    var start = parseInt(f[19], 10) / 100;
    return isFinite(up) && isFinite(start) ? up - start : 99999;
  } catch (e) { return 99999; }
};
(function () {
  try {
    var libc = Process.getModuleByName('libc.so');
    var fo = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
    var fr = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
    var fc = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
    var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
    var base = '/data/data/com.exvius.offline.forever/files/exvius/state/', sb = Memory.alloc(256);
    var f = fo(Memory.allocUtf8String(base + 'active_save.json'), Memory.allocUtf8String('r')), id = 'campaign';
    if (!f.isNull()) {
      var m = Memory.alloc(512), n = fr(m, 1, 500, f); fc(f);
      var x = n > 0 ? /"active"\s*:\s*"([^"]*)"/.exec(m.readUtf8String(n)) : null;
      if (x && /^(campaign|save-[0-9]+)$/.test(x[1])) id = x[1];
    }
    var path = id === 'campaign' ? base + 'campaign.json' : base + 'saves/' + id + '.json';
    if (stat(Memory.allocUtf8String(path), sb) !== 0) return;
    globalThis.__kupoT0 = { path: path, mt: sb.add(88).readS64().valueOf(), age: globalThis.__kupoAge() };
  } catch (e) { }
})();
