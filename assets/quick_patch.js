/*FFBE-QUICK-PATCH v1*/
;(function () {
  // In-game patches: Quick party and the unit label. Switched on by the editor app (globalThis.FFBE_OPT).
  // Written against the pinned v0.1.6 libgame.so. Every step checks what it expects and logs why it stops.
  var OPT = globalThis.FFBE_OPT || {};
  var logs = [], TRACE = true;
  // every line also goes to Android's log (tag FFBE_QUICK), so it is in the game's log file after a crash
  var alw = null;
  function androidLog(m) {
    try {
      if (!alw) {
        var ll = Process.findModuleByName('liblog.so');
        var f = ll && ll.findExportByName('__android_log_write');
        if (f) alw = new NativeFunction(f, 'int', ['int', 'pointer', 'pointer']);
        else alw = false;
      }
      if (alw) alw(4, Memory.allocUtf8String('ExviusTest'), Memory.allocUtf8String('[KUPOFORGE] ' + String(m)));
    } catch (e) { alw = false; }
  }
  function log(m) {
    var s = new Date().toISOString().substr(11, 8) + ' ' + m;
    logs.push(s);
    if (logs.length > 200) logs.shift();
    androidLog(m);
    try { console.log('[KUPOFORGE] FFBE_QUICK ' + m); } catch (e) {}
  }
  var cmd = globalThis.FFBE_CMD = globalThis.FFBE_CMD || {};
  var plogs = [], glogs = [];
  cmd.qlog = function () { return (logs.join('\n') || '(no log lines yet)') + (glogs.length ? '\n--- gambit ---\n' + glogs.join('\n') : '') + (plogs.length ? '\n--- battle probe ---\n' + plogs.join('\n') : ''); };
  cmd.qclear = function () { logs = []; return 'cleared'; };

  var game = null;
  function sym(n) { var a = game.findExportByName(n); if (!a) log('missing symbol ' + n); return a; }

  // ---------- Quick party ----------
  var utop = null, utopVisible = false, uot = null;
  var stage = 0, stageAt = 0, frames = 0, hooksOk = false;
  var pushUnitOption = null, qSlot = -1;

  function quickHooks() {
    var vis = sym('_ZN12UnitTopScene14onSceneVisibleEv');
    var inv = sym('_ZN12UnitTopScene16onSceneInvisibleEv');
    var push = sym('_ZN12UnitTopScene19pushUnitOptionSceneEv');
    var ouv = sym('_ZN18UnitOptionTopScene14onSceneVisibleEv');
    var upd = sym('_ZN7cocos2d9Scheduler6updateEf');
    if (!vis || !inv || !push || !ouv || !upd) { log('Quick party: symbols missing, not installed'); return; }
    pushUnitOption = new NativeFunction(push, 'void', ['pointer']);
    Interceptor.attach(vis, { onEnter: function (a) { utop = a[0]; utopVisible = true; log('UnitTopScene visible ' + utop); } });
    Interceptor.attach(inv, { onEnter: function (a) { utopVisible = false; log('UnitTopScene hidden'); } });
    // the menu can be left without onSceneInvisible (scene swapped or destroyed): watch the scene changes too
    var leave = ['_ZN9BaseScene11changeSceneEPS_', '_ZN9BaseScene11changeSceneEPS_b', '_ZN9BaseScene11changeSceneEPS_bi',
      '_ZN9BaseScene9pushSceneEPS_b', '_ZN9GameScene11changeSceneEP9BaseSceneb', '_ZN9GameScene11changeSceneEP9BaseScenebi',
      '_ZN9GameScene9pushSceneEP9BaseSceneb', '_ZN9GameScene8popSceneEbb', '_ZN9GameScene8backSceneEv',
      '_ZN12UnitTopSceneD0Ev', '_ZN12UnitTopSceneD1Ev'];
    leave.forEach(function (n) {
      var f = game.findExportByName(n);
      if (!f) { log('no ' + n); return; }
      Interceptor.attach(f, { onEnter: function (a) {
        if (utop && a[0].equals(utop) && utopVisible) { utopVisible = false; log('left unit menu via ' + n); }
      } });
    });
    Interceptor.attach(ouv, { onEnter: function (a) {
      uot = a[0];
      log('UnitOptionTopScene visible ' + uot + ' stage ' + stage);
      if (stage === 2) { stage = 3; frames = 0; }
    } });
    Interceptor.attach(upd, { onEnter: function () {
      if (!stage) return;
      if (Date.now() - stageAt > 6000) { log('Quick party timed out at stage ' + stage); stage = 0; return; }
      try {
        if (stage === 1) {
          stage = 2;
          // Use the party slot that is on screen (the game's own getActiveDeckNo), not the saved base slot.
          if (qSlot >= 0) {
            try {
              var gbd = nf('_ZN12UnitTopScene14getBaseDeckNumEv', 'int', ['pointer']);
              var sad = nf('_ZN12UnitTopScene16setActiveDeckNumEi', 'void', ['pointer', 'int']);
              var before = gbd(utop);
              if (before !== qSlot) sad(utop, qSlot);
              log('Quick party: slot on screen ' + qSlot + ', saved slot was ' + before + ', now ' + gbd(utop));
            } catch (e) { log('Quick party slot error: ' + e); }
          }
          log('calling pushUnitOptionScene');
          pushUnitOption(utop);
        } else if (stage === 3) {
          if (++frames < 4) return;     // let the new scene finish its first frames
          stage = 0;
          selectParty();
        }
      } catch (e) { log('Quick party error: ' + e); stage = 0; }
    } });
    hooksOk = true;
    log('Quick party hooks installed');
  }

  function selectParty() {
    var createFn = sym('_ZN18UnitOptionTopScene30createPartyUnitSelectListSceneEv');
    var vt = uot.readPointer();
    var slotCreate = vt.add(0x8b0).readPointer();
    if (!createFn || !slotCreate.equals(createFn)) {
      log('stop: scene is not the plain unit option scene (slot 0x8b0 = ' + slotCreate + ', expected ' + createFn + ')');
      return;
    }
    if (qSlot >= 0) {
      try {
        log('Quick party: party list slot was ' + uot.add(0x5e4).readS32() + ', set to ' + qSlot);
        uot.add(0x5e4).writeS32(qSlot);
        var sel = nf('_ZN18SelectingUnitParty6sharedEv', 'pointer', [])();
        if (!sel.isNull()) sel.add(0xc).writeS32(qSlot);
      } catch (e) { log('Quick party slot error: ' + e); }
    }
    uot.add(0x399).writeU8(1);
    uot.add(0x5ec).writeU8(1);
    var scene = new NativeFunction(slotCreate, 'pointer', ['pointer'])(uot);
    log('party select scene ' + scene);
    if (scene.isNull()) { log('stop: no scene made'); return; }
    var mission = uot.add(0x5f0).readS32();
    var bp = sym('_ZN11MissionUtil20isBrokenPieceMissionEi');
    var bps = sym('_ZN13UnitListScene23setIsBroKenPieceMissionEv');
    if (bp && bps && new NativeFunction(bp, 'bool', ['int'])(mission)) new NativeFunction(bps, 'void', ['pointer'])(scene);
    var pushScene = vt.add(0x6a8).readPointer();
    new NativeFunction(pushScene, 'void', ['pointer', 'pointer', 'int'])(uot, scene, 0);
    log('party select scene pushed');
    utopVisible = false;     // hides the Quick button until the unit menu is back
  }

  cmd.quick = function () {
    if (!hooksOk) return 'no: quick party hooks are not installed (see log)';
    if (!utopVisible || !utop) return 'no: open the unit/party menu first';
    if (stage) return 'busy';
    qSlot = -1;
    try {
      var n = nf('_ZN15UnitPartyCommon15getActiveDeckNoEv', 'int', ['pointer'])(utop);
      if (n >= 0 && n < 100) qSlot = n;
      log('quick requested, slot on screen ' + n);
    } catch (e) { log('quick requested (slot read failed: ' + e + ')'); }
    stage = 1; stageAt = Date.now();
    return 'ok';
  };

  // ---------- Unit label: level, name and rarity together in the unit lists ----------
  // The game draws one thing under each unit depending on the sort: level (sort 0), rarity stars (sort 7),
  // the name only in a few lists. This draws the other two as well, with the game's own functions.
  var busy = false, labelHits = 0, seen = {}, seenR = {}, raCalls = 0, nullUnit = 0, rarHits = 0, errs = 0, lvHits = 0;
  var pool = null, poolAt = 0, rmCalls = 0, redrawNoClear = 0, tileSeen = {}, cleared = {};
  function noteTile(t) {
    var k = t.toString();
    if (tileSeen[k] && !cleared[k]) redrawNoClear++;
    tileSeen[k] = 1; cleared[k] = 0;
  }
  function labelHooks() {
    var lvA = sym('_ZN6UnitUI9setThumLvEiiRKN7cocos2d4RectEP12UnitInfoBasef');
    var raA = sym('_ZN6UnitUI17setThumUnitRarityEiiRKN7cocos2d4RectEiii');
    var nmA = sym('_ZN6UnitUI15setThumUnitNameEP7UnitMstiiiif');
    var gm = sym('_ZN12UnitInfoBase10getUnitMstEv');
    var gr = sym('_ZN12UnitInfoBase7getRareEv');
    var gn = sym('_ZN12UnitInfoBase9getNvTypeEv');
    var ml = sym('_ZN11UnitMstList6sharedEv');
    var mx = sym('_ZN11UnitMstList24getMaxUnitRarityAtUnitIdEi');
    if (!lvA || !raA || !nmA || !gm || !gr || !gn || !ml || !mx) { log('Unit label: symbols missing, not installed'); return; }
    var fLv = new NativeFunction(lvA, 'void', ['pointer', 'int', 'int', 'pointer', 'pointer', 'float']);
    var fRa = new NativeFunction(raA, 'void', ['pointer', 'int', 'int', 'pointer', 'int', 'int', 'int']);
    var fNm = new NativeFunction(nmA, 'void', ['pointer', 'pointer', 'int', 'int', 'int', 'int', 'float']);
    var fMst = new NativeFunction(gm, 'pointer', ['pointer']);
    var fRare = new NativeFunction(gr, 'int', ['pointer']);
    var fNv = new NativeFunction(gn, 'int', ['pointer']);
    var fList = new NativeFunction(ml, 'pointer', []);
    var fMax = new NativeFunction(mx, 'int', ['pointer', 'int']);
    var STAR_DY = 64, NAME_DY = 12;
    var LV_DY_R = -24, NAME_DY_R = -4;   // Rarity sort: the game's stars stay put; level goes just above them, name just below   // order: level, name, stars     // the second line sits this far below the first (game units)
    function shift(r, dy) {
      // never freed: the game may keep the rect pointer after the call returns
      if (!pool) pool = Memory.alloc(16 * 16384);
      var b = pool.add((poolAt++ % 16384) * 16);
      Memory.copy(b, r, 16);
      b.add(4).writeFloat(b.add(4).readFloat() + dy);
      return b;
    }
    function mode(t) { return t.add(0xe0).readS32(); }
    function addName(t, a, b, rect, unit) {
      var mst = fMst(unit);
      if (mst.isNull()) return;
      var x = Math.floor(rect.readFloat() + rect.add(8).readFloat() * 0.5);
      var y = Math.floor(rect.add(4).readFloat() + rect.add(12).readFloat() - 8 + 26);
      fNm(t, mst, a, b, x, y, 1.0);
    }
    // Level sort: the game draws "Lv/80"; add name and stars
    Interceptor.attach(lvA, {
      onEnter: function (a) { this.t = a[0]; this.a = a[1].toInt32(); this.b = a[2].toInt32(); this.r = a[3]; this.u = a[4]; },
      onLeave: function () {
        if (busy) return;
        try {
          var m = mode(this.t);
          if (!seen[m]) { seen[m] = 0; log('setThumLv called in mode ' + m); }
          lvHits++; if (++seen[m] <= 3 || seen[m] % 100 === 0) log('lv hit mode ' + m + ' #' + seen[m] + ' tile ' + this.t + ' unit ' + this.u);
          if (TRACE) log('draw level tile ' + this.t + ' unit ' + this.u);
          busy = true; labelHits++;
          addStars2(this.t, this.a, this.b, shift(this.r, STAR_DY), this.u);
          if (OPT.name) addName(this.t, this.a, this.b, shift(this.r, NAME_DY), this.u);
        } catch (e) { errs++; log('label (level) error: ' + e); } finally { busy = false; }
      }
    });
    // Rarity sort: the game draws the stars; add the level and the name below them (as in 1.13.14)
    Interceptor.attach(raA, {
      onEnter: function (a) { this.t = a[0]; this.a = a[1].toInt32(); this.b = a[2].toInt32(); this.r = a[3]; },
      onLeave: function () {
        if (busy) return;
        try {
          var rm = mode(this.t);
          raCalls++;
          if (rm !== 7) { if (!seenR[rm]) { seenR[rm] = 1; log('setThumUnitRarity called in mode ' + rm + ' (skipped)'); } return; }
          var unit = this.t.add(0x270).readPointer();
          if (unit.isNull()) { nullUnit++; if (nullUnit <= 5) log('rarity: unit pointer empty on tile ' + this.t); return; }
          rarHits++;
          if (TRACE) log('draw rarity tile ' + this.t + ' unit ' + unit);
          busy = true; labelHits++;
          fLv(this.t, this.a, this.b, shift(this.r, LV_DY_R), unit, 1.0);
          if (OPT.name) addName(this.t, this.a, this.b, shift(this.r, NAME_DY_R), unit);
        } catch (e) { errs++; log('label (rarity) error: ' + e); } finally { busy = false; }
      }
    });
    function addStars2(t, a, b, rect, unit) {
      // maxRarity comes from the unit's id (virtual call, slot 0x248 on the unit info, as the game does)
      var id = unit.readPointer().add(0x248).readPointer();
      var uid = new NativeFunction(id, 'int', ['pointer'])(unit);
      fRa(t, a, b, rect, fMax(fList(), uid), fRare(unit), fNv(unit));
    }
    log('Unit label hooks installed');
  }



  // ---------- Auto skip of animation screens ----------
  // When a chosen animation scene appears, wait a few frames, then call the game's own skip function for it
  // (the same one its Skip button calls). Only on the game's render thread, from the frame update.
  var skips = [], skipTick = false, tickFns = [], tickErr = 0;
  var RT = { bot: false, auto: false, res: false, rep: false, cloud: !!OPT.cloud, pendAuto: -1, battleAt: 0, resAt: 0, taps: 0 };
  function ensureTick() {
    if (skipTick) return;
    var upd = sym('_ZN7cocos2d9Scheduler6updateEf');
    if (!upd) return;
    skipTick = true;
    Interceptor.attach(upd, { onEnter: function () {
      for (var j = 0; j < tickFns.length; j++) { try { tickFns[j](); } catch (e) { if (++tickErr <= 5) log('tick error: ' + e); } }
      if (!skips.length) return;
      for (var i = skips.length - 1; i >= 0; i--) {
        var k = skips[i];
        if (Date.now() - k.at > 15000) { log(k.name + ': gave up (no skip in 15 s)'); skips.splice(i, 1); continue; }
        if (++k.f < k.delay) continue;
        skips.splice(i, 1);
        try { log(k.name + ': calling skip on ' + k.scene); k.fn(k.scene); }
        catch (e) { log(k.name + ' skip error: ' + e); }
      }
    } });
  }
  function autoSkip(name, visName, invName, skipName, delay) {
    var vis = sym(visName), inv = sym(invName), sk = sym(skipName);
    if (!vis || !inv || !sk) { log(name + ': symbols missing, not installed'); return; }
    var fn = new NativeFunction(sk, 'void', ['pointer']);
    ensureTick();
    Interceptor.attach(vis, { onEnter: function (a) {
      for (var i = skips.length - 1; i >= 0; i--) if (skips[i].name === name) skips.splice(i, 1);
      skips.push({ name: name, scene: a[0], fn: fn, f: 0, delay: delay, at: Date.now() });
      log(name + ': scene visible ' + a[0]);
    } });
    Interceptor.attach(inv, { onEnter: function (a) {
      for (var i = skips.length - 1; i >= 0; i--) if (skips[i].name === name && skips[i].scene.equals(a[0])) skips.splice(i, 1);
    } });
    log(name + ': hooks installed');
  }
  var skipsOn = [];


  // ---------- Skip the Vision summon ----------
  // 1) The result screen opens the shards/treasures one by one unless its own "skip" flag is set (what its Skip tap sets).
  //    We set that flag as soon as the screen runs, so everything is open at once.
  // 2) The Vision card reveal scene waits for taps; call the game's own tap handler whenever it is ready for one.
  function visionSummonHooks() {
    var ru = sym('_ZN20GachaResultItemScene11updateEventEv');
    var eu = sym('_ZN26GachaVisionCardEffectScene11updateEventEv');
    var et = sym('_ZN26GachaVisionCardEffectScene5onTapEPK9TouchInfo');
    if (!ru) { log('Skip Vision summon: result screen symbol missing, not installed'); return; }
    var n1 = 0, n2 = 0;
    // Diagnostics only (the result list is left to the game): log its state now and then, so a stuck screen can be read.
    var fin = nf('_ZN24MissionResultTreasureObj15isFinishedAnimeEv', 'int', ['pointer']);
    var fr = 0;
    Interceptor.attach(ru, { onEnter: function (a) {
      try {
        if (++fr % 90 !== 1 || n1 >= 30) return;
        n1++;
        var t = a[0], arr = t.add(0x5f8).readPointer(), tot = -1, nf2 = -1;
        if (!arr.isNull()) {
          var ca = arr.add(0x30).readPointer();
          tot = ca.readS32(); nf2 = 0;
          var data = ca.add(0x10).readPointer();
          if (fin) for (var i = 0; i < tot; i++) nf2 += fin(data.add(i * 8).readPointer()) & 1;
        }
        log('Vision result: state ' + t.add(0x5e4).readS32() + ' sub ' + t.add(0x5e0).readS32() + ' started ' + t.add(0x624).readS32()
          + '/' + tot + ' finished ' + nf2 + ' skip ' + t.add(0x628).readU8() + ' touchlock ' + t.add(0x630).readU8());
      } catch (e) { log('Vision result log error: ' + e); }
    } });
    if (eu && et) {
      var tap = new NativeFunction(et, 'void', ['pointer', 'pointer']);
      Interceptor.attach(eu, { onEnter: function (a) {
        try {
          var s = a[0];
          if (s.add(0x64c).readS32() === 1 && s.add(0x605).readU8() === 0 && s.add(0x606).readU8() !== 0) {
            if (++n2 <= 20) log('Skip Vision summon: tap on card scene ' + s + ' card ' + s.add(0x600).readS32());
            tap(s, ptr(0));
          }
        } catch (e) { log('Skip Vision summon (cards) error: ' + e); }
      } });
    } else log('Skip Vision summon: card scene symbols missing (result skip only)');
    log('Skip Vision summon: hooks installed');
  }


  // ---------- More in-game patches (each switched on alone) ----------
  function sget(n) { return sym(n); }
  function nf(n, ret, args) { var a = sym(n); return a ? new NativeFunction(a, ret, args) : null; }

  // Normal summon: press the game's tap handler whenever the animation waits for a tap, and open the result list with
  // its own "skip" flag set (the flag its Skip tap sets), so units are not revealed one by one.
  function summonHooks() {
    var eu = sym('_ZN16GachaEffectScene11updateEventEv');
    var et = sym('_ZN16GachaEffectScene5onTapEPK9TouchInfo');
    var rl = sym('_ZN16GachaResultScene11updateEventEv');
    var n1 = 0, n2 = 0;
    if (eu && et) {
      var tap = new NativeFunction(et, 'void', ['pointer', 'pointer']);
      Interceptor.attach(eu, { onEnter: function (a) {
        try {
          var s = a[0];
          if (s.add(0x605).readU8() === 0 && s.add(0x606).readU8() !== 0) {
            if (++n1 <= 20) log('Skip summon: tap on animation scene ' + s + ' step ' + s.add(0x600).readS32());
            tap(s, ptr(0));
          }
        } catch (e) { log('Skip summon (animation) error: ' + e); }
      } });
    } else log('Skip summon: animation symbols missing');
    if (rl) {
      // Do what a tap on the unit list does in the game: skip flag on and the frame counter moved on (unit count * 5).
      Interceptor.attach(rl, { onEnter: function (a) {
        try {
          var t = a[0];
          if (t.add(0x604).readS32() !== 0) return;
          var busy = new NativeFunction(t.readPointer().add(0x928).readPointer(), 'int', ['pointer'])(t) & 1;
          if (busy) return;
          var cnt = t.add(0x5f8).readS32();
          if (cnt < 1 || cnt > 200) return;
          t.add(0x604).writeS32(1);
          t.add(0x5fc).writeS32(cnt * 5);
          if (++n2 <= 5) log('Skip summon: unit list skipped on ' + t + ' (' + cnt + ' units)');
        } catch (e) { log('Skip summon (result) error: ' + e); }
      } });
    } else log('Skip summon: result list symbol missing');
    log('Skip summon: hooks installed');
  }

  // Auto battle on at the start of a battle (and live On/Off from the floating button), with the game's own setters.
  function battleDefaults() {
    var st = nf('_ZN11BattleState6sharedEv', 'pointer', []);
    var sUser = nf('_ZN11BattleState20setUserAutoBattleFlgEb', 'void', ['pointer', 'int']);
    var sAuto = nf('_ZN11BattleState16setAutoBattleFlgEb', 'void', ['pointer', 'int']);
    if (!st || !sUser || !sAuto) { log('Auto battle: BattleState symbols missing'); return; }
    var classes = ['11BattleScene', '19AllianceBattleScene', '13RbBattleScene', '16TowerBattleScene', '18DmgRankBattleScene'];
    var cnt = 0;
    classes.forEach(function (c) {
      var f = game.findExportByName('_ZN' + c + '21setBattleStartAutoFlgEv');
      if (f) Interceptor.attach(f, { onLeave: function () {
        try { if (!RT.auto) return; var b = st(); sUser(b, 1); sAuto(b, 1); if (++cnt <= 5) log('Auto battle set on at battle start (' + c + ')'); }
        catch (e) { log('Auto battle error: ' + e); }
      } });
      var u = game.findExportByName('_ZN' + c + '11updateEventEv');
      if (u) Interceptor.attach(u, { onEnter: function () { RT.battleAt = Date.now(); } });
    });
    ensureTick();
    tickFns.push(function () {
      if (RT.pendAuto < 0 || Date.now() - RT.battleAt > 1500) return;
      var v = RT.pendAuto; RT.pendAuto = -1;
      var b = st(); sUser(b, v); sAuto(b, v);
      log('Auto battle switched ' + (v ? 'on' : 'off') + ' from the floating button');
    });
    cmd.auto = function (t) { var v = (t.split(' ')[1] === '1'); RT.auto = v; RT.pendAuto = v ? 1 : 0; if (v && RT.bot && cmd.bot) cmd.bot('bot 0'); return 'ok'; };   // Auto battle and Gambit exclude each other
    log('Auto battle installed (start ' + (RT.auto ? 'on' : 'off') + ')');
  }

  // Repeat battle: when the final result summary screen is up for a moment, start the same quest again with the game's
  // own repeat path (what its hidden quest loop does): new MissionStartScene, setParams(last mission id), changeStepScene.
  function repeatHooks() {
    var vis = sym('_ZN18ResultSummaryScene14onSceneVisibleEv');
    var inv = sym('_ZN18ResultSummaryScene16onSceneInvisibleEv');
    var ctor = sym('_ZN17MissionStartSceneC1Ev');
    var setp = sym('_ZN17MissionStartScene9setParamsEiiRKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEEP15DungeonBonusMstS8_');
    var step = sym('_ZN9GameScene15changeStepSceneEPS_b');
    var us = sym('_ZN9UserState6sharedEv');
    var nw = Module.findGlobalExportByName ? Module.findGlobalExportByName('_Znwm') : Module.findExportByName(null, '_Znwm');
    if (!vis || !inv || !ctor || !setp || !step || !us || !nw) { log('Repeat battle: symbols missing, not installed (new ' + (nw ? 'ok' : 'missing') + ')'); return; }
    var fNew = new NativeFunction(nw, 'pointer', ['ulong']);
    var fCtor = new NativeFunction(ctor, 'void', ['pointer']);
    var fSet = new NativeFunction(setp, 'void', ['pointer', 'int', 'int', 'pointer', 'pointer', 'pointer']);
    var fStep = new NativeFunction(step, 'void', ['pointer', 'pointer', 'int']);
    var fUs = new NativeFunction(us, 'pointer', []);
    var cur = null, frames = 0, done = false, runs = 0;
    Interceptor.attach(vis, { onEnter: function (a) { cur = a[0]; frames = 0; done = false; RT.sumUp = 1; log('Repeat battle: summary screen up ' + cur); } });
    Interceptor.attach(inv, { onEnter: function (a) { if (cur && cur.equals(a[0])) { cur = null; RT.sumUp = 0; } } });
    ensureTick();
    tickFns.push(function () {
      if (!cur || done || !RT.rep) return;
      if (++frames < 150) return;
      done = true;
      var id = fUs().add(0x140).readS32();
      if (id <= 0) { log('Repeat battle: no last mission id'); return; }
      var sa = Memory.alloc(64), sb = Memory.alloc(64);
      sa.writeByteArray(new Array(24).fill(0)); sb.writeByteArray(new Array(24).fill(0));
      var sc = fNew(0x670);
      fCtor(sc);
      fSet(sc, id, 0, sa, ptr(0), sb);
      log('Repeat battle: starting mission ' + id + ' again (run ' + (++runs) + ')');
      fStep(cur, sc, 0);
    });
    cmd.rep = function (t) { RT.rep = (t.split(' ')[1] === '1'); return 'ok'; };
    log('Repeat battle: hooks installed');
  }

  // Result screens: set the exp screen's own skip flag, run the unit result's own skip after a short wait,
  // and send a real tap (through the game's own touch entry) every so often while a result screen is up.

  // ---------- Reward probe (read-only): what the client holds as battle rewards. Logs only; changes nothing. Switch "Reward probe" in the Patch tab. ----------
  // The local server credits exactly what the client reports in its MissionEnd request (gil, exp, drops), so the later multiplier would change these same numbers.
  // This probe shows where they are: every drop the battle adds (id, type, count), the gil total, the item csv and the exp the result scene gets.
  function rewardProbe() {
    var n = { drop: 0, gil: 0, csv: 0, exp: 0, num: 0, bld: 0 };
    function strRead(p) {
      try { var b0 = p.readU8(); return (b0 & 1) ? p.add(0x10).readPointer().readUtf8String() : p.add(1).readUtf8String(); } catch (e) { return '?'; }
    }
    var gId = nf('_ZNK19MissionDropItemInfo9getItemIDEv', 'int', ['pointer']);
    var gTy = nf('_ZNK19MissionDropItemInfo11getItemTypeEv', 'int', ['pointer']);
    var gNm = nf('_ZNK19MissionDropItemInfo10getItemNumEv', 'int', ['pointer']);
    var gMo = nf('_ZNK19MissionDropItemInfo12getMonsterIDEv', 'int', ['pointer']);
    var add = sym('_ZN13BattleManager17addItemDropResultEP19MissionDropItemInfo');
    if (add && gId && gTy && gNm) Interceptor.attach(add, { onEnter: function (a) {
      if (++n.drop > 400) return;
      try { log('Reward probe: drop added item id ' + gId(a[1]) + ' type ' + gTy(a[1]) + ' count ' + gNm(a[1]) + ' monster ' + (gMo ? gMo(a[1]) : '?')); } catch (e) { log('Reward probe: drop read failed: ' + e); }
    } });
    var tg = sym('_ZN21MissionResultInfoBase11getTotalGilEv');
    if (tg) Interceptor.attach(tg, { onLeave: function (r) { if (++n.gil <= 60) log('Reward probe: getTotalGil returns ' + r.toInt32()); } });
    var gc = sym('_ZN21MissionResultInfoBase10getItemCsvEiiRNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE');
    if (gc) Interceptor.attach(gc, {
      onEnter: function (a) { this.a1 = a[1].toInt32(); this.a2 = a[2].toInt32(); this.s = a[3]; },
      onLeave: function () { if (++n.csv <= 60) log('Reward probe: getItemCsv(' + this.a1 + ',' + this.a2 + ') = "' + strRead(this.s) + '"'); }
    });
    var gi = sym('_ZN21MissionResultInfoBase10getItemNumEiiii');
    if (gi) Interceptor.attach(gi, {
      onEnter: function (a) { this.q = [a[1].toInt32(), a[2].toInt32(), a[3].toInt32(), a[4].toInt32()]; },
      onLeave: function (r) { if (++n.num <= 80) log('Reward probe: getItemNum(' + this.q.join(',') + ') returns ' + r.toInt32()); }
    });
    var me = sym('_ZN11BattleScene20setMissionMstUnitExpEP21MissionResultInfoBase');
    if (me) Interceptor.attach(me, { onEnter: function () { if (++n.exp <= 20) log('Reward probe: setMissionMstUnitExp called (exp is set here)'); } });
    var cb = sym('_ZN17MissionEndRequest10createBodyEv');
    if (cb) Interceptor.attach(cb, { onEnter: function () { if (++n.bld <= 20) log('Reward probe: MissionEndRequest body built (reward numbers are final here)'); } });
    log('Reward probe: installed (' + [add, tg, gc, gi, me, cb].map(function (x) { return x ? 'ok' : 'missing'; }).join(',') + ')');
  }

  function resultHooks() {
    var eu = sym('_ZN18ResultUnitExpScene11updateEventEv');
    var mu = sym('_ZN22MissionResultUnitScene11updateEventEv');
    var mv = sym('_ZN22MissionResultUnitScene14onSceneVisibleEv');
    var ms = sym('_ZN22MissionResultUnitScene13skipAnimationEv');
    var n = 0;
    if (eu) Interceptor.attach(eu, { onEnter: function (a) {
      try { if (!RT.res) return; var p = a[0].add(0x731); if (p.readU8() === 0) { p.writeU8(1); if (++n <= 5) log('Skip results: exp screen skip flag set on ' + a[0]); } }
      catch (e) { log('Skip results (exp) error: ' + e); }
    } });
    else log('Skip results: exp screen symbol missing');
    if (mu && mv && ms) {
      var skip = new NativeFunction(ms, 'void', ['pointer']);
      var frames = {};
      Interceptor.attach(mv, { onEnter: function (a) { frames[a[0].toString()] = 0; } });
      Interceptor.attach(mu, { onEnter: function (a) {
        try {
          if (!RT.res) return;
          var k = a[0].toString();
          if (frames[k] === undefined) return;
          if (++frames[k] === 40) { log('Skip results: unit result skip on ' + a[0]); skip(a[0]); }
        } catch (e) { log('Skip results (unit) error: ' + e); }
      } });
    } else log('Skip results: unit result symbols missing');

    // which result screens are up
    var names = ['18MissionResultScene', '18ResultUnitExpScene', '22MissionResultUnitScene', '26MissionResultMaterialScene',
      '28MissionResultGetUnitExpScene', '18ResultSummaryScene', '27MissionResultChallengeScene', '15ResultItemScene'];
    var up = {}, nUp = 0;
    names.forEach(function (c) {
      var v = game.findExportByName('_ZN' + c + '14onSceneVisibleEv');
      var iv = game.findExportByName('_ZN' + c + '16onSceneInvisibleEv');
      if (v) Interceptor.attach(v, { onEnter: function (a) { var k = a[0].toString(); if (!up[k]) { up[k] = c; nUp++; } } });
      if (iv) Interceptor.attach(iv, { onEnter: function (a) { var k = a[0].toString(); if (up[k]) { delete up[k]; nUp--; } } });
    });

    // tap through the game's own touch input
    var tb = sym('_ZN7cocos2d6GLView18handleTouchesBeginEiPlPfS2_');
    var te = sym('_ZN7cocos2d6GLView16handleTouchesEndEiPlPfS2_');
    var dg = game.findExportByName('_ZN7cocos2d8Director11getInstanceEv');
    var sc = game.findExportByName('Java_org_cocos2dx_lib_Cocos2dxRenderer_nativeOnSurfaceChanged');
    if (!tb || !te) { log('Skip results: touch symbols missing, no auto tap'); return; }
    var fB = new NativeFunction(tb, 'void', ['pointer', 'int', 'pointer', 'pointer', 'pointer']);
    var fE = new NativeFunction(te, 'void', ['pointer', 'int', 'pointer', 'pointer', 'pointer']);
    var view = null, surf = { w: 0, h: 0 }, last = null, synth = false;
    var ids = Memory.alloc(16), xs = Memory.alloc(16), ys = Memory.alloc(16);
    ids.writeS64(7771);
    Interceptor.attach(tb, { onEnter: function (a) {
      if (synth) return;
      view = a[0];
      try { if (a[1].toInt32() >= 1) last = { x: a[3].readFloat(), y: a[4].readFloat() }; } catch (e) {}
    } });
    if (sc) Interceptor.attach(sc, { onEnter: function (a) { surf.w = a[2].toInt32(); surf.h = a[3].toInt32(); } });
    function glView() {
      if (view) return view;
      if (!dg) return null;
      try {
        var v = new NativeFunction(dg, 'pointer', [])().add(0x110).readPointer();
        if (v.isNull()) return null;
        var vt = v.readPointer();
        if (vt.compare(game.base) < 0 || vt.compare(game.base.add(game.size)) >= 0) return null;
        return v;
      } catch (e) { return null; }
    }
    function pos() {
      var W = land.on && land.W ? land.W : surf.w, H = land.on && land.H ? land.H : surf.h;
      if (land.on && land.PW) return { x: land.X0 + land.PW / 2, y: H / 2 };
      if (W && H) return { x: W / 2, y: H / 2 };
      return last;
    }
    var ph = 0, fr = 0, warned = false;
    ensureTick();
    tickFns.push(function () {
      if (nUp > 0) RT.resAt = Date.now();
      var active = RT.res && nUp > 0 && !(RT.rep && RT.sumUp);
      if (ph === 0) { if (!active) return; if (++fr < 45) return; }
      else if (++fr < 4) return;
      var v = glView(), p = pos();
      if (!v || !p) { if (!warned) { warned = true; log('Skip results: no touch view or screen size yet'); } fr = 0; return; }
      xs.writeFloat(p.x); ys.writeFloat(p.y);
      synth = true;
      try {
        if (ph === 0) { fB(v, 1, ids, xs, ys); ph = 1; }
        else { fE(v, 1, ids, xs, ys); ph = 0; if (++RT.taps <= 5) log('Skip results: tapped at ' + Math.round(p.x) + ',' + Math.round(p.y)); }
      } finally { synth = false; }
      fr = 0;
    });
    log('Skip results: hooks installed');
  }

  // ---------- Landscape mode ----------
  // The game keeps a portrait-shaped frame. When the phone is turned, the game is told a portrait size of the same
  // shape (strip width = H * H / W), drawn in the middle of the real surface. Black bars on both sides.
  // Needs: the strip size passed to the game, GL viewport/scissor shifted right by the bar width (default framebuffer
  // only), and touch x shifted back.
  var land = { W: 0, H: 0, PW: 0, X0: 0, fb: 0, frameW: 0, frameH: 0, on: false, vp: 0, sc: 0, tc: 0 };
  function landCalc(w, h) {
    land.W = w; land.H = h;
    if (w > h) { land.PW = Math.round(h * h / w); land.X0 = Math.floor((w - land.PW) / 2); }
    else { land.PW = w; land.X0 = 0; }
  }
  function landHooks() {
    var gl = Process.findModuleByName('libGLESv2.so');
    if (!gl) { log('landscape: libGLESv2.so not loaded'); return; }
    var gv = gl.findExportByName('glViewport');
    var gs = gl.findExportByName('glScissor');
    var gb = gl.findExportByName('glBindFramebuffer');
    var ni = sym('Java_org_cocos2dx_lib_Cocos2dxRenderer_nativeInit');
    var sc = sym('Java_org_cocos2dx_lib_Cocos2dxRenderer_nativeOnSurfaceChanged');
    var tb = sym('_ZN7cocos2d6GLView18handleTouchesBeginEiPlPfS2_');
    var te = sym('_ZN7cocos2d6GLView16handleTouchesEndEiPlPfS2_');
        var tm2 = sym('_ZN7cocos2d6GLView17handleTouchesMoveEiPlPfS2_S2_S2_');
    var tc = sym('_ZN7cocos2d6GLView19handleTouchesCancelEiPlPfS2_');
    var dg = sym('_ZN7cocos2d8Director11getInstanceEv');
    var dv = sym('_ZN7cocos2d8Director11setViewportEv');
    var sd = sym('_ZN7cocos2d6GLView23setDesignResolutionSizeEff16ResolutionPolicy');
    if (!gv) log('landscape: glViewport not found in libGLESv2.so');
    if (!gs) log('landscape: glScissor not found in libGLESv2.so');
    if (!gb) log('landscape: glBindFramebuffer not found in libGLESv2.so');
    if (!gv || !gs || !gb || !ni || !sc || !tb || !te || !tm2 || !tc || !dg || !dv || !sd) { log('Landscape: symbols missing, not installed'); return; }
    var fDir = new NativeFunction(dg, 'pointer', []);
    var fVp = new NativeFunction(dv, 'void', ['pointer']);
    var fDes = new NativeFunction(sd, 'void', ['pointer', 'float', 'float', 'int']);

    Interceptor.attach(gb, { onEnter: function (a) { land.fb = a[1].toInt32(); } });
    Interceptor.attach(gv, { onEnter: function (a) {
      if (land.X0 && land.fb === 0) { land.vp++; a[0] = ptr(a[0].toInt32() + land.X0); }
    } });
    Interceptor.attach(gs, { onEnter: function (a) {
      if (land.X0 && land.fb === 0) { land.sc++; a[0] = ptr(a[0].toInt32() + land.X0); }
    } });

    // the size the game is told
    Interceptor.attach(ni, { onEnter: function (a) {
      landCalc(a[2].toInt32(), a[3].toInt32());
      a[2] = ptr(land.PW);
      land.frameW = land.PW; land.frameH = land.H;
      log('landscape: init surface ' + land.W + 'x' + land.H + ' -> game strip ' + land.PW + 'x' + land.H + ' offset ' + land.X0);
    } });
    Interceptor.attach(sc, { onEnter: function (a) {
      try {
        landCalc(a[2].toInt32(), a[3].toInt32());
        a[2] = ptr(land.PW);
        log('landscape: surface ' + land.W + 'x' + land.H + ' -> strip ' + land.PW + 'x' + land.H + ' offset ' + land.X0);
        if ((land.frameW && land.frameW !== land.PW) || (land.frameH && land.frameH !== land.H)) {
          // the phone was turned: give the game the new frame and redo its viewport
          var d = fDir();
          var view = d.add(0x110).readPointer();
          if (view.isNull()) { log('landscape: no GL view yet'); return; }
          var setFrame = new NativeFunction(view.readPointer().add(0x50).readPointer(), 'void', ['pointer', 'float', 'float']);
          setFrame(view, land.PW, land.H);
          var dw = view.add(0x2c).readFloat(), dh = view.add(0x30).readFloat(), pol = view.add(0x68).readS32();
          fDes(view, dw, dh, pol);
          fVp(d);
          land.frameW = land.PW; land.frameH = land.H;
          log('landscape: frame reset, design ' + dw + 'x' + dh + ' policy ' + pol);
        }
      } catch (e) { log('landscape resize error: ' + e); }
    } });

    // touches: shift x back, ignore taps on the black bars
    function fix(a, begin) {
      if (!land.X0) return;
      var n = a[1].toInt32(), xs = a[3];
      land.tc++;
      if (n < 1 || n > 10) return;
      if (begin && n === 1) {
        var x0 = xs.readFloat() - land.X0;
        if (x0 < 0 || x0 > land.PW) { a[1] = ptr(0); return; }      // a tap on a black bar
      }
      for (var i = 0; i < n; i++) {
        var x = xs.add(i * 4).readFloat() - land.X0;
        if (x < 0) x = 0;
        if (x > land.PW - 1) x = land.PW - 1;
        xs.add(i * 4).writeFloat(x);
      }
    }
    Interceptor.attach(tb, { onEnter: function (a) { fix(a, true); } });
    Interceptor.attach(te, { onEnter: function (a) { fix(a, false); } });
    Interceptor.attach(tc, { onEnter: function (a) { fix(a, false); } });
    Interceptor.attach(tm2, { onEnter: function (a) { fix(a, false); } });   // the 4-argument Move calls this one
    land.on = true;
    log('Landscape hooks installed');
  }

  cmd.qtest = function () {
    var out = ['landscape ' + (land.on ? 'on' : 'off') + ' surface ' + land.W + 'x' + land.H + ' strip ' + land.PW + ' offset ' + land.X0
      + ' viewport shifts ' + land.vp + ' scissor shifts ' + land.sc + ' touch fixes ' + land.tc, 'skips waiting ' + skips.length, 'quick hooks ' + (hooksOk ? 'on' : 'off'), 'on unit menu ' + utopVisible, 'stage ' + stage,
      'label draws ' + labelHits, 'level hits ' + lvHits + ', rarity calls ' + raCalls + ', hits ' + rarHits + ', empty unit ' + nullUnit, 'errors ' + errs];
    return out.join('\n');
  };
  cmd.res = function (t) { RT.res = (t.split(' ')[1] === '1'); return 'ok'; };
  RT.req = { seq: 0, name: '' };
  // Show or hide the in-game moogle button live ("ms 1" / "ms 0"). Hiding takes the button down at once; showing draws it again on the next tick.
  // While the button is hidden the panel cannot be opened in the game; turn it back On in the app.
  cmd.ms = function (t) {
    var on = t.split(' ')[1] !== '0';
    OPT.mshow = on ? 1 : 0;
    try { if (globalThis.__kupoMoogle) globalThis.__kupoMoogle(on); } catch (e) { log('Moogle toggle failed: ' + e); }
    return 'ok';
  };
  cmd.fn = function (t) { RT.fnApp = (t.split(' ')[1] === '1'); return 'ok'; };
  cmd.cs = function (t) { RT.cloudApp = (t.split(' ')[1] === '1'); return 'ok'; };
  // the app tells the game the Gambit preset names and which one is active ("gnames {a: index, n: [names]}"), for the "Choose gambit" menu
  cmd.gnames = function (t) {
    try { var o = JSON.parse(t.substring(7)); RT.gnames = o.n instanceof Array ? o.n : []; RT.gactive = o.a | 0; return 'ok ' + RT.gnames.length; }
    catch (e) { return 'error ' + e; }
  };
  globalThis.FFBE_XTRA = function () {
    var now = Date.now(), scr = now - RT.battleAt < 1500 ? 1 : (now - RT.resAt < 1500 ? 2 : 0);
    return 'q' + (hooksOk && utopVisible && !stage ? 1 : 0) + ' b' + scr + ' a' + (OPT.auto ? (RT.auto ? 1 : 0) : 'x') + ' s' + (OPT.res ? (RT.res ? 1 : 0) : 'x') + ' r' + (OPT.rep ? (RT.rep ? 1 : 0) : 'x') + ' c' + (OPT.cloud ? 1 : 'x') + ' k' + (OPT.bot ? (RT.bot ? 1 : 0) : 'x') + ' g' + RT.req.seq + ':' + (RT.req.name || '-');
  };


  // ---------- Cloud save sync: the game side only hands over and takes in the save file ----------
  // The editor app does the folder work (it can read and write the synced folder; the game often cannot read files that
  // a cloud app created). Commands over the local socket:  shash = hash and size of the phone save;  sget off len = a piece
  // (base64);  sput idx total b64 = stage a piece;  sapply hash len = check the staged file, keep the old save as a private
  // copy, write the new one, then restart the game.
  // ---------- Battle bot test (log only, casts nothing) ----------
  // Writes what the ability menu knows to the patch log: the unit, each ability's type and id, and whether it can be used now.
  // Also logs which ability the player casts. This is only to learn the game's data before any bot is built.
  var SKT = {"201130":10.0,"200140":"b","204560":1.8,"204570":1.7,"204550":"b","20010":1.2,"20060":1.2,"20080":15.0,"20130":15.0,"20200":30.0,"20250":30.0,"200320":1.2,"200330":1.1,"201030":"o","205550":1.8,"205530":3.6,"205510":1.2,"20020":1.2,"20050":1.2,"20090":15.0,"20120":15.0,"20210":30.0,"20240":30.0,"200390":1.2,"200060":"h","200470":"o","200450":"b","213290":2.2,"213280":"h","10020":"h","10030":"h","30090":"b","10040":"h","10050":"h","10060":1.2,"30140":"b","10070":"h","10140":"h","10150":"h","10160":"h","10100":"h","10110":"h","204380":"o","204360":"o","200160":"o","204350":9.0,"226761":"o","226762":"o","226763":"o","226765":"b","226766":"o","236780":35.0,"236781":45.0,"236782":50.0,"236783":58.0,"236784":63.0,"236787":40.0,"236788":45.0,"236789":50.0,"236790":55.0,"236791":60.0,"236792":"o","236793":"o","20220":30.0,"20230":30.0,"20260":60.0,"20190":0.3,"20270":1.5,"20440":37.5,"20390":36.0,"20400":36.0,"236800":48.0,"236801":57.0,"236802":71.0,"236803":76.0,"236804":88.0,"236805":90.0,"236806":"o","202960":"b","202950":"b","20040":1.2,"30040":"b","20110":15.0,"30060":"b","30200":"o","30120":"b","30170":"b","201360":1.2,"200050":"x","201760":"b","201310":1.3,"201430":1.8,"201770":"b","201290":"h","201780":"o","200460":"b","206920":1.5,"206930":1.5,"206940":1.5,"206950":3.0,"206960":3.0,"206970":3.0,"206990":"b","206980":"b","20030":1.2,"20100":15.0,"20310":52.5,"204630":1.6,"200410":"o","200250":"o","204660":1.8,"204640":1.6,"204670":1.7,"204650":1.6,"200210":"b","200280":0.8,"200480":"b","10010":"x","200010":"o","30010":"o","200120":"b","200200":1.7,"200220":"h","201350":1.2,"201400":1.7,"200850":"h","200830":"o","200880":"b","200820":"o","200340":1.4,"200350":2.7,"30030":"b","30050":"b","30100":"o","30020":"o","30110":"b","30130":"b","200510":1.8,"200520":1.8,"200530":1.8,"200590":1.8,"200540":1.8,"200550":1.8,"200610":2.1,"200620":2.1,"200630":2.1,"200080":"o","200100":"h","10080":"h","10120":"h","10090":"h","20150":1.0,"20180":30.0,"200400":1.8,"200600":1.8,"30160":"o","30150":"o","201140":10.0,"201150":1.2,"200170":60.0,"200800":1.9,"20160":0.8,"20280":"o","200150":"o","30210":"b","30220":"b","30260":"b","30270":"b","30280":"b","30320":"b","219440":"h","219460":"h","219450":"h","201340":1.2,"201420":2.0,"201380":1.5,"201410":2.2,"201180":"o","201200":"h","201210":"o","200260":1.8,"200270":1.1,"200430":240.0,"200180":"o","201020":"o","200960":1.8,"201000":3.2,"201040":"o","201050":"o","218130":2.7,"217700":4.5,"218090":6.0,"218100":2.0,"218110":2.0,"218120":"o","218140":3.6,"200810":"o","200860":"h","200840":1.5,"201550":"b","200300":21.0,"200930":"b","200230":60.0,"200650":2.1,"200640":2.1,"200660":2.1,"201240":"h","200740":2.7,"200750":2.7,"200730":2.7,"201160":1.2,"202130":"h","211980":"h","212000":1.6,"203360":"b","211990":"o","30330":"b","200310":60.0,"200360":"b","200370":"b","204370":2.0,"204510":"o","204520":2.1,"204500":2.1,"201500":1.5,"200940":1.8,"201620":"o","200980":3.2,"201630":80.0,"212020":4.5,"212050":3.0,"212040":"o","212030":6.0,"204620":"h","204470":1.5,"204450":2.1,"204460":2.7,"204480":2.8,"204420":"h","204440":2.7,"204410":"h","204430":"b","204400":"b","200020":1.2,"204060":"b","204050":2.7,"202670":1.6,"202690":1.6,"204850":1.6,"204860":1.6,"204840":1.6,"202710":2.1,"204810":"b","201190":"o","204750":"o","204010":"o","204770":"b","204780":"b","204790":"b","204800":"b","205150":2.0,"205180":"o","205140":2.0,"205170":"o","205160":"o","205120":4.2,"205400":1.8,"205410":1.5,"200440":87.0,"205420":2.8,"206280":2.7,"206310":"b","206290":2.1,"206300":"b","206260":3.4,"206330":2.1,"206340":3.4,"206370":"b","206350":3.8,"224130":"b","224140":"b","20430":1.5,"205080":"b","205070":"o","205060":"o","205050":"b","226781":"o","226782":"b","226783":"o","30370":"o","30290":"b","30300":"b","30310":"b","206200":1.8,"206230":"h","206220":"h","206210":1.8,"206240":"b","206190":2.0,"226801":"o","226802":"b","226803":"b","236600":35.0,"236601":44.0,"236602":52.5,"236603":60.0,"236604":66.0,"236606":"b","236607":"b","236608":"b","236609":"b","236610":"b","236611":"b","236618":120.0,"236619":144.0,"236620":158.0,"236621":166.0,"236622":175.0,"236623":"b","236624":"o","236625":"b","236626":"o","206380":1.2,"224070":2.4,"207130":1.4,"207120":1.2,"207140":"b","207110":1.8,"207100":"b","226831":"o","226832":4.0,"226833":4.0,"226834":"o","201260":1.2,"201270":1.2,"201280":1.2,"207240":"x","201300":140.0,"201330":1.4,"207220":"o","207190":2.1,"207200":2.7,"207230":15.0,"207210":2.1,"207180":"o","207250":1.2,"207270":1.2,"207260":1.2,"207280":1.2,"207300":1.8,"207290":1.8,"214070":"h","214040":3.0,"214060":"b","214050":3.0,"207320":1.3,"207350":"h","207340":"h","207360":"b","207330":1.2,"208830":1.4,"208840":2.7,"208850":"b","208820":2.1,"223320":"h","223290":2.5,"223330":"b","223310":2.5,"223280":3.0,"223300":3.0,"208940":"o","208800":"o","200090":"o","208750":"b","208950":"b","208790":"o","208960":"b","208930":2.7,"208910":1.7,"208650":"b","208640":"b","208630":75.0,"208610":50.0,"226862":"o","226861":"b","210970":"o","210980":"o","210960":14.1,"20450":1.2,"20460":15.0,"20470":30.0,"20290":52.5,"202320":1.3,"202330":1.3,"208710":2.7,"202340":1.0,"208890":"b","208700":2.1,"208690":3.0,"208130":"b","208190":"o","208170":"h","208180":"o","208150":9.0,"208140":"o","226841":"o","226843":"b","226844":"o","20380":36.0,"208210":"o","208220":"o","208200":"o","209710":1.5,"209730":1.5,"209700":2.7,"209720":3.0,"200690":1.7,"209640":"h","209750":"b","209740":3.0,"209680":4.1,"209610":"o","209600":"o","209590":"o","209650":"b","209630":15.0,"209620":15.0,"209570":6.0,"227081":25.0,"227082":"o","10190":"h","10210":1.0,"209880":1.5,"209910":1.6,"209890":3.0,"209900":"h","209920":2.1,"209870":"o","219830":"o","219850":"b","219840":"b","210730":225.0,"210740":"b","210710":2.0,"211110":2.0,"226871":6.0,"226873":"o","236330":28.0,"236331":36.0,"236332":43.0,"236333":48.0,"236334":52.0,"236336":30.0,"236337":37.5,"236338":45.0,"236339":52.5,"236340":60.0,"236341":750.0,"20410":3.2,"236348":130.0,"236349":160.0,"236350":190.0,"236351":220.0,"236352":250.0,"236353":65.0,"236354":50.0,"236355":68.0,"236356":56.0,"212200":2.0,"212190":3.6,"212250":"b","212240":3.8,"20330":52.5,"212290":"o","212280":"o","212300":"o","20320":52.5,"210810":1.5,"210830":1.5,"210840":1.5,"210850":"b","210820":1.5,"210780":2.5,"227131":9.0,"227132":"b","227133":"o","227134":"o","210920":"b","210900":2.7,"210940":"b","210910":"b","210930":"o","210880":4.1,"20340":52.5,"212080":"h","212120":"h","212070":"o","212100":12.0,"212110":2.2,"212090":10.5,"227141":"b","227142":"o","227143":"o","200030":"o","209800":2.1,"209820":"h","209850":"h","209810":4.5,"209830":"b","209840":"h","213940":"h","202260":"b","213960":"h","213920":"o","213950":"h","213970":"h","227510":"o","227511":"o","227512":"o","10200":"o","10290":"h","10180":"h","10230":"h","211050":"o","211060":"o","211070":"o","211080":"o","211010":"o","211020":"o","211030":"o","211040":"o","211190":1.0,"211200":1.0,"211210":1.0,"211220":1.0,"211230":1.0,"211240":1.0,"211300":0.9,"211270":"b","211260":1.0,"211250":1.0,"211180":"h","219500":"o","212440":"b","212410":2.1,"212430":"b","212420":3.2,"212400":2.4,"212530":"h","212520":"b","212480":1.2,"212510":"b","212470":3.0,"212540":"b","212500":4.5,"212490":1.8,"213830":"b","213840":"o","206500":"o","206510":"o","213810":1.5,"213820":4.0,"213790":"b","227161":6.0,"227162":6.0,"227163":6.0,"227165":"o","227164":9.3,"237000":52.0,"237001":62.0,"237002":72.0,"237003":79.0,"237004":87.0,"237005":66.0,"237006":66.0,"237008":"o","237010":58.0,"237011":69.0,"237012":78.0,"237013":86.0,"237014":94.0,"237015":157.5,"237016":"o","237022":63.0,"237023":75.0,"237024":86.0,"237025":94.0,"237026":103.0,"237027":78.0,"237028":78.0,"237029":180.0,"237030":"o","215840":"h","215860":"o","215810":"b","215800":"b","215790":"h","215830":"b","215820":"b","215850":"o","208500":1.2,"215640":"o","215630":2.0,"215660":"o","215650":"o","215620":"h","215670":1.0,"215680":1.4,"20070":10.0,"215560":2.7,"215590":2.7,"214960":3.0,"215550":"h","215570":2.7,"215580":2.7,"215760":"h","215720":"h","215740":"b","215730":"o","215750":"o","215920":3.8,"215910":"b","215880":"b","215900":"o","215890":"o","10170":30.0,"215990":2.7,"215960":"o","215950":"h","215980":2.7,"215940":"b","215970":2.7,"216000":4.2,"215010":3.0,"215020":3.0,"215040":"o","215060":"o","215070":"o","215080":"o","215090":"o","215050":"b","215030":4.5,"227522":"b","227523":9.0,"227520":"o","227521":"o","214940":"o","201440":1.6,"214950":"h","201450":2.1,"226150":1.8,"226160":150.0,"226170":100.0,"226140":100.0,"226130":150.0,"226180":1.8,"226120":4.8,"226190":"h","228080":150.0,"228081":3.5,"228082":"o","228083":"o","239220":140.0,"239221":155.0,"239222":170.0,"239223":185.0,"239224":200.0,"239226":"o","239227":"o","20300":52.5,"239228":86.0,"239229":102.0,"239230":118.0,"239231":134.0,"239232":150.0,"239233":"o","239234":"o","239235":"h","214180":2.7,"214150":"b","214140":6.0,"214190":2.7,"214160":"b","214120":6.0,"200770":4.6,"214130":6.0,"214170":6.0,"214110":6.0,"214240":2.2,"214220":2.2,"214250":4.5,"214260":1.5,"214230":13.5,"200970":1.8,"214310":1.5,"201010":300.0,"214300":1.5,"214270":3.2,"214280":4.5,"218170":4.5,"218180":6.0,"218210":3.0,"218220":3.0,"218190":6.0,"218200":6.4,"218230":2.7,"215210":"h","215240":"o","215250":"h","215540":"o","227532":"o","227533":"o","214900":"b","214890":"o","214910":"b","214920":3.0,"215460":"b","215450":"b","215270":"b","215280":"b","215330":"x","215500":"b","215490":"b","215480":"b","215470":"b","215290":"b","215300":"b","215510":"b","215520":"b","215530":"o","215320":"b","215310":"b","215370":1.8,"215420":3.0,"215380":1.0,"215410":2.7,"215390":1.0,"215400":"b","216420":2.0,"216430":2.0,"216440":2.0,"216460":2.5,"216480":2.7,"216490":1.6,"216450":2.0,"227540":"b","227541":6.4,"227542":"o","216530":3.5,"216600":"b","216590":"b","216550":"b","216540":"b","216560":"h","216620":2.8,"216610":2.8,"216580":"b","216570":"b","216690":"b","216650":"h","216670":"b","216640":"b","216660":"b","216680":"o","10130":"o","216760":"b","216750":1.5,"216810":"o","216740":1.5,"216770":"o","216800":"o","216780":"h","216790":"o","216820":"h","225230":"o","225210":"b","225220":"b","216830":1.5,"216840":1.5,"216860":1.5,"216880":2.7,"216870":2.0,"216850":1.5,"216890":1.6,"217740":"o","217710":"h","217720":2.7,"218350":"o","217750":5.5,"217760":6.8,"217730":1.0,"218340":"b","217790":2.0,"217770":"b","227963":18.0,"227961":"o","227960":2.4,"227962":"o","218010":3.0,"217960":2.0,"218000":"b","217970":1.9,"217990":"b","217980":3.0,"205360":2.0,"217840":2.7,"217850":"b","218060":"o","218040":"h","218030":"o","218050":"o","218260":3.5,"218280":3.4,"218290":2.0,"227560":5.2,"227561":4.5,"227562":"o","236640":30.0,"236641":40.0,"236642":50.0,"236643":55.0,"236644":60.0,"236647":23.0,"236648":30.0,"236649":37.0,"236650":45.0,"236651":49.0,"236652":40.0,"236659":69.0,"236660":76.0,"236661":83.0,"236662":92.0,"236663":96.0,"236664":120.0,"236665":"o","236666":60.0,"236667":75.0,"221010":"h","221020":"h","221030":"h","221050":"h","221070":5.4,"221080":"b","221060":5.2,"227600":"o","227601":"o","237740":"h","237741":"h","237742":"h","237743":"h","237744":"h","237745":"h","237746":"h","237747":"h","237748":"h","237749":"h","237750":"b","237751":"h","237752":"h","237753":"h","237754":"h","237755":"h","237756":"h","237757":"h","237758":192.0,"223830":3.0,"223840":3.0,"223850":"b","223870":1.5,"223860":1.5,"223930":"o","223920":"h","223900":"o","223910":"o","223890":"o","223880":"o","228032":"o","228031":"o","228033":"o","222940":3.6,"222950":3.8,"222960":2.5,"222900":1.5,"222910":1.5,"222920":1.5,"222930":1.5,"227992":7.2,"227993":6.2,"227994":"o","227990":"o","227991":"o","223110":1.8,"223100":2.4,"223160":2.7,"223120":3.8,"223150":"o","223130":3.2,"223250":7.5,"223260":"b","223240":"o","223270":"b","223200":"h","223220":2.5,"223210":"o","223190":"b","219360":1.8,"219370":1.8,"219380":1.8,"219320":15.0,"219330":3.4,"219340":3.4,"219350":3.4,"219290":"b","219300":"b","219390":"b","219310":"h","219490":9.8,"227591":"b","227590":"o","227592":"o","227593":"o","236820":"b","236821":"b","236822":"b","236823":"b","236824":"b","236825":"o","236828":44.0,"236829":48.0,"236830":58.0,"236831":62.0,"236832":78.0,"236833":450.0,"236834":"o","236840":50.0,"236841":60.0,"236842":70.0,"236843":80.0,"236844":90.0,"236845":"h","236846":"o","236847":"o","224970":"b","224990":"b","225080":2.7,"224960":"b","224980":"b","225050":"h","225010":"b","225020":"b","225000":"b","225060":"h","225070":"h","225030":"b","225040":"h","228073":"o","228072":"o","228071":"o","228070":"b","20760":12.0,"220770":12.0,"220780":12.0,"220790":12.0,"220800":12.0,"220820":5.5,"220830":5.5,"220840":5.5,"220850":5.5,"220860":5.5,"220700":5.5,"220710":5.5,"220720":5.5,"220730":5.5,"220740":5.5,"220760":11.0,"220750":7.0,"227610":16.5,"227611":"o","227612":"o","238310":123.0,"238311":129.0,"238312":135.0,"238313":141.0,"238314":147.0,"238316":123.0,"238317":129.0,"238318":135.0,"238319":141.0,"238320":147.0,"238321":1.0,"238322":1.0,"238323":"o","238324":72.5,"238325":78.5,"238326":84.5,"238327":90.5,"238328":96.5,"238329":"o","238330":201.0,"220950":4.0,"220980":"h","220900":"o","221340":4.0,"221350":4.0,"220920":"h","220930":10.0,"220940":10.0,"220910":"o","220960":"b","220970":"b","219510":"o","219530":"o","219540":"o","219550":"o","219520":"o","219630":4.0,"219650":2.2,"219660":2.2,"219680":2.7,"219690":6.0,"219640":2.8,"219670":2.2,"219700":5.4,"219710":"b","221210":"b","221200":"b","221180":2.7,"221190":"b","221170":3.0,"223070":"b","223020":4.8,"223040":3.0,"223050":3.2,"223060":"b","223030":4.2,"228003":2.4,"228000":"o","228001":"o","212990":3.0,"223990":2.7,"224030":"b","224000":3.5,"224020":4.2,"224040":"b","224010":"b","224780":"b","224790":"b","230103":"h","230102":"o","230104":"b","230101":2.5,"230100":3.2,"224630":2.0,"224660":10.0,"224640":2.7,"224720":"o","224700":"o","224710":"o","224650":6.5,"224690":"h","224670":11.2,"224680":5.2,"224740":"h","228062":3.6,"228063":"h","228060":"o","228061":"o","224810":"h","224830":"h","224820":"h","10380":"h","228046":"h","228040":"o","228041":"o","241798":"b","241780":1.5,"241781":1.5,"241782":1.5,"241783":1.5,"241784":1.5,"241785":"b","30380":"o","30400":"o","30440":"o","20360":36.0,"20550":12.0,"225260":"o","225270":"b","225280":3.0,"225160":3.0,"225190":"h","225140":"o","225150":"o","225170":1.8,"225180":"o","225310":2.8,"225330":"b","225320":3.6,"226392":1.7,"226393":"b","226397":2.1,"226399":2.1,"226398":4.8,"226390":3.0,"226394":"b","226395":4.8,"226396":6.0,"226400":4.8,"226401":4.8,"226391":4.5,"226290":"o","226260":"h","226280":"b","226270":"b","226250":"b","226340":2.1,"226350":2.2,"226330":"b","226360":"b","226230":1.3,"226210":"o","226910":2.5,"226911":4.5,"226914":"h","226915":"b","226912":4.0,"226913":2.5,"226916":"o","237500":60.0,"237501":63.0,"237502":66.0,"237503":70.0,"237504":73.0,"237506":"o","237507":"o","237509":70.5,"237510":81.0,"237511":91.5,"237512":102.0,"237513":112.5,"237514":121.5,"237515":"o","237522":90.0,"237523":103.5,"237524":114.0,"237525":120.0,"237526":126.0,"237527":100.0,"237528":"o","237529":"o","226930":1.8,"226931":2.4,"226934":"o","226932":3.4,"226935":"o","226933":3.0,"226950":2.8,"226951":3.8,"226953":1.2,"226952":1.6,"226955":"b","226956":2.0,"226970":3.6,"226974":3.0,"226971":2.5,"226972":"b","226973":2.3,"226411":"b","226410":3.6,"226413":1.7,"226412":"b","226414":"o","228091":3.6,"228092":3.6,"228094":"o","228093":"o","228090":"h","227287":"b","227282":3.5,"227283":3.5,"227284":2.1,"227285":2.7,"227286":"b","227281":4.2,"227280":3.2,"227288":"o","227292":"o","227293":"o","227302":"o","227301":5.0,"227300":"b","227311":1.2,"227312":"b","227310":2.5,"226672":1.4,"226671":3.0,"226674":"o","226673":"o","226670":2.2,"227343":3.0,"227344":1.1,"227345":"b","227346":1.4,"227347":3.8,"227352":"b","227351":4.5,"227353":"b","227350":3.0,"227670":2.1,"227675":1.2,"227673":1.2,"227674":1.2,"227671":4.2,"227678":1.5,"227677":"h","227676":"b","227672":2.1,"227690":2.0,"227693":2.0,"227691":2.0,"227694":2.0,"227695":2.0,"227692":2.0,"227697":2.0,"227698":"b","227696":2.0,"227654":"b","227651":"h","227655":"b","227652":6.8,"227653":5.2,"227650":10.0,"227662":"h","227663":"o","241750":60.0,"241751":72.0,"241752":84.0,"241753":96.0,"241754":108.0,"241755":"o","241756":"o","241757":"o","241758":"o","241759":"o","241760":"b","228104":"b","228105":0.4,"228103":"b","228101":"h","228190":"o","228102":"h","228191":"o","228110":"h","228106":"o","228107":"o","228108":"o","228109":"o","228123":1.5,"228120":"o","228122":"b","228130":17.2,"228131":"o","228132":"h","228145":"h","228146":"h","228142":3.2,"228144":"o","228143":2.5,"228141":"o","230081":1.0,"230082":"b","230092":"h","230083":"b","230084":"b","230085":"b","230086":"b","230087":"b","230088":"b","230089":"b","230090":"o","230091":"o","228494":"b","228495":"b","228496":"b","228497":"h","228498":"h","228499":"h","228503":"h","228504":"h","228505":"b","228506":"b","228508":1.0,"228509":"h","228510":"h","228507":"b","228513":"b","228514":"b","228515":"o","228516":"o","238270":"b","238271":"b","238272":"b","238273":"b","238274":"b","238275":"b","238276":"b","238277":"b","238278":"b","238279":"b","238280":"b","238281":"b","238282":"h","238283":"h","238284":"h","238285":"h","238286":"h","238288":"h","238289":"b","238290":"o","238291":"o","238292":"b","228520":2.5,"228521":2.0,"228524":2.8,"228525":3.6,"228526":"b","228529":3.8,"228530":3.4,"228528":4.2,"228540":3.0,"228541":4.4,"228542":2.5,"228543":1.5,"228545":"b","228546":3.0,"228547":4.2,"228403":32.0,"228401":"o","228417":49.2,"228402":32.0,"228404":2.5,"228405":"o","228406":"o","228407":"o","228408":"o","241020":57.0,"241021":61.5,"241022":66.0,"241023":70.5,"241024":75.0,"241025":"b","241026":"b","241027":"b","241028":"b","241029":"b","30190":"b","241030":"b","241031":"b","241032":"b","241033":"b","241034":"b","241035":"o","241036":1.5,"228422":2.1,"228424":2.2,"228425":2.2,"228421":4.5,"228420":2.2,"228433":"b","228431":2.8,"228432":"b","228430":9.0,"228441":"h","228443":"h","228439":2.5,"228442":"h","228438":2.5,"228444":"o","228440":2.7,"228437":1.8,"228627":"b","228624":"h","228625":0.4,"228626":"b","228623":"h","228622":"h","228621":"b","228628":"o","228629":"o","228630":"o","228631":"h","10310":"o","10330":"o","10350":"o","10370":"o","30610":"b","201170":1.6,"228660":1.8,"228654":1.0,"228653":2.1,"228652":2.5,"228650":3.8,"228651":3.8,"228641":2.1,"201230":0.3,"228642":3.4,"228640":"b","228963":2.0,"228973":"b","228964":1.1,"228968":"b","228970":"b","228962":2.8,"228969":"b","228974":"b","228971":"b","228961":2.5,"228975":"o","228965":7.5,"228966":"o","228972":"o","239650":"o","239651":45.0,"239652":52.0,"239653":57.0,"239654":65.0,"239655":68.0,"239656":55.5,"239657":64.5,"239658":76.5,"239659":85.5,"239660":90.0,"239661":"o","239662":100.0,"228993":"o","228992":2.8,"228991":2.7,"228994":"o","228990":2.5,"229013":2.7,"229016":2.7,"229014":2.7,"229017":2.7,"229012":2.7,"229015":2.7,"229011":2.7,"229010":1.0,"229025":"b","229024":"b","229023":"b","229022":"b","229020":"b","229021":"b","229392":11.2,"229393":11.2,"229394":5.0,"229398":8.2,"229399":16.5,"229400":16.5,"229401":16.5,"229405":26.2,"229406":"o","229404":"o","229412":200.0,"229413":"h","229414":2.8,"229418":6.0,"229419":225.0,"229420":5.6,"229425":"o","229426":7.0,"229427":"o","229428":"o","229373":2.0,"229374":2.0,"229372":"b","229371":"b","229377":"h","229378":3.4,"229379":3.4,"229382":4.2,"229383":"b","229384":"o","229351":1.8,"229352":2.2,"229353":2.2,"229355":2.6,"229356":3.0,"229357":2.7,"229359":"b","229360":1.9,"229361":3.8,"229275":2.5,"229274":1.1,"221330":2.0,"229276":"h","229277":"b","229278":"o","229295":"h","229298":"o","229297":"o","229292":"b","229296":"h","229291":"b","229293":"b","229294":"b","229290":"o","229314":"b","229312":"b","229315":"b","229311":4.0,"229313":"h","229316":"o","229310":3.2,"229334":2.0,"229332":3.6,"229333":3.6,"229331":4.5,"229335":0.4,"229330":4.5,"229249":"b","229250":"b","229243":500.0,"229247":"o","229244":500.0,"229248":"o","229241":500.0,"229245":"o","229242":500.0,"229246":"o","229253":16.5,"229251":"h","229252":"b","229254":"o","229255":"o","20350":70.0,"20370":36.0,"229568":"o","229572":"h","229566":4.8,"229563":3.0,"229562":105.0,"229567":4.8,"229564":4.2,"229573":"h","229565":2.3,"229561":7.5,"229569":"o","229570":"o","229571":2.3,"229594":3.2,"229592":2.1,"229595":"b","229593":2.8,"229591":4.8,"229590":2.5,"229615":2.0,"229612":1.5,"229614":2.0,"229611":2.5,"229613":6.8,"229610":2.8,"229625":"o","229623":3.2,"229624":3.2,"229620":8.6,"229621":"o","229622":"o","229806":"h","229801":"b","229807":"o","229800":"b","229808":"o","229809":"o","237340":"h","237341":"h","237342":"h","237343":"h","237344":"h","237347":40.0,"237348":48.0,"237349":53.0,"237350":56.0,"237351":58.0,"237352":"o","237358":44.0,"237359":54.0,"237360":69.0,"237361":77.0,"237362":89.0,"237363":110.0,"237364":75.0,"237365":52.0,"229825":2.7,"229824":2.8,"229827":2.1,"229822":3.5,"229828":4.8,"229823":2.8,"229826":2.1,"229821":6.5,"229832":"o","229829":3.8,"229830":"o","229831":"o","229844":4.2,"229842":2.8,"229843":4.2,"229846":"b","229841":2.8,"229845":4.8,"229840":2.5,"229852":"h","229854":"o","229853":"h","229851":"h","229856":"o","229855":"o","229850":"h","230066":"b","230064":"b","230063":"o","230061":"o","230068":"h","230067":"b","230065":"b","230062":"o","230069":"o","230070":78.0,"237300":"o","237301":"o","237302":"o","237303":"o","237304":"o","237307":"b","237308":"b","237309":"b","237310":"b","237311":"b","237312":"h","237313":"o","237319":70.0,"237320":76.0,"237321":82.0,"237322":85.0,"237323":90.0,"237324":100.0,"237325":"o","237326":"o","230820":"b","230821":"b","230822":"b","230823":7.0,"230152":1.8,"230157":1.8,"230153":1.8,"230155":1.8,"230154":1.8,"230156":1.8,"230151":4.8,"230160":"b","230158":1.8,"230159":1.8,"230161":"h","238250":18.0,"238252":"o","238253":"b","238265":"o","230113":"b","230112":"h","230111":2.3,"230110":2.3,"230125":"b","230123":"b","230124":"b","230122":"b","230121":"b","230120":"b","230182":"b","230183":2.3,"230184":2.3,"230185":"b","230189":2.7,"230190":2.7,"230191":"b","230192":"h","230193":"o","230196":"o","230197":18.0,"230198":18.0,"237780":"o","237781":"o","237782":"o","237783":"o","237784":"o","237786":"h","237787":"o","237788":"o","237789":"o","237790":"o","237791":"o","237792":27.0,"237793":"o","237794":"o","237795":"o","237796":"o","237797":"o","237798":"b","237799":72.0,"230221":2.3,"230222":2.7,"230223":"h","230225":2.5,"230226":2.7,"230227":"b","230229":3.0,"230230":2.7,"230231":3.2,"230252":3.0,"230253":3.3,"230254":3.6,"230255":"h","230259":6.0,"230260":"h","230261":"h","230266":"o","230267":"o","230268":12.0,"230269":12.0,"230280":"o","230281":"h","230282":"b","230283":2.5,"230284":1.8,"230285":2.7,"230286":"h","230288":2.3,"230287":3.0,"230293":"b","230290":1.8,"230291":"o","230292":"o","230201":2.2,"230202":2.0,"230203":2.0,"230206":2.0,"230207":2.0,"230210":4.2,"230211":6.0,"230212":6.5,"230740":3.5,"230741":3.4,"230742":2.1,"230743":2.1,"230744":"h","230745":3.5,"230746":7.5,"230747":"h","230749":"h","230750":7.5,"230751":"h","230752":"h","230470":"b","230471":2.1,"230472":6.3,"230473":2.7,"230474":6.3,"230475":"b","230495":6.3,"230476":3.8,"230477":"o","230479":24.8,"230480":24.8,"230481":5.2,"230496":24.8,"230482":"o","230487":"h","230483":"o","230500":2.1,"230501":"h","230502":2.1,"230503":2.1,"230504":6.9,"230505":3.4,"230520":"o","230521":"o","230523":"o","230524":"o","230522":"o","230526":"o","230527":"o","230525":"o","230540":1.0,"230541":1.1,"230543":"o","230542":5.7,"230544":"b","230882":6.8,"230883":1.5,"230884":3.4,"230885":3.4,"230886":3.4,"230887":3.4,"230891":4.8,"230892":4.8,"230893":4.5,"230894":5.5,"230895":12.0,"230901":8.0,"230902":9.0,"230903":"o","230904":"o","230780":4.0,"230781":4.1,"230782":"o","230783":"o","230785":2.1,"230786":2.8,"230789":5.0,"230792":3.0,"230790":"o","230791":"o","230788":"o","230787":"b","230800":1.8,"230801":2.1,"230802":1.8,"230803":2.1,"230804":"o","230805":4.8,"230806":1.8,"230807":2.5,"231703":1.8,"231704":6.0,"231705":4.0,"231706":6.8,"231709":4.5,"231710":4.5,"231711":5.7,"231712":5.7,"231713":11.0,"231718":7.0,"231721":"o","231719":"o","231720":"o","231722":"o","231390":3.2,"231391":2.7,"231392":"b","231393":70.0,"231394":4.2,"231395":3.5,"231396":3.5,"231397":3.5,"231398":"b","231400":11.0,"231414":"b","231415":"o","231416":"o","231401":"o","231402":"o","231403":"o","231207":3.8,"231208":"b","231213":5.2,"231214":5.2,"231215":5.2,"231216":6.0,"231217":36.0,"231222":30.0,"231223":30.0,"231224":30.0,"231225":"o","231226":"o","230720":2.1,"230723":1.2,"230724":"b","230721":2.7,"230725":1.2,"230722":4.2,"230731":1.2,"230732":1.2,"230733":"o","230734":"b","230730":3.2,"231920":2.1,"231921":2.3,"231922":4.5,"231923":4.1,"231924":3.2,"231925":2.3,"231926":3.8,"231927":5.0,"231929":"o","231930":"o","231931":"o","231932":"o","231933":3.0,"231934":80.0,"231021":"b","231020":"b","231023":"h","231022":"b","231025":"h","231024":"b","231026":"o","30180":"b","231950":"o","231951":3.2,"231952":"b","231953":9.5,"231954":"b","231955":3.4,"231960":2.1,"231961":"o","231962":"o","231963":1.3,"231964":"b","231965":0.3,"231966":3.8,"231041":"x","231040":"b","231243":"o","231244":16.5,"231245":5.2,"231246":3.0,"231249":"b","231250":5.2,"231251":"h","231252":3.0,"231256":2.1,"231257":"o","231258":"o","231263":2.8,"231264":2.8,"231265":"o","231269":2.8,"231270":"o","231271":"o","231272":"o","231276":"o","231277":"o","231278":"o","231279":"o","231280":"o","230930":3.0,"230931":3.0,"230935":5.7,"230937":5.7,"230933":"o","230932":6.8,"230936":24.0,"230938":24.0,"230934":5.0,"230940":"o","230941":"o","230942":"o","230943":"o","231440":1.8,"231441":3.4,"231442":1.8,"231443":2.1,"231444":"b","231445":2.7,"231446":2.8,"231460":"b","231462":"b","231461":"b","231463":2.3,"231464":5.5,"231482":"h","231483":"h","231484":18.9,"231488":"b","231489":"b","231490":22.5,"231491":22.5,"231492":22.5,"231498":"o","231499":"o","231497":"o","231500":"o","238580":"b","238581":"b","238582":"b","238583":"b","238584":"b","238585":52.5,"238586":61.5,"238587":69.0,"238588":76.5,"238589":85.5,"238590":52.0,"238591":56.0,"238592":62.0,"238593":66.0,"238594":72.0,"238595":54.0,"238596":"b","231681":3.2,"231682":2.6,"231684":2.6,"231685":1.2,"231687":4.6,"231688":"h","231689":"b","231691":1.8,"231692":"b","231694":2.6,"231695":1.8,"231697":3.4,"231698":"h","232210":"h","232211":"h","232212":0.3,"232213":5.4,"232390":"b","232391":"h","232392":"h","232393":"b","232394":"b","233900":"o","233901":"o","233902":"h","233903":"o","241500":"o","241501":68.0,"241502":72.0,"241503":76.0,"241504":80.0,"241505":84.0,"241506":"h","232513":1.0,"232515":"o","232514":"b","232516":"b","232517":"b","232518":"h","232520":"b","232521":"b","232522":"b","232523":"b","232524":"o","232525":"o","232570":0.5,"232571":0.5,"232573":"h","232578":"h","232579":"h","232576":"b","232574":3.6,"232577":"h","232582":"b","232583":"b","232584":"o","232585":"o","232540":3.0,"232541":"o","232542":"x","232543":"o","232544":"o","232545":2.3,"232546":5.4,"232547":"o","232560":1.0,"232561":1.8,"232562":1.7,"232563":"b","232564":1.2,"232565":3.0,"236860":2.0,"236861":15.0,"236862":15.0,"236863":"o","236864":24.8,"236865":24.8,"236867":39.0,"236868":39.0,"236869":"o","236870":"o","233540":4.8,"233545":8.0,"233546":"b","233548":"o","233549":14.0,"233550":"o","238860":52.0,"238861":58.0,"238862":63.0,"238863":70.0,"238864":73.0,"238865":"o","238866":"o","238867":"b","233470":8.4,"233471":"h","233472":8.4,"233473":"h","233474":24.0,"233475":24.0,"233477":"o","233478":60.0,"233479":60.0,"233480":"o","238960":51.5,"238961":57.0,"238962":62.0,"238963":68.0,"238964":73.0,"238965":"o","238966":"b","238968":"b","233490":"b","233491":9.8,"233492":9.8,"233493":3.4,"233494":"b","233495":3.8,"233496":"o","233497":6.8,"233500":72.0,"233501":72.0,"233502":"o","233503":"o","233527":"o","233528":"o","233610":"b","233611":6.0,"233612":6.0,"233613":"b","233614":"b","233615":"b","233616":4.5,"233617":4.5,"233618":"b","233619":"h","233620":"h","233621":"b","233622":"o","233623":"o","233624":"o","233690":20.0,"233691":45.0,"233692":70.0,"233693":70.0,"233694":70.0,"233695":15.0,"233696":60.0,"233697":60.0,"233698":105.0,"233700":"o","233701":"o","233702":"h","233703":"o","233640":4.8,"233641":4.8,"233642":"b","233643":"b","233644":4.8,"233645":4.8,"233646":5.5,"233647":5.2,"233648":5.6,"233649":13.0,"233650":13.0,"233652":"o","233653":"o","233654":"o","233655":25.0,"233670":3.8,"233671":3.2,"233672":6.0,"233673":7.2,"233674":7.2,"233675":4.1,"233676":5.9,"233677":7.5,"233678":8.1,"233680":"o","233681":8.2,"233682":"o","238810":38.0,"238811":46.0,"238812":54.0,"238813":62.0,"238814":70.0,"238815":"o","238816":"b","233580":6.0,"233581":13.5,"233582":13.5,"233583":"b","233586":18.8,"233587":18.8,"233589":21.8,"233590":21.8,"233591":"o","233592":"o","233593":"o","233594":"o","235400":5.6,"235401":6.8,"235402":4.8,"235404":4.8,"235403":"b","235405":"b","235406":7.5,"235407":4.8,"235409":4.8,"235408":"b","235410":"b","235412":"o","235413":"o","234370":3.8,"234371":"b","234372":"b","234373":24.0,"234374":24.0,"234375":36.0,"234376":"b","234378":"b","234379":12.4,"234380":"o","234381":"o","241915":"b","241916":"b","241917":"b","241918":"b","241919":"b","30630":"b","241900":56.0,"241901":60.0,"241902":64.0,"241903":68.0,"241904":72.0,"241905":"o","30530":"b","234700":"h","234701":"h","234702":"h","234703":"h","234704":"h","234705":"h","234706":"o","234708":18.0,"234709":"h","234710":"o","234711":"o","234712":"o","234713":"h","235012":8.0,"235013":8.0,"235021":"o","235023":"o","235024":"o","235025":"o","235026":"o","235190":22.0,"235191":22.0,"235192":22.0,"235193":"b","235194":37.5,"235195":37.5,"235196":37.5,"235197":"b","235200":28.6,"235201":28.6,"235202":28.6,"235207":"o","235203":"o","235204":"o","235206":62.5,"235205":"o","235250":"x","235251":"h","235252":"h","235253":"o","235254":"o","235255":12.0,"235256":12.0,"235257":"h","235258":15.0,"235261":"o","235262":"o","235263":"o","235264":"b","235220":10.0,"235221":5.0,"235222":"b","235223":"h","235225":"b","235226":"b","235227":"b","235228":"b","235229":"b","235230":"b","235232":"o","235233":"o","235234":"o","235440":9.8,"235443":"o","235444":5.6,"235445":7.2,"235446":3.8,"235447":5.2,"235449":"o","235450":"o","235451":"o","235880":10.0,"235881":8.5,"235882":8.5,"235883":8.5,"235884":"b","235885":"b","235886":"b","235887":"b","235888":"b","235889":"b","235892":"o","235893":"o","241880":56.0,"241881":60.0,"241882":64.0,"241883":68.0,"241884":72.0,"241885":"b","241886":"b","241887":"b","241888":"b","241889":"b","236300":150.0,"236301":"b","236303":31.0,"236304":39.0,"236305":46.5,"236306":53.0,"236307":62.0,"236290":45.0,"236291":56.2,"236292":67.5,"236293":78.0,"236294":85.5,"236308":70.0,"236309":"o","236310":"o","236313":17.5,"236315":25.0,"236316":28.0,"236317":35.0,"236318":43.5,"236319":51.0,"236320":130.0,"236321":"o","236322":"o","236680":11.0,"236681":12.0,"236682":12.0,"236683":15.0,"236684":15.0,"236685":15.0,"236687":"o","236688":"o","236370":"o","236371":15.0,"236372":15.0,"236373":9.0,"236374":"h","236375":10.0,"236376":14.0,"236377":14.0,"236379":"o","236380":"o","236381":129.0,"236390":"b","236391":6.0,"236392":"b","236393":"o","236394":"o","236395":"o","236397":"o","236398":"o","236399":"o","236700":16.5,"236701":16.5,"236702":14.0,"236703":17.0,"236704":22.5,"236706":"o","236707":"o","236565":1.0,"236571":34.5,"236572":30.0,"236574":"o","236575":"o","236576":"o","236577":"o","236578":"o","236560":36.0,"236561":41.0,"236562":45.0,"236563":50.0,"236564":53.0,"236579":120.0,"236591":65.0,"236580":"o","236581":"o","236584":30.0,"236586":25.0,"236587":27.5,"236588":29.0,"236589":32.0,"236590":35.0,"236592":"h","236593":"o","236752":"b","236753":45.0,"236754":170.0,"236755":190.0,"236756":210.0,"236757":230.0,"236758":250.0,"236740":30.0,"236741":35.0,"236742":40.0,"236743":45.0,"236744":50.0,"236759":75.0,"236760":"o","236761":"o","236764":35.0,"236765":40.0,"236766":56.0,"236767":62.0,"236768":65.0,"236769":68.0,"236770":72.0,"236771":75.0,"236772":"o","236773":"o","237276":"b","237277":"h","237265":"h","237266":"b","237278":"b","237279":"b","237280":"b","237281":"b","237282":"b","237270":"o","237260":"o","237261":"o","237262":"o","237263":"o","237264":"o","237285":"b","237267":"o","237268":"o","237269":"o","237284":50.0,"237288":18.0,"237289":"o","237290":"b","237291":"b","237292":"b","237293":"b","237294":"b","237297":"b","237296":50.0,"237380":42.0,"237381":"b","237382":"b","237383":"o","237384":"b","237386":"o","237387":"o","237388":"o","237460":48.0,"237461":48.0,"237462":46.0,"237463":52.0,"237464":58.0,"237465":64.0,"237466":70.0,"237475":45.0,"237476":"b","237477":"b","237478":"b","237479":"b","237480":"b","237468":"o","237481":"b","237482":"o","237483":"o","237486":66.0,"237487":72.0,"237488":78.0,"237489":84.0,"237490":90.0,"237491":100.0,"237492":"o","237493":"o","237494":"o","237420":"o","237421":50.5,"237422":36.0,"237424":"o","237425":"o","237440":"o","237441":55.0,"237442":36.0,"237444":"o","237445":"o","237820":50.0,"237821":58.0,"237822":66.0,"237823":103.0,"237824":153.0,"237834":50.0,"237835":58.0,"237836":66.0,"237837":103.0,"237838":153.0,"237826":110.5,"237839":110.5,"237829":"o","237841":"b","237827":180.0,"237845":50.0,"237846":58.0,"237847":66.0,"237848":103.0,"237849":153.0,"237853":"o","237850":110.5,"237854":"b","237860":"h","237861":33.0,"237862":1.0,"237864":"o","237865":"o","238350":47.0,"238351":56.0,"238352":63.0,"238353":72.0,"238354":80.0,"238357":170.0,"238358":190.0,"238359":210.0,"238360":230.0,"238361":250.0,"238362":43.0,"238363":20.0,"238365":"o","238364":"o","238356":"b","238366":170.0,"238367":190.0,"238368":210.0,"238369":230.0,"238370":250.0,"238371":92.0,"238372":92.0,"238374":"o","238667":48.0,"238668":55.0,"238669":63.0,"238670":68.0,"238671":72.0,"238660":97.5,"238661":112.5,"238662":129.0,"238663":142.5,"238664":150.0,"238666":"o","238672":"o","238673":"o","238674":"b","238675":"o","238676":58.0,"238677":58.0,"238678":58.0,"238679":67.0,"238680":73.0,"238681":81.0,"238682":85.0,"238683":"o","238684":"o","238685":130.0,"238708":28.0,"238709":32.0,"238710":35.0,"238711":100.0,"238712":150.0,"238700":46.0,"238701":55.0,"238702":63.0,"238703":150.0,"238704":200.0,"238705":"h","238707":"o","238720":"o","238714":200.0,"238713":"o","238715":69.0,"238716":78.5,"238717":91.5,"238718":268.0,"238719":351.0,"238721":600.0,"238730":54.0,"238731":59.0,"238732":65.0,"238733":72.0,"238734":80.0,"238738":45.0,"238739":49.0,"238740":55.0,"238741":60.0,"238742":67.0,"238743":28.0,"238744":"b","238745":1.0,"238736":"o","238737":"o","238746":"b","238747":50.0,"238748":54.0,"238749":59.0,"238750":65.0,"238751":72.0,"238752":91.0,"238899":84.0,"238900":93.0,"238901":102.0,"238902":261.0,"238903":315.0,"238890":56.0,"238891":60.0,"238892":64.0,"238893":168.0,"238894":207.0,"238895":120.0,"238897":"o","238904":"o","238898":"o","238905":57.0,"238906":64.0,"238907":71.0,"238908":178.0,"238909":215.0,"238910":150.0,"238911":"o","238912":"b","238920":20.0,"238921":25.0,"238922":30.0,"238923":35.0,"238924":40.0,"238929":56.0,"238930":62.0,"238931":68.0,"238932":74.0,"238933":80.0,"238934":"o","238926":"o","238935":"o","238927":"o","238928":"b","238936":"o","238937":24.0,"238938":28.0,"238939":32.0,"238940":36.0,"238941":40.0,"238942":"o","238943":"o","238944":"o","239190":67.5,"239191":73.5,"239192":81.0,"239193":300.0,"239194":375.0,"239195":1.0,"239196":"o","239198":"o","239199":"o","239200":150.0,"239201":"h","239202":"b","239203":"b","239204":"b","239205":"b","239206":"b","239207":"o","239250":4.5,"239251":"b","239252":"h","239253":"b","239254":"b","239256":"b","239257":"o","239258":"o","239611":77.0,"239612":85.0,"239613":93.0,"239614":233.0,"239615":300.0,"239616":150.0,"239617":150.0,"239618":"o","239619":"b","239620":"b","239621":"b","239622":"b","239623":"b","239624":"b","239625":"b","239641":"b","239642":"o","229904":1.8,"240600":84.0,"240601":87.0,"240602":90.0,"240603":150.0,"240604":225.0,"240605":72.0,"240606":78.0,"240607":84.0,"240608":150.0,"240609":225.0,"240610":"o","240611":375.0,"240612":192.0,"240613":194.0,"240614":196.0,"240615":350.0,"240616":400.0,"240617":"o","240618":500.0,"238421":"o","240650":"b","240651":"b","240652":"b","240653":"b","240654":"b","240657":"b","240658":"b","240659":"b","240660":"b","240661":"b","240662":60.0,"240656":"b","240663":"o","240655":375.0,"240664":53.0,"240665":56.0,"240666":59.0,"240667":70.0,"240668":80.0,"240669":"b","240630":30.0,"240631":"o","240632":"o","240633":"o","240634":"o","240635":"o","240636":"o","240870":450.0,"240871":220.0,"240872":240.0,"240873":260.0,"240874":280.0,"240875":300.0,"240876":"b","242490":"o","240877":87.0,"240878":91.5,"240879":96.0,"240880":100.5,"240881":105.0,"240882":87.0,"240883":91.5,"240884":96.0,"240885":100.5,"240886":105.0,"240887":82.5,"240888":150.0,"240900":68.0,"240901":76.0,"240902":84.0,"240903":116.0,"240904":130.0,"240905":63.0,"240906":75.0,"240907":87.0,"240908":129.0,"240909":156.0,"240910":142.5,"240911":"o","240912":"b","240913":15.0,"240914":21.0,"240915":27.0,"240916":33.0,"240917":37.5,"240918":"b","240919":"b","240920":"b","240921":1.0,"240922":"h","241470":"b","241471":"b","241472":"b","241473":"b","241474":"b","241476":40.0,"241477":46.0,"241478":52.0,"241479":58.0,"241480":64.0,"241481":"b","241482":"b","241483":"b","241484":"b","241485":"b","241486":"o","241830":"b","241831":"b","241832":"b","241833":"b","241834":"b","241835":"o","241836":"o","241837":"o","241838":"o","241839":"o","241840":"b","241841":"b","241842":"b","241843":"b","241844":"b","241845":"o","241851":"o","241846":40.0,"241847":"o","241860":"o","241861":56.0,"241862":60.0,"241863":64.0,"241864":68.0,"241865":72.0,"241866":56.0,"241867":60.0,"241868":64.0,"241869":68.0,"241870":72.0,"242392":56.0,"242393":60.0,"242394":64.0,"242395":68.0,"242396":72.0,"242397":56.0,"242398":60.0,"242399":64.0,"242400":68.0,"242401":72.0,"242402":"o","242403":"b","241720":"b","241721":"b","241722":"b","241723":"b","241724":"b","241725":40.0,"241726":46.0,"241727":52.0,"241728":58.0,"241729":64.0,"241730":"b","241731":"b","241732":"b","241733":"b","241734":"b","241735":"o","242472":78.0,"242473":84.0,"242474":90.0,"242475":300.0,"242476":375.0,"242477":78.0,"242478":84.0,"242479":90.0,"242480":300.0,"242481":375.0,"242482":"b","242483":"b","242412":"b","242413":"b","242414":"b","242415":"b","242416":"b","242417":56.0,"242418":"o","242432":56.0,"242433":60.0,"242434":64.0,"242435":68.0,"242436":72.0,"242437":192.0,"242438":194.0,"242439":196.0,"242440":198.0,"242441":200.0,"242442":"b","235296":20.0,"235291":22.5,"242502":78.0,"242503":84.0,"242504":90.0,"242505":300.0,"242506":375.0,"242507":78.0,"242508":84.0,"242509":90.0,"242510":300.0,"242511":375.0,"242512":"b","242522":78.0,"242523":84.0,"242524":90.0,"242525":96.0,"242526":102.0,"242527":"b","243222":"b","243223":"b","243224":"b","243225":"b","243226":"b","243227":"b","243228":"o","243253":51.0,"243254":57.0,"243255":63.0,"243256":69.0,"243257":75.0,"243258":"o","243259":"o","30650":"b","20800":"o","30740":"b","30460":"b","30670":"b","243474":"b","30820":"b","30730":"b","243609":80.0,"30560":"b","30710":"b","20880":"b","30660":"b","10520":"h","30830":"b","10510":"h","30800":"b","30201":"o","30202":"o","30720":"b","30690":"b","243143":"o","201690":"h","201650":3.2,"206490":"b","206480":"o","206540":"o","206560":3.0,"206570":3.0,"206580":3.0,"206590":3.0,"206550":2.1,"206530":2.1,"200870":"b","200890":"b","231318":3.0,"231315":"h","231311":6.8,"231312":"b","231313":"b","231310":5.4,"231316":1.5,"231314":"h","231317":"b","231323":"o","231325":"o","231324":"o","231322":"o","231344":5.4,"231341":5.4,"231342":5.4,"231343":5.4,"231340":2.7,"231346":4.5,"231345":4.8,"231347":30.0,"231348":37.5,"231349":6.5,"231351":"o","231353":"o","231352":"o","243392":"b","30580":"b","200670":2.2,"205250":1.8,"205340":"b","205370":2.4,"205390":"h","205270":1.5,"205300":"o","205320":"b","205280":2.8,"205290":1.6,"205310":4.8,"205020":"o","204990":"o","204980":20.0,"205010":"h","205000":"b","226791":"o","226792":"o","237160":"o","237161":"o","237162":"o","237163":"o","237164":"o","237167":59.0,"237168":65.0,"237169":68.0,"237170":71.0,"237171":75.0,"237172":80.0,"237173":1100.0,"237180":63.0,"237181":68.0,"237182":72.0,"237183":75.0,"237184":78.0,"237185":120.0,"237186":60.0,"231150":70.0,"231152":2.4,"231153":30.0,"231154":70.0,"231155":75.0,"231151":105.0,"231159":100.0,"231160":100.0,"231161":100.0,"231158":70.0,"231157":70.0,"231156":70.0,"231162":200.0,"231165":"o","231164":"o","231166":"o","231180":2.4,"231181":5.4,"231183":2.1,"231184":0.3,"231186":4.5,"231182":43.5,"231185":13.0,"231189":"o","231190":25.0,"231188":"o","237200":57.0,"237201":66.0,"237202":75.0,"237203":84.0,"237204":96.0,"237205":"o","237206":"o","237208":52.0,"237209":60.0,"237210":69.0,"237211":80.0,"237212":87.0,"237213":144.0,"237214":"o","237220":55.0,"237221":64.0,"237222":73.0,"237223":82.0,"237224":91.0,"237225":102.0,"237226":"o","237227":"o","237080":1.0,"237081":48.0,"237082":53.0,"237083":58.0,"237084":64.0,"237085":67.0,"237095":69.0,"237096":51.0,"237097":52.0,"237098":57.0,"237099":63.0,"237100":70.0,"237101":76.0,"237102":"b","237087":45.0,"237103":"o","237088":"o","237106":"o","237107":50.0,"237108":55.0,"237109":61.0,"237110":67.0,"237111":70.0,"237112":"b","237113":"o","237120":27.0,"237121":27.0,"237122":27.0,"237123":"h","237124":"b","237125":"h","237126":225.0,"237128":"o","237129":"o","237140":"h","237141":120.0,"237142":"h","237143":"b","237145":"o","237146":"o","20870":"o","211420":3.3,"226741":"o","226742":"b","203330":2.7,"203320":"b","226731":10.0,"226732":"o","228591":"h","228590":"h","20770":"o","20780":"o","211530":"o","211540":"o","211550":"o","211510":"o","211520":"o","211570":2.0,"211560":3.0,"203280":"o","203290":"b","203300":"h","203270":2.1,"211620":"h","211630":"h","211610":"h","211660":"h","211650":"o","30070":"b","228550":"b","228551":38.5,"228554":"o","228555":"o","228552":"o","228553":"o","228556":"o","228557":"o","237980":57.0,"237981":66.0,"237982":75.0,"237983":84.0,"237984":96.0,"237985":87.0,"237986":"o","237989":52.0,"237990":57.0,"237991":62.0,"237992":67.0,"237993":72.0,"237994":"b","237995":"o","237996":"o","237997":"o","238003":"b","238004":"b","238005":"b","238006":"b","238007":"b","238008":"b","238009":"o","238010":"b","238011":"o","228571":1.3,"228574":3.0,"228575":3.0,"228570":3.5,"228573":57.0,"228572":57.0,"228576":72.0,"228577":"o","228578":"o","237940":20.0,"237941":26.0,"237942":30.0,"237943":34.0,"237944":40.0,"237946":35.0,"237947":43.0,"237948":49.0,"237949":53.0,"237950":60.0,"237951":50.1,"237952":"b","237953":"o","237960":90.0,"237961":99.0,"237962":110.0,"237963":120.0,"237964":130.0,"237965":55.0,"237966":55.0,"237967":200.0,"237968":"o","237969":"o","228600":4.1,"228601":"o","231570":5.4,"231571":4.0,"231572":4.0,"231573":4.0,"231574":4.0,"231575":27.0,"231576":27.0,"231577":27.0,"231578":27.0,"231579":"o","231581":4.0,"231582":4.0,"231583":4.0,"231584":4.0,"231585":6.8,"231586":"o","231587":"o","231630":3.6,"231631":6.0,"231632":6.0,"231633":"x","231634":"o","231635":"o","231638":5.2,"231639":5.2,"231640":"o","231600":6.3,"231601":6.3,"231602":6.3,"231603":5.2,"231604":5.2,"231605":5.2,"231608":22.5,"231609":22.5,"231610":22.5,"231611":"o","231612":"b","231613":"b","231614":"b","237900":54.0,"237901":58.5,"237902":63.0,"237903":225.0,"237904":300.0,"237915":1125.0,"237916":40.0,"237917":48.0,"237918":56.0,"237919":224.0,"237920":300.0,"237922":"o","237906":"h","237907":"o","237937":"o","237921":"b","237908":"o","237926":60.0,"237927":64.0,"237928":68.0,"237929":150.0,"237930":200.0,"237931":1.0,"237933":"o","237932":460.0,"238030":"o","238031":"o","238032":"o","238033":"o","238034":18.0,"238035":18.0,"238037":"o","238038":"o","30620":"b","202940":3.8,"202790":"o","202820":1.0,"202810":3.8,"202800":3.2,"226710":"o","226712":"h","226711":"o","202850":"b","202870":2.1,"202890":1.5,"202880":2.1,"200190":"h","223640":3.0,"223630":"o","10300":"o","10320":"o","10340":"o","10360":"o","201660":"o","223620":"o","223610":"h","223790":2.0,"223570":"b","232160":1.2,"232161":2.2,"232162":"b","232163":"b","232164":"b","232165":"b","232166":"b","232167":"b","232168":4.2,"202900":1.0,"200990":3.2,"200950":240.0,"202920":"o","202930":"o","202910":"o","200580":1.8,"201460":"o","223400":2.5,"223420":5.0,"223430":8.0,"223410":10.5,"223390":"b","228010":"o","228011":"o","223530":"o","223520":"o","223510":"o","223500":3.5,"223490":3.5,"223480":3.5,"223540":6.0,"223470":2.2,"228022":"o","228020":"o","228021":4.5,"229442":8.1,"229444":4.5,"229445":"o","229440":"b","229441":"b","229446":8.2,"229447":6.8,"229448":6.8,"229449":6.8,"229453":"o","229451":3.0,"229452":"o","232180":"b","232181":7.2,"232182":2.1,"232184":7.2,"232183":"o","232185":"o","232186":"o","232120":5.2,"232121":"h","232122":6.3,"232123":"h","232124":"h","232126":"o","232127":"o","232128":5.0,"232140":3.6,"232141":"b","232142":"o","232143":6.0,"232144":36.0,"232146":"o","232147":"o","232148":"h","232149":"o","242912":"b","242913":"o","242914":72.0,"242915":78.0,"242916":84.0,"242917":90.0,"242918":96.0,"242919":"o","242920":"o","242921":"o","242922":"o","242923":"o","242924":"o","242925":"o","242926":"o","242927":"o","242928":"o","242951":"b","242959":"b","242954":8.0,"242955":16.0,"242956":24.0,"242957":64.0,"242958":80.0,"243132":78.0,"243133":84.0,"243134":90.0,"243135":96.0,"243136":102.0,"243137":78.0,"243138":84.0,"243139":90.0,"243140":96.0,"243141":102.0,"243142":78.0,"243144":"b","243145":"b","243146":"o","10500":"h","203880":"h","202540":2.7,"203870":4.0,"203860":5.4,"200130":"b","20171":2.3,"203920":2.7,"203910":4.2,"204000":33.0,"204020":12.0,"203990":20.0,"227480":"o","227481":1.0,"227482":1.0,"227483":30.0,"239491":56.0,"239492":60.0,"239493":64.0,"239494":68.0,"239495":72.0,"239496":72.0,"239497":1.0,"239498":1.0,"239499":1.0,"239500":1.0,"239501":1.0,"239502":"b","230603":270.0,"230604":270.0,"230605":270.0,"230606":5.2,"230610":315.0,"230611":315.0,"230612":315.0,"230613":18.0,"230614":4.5,"230615":4.5,"230620":270.0,"230621":270.0,"230622":270.0,"230623":"o","230624":"o","230632":120.0,"230633":120.0,"230634":"b","230638":150.0,"230639":150.0,"230640":130.0,"230641":"o","230645":"o","230646":"o","230647":"o","230648":6.5,"242631":90.0,"242633":"b","242634":"b","242635":"b","242636":"b","242637":"b","242639":64.0,"242640":64.0,"242641":64.0,"242642":64.0,"242643":64.0,"242644":"o","242645":"b","230660":"b","230661":5.4,"230663":"h","230665":46.5,"230667":"h","230666":15.0,"230662":"o","230664":45.5,"230669":"b","230671":"o","230670":"o","239530":64.5,"239531":75.0,"239532":85.5,"239533":94.5,"239534":99.0,"239536":"o","239537":33.0,"239538":37.5,"239539":42.0,"239540":45.0,"239541":48.0,"239542":130.0,"239543":58.5,"239544":67.5,"239545":78.0,"239546":85.5,"239547":90.0,"239548":"o","239549":"b","230694":"o","230691":"h","230692":"h","230695":"h","230698":"h","230693":"h","230696":"b","230697":"b","230690":"h","230701":"o","230703":"o","230702":"o","230700":"o","230573":4.2,"230574":4.2,"230575":4.0,"230576":"h","230580":15.0,"230581":"h","230582":"h","230583":6.3,"230584":6.3,"230589":"o","230590":"o","230591":20.0,"230592":30.0,"230593":30.0,"239461":20.0,"239464":108.0,"239465":108.0,"239466":108.0,"239467":108.0,"239468":108.0,"239471":"b","239472":120.0,"239469":432.0,"239470":432.0,"239473":56.0,"239474":60.0,"239475":64.0,"239476":68.0,"239477":72.0,"239462":"b","239479":"o","239463":"o","239570":40.0,"239572":48.0,"239573":"b","239574":"o","242571":"b","242572":90.0,"242574":12.0,"242575":24.0,"242576":36.0,"242577":48.0,"242578":60.0,"242579":"h","242581":24.0,"242582":32.0,"242583":40.0,"242584":48.0,"242585":56.0,"242586":"o","242587":"o","242612":56.0,"242613":60.0,"242614":64.0,"242615":68.0,"242616":72.0,"242617":56.0,"242618":60.0,"242619":64.0,"242620":68.0,"242621":72.0,"242622":90.0,"242623":"b","30700":"b","208400":"h","208390":"b","201320":2.0,"208460":"b","208240":"b","226851":"o","226852":21.0,"226853":21.0,"226854":"o","201510":1.3,"201490":1.8,"200240":1.3,"201470":"o","208300":"o","208350":"h","208330":1.2,"208360":2.7,"208340":"o","208370":"o","208310":"o","208470":"o","208320":2.7,"208290":3.2,"238180":"b","238160":14.0,"238161":14.0,"238162":"b","238163":"b","238164":"b","238165":"o","238166":"o","238167":"o","238169":"o","238170":"o","208530":1.2,"208540":"o","208520":1.4,"208550":1.6,"208510":2.1,"208560":"o","201520":"o","201530":1.5,"201540":2.3,"208430":5.7,"208420":4.2,"229940":8.1,"229949":"h","229943":4.5,"229942":"o","229941":"o","229950":"b","229944":2.4,"229945":4.8,"229948":4.2,"229946":"o","229947":"o","229953":"o","229952":"o","229954":"o","238450":48.0,"238451":57.0,"238452":65.0,"238453":73.0,"238454":80.0,"238455":30.0,"238456":30.0,"238458":38.0,"238459":42.0,"238460":47.0,"238461":51.0,"238462":56.0,"238463":"o","238464":"b","238465":3.0,"238466":3.0,"238467":3.0,"238468":3.0,"238469":3.0,"229970":3.0,"229971":2.5,"229975":"h","229972":2.8,"229973":49.8,"229974":49.8,"229976":"h","229978":"h","229979":"h","229977":21.0,"229982":"o","229981":"o","229983":"o","238410":"b","238411":"b","238412":"b","238413":"b","238414":"b","238415":"b","238416":"o","238417":"o","238418":"o","238419":"o","238420":"o","238422":"b","238423":42.0,"238424":49.0,"238425":56.0,"238426":63.0,"238427":70.0,"238428":52.5,"238429":52.5,"238430":90.0,"230960":9.0,"230961":9.0,"230962":9.0,"230969":"b","230967":"h","230968":"b","230963":24.0,"230964":22.5,"230965":22.5,"230966":22.5,"230971":"o","230972":"o","20420":6.8,"230991":5.4,"230995":"o","230997":"o","230996":"o","230994":"o","230993":2.2,"230992":2.2,"230999":4.5,"230998":5.5,"231001":"o","231000":"o","231002":"o","231112":7.0,"231113":7.0,"231114":7.0,"231118":50.2,"231119":50.2,"231120":50.2,"231121":"o","231122":"o","231123":25.0,"231128":23.2,"231129":23.2,"231130":23.2,"231131":"o","231132":"o","238190":"h","238191":"h","238192":"h","238193":"h","238194":"h","238195":84.0,"238196":99.0,"238197":108.0,"238198":123.0,"238199":132.0,"238200":84.0,"238201":99.0,"238202":114.0,"238203":129.0,"238204":144.0,"238205":"o","238206":"b","238207":"b","238208":"b","238209":30.0,"238210":"o","231082":3.5,"231083":3.9,"231084":"b","231085":5.0,"231089":3.2,"231090":10.5,"231091":5.0,"231092":10.5,"231093":10.5,"231098":"o","231099":12.0,"231100":40.0,"231101":"o","238120":1.0,"238121":1.0,"238122":1.0,"238123":1.0,"238124":1.0,"238126":32.0,"238127":38.0,"238128":44.0,"238129":49.0,"238130":51.0,"238131":1.0,"238132":"o","238133":"b","238134":"b","238135":"b","238136":"b","238137":"b","238138":90.0,"238139":112.5,"238140":80.0,"238141":97.5,"238158":90.0,"238159":112.5,"238086":46.0,"238087":46.0,"238080":"b","238081":"b","238082":"b","238083":"b","238084":"b","238088":51.0,"238089":56.0,"238090":61.0,"238091":67.0,"238092":72.0,"238093":"o","238094":72.0,"238095":84.0,"238096":96.0,"238097":108.0,"238098":120.0,"238099":"b","238100":"b","238101":"b","238102":"o","238490":3.0,"238491":3.0,"238492":3.0,"238493":3.0,"238494":3.0,"238502":1.0,"238497":54.0,"238498":58.0,"238499":62.0,"238500":66.0,"238501":70.0,"238503":72.0,"238496":"b","238504":"o","238505":61.0,"238506":65.0,"238507":69.0,"238508":73.0,"238509":77.0,"238510":100.0,"238511":125.0,"238512":"o","238513":150.0,"238530":35.0,"238531":1.5,"238532":32.0,"238533":"o","238534":"o","238536":1.0,"238537":"o","238538":"o","241590":40.0,"241591":45.0,"241592":50.0,"241593":200.0,"241594":250.0,"241595":"b","241596":"b","241597":"b","241598":"b","241599":"b","241600":"b","241601":"b","241602":"b","241603":"b","241604":"b","241605":"o","241570":64.0,"241571":72.0,"241572":84.0,"241573":200.0,"241574":250.0,"241575":"b","241576":150.0,"241577":180.0,"241578":210.0,"241579":240.0,"241580":270.0,"241581":300.0,"241620":40.0,"241621":"o","241622":"o","241623":"o","214800":2.0,"214790":3.0,"214810":4.5,"214820":2.8,"214830":3.0,"214840":6.0,"227151":"o","236480":32.0,"236481":42.0,"236482":52.0,"236483":62.0,"236484":68.0,"236486":"o","236488":48.0,"236489":63.0,"236490":75.0,"236491":85.5,"236492":94.5,"236493":"b","236494":"o","236500":31.0,"236501":39.0,"236502":46.5,"236503":56.0,"236504":64.0,"236505":"b","236506":"o","236507":"o","227870":2.5,"227871":0.3,"227872":2.7,"227873":3.5,"227876":"o","227874":4.5,"227875":2.0,"227830":2.2,"227831":2.4,"227832":4.7,"227833":4.5,"227834":3.3,"227835":6.0,"227837":3.0,"227836":11.0,"227838":"o","227839":"o","236111":38.0,"236112":49.5,"236113":59.0,"236114":65.0,"236115":72.0,"236120":"h","236121":"h","236122":"h","236123":"h","236124":"h","236125":80.0,"236117":"o","236134":"b","236135":"b","236136":"b","236137":"b","236138":"b","236139":"o","236141":"b","236142":"b","236143":"o","229750":"h","229751":"o","229752":"b","229753":"b","229754":"b","229755":"h","229756":"h","229757":"h","229758":"h","229759":"h","229763":"o","229762":"o","229761":"o","229780":5.5,"229781":"b","229782":2.0,"229783":3.0,"229784":3.6,"229786":10.0,"229787":10.0,"229788":10.0,"229785":32.0,"229790":"o","229791":"o","236061":30.0,"236062":35.0,"236063":40.0,"236064":45.0,"236065":50.0,"236068":30.0,"236069":35.0,"236070":40.0,"236071":45.0,"236072":50.0,"236073":30.0,"236082":40.0,"236083":42.5,"236084":47.5,"236085":50.0,"236086":55.0,"236087":40.0,"236089":50.0,"229902":9.0,"229903":3.3,"229905":"h","229909":4.5,"229910":4.5,"229911":4.5,"229912":5.8,"229917":"o","229918":12.0,"229919":12.0,"229920":12.0,"229921":"o","227892":"o","227891":"o","227890":"o","227851":3.0,"227850":5.2,"227853":5.2,"227852":3.4,"227854":6.7,"227855":"o","227856":3.4,"227857":"o","224500":2.8,"224510":5.4,"224520":4.8,"224550":2.7,"224530":5.0,"224560":"h","224540":2.7,"228052":6.9,"228053":6.9,"228051":13.0,"228050":"o","229881":3.2,"229882":6.3,"229883":4.1,"229884":2.5,"229888":5.4,"229889":6.1,"229890":2.4,"229891":6.0,"229896":"o","229897":"o","229898":"h","236520":"o","236521":72.0,"236522":"o","236523":72.0,"236524":60.0,"236525":90.0,"236526":"b","236527":"b","236528":"o","241260":64.0,"241261":68.0,"241262":72.0,"241263":126.0,"241264":180.0,"241265":40.0,"241266":48.0,"241267":56.0,"241268":104.0,"241269":152.0,"241270":35.0,"241271":42.0,"241272":49.0,"241273":96.0,"241274":113.0,"236190":4.5,"236191":"b","236192":7.0,"236193":5.0,"236194":30.0,"236195":5.2,"236196":30.0,"236199":"o","236200":"o","236201":"o","241290":73.0,"241291":76.0,"241292":79.0,"241293":82.0,"241294":85.0,"241295":100.0,"241296":99.0,"241297":103.5,"241298":108.0,"241299":112.5,"241300":117.0,"241301":99.0,"241302":103.5,"241303":108.0,"241304":112.5,"241305":117.0,"241320":45.0,"241321":"h","241322":"o","241211":"b","241212":"b","241213":"b","241214":"b","241215":"b","241210":"o","241216":56.0,"241217":60.0,"241218":64.0,"241219":68.0,"241220":72.0,"241221":"b","241222":"b","241223":"b","241224":"b","241225":"b","233930":4.5,"233931":5.4,"233932":4.8,"233933":4.8,"233934":5.5,"233935":12.0,"233936":10.5,"233938":"o","233940":21.5,"233941":23.0,"233939":"o","233942":31.5,"233943":33.0,"233960":4.2,"233961":4.5,"233962":6.8,"233963":5.5,"233964":5.2,"233965":5.2,"233966":3.0,"233968":"o","233969":29.0,"233970":"o","234000":"b","234001":10.0,"234002":3.0,"234003":3.8,"234004":3.8,"234005":5.0,"234007":"o","234008":"o","234009":"o","234010":6.0,"233980":4.5,"233981":4.8,"233982":"o","233983":3.6,"233984":"b","233985":8.0,"233986":"b","233987":2.5,"236002":146.5,"236003":181.0,"236004":230.0,"236001":114.2,"236006":140.8,"236007":170.0,"236008":194.4,"236005":"b","236010":31.6,"236011":47.4,"236012":66.4,"236013":200.0,"236014":250.0,"236015":"o","236017":"o","236027":31.5,"236028":41.0,"236029":53.0,"236030":152.0,"236031":202.0,"236032":1400.0,"236018":"o","236034":"o","236038":100.0,"236040":40.0,"236041":56.0,"236042":72.5,"236043":300.5,"236044":375.5,"236045":2100.5,"236047":"o","236220":4.6,"236221":10.0,"236222":12.0,"236223":16.0,"236225":30.0,"236226":90.0,"236227":"o","236228":"o","241380":140.0,"241381":"b","241382":"b","241383":"b","241384":"b","241385":"b","241386":60.0,"241387":63.0,"241388":66.0,"241389":69.0,"241390":72.0,"241391":"o","241392":"b","236430":"h","236431":"h","236445":"h","236432":28.0,"236433":33.0,"236434":46.0,"236435":53.0,"236436":60.0,"236446":"h","236447":"h","236448":"h","236449":"b","236450":"b","236451":"b","236452":"b","236453":"b","236454":"h","236438":"o","236455":"o","236458":54.0,"236459":34.0,"236460":34.0,"236461":34.0,"236462":32.0,"236463":42.0,"236464":52.0,"236465":65.0,"236466":75.0,"236467":"b","236468":"o","241240":"b","241241":56.0,"241242":60.0,"241243":64.0,"241244":120.0,"241245":250.0,"241246":56.0,"241247":60.0,"241248":64.0,"241249":120.0,"241250":250.0,"241360":"b","241361":56.0,"241362":60.0,"241363":64.0,"241364":120.0,"241365":250.0,"241366":56.0,"241367":60.0,"241368":64.0,"241369":120.0,"241370":250.0,"241410":57.0,"241411":"o","241412":"o","30680":"b","30750":"b","226620":25.0,"226621":75.0,"226628":"b","226624":30.0,"226626":30.0,"226625":30.0,"226627":30.0,"226622":3.0,"226629":"o","226640":"o","226643":"o","226642":"o","226644":28.5,"20560":187.5,"234770":80.0,"234771":80.0,"234773":"h","234774":"h","234775":150.0,"234779":"b","234776":180.0,"234777":180.0,"234778":"h","234782":420.0,"234783":420.0,"234784":"o","234785":"o","234786":"o","234800":6.0,"234801":16.5,"234802":13.5,"234803":145.0,"234804":18.0,"234805":18.0,"234808":"o","234809":"o","234818":"o","234570":6.0,"234571":"o","234572":15.0,"234573":9.0,"234574":10.0,"234575":10.0,"234576":20.0,"234577":16.0,"234580":"o","234581":"o","234582":"o","234820":150.0,"234821":"o","234822":100.0,"234824":"b","234825":"o","234826":100.0,"234540":6.0,"234541":"o","234542":15.0,"234543":12.0,"234544":10.0,"234545":10.0,"234546":10.0,"234547":18.0,"234549":"o","234550":"o","234551":"o","234600":1.0,"234601":6.0,"234602":"b","234603":12.0,"234605":13.0,"234606":13.0,"234604":15.0,"234607":"o","226623":12.0,"217360":1.6,"217370":0.3,"217380":"o","217390":"o","217400":"o","217420":"o","217410":"h","217430":3.6,"20170":10.0,"20530":"o","217690":"o","217490":"h","217190":3.0,"217200":20.0,"217210":3.0,"217220":105.0,"217230":7.5,"217250":3.0,"217240":150.0,"201700":"o","201710":"h","217680":"b","217660":"h","227171":"h","227172":"h","227173":"o","227175":"h","201720":1.0,"201730":"o","201740":"h","217670":2.7,"217580":"b","240530":"b","240531":"b","240532":"b","240533":"b","240534":"b","217050":72.0,"217080":120.0,"227121":"o","227122":42.0,"240535":"o","240536":"o","240537":"o","240538":"o","240539":"o","240540":"b","240541":"b","240542":"b","240543":"b","240544":"b","240545":75.0,"217310":4.5,"217320":"o","217330":"o","217340":3.0,"232480":5.2,"232481":5.2,"232482":5.2,"232483":5.7,"232484":"b","232488":33.8,"232489":33.8,"232490":33.8,"232486":"o","232487":"o","232430":4.5,"232431":4.1,"232432":36.0,"232433":4.2,"232434":5.0,"232435":"o","232436":"o","232437":"o","232438":3.0,"232460":"h","232461":"h","232462":1.1,"232463":500.0,"232464":500.0,"232465":500.0,"232466":"o","232467":"o","232405":1.8,"232406":4.0,"232407":4.2,"232408":"o","232409":"b","232420":1.6,"232421":3.9,"232422":"b","232423":"b","235702":3.0,"235700":22.0,"235701":22.0,"235703":"h","235704":3.8,"235705":30.0,"235706":30.0,"235709":"o","235710":"o","235711":"o","235712":60.5,"235713":60.5,"235730":100.0,"235731":80.0,"235732":80.0,"235733":4.8,"235734":7.2,"235735":"o","235736":"o","235737":60.0,"235738":60.0,"235741":"o","235742":"o","235743":"o","235780":200.0,"235781":200.0,"235782":"o","235783":4.2,"235784":4.2,"235787":"o","235788":"o","235760":3.0,"235761":"o","235762":"o","235763":"b","235764":"b","235766":"b","235767":"o","240450":45.0,"240451":54.0,"240452":63.0,"240453":72.0,"240454":81.0,"240455":48.0,"240456":64.0,"240457":64.0,"240458":64.0,"240459":64.0,"240460":"h","240461":"o","240462":"h","240463":110.0,"240464":110.0,"240465":110.0,"240466":110.0,"240467":110.0,"240468":90.0,"240469":120.0,"240470":120.0,"240500":"b","240501":"b","240502":"b","240503":"b","240504":"b","240505":"o","240506":"h","240507":"b","240508":"b","240509":"b","240510":"b","240511":"b","240512":"b","240513":"b","240490":30.0,"240491":30.0,"240492":40.0,"240493":"o","236164":"h","236163":60.0,"236171":"o","236172":"o","236173":"o","236167":60.0,"200500":"b","30770":"b","30790":"b","244507":45.0,"244508":45.0,"244509":45.0,"244513":45.0,"235960":"o","10280":1.2,"235968":"o","207020":2.1,"207030":2.7,"207060":1.4,"207050":"h","207040":27.0,"207010":4.0,"226821":"o","226823":3.6,"226822":"o","219130":"b","227571":"o","227570":"o","209130":"o","209160":2.1,"209150":"o","209180":2.8,"209170":1.0,"209140":"o","209190":"b","209120":3.2,"219100":"o","219120":"o","219090":"b","242290":15.0,"242291":15.0,"227580":"o","227581":"h","227582":"o","232750":3.2,"232751":3.2,"232752":3.2,"232753":3.2,"232754":"o","228909":1.2,"228910":1.2,"228904":"o","228905":"o","228911":1.2,"228912":1.2,"228903":1.8,"228901":5.4,"228906":4.5,"228907":4.5,"228902":2.8,"228913":"o","228914":"o","209060":2.3,"209040":"h","209070":2.3,"209020":"o","209080":2.3,"209050":70.0,"209010":"h","219050":2.5,"219060":4.0,"219070":"b","30810":"b","232770":5.2,"232771":5.2,"232772":5.2,"232773":"h","232774":"b","232775":13.5,"232778":43.5,"232779":43.5,"232780":"o","232781":"o","228880":1.8,"228883":5.7,"228884":2.7,"228885":100.0,"228881":3.0,"228882":5.2,"228886":1.8,"228887":"o","228888":"o","242340":56.0,"242341":60.0,"242342":64.0,"242343":68.0,"242344":72.0,"242345":"b","242346":"b","242347":"b","242348":"b","242349":"b","242350":"b","242361":"o","242362":"o","228952":2.0,"228950":2.8,"228953":"b","228954":"b","228951":"o","228955":2.7,"232730":4.1,"232731":4.5,"232732":1.8,"232733":3.6,"232734":3.6,"232735":3.8,"232710":3.2,"232711":2.7,"232712":30.0,"232714":5.0,"232715":5.0,"232716":5.0,"232718":20.0,"232719":"o","232720":"o","242271":56.0,"242272":60.0,"242273":64.0,"242274":68.0,"242275":72.0,"242276":"b","228930":3.8,"228931":4.5,"228932":4.5,"228933":4.5,"228934":2.7,"228935":4.0,"228936":5.2,"228937":2.2,"228939":"b","242232":"o","242233":"o","242234":"o","242235":"o","242236":"o","242237":"o","242238":"o","242239":"b","242251":64.0,"242252":68.0,"242253":72.0,"242254":76.0,"242255":80.0,"242256":"b","242257":"h","30600":"b","30640":"b","242322":126.0,"242323":375.0,"242324":450.0,"242325":525.0,"242326":600.0,"242327":216.0,"242328":375.0,"242329":450.0,"242330":525.0,"242331":600.0,"242332":"b","30780":"b","210120":2.7,"210130":2.7,"210140":3.2,"210150":"h","210100":4.1,"210170":"b","227252":3.6,"227250":"h","227253":"h","227251":"b","209980":2.7,"209990":"b","209960":3.5,"227091":7.8,"227092":"o","227093":"o","227190":"b","227193":11.2,"227195":11.0,"227191":"b","227200":4.8,"227194":10.8,"227192":11.5,"227198":"o","241140":45.0,"241141":52.5,"241142":60.0,"241143":67.5,"241144":75.0,"20570":8.0,"20580":8.1,"20590":8.2,"20600":8.3,"20610":8.4,"20620":8.5,"20630":450.0,"20640":450.0,"20650":450.0,"20660":450.0,"20670":450.0,"20680":450.0,"20690":1.2,"20730":7.0,"20700":7.0,"20810":202.5,"20820":202.5,"20830":202.5,"20840":202.5,"20850":202.5,"20860":202.5,"241145":45.0,"241146":52.5,"241147":60.0,"241148":67.5,"241149":75.0,"241150":45.0,"241151":45.0,"241152":360.0,"227220":2.0,"227221":2.0,"227223":"h","227224":"h","227231":"o","227222":"h","241080":68.0,"241081":72.0,"241082":76.0,"241083":80.0,"241084":84.0,"241085":"b","241086":102.0,"241087":"b","241088":"o","241089":69.0,"241090":75.0,"241091":81.0,"241092":90.0,"241093":96.0,"227246":"o","233341":15.0,"233340":15.0,"233344":"b","233342":15.0,"233343":"b","233346":22.5,"233345":22.5,"233347":22.5,"233349":"o","233350":"o","233351":"o","233360":7.5,"233361":"h","233362":"b","233363":7.0,"233364":7.0,"233365":6.8,"233367":"o","233366":15.0,"233368":"b","233369":"b","233371":"o","233372":"o","233390":"b","233391":3.6,"233392":4.5,"233393":"b","233394":3.8,"233396":"h","233395":"b","241110":40.0,"241111":45.0,"241112":50.0,"241113":100.0,"241114":150.0,"241115":"o","241116":70.0,"241123":"o","241117":200.0,"241118":"b","241119":"b","241120":"b","241121":"b","241122":"b","241124":50.0,"241125":"o","209420":"o","209430":3.2,"209440":"b","209410":2.7,"209330":2.7,"209340":3.4,"209320":2.7,"209310":3.4,"201220":0.8,"201250":4.0,"209460":"h","209250":1.5,"202160":70.0,"202180":70.0,"209260":1.5,"209270":1.5,"209280":1.5,"209240":3.0,"227500":"o","227501":"o","227502":1.7,"236960":35.0,"236961":40.0,"236962":45.0,"236963":47.5,"236964":50.0,"236967":70.0,"236968":80.0,"236969":85.0,"236970":88.0,"236971":90.0,"236972":60.0,"236973":100.0,"236979":60.0,"236980":70.0,"236981":75.0,"236982":78.0,"236983":80.0,"236984":100.0,"236985":"o","236986":75.0,"221670":"b","221680":3.0,"221710":3.0,"221690":3.0,"221700":3.0,"221660":"o","221650":"o","227982":"h","227981":"o","227983":"h","209360":"b","209370":"o","209520":3.0,"221920":3.0,"221940":4.2,"221930":"h","221950":4.0,"227970":"o","227973":"o","227972":"b","227971":"o","222090":3.0,"222130":"b","222100":3.4,"222140":"h","222150":"b","222120":"b","222110":"b","221830":"h","221850":"h","221870":1.5,"221880":1.5,"221860":"h","221840":"h","221890":1.5,"221900":"o","221760":"h","221800":"b","221750":"o","222080":2.5,"221770":"h","221790":"o","221780":4.5,"222070":6.3,"235290":15.0,"235292":15.0,"235293":"h","235294":20.0,"235295":20.0,"235297":35.0,"235300":"o","235301":"o","235302":25.0,"235314":"h","235315":"o","235316":35.0,"235319":"o","235320":"o","235321":25.0,"235350":20.0,"235351":20.0,"235353":20.0,"235354":"b","235355":"b","235356":"b","235359":"o","235360":"b","235361":"o","235362":25.0,"237044":"h","235615":"b","235330":5.0,"235331":5.0,"235332":"o","235333":"h","235334":13.0,"235336":20.0,"235610":16.0,"235611":16.0,"235612":16.0,"235613":16.8,"235614":"b","235616":19.8,"235617":19.8,"235618":19.8,"235619":22.5,"235620":22.5,"235621":22.5,"235624":"o","235625":"o","235626":"o","235627":"o","235628":"b","235629":25.0,"235670":"b","235671":"b","235672":"b","235673":8.0,"235674":20.0,"235675":20.0,"235676":"b","235679":"o","235680":"o","235681":"o","235682":25.0,"235650":3.0,"235651":"b","235652":8.0,"235653":"b","235654":"b","236910":1.0,"236911":48.0,"236912":56.0,"236913":64.0,"236914":74.0,"236915":81.0,"236916":86.0,"236926":"b","236927":57.0,"236928":"b","236929":"b","236930":"b","236931":"b","236932":"b","236918":"b","236933":"h","236919":"o","236934":"o","236935":"o","236938":48.0,"236939":60.0,"236940":69.0,"236941":80.0,"236942":87.0,"236943":92.0,"236945":120.0,"236946":"o","236947":"o","237040":"h","237041":"h","237042":"h","237043":"o","237045":"b","237046":"b","237047":"h","237049":"o","237050":"o","237051":"o","30731":"b","202020":1.5,"202030":1.4,"202050":1.4,"202070":2.7,"202120":2.7,"202060":1.4,"202080":3.0,"202090":2.8,"202100":2.8,"202110":2.8,"226681":"o","226682":7.2,"239090":52.0,"239091":58.0,"239092":63.0,"239093":69.0,"239094":72.0,"239096":47.2,"239097":61.5,"239098":75.0,"239099":85.5,"239100":93.0,"239101":58.0,"239102":66.0,"239103":73.0,"239104":76.0,"239105":80.0,"239106":"o","239107":"b","202410":1.5,"202420":"b","230380":4.0,"230390":100.0,"230382":4.8,"230388":100.0,"230389":100.0,"230391":100.0,"230383":2.5,"230381":6.8,"230384":2.8,"230385":49.1,"230386":5.0,"230393":"o","230394":"o","230395":"o","230387":3.0,"230396":"o","239130":46.0,"239131":53.0,"239132":61.0,"239133":67.0,"239134":70.0,"239136":47.0,"239137":54.0,"239138":62.0,"239139":68.0,"239140":72.0,"239141":110.0,"30470":"o","239142":70.0,"239143":72.0,"239144":78.0,"239145":80.0,"239146":86.0,"239147":"o","239148":"b","239149":110.0,"230420":"b","230410":"o","230418":105.0,"230413":"o","230414":"b","230416":"b","230411":"o","230415":"h","230412":"o","230417":"b","230422":"h","230419":6.3,"230421":3.8,"230425":"o","230424":"o","30500":"o","234470":6.0,"234471":7.5,"234472":8.0,"234473":1.8,"234219":"b","234474":"o","234475":"o","234476":120.0,"234477":120.0,"234478":"o","234480":"o","234481":"o","234482":"o","234483":78.8,"234500":70.0,"234501":70.0,"234502":100.0,"234503":100.0,"234504":100.0,"234505":67.5,"234507":"o","234508":70.0,"234509":70.0,"234510":"o","30480":"o","30490":"o","30510":"o","207750":"b","207740":"o","207760":"h","207780":1.5,"207860":"b","207850":"b","207820":2.2,"207840":"b","207880":"o","207890":"h","207900":"b","207830":2.7,"207930":"b","207940":"b","207950":"b","208070":"h","208050":"b","208060":"b","207960":"b","207970":"b","207980":"b","208010":"b","208020":"b","208030":"b","208040":"b","207990":"b","208000":"b","208080":"b","216920":1.6,"216910":"h","216930":1.8,"217010":"b","217020":"b","217030":"o","20520":1.0,"20500":0.8,"20510":0.8,"232675":"b","232672":3.2,"232673":3.2,"232674":7.2,"232679":"h","232677":30.0,"232678":30.0,"232681":"o","232682":45.0,"232683":"o","211750":3.0,"211860":"b","211820":"h","211800":1.0,"211780":5.0,"211830":6.0,"211840":6.0,"211850":6.0,"211770":"o","211810":"h","211760":2.5,"227181":"o","227182":"o","216070":48.0,"216080":21.0,"216120":"o","240361":"o","240362":"o","240363":"o","240364":"o","240365":"o","216130":"b","216090":45.0,"216110":"o","216100":"b","227550":"b","227552":"o","227551":9.0,"240367":66.0,"240368":74.0,"240369":82.0,"240370":90.0,"240371":99.0,"240372":60.0,"240373":1.0,"240374":1.0,"240375":"o","240376":"o","240377":"o","216180":1.9,"216190":1.9,"216200":2.2,"216210":2.8,"216280":"h","216270":1.2,"216300":"o","216310":"h","216290":"h","216320":"h","221520":"h","221510":"h","221500":"h","221530":"b","227364":3.0,"227360":"x","227365":"h","227366":"b","227362":"h","227363":"o","227371":"o","227370":"o","240312":"o","240313":"b","240300":"b","240301":"b","240302":"b","240303":"b","240304":"b","240305":225.0,"240307":"b","240308":"b","240309":"b","240310":"b","240311":"b","240315":"b","240316":"b","240317":"b","240318":"b","240319":"b","240320":150.0,"240321":150.0,"240322":150.0,"240323":"b","240324":"o","221240":4.0,"221250":3.0,"221270":5.5,"221230":6.0,"221260":8.2,"227620":"o","227621":"o","227622":"o","240021":56.0,"240022":59.0,"240023":62.0,"240024":65.0,"240025":68.0,"240026":"b","240027":1.0,"240028":"o","240029":"o","240030":"o","240031":"o","240032":"o","240033":"o","221360":12.0,"221410":"o","221440":30.0,"221460":30.0,"221370":45.0,"221400":3.3,"221380":"o","221420":2.2,"221430":3.6,"221390":30.0,"221450":2.0,"227630":"o","227631":"o","227632":"o","240271":40.0,"240272":45.0,"240273":50.0,"240274":55.0,"240275":60.0,"240276":45.0,"240277":50.0,"240278":55.0,"240279":60.0,"240280":65.0,"240281":1.0,"240282":450.0,"240283":450.0,"240284":"o","232040":4.2,"232041":4.2,"232042":2.5,"232043":3.2,"232044":3.2,"232045":3.2,"232052":4.9,"232053":3.2,"232054":4.5,"232060":32.5,"232063":"b","232064":"o","232061":"o","232062":"o","227380":3.0,"227381":2.5,"227383":"b","227382":4.0,"227384":2.5,"213350":2.4,"213340":2.0,"213330":2.0,"213360":2.0,"213370":4.0,"213320":11.2,"227951":"o","227952":"o","239981":58.0,"239982":61.0,"239983":64.0,"239984":67.0,"239985":70.0,"239986":"o","239987":"b","239988":56.0,"239989":62.0,"239990":68.0,"239991":74.0,"239992":80.0,"239993":"o","239994":"o","239995":"b","213490":"h","213440":1.7,"213430":"b","213460":1.5,"213450":1.5,"213470":2.0,"213480":2.0,"213620":"h","213580":"b","213560":"b","213600":"b","213590":"b","213570":"b","213610":"b","213550":"b","213540":"b","213650":"h","213660":"o","213640":"h","227391":"h","227390":"b","231865":3.8,"231866":"h","231870":"b","231871":"b","231872":"b","231873":6.0,"231879":"o","231880":"b","231881":"o","231882":"b","231883":"b","231884":"o","231885":"b","231893":"h","231894":"h","231895":"h","231899":"b","231900":"b","231901":"h","231902":"h","231906":"o","231907":"o","231908":"o","240061":"b","240062":"h","240063":"o","240064":"o","240065":"o","240230":79.5,"240231":84.0,"240232":88.5,"240233":93.0,"240234":97.5,"240253":50.0,"240236":47.0,"240237":54.0,"240238":62.0,"240239":68.0,"240240":72.0,"240254":100.0,"240241":"o","240242":100.0,"240243":53.0,"240244":56.0,"240245":59.0,"240246":62.0,"240247":65.0,"240248":"b","240249":"o","240250":"h","240251":"b","240252":"b","231842":4.2,"231843":5.2,"231846":"b","231847":3.8,"231848":30.0,"231852":"o","231853":12.0,"231854":"b","231855":"o","232070":2.7,"232071":"b","232072":"h","232073":"h","232076":"b","232077":"b","232078":"b","232079":35.0,"232085":"o","232083":37.5,"232086":"o","232087":"o","231822":2.8,"231823":1.2,"231826":2.3,"231827":2.6,"231830":2.0,"231831":"o","239951":225.0,"239952":47.0,"239953":54.0,"239954":62.0,"239955":68.0,"239956":72.0,"239957":"o","239958":90.0,"239959":"o","239960":"o","239961":"o","239962":"o","239963":"o","239964":"o","239965":500.0,"239921":54.0,"239923":47.0,"239924":54.0,"239925":62.0,"239926":68.0,"239927":72.0,"239928":"o","239929":90.0,"239930":"o","239931":"o","239932":"o","239933":"o","239934":"o","239935":"o","239936":100.0,"232866":3.6,"232867":3.6,"232868":18.0,"232869":9.0,"232870":9.0,"232871":9.0,"232872":12.0,"232874":10.0,"232875":"o","232876":"o","232890":3.4,"232891":3.4,"232892":4.2,"232893":3.3,"232894":3.3,"232895":3.5,"232897":13.5,"232898":"o","232899":"o","232900":"o","232910":3.8,"232911":3.8,"232912":3.8,"232913":3.8,"232914":10.8,"232915":28.5,"232917":"o","232918":45.0,"232919":"o","234210":8.0,"234211":8.0,"234212":8.0,"234213":"o","234214":37.5,"234215":"h","234216":14.2,"234217":14.2,"234218":14.2,"234220":34.5,"234222":"o","234223":"o","234224":30.0,"239390":41.0,"239391":46.0,"239392":50.0,"239393":55.0,"239394":58.0,"239396":46.0,"239397":53.0,"239398":61.0,"239399":67.0,"239400":70.0,"239401":100.0,"239402":35.0,"239403":40.0,"239404":48.0,"239405":55.0,"239406":62.0,"239407":"o","239408":"h","234280":2.8,"234281":5.4,"234282":"b","234283":"b","234284":"b","234285":"o","234286":4.8,"234287":"b","234288":"o","234290":"o","234291":"o","234292":6.0,"234240":5.7,"234241":4.5,"234242":4.5,"234243":8.0,"234244":8.0,"234245":5.5,"234246":6.2,"234247":6.2,"234248":"b","234249":13.5,"234251":"o","234252":"o","234253":12.5,"234254":"o","234270":4.5,"234271":"b","234272":"h","234273":4.5,"234274":"b","234275":"b","235090":18.0,"235091":14.0,"235092":"b","235093":14.0,"235094":14.0,"235095":"b","235097":"b","235098":"b","235099":"b","235100":"b","235101":"o","202170":70.0,"202210":"h","202200":"b","226691":1.8,"226692":1.8,"226693":250.0,"226695":"o","210620":"b","202280":1.0,"210520":2.5,"227101":6.8,"227102":"o","227103":"o","227104":"o","210550":"h","202190":70.0,"200720":28.0,"202250":35.0,"226701":"o","204740":200.0,"204730":200.0,"204710":90.0,"210310":2.0,"210300":2.5,"226772":300.0,"227111":"o","202270":300.0,"210610":30.0,"202290":100.3,"202300":1.6,"202310":3.8,"210590":"b","210470":5.2,"229184":"h","229180":6.0,"229185":"b","229181":6.0,"229186":"b","229182":6.0,"229183":"b","204720":100.0,"204700":55.0,"226771":"o","210630":1.9,"210570":"b","210580":"b","210560":"b","229155":2.5,"229150":1.0,"229151":1.0,"229158":"o","229159":"o","229152":"o","229153":"o","229154":"o","229157":"o","229160":"o","229156":"o","229161":"o","229163":"o","229162":"o","229165":18.0,"229166":18.0,"229167":"o","229168":"o","210380":"h","210400":"b","210410":"o","210390":"b","210370":"h","210420":"b","229121":4.8,"229122":5.2,"229123":4.5,"229124":4.5,"229127":"o","229128":"o","229125":27.8,"229129":"o","229130":"o","229200":3.0,"229201":2.3,"229202":2.5,"229203":2.5,"229204":"o","229205":"b","203000":"o","203010":"o","202990":"o","203030":"o","203020":"o","202980":4.2,"226721":"o","226722":"o","226813":"o","204110":1.8,"204090":"h","204100":2.0,"204080":5.5,"226751":5.5,"226753":3.8,"226754":"o","241690":56.0,"241691":60.0,"241692":64.0,"241693":68.0,"241694":72.0,"241695":"b","204150":1.8,"204160":"o","204140":2.7,"204130":4.5,"203130":"o","202740":"b","203120":2.0,"203140":180.0,"204190":2.8,"204180":2.7,"204200":2.1,"203040":3.2,"203050":1.4,"203100":0.8,"203110":0.3,"203080":2.3,"203090":1.8,"206640":0.3,"206900":"h","206620":"h","226811":6.4,"226812":"o","226814":"h","204220":"b","204230":0.8,"204210":3.2,"228368":"o","206690":2.1,"206730":"b","206700":1.9,"206670":"h","206800":2.0,"206780":225.0,"206790":225.0,"206810":100.0,"206770":225.0,"206760":225.0,"206820":"o","206830":"o","234850":375.0,"234851":13.0,"234852":100.0,"234853":100.0,"234854":100.0,"234858":"o","234859":30.0,"234861":30.0,"234862":30.0,"234876":53.0,"234877":53.0,"234863":"o","234864":"o","234865":"o","234890":3.0,"234891":105.0,"234892":105.0,"234893":105.0,"234894":375.0,"234895":70.0,"234896":70.0,"234897":70.0,"234898":"h","234905":"o","234902":"o","234903":"o","234904":"o","234920":15.0,"234921":15.0,"234922":"b","234923":"o","234924":21.0,"234925":21.0,"228313":6.0,"228312":3.0,"228311":3.0,"228314":"b","228315":"b","228310":5.2,"228316":"o","241680":56.0,"241681":60.0,"241682":64.0,"241683":68.0,"241684":72.0,"241685":"b","228335":12.0,"228332":10.5,"228331":10.5,"228334":"b","228330":"o","228333":12.6,"228337":"o","228338":"o","228352":"h","228350":15.8,"228353":"b","228351":375.0,"234940":10.0,"234941":10.0,"234942":13.0,"234943":13.0,"234944":16.0,"234945":"b","234946":"b","234948":"o","234949":"o","234950":"o","234951":20.0,"241660":84.0,"241661":90.0,"241662":96.0,"241663":225.0,"241664":300.0,"241665":84.0,"241666":90.0,"241667":96.0,"241668":225.0,"241669":300.0,"241670":"b","241671":"o","30732":"b","202520":"o","202570":"o","202560":"o","202580":3.0,"202590":"h","202600":4.5,"202630":0.8,"202610":"o","202640":"o","202620":3.0,"202650":0.3,"202660":2.0,"202720":2.1,"202680":"o","202700":1.5,"202730":"b","239360":"b","239361":"b","239362":"b","239363":"b","239364":"b","239365":"b","239366":"h","239368":"b","239369":"b","239370":"b","239371":"b","239372":"b","239373":"o","239389":400.0,"239374":"b","239375":"b","239376":"b","239377":"b","239378":"b","239379":800.0,"239380":"b","239050":"b","239051":"b","239052":"b","239053":"b","239054":"b","239055":"b","239057":69.0,"239058":79.5,"239059":91.5,"239060":300.0,"239061":375.0,"239062":100.0,"239063":1.0,"239064":"h","239065":78.0,"239066":90.0,"239067":103.5,"239068":300.0,"239069":375.0,"239070":300.0,"239071":1.0,"239072":"o","239073":225.0,"239074":225.0,"239075":1650.0,"239030":"h","239031":15.0,"239032":1.5,"239034":51.0,"239035":"o","239036":"b","239033":"o","239039":"o","201910":2.7,"201920":"o","201900":"h","201930":"b","201940":"b","207560":"b","207550":"o","207570":"h","207540":"h","207530":"h","201990":1.2,"201840":1.4,"201850":2.7,"201830":2.4,"201860":"b","201870":1.4,"202000":2.1,"201880":2.8,"201890":"b","207450":1.5,"207440":2.7,"207470":3.8,"207460":3.0,"207490":"h","207430":3.8,"207640":2.7,"207630":2.7,"207610":"h","207620":3.2,"207650":"h","207600":2.0,"203430":"b","203450":2.1,"228248":"o","228249":"o","228240":150.0,"228241":122.5,"228242":122.5,"228243":8.0,"228250":"o","228244":8.0,"228245":8.0,"228246":1.1,"228247":"h","203600":"x","203500":"h","203510":"h","203490":2.3,"203560":"b","203550":2.3,"203590":2.3,"203650":75.0,"203630":2.3,"203750":"b","203740":2.1,"203640":75.0,"203770":"h","203720":3.6,"203730":75.0,"203520":1.2,"203780":"o","203580":1.2,"203610":1.2,"203660":1.2,"203530":2.1,"203620":1.4,"203680":300.0,"203570":2.7,"203710":"o","203690":1.5,"203540":0.8,"203670":1.7,"203760":0.3,"203700":1.9,"203790":2.4,"220300":"o","220640":6.0,"228212":3.0,"228210":4.5,"228213":"h","228211":"h","228216":"o","228217":"o","228218":"b","228214":"b","228215":"b","228221":"o","228219":"o","228220":"o","228222":3.4,"228290":1.8,"228291":1.5,"228292":2.5,"228293":2.0,"228294":2.1,"228295":"b","242180":"b","242181":"b","205590":2.1,"205620":"h","205630":"o","205580":48.0,"205600":2.7,"205610":2.2,"205640":"h","227031":"o","227032":"h","242020":"b","242021":"o","242010":84.0,"242011":88.0,"242012":92.0,"242013":96.0,"242014":100.0,"242015":72.0,"242016":78.0,"242017":84.0,"242018":90.0,"242019":108.0,"206040":"b","206060":"o","206000":2.1,"206050":1.5,"206020":4.8,"206010":2.7,"205980":105.0,"205710":1.0,"205750":1.2,"206140":2.1,"206130":2.4,"205700":1.8,"205760":1.2,"205770":1.2,"205680":"b","205720":30.0,"205730":1.6,"205740":1.6,"205690":3.0,"205860":"b","205850":1.0,"205820":1.0,"205800":"b","205830":1.2,"205810":"b","205840":2.8,"205790":"o","205940":"h","205910":1.2,"205930":2.0,"205920":2.1,"205890":"h","205950":"o","205970":"o","205960":"o","205900":"o","242040":"b","242041":"b","242042":"b","242043":"b","242044":"b","242045":"b","242046":"b","242047":"b","242048":"b","242049":"b","206100":48.0,"206110":2.8,"226983":1.8,"226984":4.0,"226986":"o","226987":3.8,"226982":"b","226985":1.7,"226988":"b","226981":2.5,"226992":"o","226991":"o","227003":1.8,"227001":3.8,"227005":6.0,"227004":0.6,"227000":2.6,"227023":"o","227002":5.4,"227022":"b","227024":7.0,"227052":"o","227051":2.6,"227053":2.5,"227054":2.5,"227055":2.5,"227050":4.8,"227043":1.8,"227042":3.8,"227041":6.0,"227072":2.1,"227073":2.8,"227070":1.5,"227074":0.3,"227071":1.8,"227075":"b","241960":56.0,"241961":60.0,"241962":64.0,"241963":68.0,"241964":72.0,"241965":165.0,"241966":180.0,"241967":195.0,"241968":210.0,"241969":225.0,"241970":"o","241971":"b","241980":"b","241981":72.0,"241982":78.0,"241983":84.0,"241984":90.0,"241985":96.0,"241986":96.0,"241987":"o","241988":102.0,"241989":114.0,"241990":126.0,"241991":138.0,"241992":150.0,"241993":177.0,"241994":189.0,"241995":201.0,"241996":213.0,"241997":225.0,"241998":270.0,"218580":"o","218530":"h","218570":15.0,"218520":"o","230023":"o","230024":"o","218710":"o","218680":"o","218740":1.0,"218720":"o","218690":"o","218700":"o","218660":15.0,"218730":"b","218670":"o","218500":"o","218480":"b","218470":7.5,"218450":2.0,"218400":"o","218440":"x","218410":"b","218430":"b","218420":"b","218460":2.2,"228280":5.5,"228281":12.0,"228282":"o","228283":"o","220020":"h","220060":"h","220070":"b","220110":2.3,"220080":"h","220090":4.8,"220120":"h","220100":"o","240821":72.0,"240822":72.0,"240823":72.0,"240824":72.0,"240825":72.0,"232284":60.0,"232285":60.0,"240831":80.0,"232286":60.0,"232287":60.0,"232294":"o","240755":"h","232293":"o","240826":120.0,"240827":127.5,"240828":135.0,"240829":142.5,"240830":150.0,"232310":75.0,"232311":75.0,"232312":75.0,"232321":74.2,"240790":56.0,"240791":60.0,"240792":64.0,"240793":68.0,"240794":72.0,"232313":54.0,"232314":54.0,"232315":54.0,"232317":67.5,"232318":45.0,"232319":"b","232323":"o","232322":"o","240802":72.0,"240801":72.0,"240800":72.0,"240795":75.0,"240796":80.0,"240797":90.0,"240798":95.0,"240799":100.0,"240750":56.0,"240751":60.0,"240752":64.0,"240753":68.0,"240754":72.0,"240757":132.0,"240758":139.5,"240759":147.0,"240760":154.5,"240761":162.0,"240762":144.0,"240763":144.0,"240764":144.0,"240765":270.0,"240766":277.5,"240767":285.0,"240768":292.5,"240769":300.0,"240770":36.0,"240772":84.0,"240773":96.0,"240771":"o","225730":2.5,"225840":1.8,"225740":2.0,"225830":2.0,"225820":1.6,"225570":2.7,"225500":"h","225530":"h","225490":"h","225510":"o","225540":"b","225550":"b","225560":"b","225520":"h","225580":9.9,"225400":16.0,"225350":"b","225360":"b","225390":2.4,"225420":5.2,"225370":"b","225380":"b","225410":3.5,"225620":2.0,"225680":"b","225630":2.2,"225640":3.0,"225650":1.5,"225660":4.2,"225670":"b","225770":3.6,"225760":3.0,"227732":2.3,"227733":"h","227734":3.5,"227735":1.3,"227746":"o","227737":2.5,"227738":2.8,"227743":4.8,"227736":"h","227747":2.0,"227750":7.5,"227751":8.4,"227752":"o","227763":3.0,"227764":1.6,"227765":4.0,"227769":5.4,"227770":5.4,"227771":5.2,"227774":6.0,"227775":"o","227781":2.5,"227782":4.5,"227783":4.0,"227786":1.0,"227788":3.2,"227789":1.0,"227792":8.0,"227793":"b","227794":"o","227800":2.2,"227801":2.5,"227804":1.5,"227805":2.5,"227807":3.2,"227809":3.6,"227810":2.7,"227812":"b","227814":2.0,"227815":"b","227818":0.1,"227819":2.5,"232606":"h","232607":5.2,"232608":"b","232609":5.2,"232610":5.2,"232612":75.0,"232613":75.0,"232614":75.0,"232615":"o","232616":"o","232630":"o","232631":3.5,"232632":"b","232633":"h","232634":3.5,"232635":3.5,"232637":69.0,"232638":69.0,"232639":69.0,"232640":"o","232641":"o","232650":2.6,"232651":3.0,"232652":1.8,"232653":2.2,"232654":"b","232655":3.4,"228713":2.8,"228714":1.0,"228715":1.8,"228716":1.5,"228722":4.0,"228723":4.0,"228724":9.5,"228725":38.0,"228730":"o","228731":6.0,"228732":6.0,"228733":6.0,"228734":6.0,"228735":6.0,"228736":100.0,"228729":"o","242782":56.0,"242783":60.0,"242784":64.0,"242785":68.0,"242786":72.0,"242787":56.0,"242788":60.0,"242789":64.0,"242790":68.0,"242791":72.0,"242800":"o","228743":4.0,"228744":4.0,"228745":4.0,"228746":4.0,"228751":1.0,"228752":1.0,"228753":3.8,"228754":3.8,"228755":3.8,"228756":3.8,"228760":21.8,"228761":21.8,"228762":21.8,"228763":21.8,"228764":"o","228765":"o","228771":1.7,"228772":2.4,"228773":2.2,"228774":2.2,"228776":3.2,"228777":2.6,"228778":2.7,"228779":2.7,"228784":4.0,"228785":3.8,"228786":2.0,"228783":4.3,"228792":"b","228793":2.5,"228794":2.2,"228795":3.0,"228799":6.6,"228800":4.5,"228801":4.5,"228802":4.6,"228803":"o","228808":"h","228809":"h","228810":"h","228811":"h","228804":"b","228813":"o","228812":"o","228821":1.8,"228822":2.1,"228823":1.7,"228825":2.5,"228826":2.1,"228827":2.7,"228829":3.4,"228830":3.4,"228831":3.0,"228840":"h","228841":"b","228842":"b","228843":"b","228844":"h","228845":2.0,"228847":3.8,"228848":4.5,"229043":2.8,"229044":2.3,"229045":1.8,"229046":5.0,"229051":4.5,"229052":4.5,"229053":6.0,"229054":4.0,"229055":5.0,"229061":"o","229062":9.0,"229063":8.8,"229064":8.5,"229065":8.0,"229066":8.8,"229060":"o","229073":4.2,"229074":3.5,"229075":3.0,"229076":3.0,"229080":26.0,"229081":0.1,"229082":4.5,"229083":2.2,"229084":12.0,"229090":5.5,"229091":6.0,"229092":7.5,"229093":5.5,"229094":"o","229095":"o","229089":"h","229101":1.8,"229102":2.2,"229103":2.4,"229104":"o","229105":"h","229108":2.7,"229109":3.0,"229110":"h","229111":"h","229114":3.8,"229115":3.5,"229116":4.2,"242702":56.0,"242703":60.0,"242704":64.0,"242705":68.0,"242706":72.0,"242707":"o","242709":78.0,"242710":84.0,"242711":90.0,"242712":96.0,"242713":102.0,"242714":68.0,"242723":"o","242769":"b","242742":"b","242743":"b","242744":"b","242745":"b","242746":"b","242748":56.0,"242749":60.0,"242750":64.0,"242751":68.0,"242752":72.0,"242753":"o","242755":56.0,"242756":60.0,"242757":64.0,"242758":68.0,"242759":72.0,"242760":"o","242768":"o","229642":"o","229643":3.5,"229644":"h","229653":4.5,"229652":"b","229648":"b","229649":"b","229650":"b","229651":"b","229658":"o","229659":"o","229657":"b","229662":"b","229663":"o","229664":4.5,"229668":"b","229669":2.6,"229670":4.2,"229674":"o","229675":7.2,"229673":"o","229681":2.3,"229682":1.6,"229683":1.6,"229685":"o","229686":3.6,"229687":2.7,"229690":"h","229701":"o","229702":2.0,"229704":"h","229705":2.4,"229706":"o","229708":"b","230842":4.2,"230843":5.4,"230844":2.5,"230845":5.5,"230849":6.4,"230850":5.2,"230851":3.5,"230856":"o","230857":15.0,"230858":16.5,"230862":3.4,"230863":3.0,"230864":4.9,"230865":2.5,"230869":5.0,"230870":4.5,"230871":8.8,"230872":4.6,"230877":"o","230878":"o","230879":19.0,"232971":3.9,"232972":3.9,"232980":4.8,"232975":5.0,"232976":5.0,"232977":5.4,"232978":"h","232970":4.5,"232973":6.0,"232981":"o","232982":20.0,"232950":7.5,"232951":7.5,"232952":4.7,"232953":7.1,"232954":6.0,"232955":8.1,"232957":10.5,"232958":"o","232959":"o","233100":2.5,"233101":4.0,"233102":5.4,"233103":5.5,"233104":5.5,"233105":9.0,"233106":9.0,"233108":"o","233109":"o","233110":"o","233111":"o","233112":"o","233130":3.5,"233131":"b","233132":9.0,"233021":9.0,"233023":27.0,"233133":18.0,"233134":"b","233136":24.0,"233137":34.0,"233138":19.5,"233139":34.5,"233140":"o","233141":"o","233142":"o","233160":4.5,"233161":"h","233162":"h","233163":"h","233164":"h","233165":"h","233166":"b","233167":"b","233168":"h","233169":"h","233170":"h","233171":"h","233172":"h","233174":"o","233175":"o","233176":"o","233178":"o","233177":"o","233200":3.0,"233201":"h","233202":4.2,"233203":4.5,"233204":5.2,"233205":16.5,"233206":4.8,"233208":4.5,"233209":"o","233210":"o","233020":8.0,"233022":9.0,"233024":19.5,"233025":15.0,"233026":15.0,"233029":"h","233030":"o","233031":"o","233032":"o","233050":5.2,"233051":"h","233052":"h","233053":3.2,"233054":6.8,"233055":9.0,"233059":"o","233057":"o","233058":"o","233060":"o","233061":"o","233080":"b","233081":2.5,"233082":"b","233083":5.7,"233084":2.1,"233085":13.2,"233086":6.0,"233780":1.5,"233781":"o","233782":6.8,"233783":0.4,"233784":9.3,"233785":9.3,"233786":10.2,"233787":10.0,"233789":9.5,"233790":"o","233791":"o","233792":"o","233793":"o","233841":"h","233840":4.8,"233842":4.5,"233844":"b","233845":5.2,"233846":"h","233851":"o","233850":"o","233853":"o","233814":3.2,"233811":3.8,"233810":5.4,"233812":3.8,"233813":3.8,"233815":4.5,"233816":4.5,"233817":4.5,"233818":7.8,"233820":11.0,"233819":9.0,"233825":"o","233823":"o","233822":9.3,"233824":"o","233870":8.0,"233871":9.0,"233872":9.0,"233873":9.0,"233874":"h","233875":"h","233876":20.0,"233877":"o","233878":"o","233879":"o","233881":28.0,"233885":"o","233882":"o","233883":"h","233884":"o","233886":"o","234100":5.7,"234101":4.4,"234102":4.4,"234103":4.4,"234104":4.4,"234105":4.7,"234106":4.7,"234107":4.7,"234108":4.7,"234110":12.8,"234111":7.5,"234112":7.5,"234113":7.5,"234114":7.5,"234115":"o","234116":"o","234130":"b","234131":"b","234132":6.8,"234133":"h","234134":6.0,"234135":7.0,"234136":12.0,"234137":"o","234138":"o","234150":1.0,"234151":"b","234152":"o","234153":"b","234154":"b","234155":"h","234157":"b","234158":"b","234159":"b","234160":"b","234161":"o","234162":"o","239750":56.0,"239751":60.0,"239752":64.0,"239753":68.0,"239754":72.0,"239755":"o","239756":"b","239757":"b","239758":"b","239759":"b","239760":"b","239761":"b","239762":34.5,"239763":39.0,"239764":43.5,"239765":48.0,"239766":52.5,"239768":150.0,"239769":"o","239770":"o","234170":3.0,"234171":4.2,"234172":2.4,"234173":2.4,"234174":2.6,"234175":3.3,"234176":4.9,"234190":1.2,"234191":"o","234192":"o","234193":"o","234194":"o","239710":40.0,"239711":50.0,"239712":60.0,"239713":70.0,"239714":80.0,"239716":80.0,"239717":85.0,"239718":90.0,"239719":95.0,"239720":100.0,"239721":120.0,"239733":"b","239723":"b","239722":"o","239724":80.0,"239725":85.0,"239726":90.0,"239727":95.0,"239728":100.0,"239729":"b","239730":"b","239731":"b","239732":"b","239830":1.0,"239831":"o","239832":1.0,"239833":32.0,"239835":"o","239836":"o","239850":38.0,"239851":35.0,"239852":32.0,"239853":34.0,"239857":75.0,"239855":"o","239856":"o","234430":105.0,"234431":"b","234432":"h","234433":0.4,"234435":105.0,"234436":"b","234437":70.0,"234434":75.0,"234439":"o","234440":"o","234441":"o","234442":"o","235560":0.1,"235561":3.6,"235562":"b","235563":5.4,"235564":5.4,"235565":8.6,"235566":8.6,"235567":15.0,"235568":"b","235570":"o","235571":"o","235572":"o","235510":4.2,"235513":4.2,"235512":"b","235514":15.4,"235515":48.0,"235516":48.0,"235517":64.9,"235518":14.0,"235520":"o","235521":57.0,"235522":"o","235530":22.8,"235531":22.4,"235532":7.8,"235533":7.8,"235534":10.6,"235535":"b","235536":14.0,"235537":32.0,"235538":32.0,"235540":"o","235541":"o","235542":"o","237590":42.0,"237591":44.0,"237592":61.5,"237593":72.0,"237594":79.5,"237595":87.0,"237596":97.5,"237606":64.5,"237607":48.0,"237608":54.0,"237609":60.0,"237610":67.0,"237611":71.0,"237612":81.0,"237598":"o","237599":"o","237613":"o","237614":"o","237617":48.0,"237618":61.0,"237619":66.0,"237620":70.0,"237621":75.0,"237622":81.0,"237627":53.0,"237623":135.0,"237624":"o","237630":1.0,"237642":67.5,"237643":48.0,"237631":42.0,"237632":50.0,"237633":58.0,"237634":64.0,"237635":67.0,"237644":66.0,"237645":76.0,"237646":83.0,"237647":88.0,"237648":92.0,"237637":"o","237649":100.0,"237650":"o","237653":60.0,"237654":72.0,"237655":82.0,"237656":89.0,"237657":100.0,"237658":150.0,"237659":"o","237670":42.0,"237671":50.0,"237672":"h","237673":61.5,"237674":72.0,"237675":78.0,"237676":87.0,"237677":96.0,"237686":47.0,"237687":"o","237688":55.0,"237689":62.0,"237690":68.0,"237691":73.0,"237692":77.0,"237679":"o","237693":95.0,"237694":"o","237695":"o","237698":"h","237699":25.0,"237700":31.0,"237701":37.0,"237702":43.0,"237703":49.0,"237704":"b","237705":"o","237706":"o","237570":35.0,"237571":29.0,"237572":29.0,"237573":14.0,"237575":"o","242130":134.0,"242131":138.0,"242132":142.0,"242133":146.0,"242134":208.0,"242135":104.0,"242136":108.0,"242137":112.0,"242138":116.0,"242139":152.0,"242140":"b","242150":"h","242151":160.0,"242152":170.0,"242153":180.0,"242154":220.0,"242155":250.0,"242156":110.0,"242157":120.0,"242158":130.0,"242159":160.0,"242160":200.0,"242161":"b","242162":"b","230043":"o","230044":"h","230045":"o","242983":"o","242984":"b","229490":"o","229491":38.0,"229494":"b","242986":"b","242987":"b","242988":"b","242989":"b","242990":"b","229472":"o","229473":"o","243163":"o","20790":12.0,"229471":"o","239796":"o","239803":"o","239798":"b","239799":"b","239800":"b","239801":"b","239802":"b","239812":"o","239811":"o","239810":33.0,"239805":26.0,"239806":29.0,"239807":32.0,"239808":35.0,"239809":38.0,"239790":20.0,"239791":23.0,"239792":25.0,"239793":28.0,"239794":30.0,"239797":40.0,"239813":"o","239814":"b","235830":"o","235831":"o","235832":"o","235833":"o","235834":58.0,"235835":58.0,"235837":225.0,"235839":77.0,"235840":"o","30760":"b","212580":3.8,"212630":"b","212600":2.1,"212640":"b","212610":2.0,"212620":2.0,"213030":3.2,"213020":3.0,"213000":2.2,"213010":2.2,"212760":"b","212750":"h","212720":6.0,"212780":"h","212740":"h","212710":"b","212730":"h","212770":"h","212790":"h","30540":"b","212970":"b","212940":2.0,"212980":0.0,"212930":"o","212960":3.0,"212950":2.0,"212860":"b","212870":"h","212880":"o","212850":"h","212840":2.1,"212830":2.0,"30520":"o","213070":2.0,"213050":2.0,"213080":"b","213040":"b","213060":2.0,"213090":2.1,"30840":"b","30850":"b","213100":1.0,"213110":1.5,"213120":7.5,"224210":2.5,"224200":"b","224350":"b","224400":"o","224370":"o","224380":"b","224390":"o","224360":"o","224450":3.0,"224440":2.0,"224430":2.5,"240940":45.0,"240941":45.0,"240942":72.0,"240943":78.0,"240944":84.0,"240945":90.0,"240946":96.0,"240947":"o","240948":"b","240949":"b","240950":"b","240951":"b","240952":"b","240953":"h","240954":"o","240955":"o","227323":1.0,"227322":"h","227326":"b","227327":"h","227331":"o","227332":"o","227333":"o","227334":"o","231662":2.0,"231663":4.5,"231664":3.8,"231668":5.2,"231669":"b","231670":"b","231671":3.8,"231676":21.0,"231677":21.0,"231678":"o","231679":"o","231420":6.3,"231421":6.3,"231422":6.3,"231423":5.2,"231424":5.2,"231425":5.2,"231428":30.0,"231429":30.0,"231430":30.0,"231431":"o","231432":"o","231512":3.2,"231513":3.0,"231514":3.0,"231518":"o","231519":"o","231520":"b","231521":"b","231522":"b","231523":"b","231524":4.0,"231529":"o","231530":"o","238620":45.0,"238621":48.0,"238622":51.0,"238623":54.0,"238624":57.0,"238625":42.0,"238626":45.0,"238627":48.0,"238628":51.0,"238629":54.0,"238630":69.0,"238631":75.0,"238632":81.0,"238633":87.0,"238634":93.0,"238635":"o","238636":165.0,"231542":3.9,"231543":1.8,"231546":3.5,"231547":3.8,"231550":"b","231551":4.8,"231770":2.7,"231771":"h","231772":"h","231773":"h","231774":"b","231775":"b","231776":"b","231777":"h","231778":"h","231780":"b","231781":"o","231782":"b","231783":"b","231784":"o","232230":"b","232231":4.3,"232232":3.8,"232233":"b","232235":"b","232236":5.3,"232238":4.5,"232239":"o","232240":"o","232250":3.5,"232251":3.4,"232252":3.0,"232253":"b","232254":3.9,"232255":5.2,"232270":"b","232271":"b","232272":"b","232273":"b","232274":"b","232275":"b","232276":"b","233720":"o","233721":"o","233722":"h","233723":9.5,"233724":9.5,"233726":5.5,"233727":"o","233728":"o","233729":"o","233730":"o","233230":5.4,"233231":"b","233232":3.7,"233233":3.7,"233234":"b","233235":"b","233236":8.4,"233238":25.2,"233239":"o","233240":"o","232840":2.7,"232841":5.4,"232842":5.7,"232843":5.2,"232844":5.2,"232846":"o","232847":"o","232848":"o","232810":4.8,"232811":"h","232812":"b","232813":"b","232802":1.8,"232803":2.0,"232804":2.6,"232820":2.8,"232822":"o","232823":2.6,"232824":2.6,"232825":"b","232827":"o","232828":"o","232829":6.0,"233740":5.4,"233741":5.4,"233742":5.4,"233743":5.4,"233744":5.4,"233745":5.4,"233746":5.4,"233747":5.4,"233748":"h","233749":"h","233750":"b","234060":"x","234061":2.0,"234063":2.0,"234064":4.2,"234065":9.8,"234067":15.0,"234068":8.0,"234070":8.0,"234071":7.5,"234073":"o","234074":"o","234075":"o","234076":24.0,"234077":24.0,"234390":2.8,"234391":3.0,"234392":2.3,"234393":7.2,"234394":7.5,"234395":3.0,"234410":2.3,"234411":"h","234412":2.8,"234413":"o","234414":"b","234415":"b","234320":9.3,"234321":9.3,"234322":"o","234323":"o","234324":120.0,"234325":120.0,"234327":"o","234328":102.0,"234329":102.0,"234330":"o","234340":"h","234341":"b","234342":"b","234343":"b","234344":"h","234345":"b","234346":"b","234347":"b","234349":13.5,"234350":"o","234351":"o","234352":"o","234030":"x","234031":12.0,"234032":"o","234034":12.0,"234035":"h","234036":15.0,"234038":"o","234039":40.5,"234042":"o","234043":"o","234055":"b","234056":"b","235380":3.0,"235381":"h","235382":"b","235383":2.8,"235384":"b","235385":"o","235386":7.6,"235387":3.8,"235389":12.0,"235390":"o","235391":"o","234660":67.2,"234661":"b","234662":"b","234664":"b","234666":"b","234663":"o","234665":"b","234668":"o","234669":"o","234677":31.0,"235050":14.0,"235051":14.0,"235052":14.0,"235053":"o","235054":"o","235055":18.0,"235056":18.0,"235057":18.0,"235059":"o","235060":"o","235061":"o","234990":3.1,"234991":"h","234992":2.6,"234993":2.6,"234995":"b","234996":"b","234970":2.2,"234971":1.6,"234972":1.8,"234973":"h","234974":2.2,"234975":"o","234730":4.0,"234731":4.8,"234732":4.5,"234733":8.6,"234734":12.0,"234736":"o","234737":11.8,"234738":"o","234690":"h","234691":"h","234692":"h","234693":"h","234694":"h","234695":"h","235460":2.8,"235461":3.0,"235462":3.0,"235463":7.5,"235464":10.0,"235465":10.0,"235467":17.5,"235468":"o","235469":"o","235470":"o","235471":"b","235480":"o","235481":"b","235482":"h","235483":"b","235484":"b","235485":"b","235486":"b","235130":"o","235131":"o","235132":21.4,"235133":"o","235134":21.4,"235135":"o","235136":"b","235137":33.0,"235138":"b","235140":"b","235141":"b","235142":"o","235143":"o","235144":"o","235150":2.1,"235151":"o","235152":"b","235153":"h","235154":"h","235155":"h","235156":"h","235157":"h","235158":20.0,"235159":20.0,"235160":"h","235161":"h","235164":"o","235165":20.0,"235166":20.0,"235910":7.0,"235911":7.0,"235912":7.0,"235913":"b","235914":15.0,"235917":"o","235918":"o","235919":"o","235920":"o","235930":3.0,"235931":"o","235932":"o","235933":"o","235934":"o","235935":"o","235937":"o","239281":"o","239282":"b","239283":"b","239284":"o","239285":"o","239290":30.0,"239291":40.0,"239292":50.0,"239293":60.0,"239294":70.0,"239295":30.0,"239296":45.0,"239297":60.0,"239298":75.0,"239299":90.0,"239300":36.0,"239301":46.0,"239302":56.0,"239303":66.0,"239304":76.0,"239305":"b","239306":"o","239307":"b","239321":48.0,"239322":"h","239323":"o","239324":"o","239325":"b","239326":"o","239327":"o","240680":63.0,"240681":36.0,"240682":"o","240683":"o","240684":"o","240685":"o","231319":50.0,"231320":50.0,"231740":6.8,"231741":6.8,"231742":6.8,"231743":4.5,"231744":5.2,"231745":5.2,"231746":5.2,"231747":1.0,"231750":26.2,"231751":26.2,"231752":26.2,"231753":5.5,"231754":"o","229492":"b","229493":0.2,"229510":1.5,"229512":"b","229511":"b","229513":3.0,"229514":"b","229515":"h","232344":30.0,"232345":30.0,"232346":"b","232347":"b","232349":32.5,"232350":"o","232351":"o","232370":"h","232371":4.0,"232372":4.5,"232373":6.0,"232376":"o","232375":14.0,"232377":"h","232378":"o","236160":"h","236161":"b","236162":"h","236165":"b","236166":"h","236170":"b","236169":27.0,"235961":"o","235962":"o","235963":"o","235964":"o","235965":"o","235966":"o","235969":"o","235970":"o","235980":"o","235981":"b","235982":"o","235983":"o","235984":"b","235985":"o","235986":"b","229710":"h","229712":"h","229714":"h","229715":"h","229717":"b","229718":"b","229721":"o","229722":"h","229723":"b","229730":"b","229729":"b","229728":"b","229727":"b","230440":3.0,"230450":"h","230451":"o","230446":1.2,"230447":1.2,"230448":1.2,"230442":4.7,"230445":"h","230443":4.1,"230444":4.1,"230441":3.0,"201560":1.2,"201580":1.5,"201590":"b","201600":"b","201610":4.3,"201790":0.8,"201800":2.1,"201810":"o","201820":1.5,"214660":"b","214670":6.0,"230351":"b","230353":1.0,"230352":3.2,"230350":2.0,"230354":2.0,"230357":2.0,"230359":3.8,"230360":"o","227424":"b","227423":5.2,"227425":"b","227422":2.4,"227420":11.2,"227426":"o","227427":"h","227443":"h","227442":"x","227445":"h","227444":"h","227440":5.4,"227441":5.4,"227470":2.4,"227472":"b","227471":"b","227473":"b","227474":2.4,"227475":"o","227454":"b","227455":"b","227450":4.2,"227453":1.2,"227451":3.8,"227452":5.0,"230310":6.0,"230311":8.0,"230313":"o","230312":"o","230314":"o","214430":3.0,"214440":"b","214450":"b","214460":"h","230330":2.7,"230331":2.7,"230333":"o","230334":"o","230335":"o","230336":"o","214520":"o","214580":"o","214600":"o","214590":"o","214610":"o","214620":0.8,"230031":3.2,"230032":4.2,"230033":3.6,"230034":"o","230038":15.0,"230036":4.5,"230037":"b","230051":3.0,"230052":2.4,"230054":2.6,"230055":"b","230057":6.0,"230058":"h","230059":"h","220160":"b","220180":"b","220220":"b","220170":"b","220200":"b","220230":45.0,"220210":"b","220190":"b","242170":"o","10390":"h","220390":3.0,"220310":"b","220330":"b","220350":"b","220380":10.0,"220320":"o","220340":"o","220360":"o","220370":6.8,"228270":5.0,"228271":6.0,"228272":"o","228273":"o","219990":3.8,"220030":"b","219960":2.7,"220000":"b","220010":1.6,"219970":3.3,"228260":7.5,"228261":3.0,"228262":7.0,"228263":"o","232282":60.0,"232283":52.5,"232288":48.0,"232290":60.0,"232289":"b","232292":15.0,"232316":108.0,"235836":60.0,"235841":"o","235842":"o","235810":30.0,"235811":"b","235812":15.0,"235813":29.0,"235814":29.0,"235815":48.0,"235816":84.0,"235818":"o","235819":"o","235850":28.0,"235851":28.0,"235852":13.0,"235853":13.0,"235854":36.0,"235855":36.0,"235857":156.0,"235858":156.0,"235859":"o","235860":"o","222270":"b","222230":3.0,"222260":3.0,"222250":4.0,"222240":4.0,"222280":"b","222320":"h","222310":"o","222300":"o","222350":"b","222360":3.3,"222340":"h","222330":"h","222370":"b","222440":"h","222820":"o","222420":"h","222430":"b","222410":"o","222460":"b","222450":"b","222470":"b","222500":1.8,"222510":5.4,"222520":3.3,"222490":"b","222530":"b","222550":"b","222560":"b","222570":"h","222580":"b","222610":2.7,"222620":1.1,"222600":2.5,"222830":"b","222640":3.0,"222650":3.0,"222660":3.0,"222670":3.0,"222680":3.0,"222710":3.0,"222720":3.0,"222840":"b","232010":"x","232011":3.2,"232012":3.2,"232013":3.2,"232014":3.2,"232015":4.8,"232016":4.8,"232017":4.8,"232018":8.6,"232021":3.8,"232022":"o","232024":"o","232023":24.8,"232025":"o","232026":24.8,"232027":24.8,"228476":1.8,"228477":"h","228478":2.4,"228482":"h","228483":2.4,"228484":4.2,"233260":3.5,"233262":6.0,"233261":3.5,"233263":4.8,"233264":4.8,"233265":21.0,"233266":"h","233269":"o","233270":"o","233268":21.8,"243031":"b","243034":100.0,"243035":110.0,"243036":120.0,"243037":130.0,"243038":144.0,"243039":84.0,"243040":90.0,"243041":96.0,"243042":102.0,"243043":108.0,"243044":"b","233280":"o","233281":4.2,"233282":6.8,"233283":3.2,"233284":3.2,"233285":3.8,"233288":23.0,"233289":23.0,"233290":"o","233291":"o","243073":100.0,"243074":110.0,"243075":120.0,"243076":130.0,"243077":144.0,"243078":120.0,"243079":135.0,"243080":150.0,"243081":165.0,"243082":180.0,"243083":"b","233310":"h","233311":"b","233312":"h","233313":"h","233315":6.8,"239804":"b","240164":"b","240165":"b","240166":"b","240167":"b","240168":"b","240169":"b","240170":"b","240161":"o","240162":"o","240163":"o","240172":"o","240173":"o","240174":"o","240175":"o","240177":45.0,"240178":45.0,"240179":78.0,"240180":85.5,"240181":93.0,"240182":300.0,"240183":375.0,"240185":1200.0,"240184":"o","240111":75.0,"240112":82.5,"240113":90.0,"240114":300.0,"240115":450.0,"240117":35.0,"240118":35.0,"240119":70.0,"240120":75.0,"240121":80.0,"240122":260.0,"240123":360.0,"240116":"o","240124":"o","240125":637.5,"240126":75.0,"240127":80.0,"240128":85.0,"240129":300.0,"240130":400.0,"240131":75.0,"240132":80.0,"240133":85.0,"240134":300.0,"240135":400.0,"240136":300.0,"240137":"o","240138":"o","240139":1800.0,"240990":48.0,"240991":52.0,"240992":56.0,"240993":60.0,"240994":64.0,"240996":84.0,"240997":90.0,"240998":96.0,"240999":102.0,"241000":108.0,"240995":"o","241001":600.0,"241002":84.0,"241003":90.0,"241004":96.0,"241005":102.0,"241006":108.0,"241007":600.0};
  var lastScene = null, acted = {}, lastOpen = 0, lastCastAt = 0;
  function botHooks() {
    var push = sym('_ZN11BattleScene20pushAbilityMenuSceneEP10PlayerUnit');
    var use = sym('_ZN22BattleAbilityMenuScene11isUseEnableEP9SkillDataRi');
    var mg = sym('_ZN11BattleScene17callBackMagicMenuEP10PlayerUnitiPN7cocos2d7__ArrayEP15BattleSkillTerm');
    var sp = sym('_ZN11BattleScene19callBackSpecialMenuEP10PlayerUnitiPN7cocos2d7__ArrayEP15BattleSkillTerm');
    var seen = {}, lines = 0, raw = 0;
    var scene = null, menu = null, bunit = null, menuAt = 0, listAt = 0, list = [], castDone = true, casts = 0;
    var setp = sym('_ZN22BattleAbilityMenuScene9setParamsEP11BattleSceneP10PlayerUnit');
    function say(t) { if (++lines <= 400 || /^(cast|Bot cast|magic cast|ability cast|summon|limit|item)/.test(t)) log('Bot test: ' + t); }
    function ints(p, n) {
      var o = [];
      for (var i = 0; i < n; i++) o.push(p.add(i * 4).readS32());
      return o.join(',');
    }
    if (push) Interceptor.attach(push, { onEnter: function (a) {
      seen = {};
      if (Date.now() - lastOpen > 60000) acted = {};
      lastOpen = Date.now();
      scene = a[0]; bunit = a[1]; menu = null; list = []; menuAt = Date.now(); castDone = false;
      var u = a[1], t = '';
      try { t = ' ints=' + ints(u.add(0x10), 12); } catch (e) { }
      say('ability menu opens, unit ' + u + t + ' | Bot cast is ' + (RT.bot ? 'ON' : 'OFF'));
    } });
    if (setp) Interceptor.attach(setp, { onEnter: function (a) { menu = a[0]; } });
    // Our own pushAbilityMenuScene call runs setParams inside our call, where hooks stay silent. The menu's per-frame update runs
    // from the game's own thread, so catch the menu there.
    var upd = sym('_ZN22BattleAbilityMenuScene11updateEventEv');
    if (upd) Interceptor.attach(upd, { onEnter: function (a) {
      if (!menu && !castDone && Date.now() - menuAt < 3000) { menu = a[0]; say('menu captured from its update'); }
    } });
    if (use) Interceptor.attach(use, {
      onEnter: function (a) { this.sd = a[1]; this.ri = a[2]; },
      onLeave: function (r) {
        try {
          var sd = this.sd, key = sd.toString();
          if (seen[key]) return;
          seen[key] = 1;
          var vt = sd.readPointer();
          var typ = new NativeFunction(vt.add(0x10).readPointer(), 'int', ['pointer'])(sd);
          var id = new NativeFunction(vt.add(0x20).readPointer(), 'int', ['pointer'])(sd);
          var why = 0;
          try { why = this.ri.readS32(); } catch (e) { }
          list.push({ type: typ, id: id, usable: (r.toInt32() & 1), sd: sd });
          listAt = Date.now();
          say('ability type=' + typ + ' id=' + id + ' usable=' + (r.toInt32() & 1) + ' reason=' + why);
          if (++raw <= 4) say('ability raw ' + key + ' ints=' + ints(sd, 24));
          if (typ === 3 || typ === 4) {   // summon and limit burst entries: dump 8-byte words so the beast info pointer of a summon cast can be matched
            var qw = [];
            for (var qi = 0; qi < 24; qi++) { try { qw.push(sd.add(qi * 8).readPointer().toString()); } catch (e) { qw.push('?'); } }
            say('summon entry type ' + typ + ' id ' + id + ' qwords ' + qw.join(' '));
          }
        } catch (e) { say('ability read error: ' + e); }
      }
    });
    function cast(name) {
      return { onEnter: function (a) {
        try { say(name + ' cast: unit ' + a[1] + ' index ' + a[2].toInt32() + ' targets ' + a[3] + ' ints=' + ints(a[3], 12)); }
        catch (e) { say(name + ' cast error: ' + e); }
      } };
    }
    if (mg) Interceptor.attach(mg, cast('magic'));
    if (sp) Interceptor.attach(sp, cast('ability'));
    // The other command paths a player can use. Logged only (never called) so the next battle log shows how a summon, a limit burst
    // and an item are cast: arguments of the game's own callbacks. These lines are never cut by the line cap.
    function safeInts(p, n) { try { return p.isNull() ? 'null' : ints(p, n); } catch (e) { return 'unreadable'; } }
    var smn = sym('_ZN11BattleScene18callBackSummonMenuEP10PlayerUnitiiP15BattleSkillTermP13UserBeastInfo');
    var lbm = sym('_ZN11BattleScene22callBackLimitBurstMenuEP10PlayerUnitiPN7cocos2d7__ArrayEP15BattleSkillTerm');
    var itm = sym('_ZN11BattleScene16callBackItemMenuEP10PlayerUnitiPN7cocos2d7__ArrayE');
    if (smn) Interceptor.attach(smn, { onEnter: function (a) {
      try { say('summon menu cast: unit ' + a[1] + ' i1 ' + a[2].toInt32() + ' i2 ' + a[3].toInt32() + ' term ' + a[4] + ' termInts=' + safeInts(a[4], 8) + ' beast ' + a[5] + ' beastInts=' + safeInts(a[5], 16)); }
      catch (e) { say('summon menu cast error: ' + e); }
    } });
    if (lbm) Interceptor.attach(lbm, { onEnter: function (a) {
      try { say('limit burst cast: unit ' + a[1] + ' idx ' + a[2].toInt32() + ' targets ' + a[3] + ' ints=' + safeInts(a[3], 12) + ' term ' + a[4] + ' termInts=' + safeInts(a[4], 8)); }
      catch (e) { say('limit burst cast error: ' + e); }
    } });
    var mts = sym('_ZN22BattleAbilityMenuScene25callBackMultiTargetSelectEiPN7cocos2d7__ArrayE');
    var mmm = sym('_ZN22BattleAbilityMenuScene19changeMultiMenuModeEP9SkillDataP12SkillMstBasei');
    if (mts) Interceptor.attach(mts, { onEnter: function (a) {
      try { say('multi pick (dual cast): i ' + a[1].toInt32() + ' targets ' + a[2] + ' ints=' + safeInts(a[2], 12) + ' menu skill ' + a[0].add(0x660).readPointer() + ' count ' + a[0].add(0x688).readS32()); }
      catch (e) { say('multi pick error: ' + e); }
    } });
    if (mmm) Interceptor.attach(mmm, { onEnter: function (a) {
      try { say('multi menu mode: skillData ' + a[1] + ' mst ' + a[2] + ' n ' + a[3].toInt32()); }
      catch (e) { say('multi mode error: ' + e); }
    } });
    if (itm) Interceptor.attach(itm, { onEnter: function (a) {
      try { say('item cast: unit ' + a[1] + ' idx ' + a[2].toInt32() + ' targets ' + a[3] + ' ints=' + safeInts(a[3], 12)); }
      catch (e) { say('item cast error: ' + e); }
    } });
    // Casting test: with the floating-circle switch on, when an ability menu has opened, use the first usable magic or ability
    // on the targets the game's own auto-target code picks, through the game's own cast callback, then close the menu.
    function doCast() {
      // Skill choice from a table built from the community skill data: d = damage (number = strength), b = buff,
      // h = heal, x = scan (Libra and the like), o = other. Turn 1 of a unit: a buff first. Then the strongest damage.
      // Heals and scans are never picked. A skill that is not in the table counts as other.
      if (scene !== lastScene) { lastScene = scene; acted = {}; }
      var first = !acted[String(bunit)];
      var pick = null, bestD = -1, bufP = null, othP = null;
      for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (!c.usable || (c.type !== 1 && c.type !== 2)) continue;
        var kd = SKT[c.id];
        if (kd === undefined) { if (!othP) othP = c; }
        else if (typeof kd === 'number') { if (kd > bestD) { bestD = kd; pick = c; } }
        else if (kd === 'b') { if (!bufP && c.id !== 200140) bufP = c; }
        else if (kd === 'o') { if (!othP) othP = c; }
      }
      if (first && bufP) pick = bufP;
      else if (!pick) pick = othP || bufP;
      acted[String(bunit)] = 1;
      if (!pick) {
        say('cast: nothing worth casting in this menu (scans and heals are skipped)');
        if (OPT.botopen) { try { menu.add(0x658).writeU8(1); say('cast: menu closed (auto-open)'); } catch (e) { } }
        return;
      }
      say('cast: chose ' + pick.id + (first ? ' (first turn of this unit)' : ''));
      fireSkill(pick);
    }

    // Casts one menu entry (type 1 magic, 2 ability, 4 limit burst) through the game's own target-select finish. Returns true when it ran.
    function fireSkill(pick) {
      var lst = nf(pick.type === 1 ? '_ZN12MagicMstList6sharedEv' : pick.type === 4 ? '_ZN17LimitBurstMstList6sharedEv' : '_ZN14AbilityMstList6sharedEv', 'pointer', [])();
      var mst = nf('_ZN7cocos2d12__Dictionary12objectForKeyEl', 'pointer', ['pointer', 'int64'])(lst, pick.id);
      if (mst.isNull()) { say('cast: skill ' + pick.id + ' not found in the game list'); return false; }
      var tl = nf('_ZN10BattleUnit22getAutoSkillTargetListEP12SkillMstBase', 'pointer', ['pointer', 'pointer'])(bunit, mst);
      if (tl.isNull() || tl.add(0x30).readPointer().isNull() || tl.add(0x30).readPointer().readS32() < 1) { say('cast: no target for ' + pick.id); return false; }
      // The menu's own finish step: it reads the chosen skill from the menu (+0x660), calls the battle scene's cast callback
      // with the skill's own term, and sets the menu's close flag (+0x658). We lend it our pick for that one call.
      var cbt = nf('_ZN22BattleAbilityMenuScene20callBackTargetSelectEPN7cocos2d7__ArrayE', 'void', ['pointer', 'pointer']);
      say('cast: type ' + pick.type + ' id ' + pick.id + ' unit ' + bunit + ' targets ' + tl.add(0x30).readPointer().readS32());
      var old = menu.add(0x660).readPointer();
      menu.add(0x660).writePointer(pick.sd);
      try { cbt(menu, tl); } finally { menu.add(0x660).writePointer(old); }
      ++casts; lastCastAt = Date.now();
      var rc = -9, cf = -9;
      try { rc = nf('_ZN10BattleUnit15getReserveCmdIDEv', 'int', ['pointer'])(bunit); cf = menu.add(0x658).readU8(); } catch (e) { }
      say('cast done (' + casts + ') reserved command ' + rc + ' menu close flag ' + cf);
      return true;
    }
    // ====================================================================================================================
    // GAMBIT stage 2: the user's rules (sent by the app with the command "gam <json>") decide what each unit does.
    // Rules of a unit are checked from the top; the first rule whose conditions all hold AND whose action can be done fires.
    // Any error or "nothing fired" hands the unit to the generic bot above. Every decision is logged as "Gambit: ..." lines.
    // ====================================================================================================================
    var GAM = { rules: null, n: 0, said: {} };
    function glog(t) {
      var s = new Date().toISOString().substr(11, 8) + ' Gambit: ' + t;
      glogs.push(s); if (glogs.length > 700) glogs.shift();
      androidLog('Gambit: ' + t);
    }
    function glogOnce(k, t) { if (GAM.said[k]) return; GAM.said[k] = 1; glog(t); }
    cmd.gam = function (t) {
      try {
        var o = JSON.parse(t.substring(4)), n = 0, c = 0;
        for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k) && o[k] instanceof Array) { c++; n += o[k].length; }
        GAM.rules = o; GAM.n = n; GAM.said = {};
        glog('rules received: ' + c + ' characters, ' + n + ' rules');
        return 'ok ' + n;
      } catch (e) { glog('rules text could not be read: ' + e); return 'error ' + e; }
    };
    var gT3 = ['pointer', 'pointer', 'pointer'];
    var gCipA = sym('_ZN11CommonUtils8toStringIN4alim12cipher_valueIxNS1_10xor_cipherEEEEENSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERKT_');
    var gCip = gCipA ? new NativeFunction(gCipA, gT3, ['pointer']) : null;
    function gStrOf(r) {
      var b = Memory.alloc(24);
      b.writePointer(r[0]); b.add(8).writePointer(r[1]); b.add(16).writePointer(r[2]);
      var f = b.readU8();
      return (f & 1) ? b.add(16).readPointer().readUtf8String(b.add(8).readU64().toNumber()) : b.add(1).readUtf8String(f >> 1);
    }
    function gV(u, off) { return new NativeFunction(u.readPointer().add(off).readPointer(), gT3, ['pointer'])(u); }
    function gLL(u, off) {
      var r = gV(u, off), b = Memory.alloc(24);
      b.writePointer(r[0]); b.add(8).writePointer(r[1]); b.add(16).writePointer(r[2]);
      return parseInt(gStrOf(gCip(b)), 10);
    }
    function gInt(u, off) {
      var r = gV(u, off), b = Memory.alloc(24);
      b.writePointer(r[0]); b.add(8).writePointer(r[1]);
      var a = b.readS32(), c = b.add(4).readS32(), d = b.add(8).readS32();
      return (d - c) ^ a;
    }
    var gF = {
      state: nf('_ZN11BattleState6sharedEv', 'pointer', []),
      party: nf('_ZN11BattleState14getPlayerPartyEv', 'pointer', ['pointer']),
      enemy: nf('_ZN11BattleState13getEnemyPartyEv', 'pointer', ['pointer']),
      turn: nf('_ZN13BattleManager10getTurnCntEv', 'int', []),
      maxHp: nf('_ZN10BattleUnit13getTotalMaxHpEv', 'int', ['pointer']),
      maxMp: nf('_ZN10BattleUnit13getTotalMaxMpEv', 'int', ['pointer']),
      dead: nf('_ZN10BattleUnit11isDeadStateEv', 'int', ['pointer']),
      bad: nf('_ZN10BattleUnit10isBadStateEi', 'int', ['pointer', 'int']),
      elemRes: nf('_ZN10BattleUnit21getTotalElementResistEi', 'int', ['pointer', 'int']),
      statRes: nf('_ZN10BattleUnit20getTotalStatusResistEi', 'int', ['pointer', 'int']),
      tribe: nf('_ZN10BattleUnit7isTribeEi', 'int', ['pointer', 'int']),
      buffStr: nf('_ZN10BattleUnit17getSuspendBuffStrEv', gT3, ['pointer']),
      umst: nf('_ZN10PlayerUnit10getUnitMstEv', 'pointer', ['pointer']),
      key: nf('_ZN7UnitMst6getKeyEv', 'int', ['pointer']),
      guard: nf('_ZN10PlayerUnit18reserveGuardActionEv', 'void', ['pointer']),
      resv: nf('_ZN10BattleUnit15getReserveCmdIDEv', 'int', ['pointer']),
      summon: nf('_ZN11BattleScene18callBackSummonMenuEP10PlayerUnitiiP15BattleSkillTermP13UserBeastInfo', 'void', ['pointer', 'pointer', 'int', 'int', 'pointer', 'pointer'])
    };
    function gUnits(party) {
      var res = [];
      if (!party || party.isNull()) return res;
      var arr = party.add(0x28).readPointer().add(0x30).readPointer();
      var num = arr.readS32(), data = arr.add(0x10).readPointer();
      for (var i = 0; i < num && i < 12; i++) { var u = data.add(i * 8).readPointer(); if (!u.isNull()) res.push(u); }
      return res;
    }
    function gSnap(u) {
      var o = { u: u, hp: NaN, mp: NaN, mx: 1, mmx: 1, lb: 0, dead: false };
      try { o.mx = gF.maxHp(u); o.mmx = gF.maxMp(u); } catch (e) { }
      try { o.dead = (gF.dead(u) & 1) === 1; } catch (e) { }
      try { o.hp = gLL(u, 0xf28); } catch (e) { }
      try { o.mp = gLL(u, 0xf30); } catch (e) { }
      try { o.lb = gInt(u, 0xf40); } catch (e) { }
      if (isNaN(o.hp)) o.hp = o.dead ? 0 : o.mx;
      if (isNaN(o.mp)) o.mp = o.mmx;
      o.ko = o.dead || o.hp <= 0;
      o.hpP = o.mx > 0 ? o.hp * 100 / o.mx : 0;
      o.mpP = o.mmx > 0 ? o.mp * 100 / o.mmx : 0;
      o.lbP = o.lb / 10;
      return o;
    }
    function gBuffs(o) {
      if (o.bf) return o.bf;
      var out = [];
      try {
        gStrOf(gF.buffStr(o.u)).split('&').forEach(function (e) {
          if (!e) return;
          var p = e.split(':');
          out.push({ type: parseInt(p[0], 10), val: parseFloat(p[1]) });
          try { glogOnce('bt:' + p[0], 'raw buff type ' + p[0] + ' value ' + p[1] + ' turns ' + p[3] + ' (' + (G_STR[parseInt(p[0], 10)] || 'no status name') + ')'); } catch (e2) { }
        });
      } catch (e) { }
      return (o.bf = out);
    }
    function gBad(o) {
      if (o.bd) return o.bd;
      var a = {};
      for (var k = 1; k <= 14; k++) { try { if ((gF.bad(o.u, k) & 1) === 1) a[k] = 1; } catch (e) { break; } }
      return (o.bd = a);
    }
    // Ailment ids follow the game's own list (ABILITY_DETAIL_BADSTATE_LIST, 1-based): poison, blind, sleep, silence, paralysis, confusion, disease, stone, stop, charm, berserk. Not yet seen in a battle log.
    var G_AIL = { 'poison': 1, 'blind': 2, 'sleep': 3, 'silence': 4, 'paralysis': 5, 'confusion': 6, 'disease': 7, 'petrify': 8, 'stop': 9, 'charm': 10, 'berserk': 11 };
    // Race ids = the game's tribe table (F_TRIBE_MST): 1 beast, 2 bird, 3 aquan, 4 demon, 5 human, 6 machine, 7 dragon, 8 spirit, 9 bug, 10 stone, 11 plant, 12 undead.
    var G_RACE = { 'beast': 1, 'bird': 2, 'aquan': 3, 'demon': 4, 'human': 5, 'machine': 6, 'dragon': 7, 'spirit': 8, 'bug': 9, 'stone': 10, 'plant': 11, 'undead': 12 };
    var G_ELEM = { 'fire': 1, 'ice': 2, 'lightning': 3, 'water': 4, 'wind': 5, 'earth': 6, 'light': 7, 'dark': 8 };
    // Status name -> game buff types [types, sign (types 1-4 only), 1 = 3 sample skills agree / 0 = unsure]. From the game's buff table + skill tables (gambit_status_samples.md).
    var G_ST = {
      "atk up": [[1,39,300], 1, 1],
      "atk down": [[5,1,304], -1, 1],
      "atk break": [[5,1,304], -1, 1],
      "def up": [[2,40,301], 1, 1],
      "def down": [[6,2,305], -1, 1],
      "def break": [[6,2,305], -1, 1],
      "mag up": [[3,41,302], 1, 1],
      "mag down": [[7,3,306], -1, 1],
      "mag break": [[7,3,306], -1, 1],
      "spr up": [[4,42,303], 1, 1],
      "spr down": [[8,4,307], -1, 1],
      "spr break": [[8,4,307], -1, 1],
      "regen": [[20], 0, 1],
      "auto-regen": [[20], 0, 1],
      "mp regen": [[22], 0, 1],
      "reraise": [[21], 0, 1],
      "provoke": [[55], 0, 1],
      "taunt": [[55], 0, 1],
      "stop": [[61], 0, 1],
      "reflect": [[60], 0, 1],
      "physical damage resist up": [[57], 0, 1],
      "magic damage resist up": [[58], 0, 1],
      "mitigation up": [[204], 0, 1],
      "physical evade up": [[56], 0, 1],
      "cover": [[200], 0, 1],
      "counter": [[208,209], 0, 1],
      "dual cast": [[201], 0, 0],
      "limit gauge boost": [[47], 0, 1],
      "lb damage up": [[221], 0, 1],
      "physical killer up": [[63,64,65,66,67,68,69,70,71,72,73,74], 0, 1],
      "magic killer up": [[75,76,77,78,79,80,81,82,83,84,85,86], 0, 1],
      "imbue": [[87,88,89,90,91,92,93,94], 0, 1],
      "fire imbue": [[87], 0, 1],
      "fire resist up": [[23,5016], 0, 1],
      "fire resist down": [[31,5024], 0, 1],
      "imperil fire": [[31,5024], 0, 1],
      "ice imbue": [[88], 0, 1],
      "ice resist up": [[24,5017], 0, 1],
      "ice resist down": [[32,5025], 0, 1],
      "imperil ice": [[32,5025], 0, 1],
      "lightning imbue": [[89], 0, 1],
      "lightning resist up": [[25,5018], 0, 1],
      "lightning resist down": [[33,5026], 0, 1],
      "imperil lightning": [[33,5026], 0, 1],
      "water imbue": [[90], 0, 1],
      "water resist up": [[26,5019], 0, 1],
      "water resist down": [[34,5027], 0, 1],
      "imperil water": [[34,5027], 0, 1],
      "wind imbue": [[91], 0, 1],
      "wind resist up": [[27,5020], 0, 1],
      "wind resist down": [[35,5028], 0, 1],
      "imperil wind": [[35,5028], 0, 1],
      "earth imbue": [[92], 0, 1],
      "earth resist up": [[28,5021], 0, 1],
      "earth resist down": [[36,5029], 0, 1],
      "imperil earth": [[36,5029], 0, 1],
      "light imbue": [[93], 0, 1],
      "light resist up": [[29,5022], 0, 1],
      "light resist down": [[37,5030], 0, 1],
      "imperil light": [[37,5030], 0, 1],
      "dark imbue": [[94], 0, 1],
      "dark resist up": [[30,5023], 0, 1],
      "dark resist down": [[38,5031], 0, 1],
      "imperil dark": [[38,5031], 0, 1],
      "imperil (all elements)": [[31,32,33,34,35,36,37,38], 0, 1],
      "poison resist up": [[12], 0, 1],
      "blind resist up": [[13], 0, 1],
      "sleep resist up": [[14], 0, 1],
      "silence resist up": [[15], 0, 1],
      "paralysis resist up": [[16], 0, 1],
      "confusion resist up": [[17], 0, 1],
      "disease resist up": [[18], 0, 1],
      "petrify resist up": [[19], 0, 1],
      "charm resist up": [[100], 0, 1],
      "stop resist up": [[99], 0, 1],
      "death resist up": [[207], 0, 0],
      "accuracy up": [[343], 0, 1],
      "accuracy down": [[344], 0, 1],
      "magic accuracy up": [[377], 0, 1],
      "magic accuracy down": [[378], 0, 1],
      "haste": [[343], 0, 0],
      "slow": [[344], 0, 0],
      "protect": [[2,40,301,57], 0, 1],
      "shell": [[4,42,303,58], 0, 1],
      "damage over time": [[218], 0, 1],
      "reduced damage taken": [[57,58,204], 0, 1],
      "increased damage taken": [[242,243,244,245,246,247,248,249,250,251,252,253,254,255,256,257], 0, 1]
    };
    var G_STR = {};   // buff type -> first status name, for the log
    Object.keys(G_ST).forEach(function (k) { G_ST[k][0].forEach(function (t) { if (!G_STR[t]) G_STR[t] = k; }); });
    // returns true / false, or null when the status is one this version cannot read
    function gHas(o, name) {
      var n = String(name || '').toLowerCase(), bad = G_AIL[n] ? !!gBad(o)[G_AIL[n]] : false;
      if (n === 'death') return !!o.ko;
      if (bad) return true;
      var en = G_ST[n];
      if (!en) return G_AIL[n] ? false : null;
      glogOnce('stn:' + n, 'status "' + name + '" checks buff type ' + en[0].join(',') + (en[2] ? '' : ' (UNSURE id mapping)'));
      var b = gBuffs(o);
      for (var i = 0; i < b.length; i++) {
        var ty = b[i].type;
        if (en[0].indexOf(ty) < 0) continue;
        if (en[1] && ty >= 1 && ty <= 4 && (en[1] > 0 ? b[i].val <= 0 : b[i].val >= 0)) continue;
        glogOnce('sth:' + n + ty, 'status "' + name + '" found: raw buff type ' + ty + ' value ' + b[i].val);
        return true;
      }
      return false;
    }
    function gCmp(op, a, b) {
      if (op === '>') return a > b;
      if (op === '>=') return a >= b;
      if (op === '=') return Math.abs(a - b) < 0.5;
      if (op === '<=') return a <= b;
      if (op === '<') return a < b;
      throw new Error('bad compare ' + op);
    }
    function gWorld() {
      var st = gF.state();
      if (st.isNull()) throw new Error('no battle state');
      var pp = gF.party(st), pu = gUnits(pp), eu = gUnits(gF.enemy(st)), me = String(bunit), w = { allies: [], foes: [], self: null, sum: -1, turn: 0 };
      pu.forEach(function (u) { var s = gSnap(u); w.allies.push(s); if (String(u) === me) w.self = s; });
      eu.forEach(function (u) { w.foes.push(gSnap(u)); });
      if (!w.self) { w.self = gSnap(bunit); w.allies.push(w.self); }
      try { w.sum = new NativeFunction(pp.readPointer().add(0x130).readPointer(), 'int', ['pointer'])(pp); } catch (e) { }
      try { w.raw = gF.turn(); } catch (e) { w.raw = -1; }
      // The game's own turn counter jumps by 2 (log: 1, 3), so the rules' turn number is our own count of command rounds:
      // a round ends when a unit that was already judged in this round is judged again. A new battle scene starts at 1.
      var rk = me;
      var mk = '?';
      try { mk = String(nf('_ZN11BattleState16getBattleManagerEv', 'pointer', ['pointer'])(st)); } catch (e) { }
      // A new stage of a multi-stage battle rebuilds the battle objects (same signal the bot uses for its 4 s pause): the manager, the player party or the
      // enemy party is a new object. Any of them changing restarts our round count at 1. Within one stage none of them changes.
      var sk = mk + '/' + String(pp) + '/' + String(gF.enemy(st));
      if (sk !== gRound.sk && gRound.sk) { gStageN++; glog('new stage or battle (battle objects changed): turn count restarts at 1'); }
      gRound.sk = sk;
      if (mk !== gRound.mgr || sk !== gRound.skUsed || (w.raw >= 0 && w.raw < gRound.raw)) { gRound.mgr = mk; gRound.skUsed = sk; gRound.n = 1; gRound.seen = {}; }   // new battle or new stage
      else if (gRound.seen[rk]) { gRound.n++; gRound.seen = {}; }
      gRound.raw = w.raw;
      gRound.seen[rk] = 1;
      w.turn = gRound.n;
      if (mk !== gDumpMgr) { gDumpMgr = mk; gDumpSeen = {}; glog('--- new battle: unit status lists follow (ally = party, foe = enemies) ---'); }
      w.allies.forEach(function (o) { gDump(o, 'ally'); });
      w.foes.filter(gAlive).forEach(function (o) { gDump(o, 'foe'); });
      return w;
    }
    var gStageN = 1;
    // Decision store for the app's viewer (switch "Decision log"): the last 500 decisions, kept in memory, read by the app with the "dlog" command.
    var DEC = [];
    // Lines for the Android overlay notice (switch "Gambit notice (floating)"): plain text only, read by the app with the "dnote" command. No game object is touched.
    var ONOTES = [];
    var ONOTE_KEEP = 8;
    // The list stays (last 8 decisions, newest last) so the box never blinks; the app replaces its text only when it changes.
    cmd.dnote = function () { return ONOTES.join('\n'); };
    function oNote(t) { ONOTES.push(String(t).replace(/[\r\n]/g, ' ').substr(0, 80)); while (ONOTES.length > ONOTE_KEEP) ONOTES.shift(); }
    var DEC_KEEP = ['c', 't', 'op', 'n', 'v', 'o', 'a', 'an'];
    function decRule(r) { var o = {}; DEC_KEEP.forEach(function (k) { if (r[k] !== undefined) o[k] = r[k]; }); return o; }
    cmd.dlog = function () { try { return JSON.stringify(DEC); } catch (e) { return '[]'; } };
    cmd.dlogclear = function () { DEC = []; return 'cleared'; };
    var gRound = { mgr: null, raw: -1, n: 1, seen: {}, sk: '', skUsed: '' };
    // Status dump (test help): the real status list of every unit, once at battle start and again whenever it changes.
    // Raw ids only, so one log is enough to correct the guessed id tables. Lines: "status dump <side> <name/key> | buffs raw ... | bad ... | elemRes ... | statRes ..."
    var gDumpSeen = {}, gDumpN = 0, gDumpMgr = null;
    function gDump(o, side) {
      try {
        var raw = '';
        try { raw = gStrOf(gF.buffStr(o.u)); } catch (e) { raw = '?'; }
        var bad = [], er = [], sr = [], k;
        for (k = 1; k <= 14; k++) { try { if ((gF.bad(o.u, k) & 1) === 1) bad.push(k); } catch (e) { break; } }
        for (k = 1; k <= 8; k++) { try { er.push(k + '=' + gF.elemRes(o.u, k)); } catch (e) { er.push(k + '=?'); } }
        for (k = 1; k <= 11; k++) { try { sr.push(k + '=' + gF.statRes(o.u, k)); } catch (e) { sr.push(k + '=?'); } }
        var key = side + ':' + String(o.u), sig = raw + '|' + bad.join(',') + '|' + er.join(',') + '|' + sr.join(',');
        if (gDumpSeen[key] === sig) return;
        gDumpSeen[key] = sig;
        if (++gDumpN > 300) return;
        var nm = ''; try { if (side === 'ally') nm = gKey(o.u); } catch (e) { }
        glog('status dump ' + side + ' ' + (nm || String(o.u)) + ' hp ' + Math.round(o.hpP) + '% | buffs raw "' + raw + '" | bad ids [' + bad.join(',') + '] | elemRes ' + er.join(' ') + ' | statRes ' + sr.join(' '));
      } catch (e) { glogOnce('dumperr', 'status dump error: ' + e); }
    }
    function gAlive(o) { return !o.ko; }
    function gSet(t, w) { return t === 'foe' ? w.foes.filter(gAlive) : t === 'ally' ? w.allies.filter(gAlive) : [w.self]; }
    function gAny(l, f) { for (var i = 0; i < l.length; i++) if (f(l[i])) return true; return false; }
    function gNum(c) {
      var v = parseFloat(c.n);
      if (isNaN(v)) throw new Error('no number in condition ' + c.c);
      return v;
    }
    // one condition: true / false (throws when it cannot be judged)
    function gCond(c, w) {
      var id = c.c || 'always', t = c.t || 'self', op = c.op, v;
      switch (id) {
        case 'always': return true;
        case 'status': {
          var want = (op !== 'isnot'), unk = false;
          var r = gAny(gSet(t, w), function (o) { var h = gHas(o, c.v); if (h === null) { unk = true; return !want; } return want ? h : !h; });
          glogOnce('stchk:' + c.v + ':' + t + ':' + want + ':' + r, 'check status "' + c.v + '" ' + (want ? 'is' : 'is not') + ' on ' + t + ' -> ' + r + (unk ? ' (name not readable)' : '') + ' | self buffs raw "' + (function () { try { return gStrOf(gF.buffStr(w.self.u)); } catch (e) { return '?'; } })() + '"');
          if (unk) glogOnce('st:' + c.v, 'status "' + c.v + '" cannot be read yet: counts as absent ("is not" passes, "is" fails)');
          return r;
        }
        case 'ally_ko': return gCmp(op || '>=', w.allies.filter(function (o) { return o.ko; }).length, gNum(c));
        case 'foe_left': return gCmp(op || '>=', w.foes.filter(gAlive).length, gNum(c));
        case 'hp_pct': v = gNum(c); return gAny(gSet(t, w), function (o) { return gCmp(op, o.hpP, v); });
        case 'hp_amt': v = gNum(c); return gAny(gSet(t, w), function (o) { return gCmp(op, o.hp, v); });
        case 'mp_pct': v = gNum(c); return gAny(gSet(t === 'foe' ? 'self' : t, w), function (o) { return gCmp(op, o.mpP, v); });
        case 'mp_amt': v = gNum(c); return gAny(gSet(t === 'foe' ? 'self' : t, w), function (o) { return gCmp(op, o.mp, v); });
        case 'lb': v = gNum(c); return gAny(gSet(t === 'foe' ? 'self' : t, w), function (o) { return gCmp(op, o.lbP, v); });
        case 'avg_hp_pct': case 'avg_hp_amt': {
          v = gNum(c);
          var l = t === 'foe' ? w.foes.filter(gAlive) : w.allies;
          if (!l.length) return false;
          var s = 0; l.forEach(function (o) { s += id === 'avg_hp_pct' ? o.hpP : o.hp; });
          return gCmp(op, s / l.length, v);
        }
        case 'summon_pct':
          if (w.sum < 0) throw new Error('summon gauge unreadable');
          return gCmp(op || '>=', w.sum * 10, gNum(c));
        case 'elem_res': case 'stat_res': {
          v = gNum(c);
          var isE = id === 'elem_res', fn = isE ? gF.elemRes : gF.statRes, nm = String(c.v || '').toLowerCase(), ids = [];
          if (isE) { if (G_ELEM[nm]) ids = [G_ELEM[nm]]; else for (var e1 = 1; e1 <= 8; e1++) ids.push(e1); }
          else { if (G_AIL[nm]) ids = [G_AIL[nm]]; else if (nm && nm.indexOf('all') !== 0) throw new Error('status resistance "' + nm + '" cannot be read'); else for (var e2 = 1; e2 <= 8; e2++) ids.push(e2); }
          var rr = gAny(gSet(t, w), function (o) { for (var i = 0; i < ids.length; i++) if (!gCmp(op, fn(o.u, ids[i]), v)) return false; return true; });
          var vals = []; try { gSet(t, w).forEach(function (o) { ids.forEach(function (x) { vals.push(x + '=' + fn(o.u, x)); }); }); } catch (e) { }
          gTrVals = 'raw ' + vals.join(' ');
          glogOnce((isE ? 'elchk:' : 'srchk:') + nm + ':' + t + ':' + op + v + ':' + rr + ':' + vals.join(','), 'check ' + id + ' "' + nm + '" ' + t + ' ' + op + ' ' + v + ' -> ' + rr + ' | raw ' + vals.join(' '));
          return rr;
        }
        case 'turn_1': return w.turn === 1;
        case 'turn_even': return w.turn % 2 === 0;
        case 'turn_odd': return w.turn % 2 === 1;
        case 'turn_mod': {
          var x = parseInt(c.v, 10), y = parseInt(c.o, 10);
          if (!(x >= 1) || !(y >= 1)) throw new Error('turn rotation needs x and y');
          return w.turn >= y && (w.turn - y) % x === 0;
        }
        case 'foe_race': {
          var rid = G_RACE[String(c.v || '').toLowerCase()];
          if (!rid) throw new Error('no race chosen');
          if (!gF.tribe) throw new Error('race check not found in this game');
          var rf = w.foes.filter(gAlive), rl = [];
          rf.forEach(function (o) { var ids = []; for (var q = 1; q <= 12; q++) { try { if (gF.tribe(o.u, q) & 1) ids.push(q); } catch (e) { } } rl.push(ids.join('/') || '-'); });
          var rres = rf.some(function (o) { try { return !!(gF.tribe(o.u, rid) & 1); } catch (e) { return false; } });
          glogOnce('race:' + c.v + ':' + rl.join(',') + ':' + rres, 'check foe_race "' + c.v + '" (id ' + rid + ') -> ' + rres + ' | races of foes ' + rl.join(' , '));
          return rres;
        }
        default:
          if (/^(foe|ally)_(highest|lowest)_/.test(id)) return true;   // a target preference, not a test: always passes (target is the game's own pick)
          throw new Error('unknown condition ' + id);
      }
    }
    // Decision log (switch "Decision log", off by default): what value each condition looked at. Uses only the snapshot w that Gambit already took,
    // plus the elem/stat resistance text gCond already built (gTrVals). No new game calls.
    var gTrVals = '';
    function gRd(c, w) {
      var id = c.c || 'always', t = c.t || 'self', R = Math.round, f = function (l, k) { return l.map(function (o) { return R(o[k]); }).join('/'); };
      var l = t === 'foe' ? w.foes.filter(gAlive) : t === 'ally' ? w.allies.filter(gAlive) : [w.self];
      switch (id) {
        case 'always': return '';
        case 'hp_pct': return 'hp% ' + t + ' ' + f(l, 'hpP');
        case 'hp_amt': return 'hp ' + t + ' ' + f(l, 'hp');
        case 'mp_pct': return 'mp% ' + f(l, 'mpP');
        case 'mp_amt': return 'mp ' + f(l, 'mp');
        case 'lb': return 'lb% ' + f(l, 'lbP');
        case 'avg_hp_pct': case 'avg_hp_amt': { var q = t === 'foe' ? w.foes.filter(gAlive) : w.allies, z = 0; q.forEach(function (o) { z += id === 'avg_hp_pct' ? o.hpP : o.hp; }); return 'avg ' + (q.length ? R(z / q.length) : '-'); }
        case 'ally_ko': return 'ko ' + w.allies.filter(function (o) { return o.ko; }).length;
        case 'foe_left': return 'foes ' + w.foes.filter(gAlive).length;
        case 'summon_pct': return 'summon% ' + (w.sum * 10);
        case 'turn_1': case 'turn_even': case 'turn_odd': case 'turn_mod': return 'turn ' + w.turn;
        case 'elem_res': case 'stat_res': case 'status': case 'foe_race': return gTrVals;
        default: return '';
      }
    }
    function gCondText(c) { return (c.c || 'always') + (c.t ? ' ' + c.t : '') + (c.op ? ' ' + c.op : '') + (c.n !== undefined ? ' ' + c.n : '') + (c.v ? ' ' + c.v : ''); }
    function gKey(u) { var m = gF.umst(u); return m.isNull() ? '' : String(gF.key(m)); }
    function gFirst(types, id, usableOnly) {
      for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (types.indexOf(c.type) < 0) continue;
        if (id !== null && c.id !== id) continue;
        if (usableOnly && !c.usable) continue;
        return c;
      }
      return null;
    }
    function gClose() { try { menu.add(0x658).writeU8(1); } catch (e) { } }
    function gSummon(e, uname, n) {
      if (!e) { glog(uname + ' rule ' + n + ' skipped: this unit has no summon in its menu'); return null; }
      if (!e.usable) { glog(uname + ' rule ' + n + ' skipped: summon ' + e.id + ' not ready (menu says unusable)'); return null; }
      if (!gF.summon) { glog(uname + ' rule ' + n + ' skipped: summon function missing'); return null; }
      var beast = e.sd.add(0xa0).readPointer();
      if (beast.isNull()) { glog(uname + ' rule ' + n + ' skipped: no summon data in the menu entry'); return null; }
      gF.summon(scene, bunit, 3, e.id, ptr(0), beast);
      gClose(); ++casts; lastCastAt = Date.now();
      return 'summon ' + e.id + ' (command ' + gF.resv(bunit) + ')';
    }
    // Dual cast: the game's own menu enters a multi-pick mode (BattleAbilityMenuScene::changeMultiMenuMode), then every spell pick goes through
    // callBackMultiTargetSelect (it keeps a list; when the list is full the menu closes). Not tested on a phone yet: every step is logged.
    var G_DUAL = {"1101130":1,"1129000":1,"1422230":1,"1454330":1,"200150":1,"200160":1,"204260":1,"205670":1,"206660":1,"207740":1,"210290":1,"217030":1,"223380":1,"227640":1,"227641":1,"227713":1,"228850":1,"229637":1,"229638":1,"230830":1,"231973":1,"232792":1,"232795":1,"232938":1,"233013":1,"233014":1,"233015":1,"235370":1,"239864":1,"240088":1,"240089":1,"240213":1,"240847":1,"241544":1,"241545":1,"241931":1,"241934":1,"241936":1,"241973":1,"242219":1,"243196":1,"243470":1,"243489":1,"243514":1,"243613":1,"243617":1,"243785":1,"244214":1,"244230":1,"244701":1,"244773":1,"244831":1,"244941":1,"245545":1,"400010":1,"400100":1,"400120":1,"451061":1,"504530":1,"504540":1,"506460":1,"506500":1,"600460":1,"702930":1,"702940":1,"703210":1,"703220":1,"800614":1,"800616":1,"910142":1,"910315":1,"910784":1,"910918":1,"910919":1,"911168":1,"911510":1,"911767":1,"911776":1,"914373":1,"915353":1,"915354":1,"915355":1,"915999":1,"916010":1,"917615":1};
    function gDual(idA, idB, uname, n) {
      var d = null, i;
      for (i = 0; i < list.length; i++) if (list[i].type === 2 && list[i].usable && G_DUAL[list[i].id]) { d = list[i]; break; }
      if (!d) { glog(uname + ' rule ' + n + ' skipped: no usable Dual cast ability in this menu'); return null; }
      var mA = gFirst([1], idA, true), mB = gFirst([1], idB, true);
      if (!mA || !mB) { glog(uname + ' rule ' + n + ' skipped: spell ' + (!mA ? idA : idB) + ' not usable now'); return null; }
      var fCnt = nf('_ZN22BattleAbilityMenuScene17getSelectMagicCntEP9SkillDataP12SkillMstBase', 'int', ['pointer', 'pointer', 'pointer']);
      var fMode = nf('_ZN22BattleAbilityMenuScene19changeMultiMenuModeEP9SkillDataP12SkillMstBasei', 'void', ['pointer', 'pointer', 'pointer', 'int']);
      var fMulti = nf('_ZN22BattleAbilityMenuScene25callBackMultiTargetSelectEiPN7cocos2d7__ArrayE', 'void', ['pointer', 'int', 'pointer']);
      if (!fCnt || !fMode || !fMulti) { glog(uname + ' rule ' + n + ' skipped: the game has no multi-cast functions here'); return null; }
      var dict = nf('_ZN7cocos2d12__Dictionary12objectForKeyEl', 'pointer', ['pointer', 'int64']);
      var abl = nf('_ZN14AbilityMstList6sharedEv', 'pointer', [])(), mgl = nf('_ZN12MagicMstList6sharedEv', 'pointer', [])();
      var mstD = dict(abl, d.id), tlf = nf('_ZN10BattleUnit22getAutoSkillTargetListEP12SkillMstBase', 'pointer', ['pointer', 'pointer']);
      if (mstD.isNull()) { glog(uname + ' rule ' + n + ' skipped: Dual cast ' + d.id + ' not in the game list'); return null; }
      var cnt = fCnt(menu, d.sd, mstD);
      glog(uname + ' dual cast: ability ' + d.id + ' select-magic count ' + cnt);
      if (cnt < 2) { glog(uname + ' rule ' + n + ' skipped: the game says this ability does not need 2 picks (' + cnt + ')'); return null; }
      var picks = [mA, mB], tls = [];
      for (i = 0; i < 2; i++) {
        var ms = dict(mgl, picks[i].id);
        if (ms.isNull()) { glog(uname + ' rule ' + n + ' skipped: spell ' + picks[i].id + ' not in the game list'); return null; }
        var tl = tlf(bunit, ms);
        if (tl.isNull() || tl.add(0x30).readPointer().isNull() || tl.add(0x30).readPointer().readS32() < 1) { glog(uname + ' rule ' + n + ' skipped: no target for spell ' + picks[i].id); return null; }
        tls.push(tl);
      }
      fMode(menu, d.sd, mstD, cnt);
      glog(uname + ' dual cast: multi mode entered');
      for (i = 0; i < 2; i++) {
        var old = menu.add(0x660).readPointer();
        menu.add(0x660).writePointer(picks[i].sd);
        try { fMulti(menu, i, tls[i]); } finally { menu.add(0x660).writePointer(old); }
        var cf = -9; try { cf = menu.add(0x658).readU8(); } catch (e) { }
        glog(uname + ' dual cast: pick ' + (i + 1) + ' = spell ' + picks[i].id + ', menu close flag ' + cf);
      }
      ++casts; lastCastAt = Date.now();
      return 'dual cast ' + d.id + ' + ' + idA + ' + ' + idB + ' (command ' + gF.resv(bunit) + ')';
    }
    // does one rule's action. Returns a short text of what was done, or null when it could not be done (the next rule is tried).
    function gDo(r, uname, n) {
      var a = String(r.a || 'attack'), e;
      if (a === 'attack') { gClose(); return 'attack'; }
      if (a === 'defend') {
        if (!gF.guard) { glog(uname + ' rule ' + n + ' skipped: guard function missing'); return null; }
        gF.guard(bunit);
        var rc = gF.resv(bunit);
        if (rc < 0) { glog(uname + ' rule ' + n + ' skipped: guard did not reserve a command (' + rc + ')'); return null; }
        gClose(); return 'defend (command ' + rc + ')';
      }
      if (a === 'lb') {
        e = gFirst([4], null, true);
        if (!e) { glog(uname + ' rule ' + n + ' skipped: limit burst not ready'); return null; }
        return fireSkill(e) ? 'limit burst ' + e.id : (glog(uname + ' rule ' + n + ' skipped: limit burst ' + e.id + ' could not be cast'), null);
      }
      if (a === 'summon') return gSummon(gFirst([3], null, false), uname, n);
      var dm = /^dual:(\d+):(\d+)$/.exec(a);
      if (dm) return gDual(parseInt(dm[1], 10), parseInt(dm[2], 10), uname, n);
      var m = /^(skill|esper):(\d+)$/.exec(a);
      if (m) {
        var sid = parseInt(m[2], 10), sm = gFirst([3], sid, false);
        if (sm) return gSummon(sm, uname, n);      // an esper picked in the Do list is a summon menu entry
        e = gFirst([1, 2], sid, true);
        if (!e) { glog(uname + ' rule ' + n + ' skipped: skill ' + sid + ' not usable now (not in the menu, no MP, or not learned)'); return null; }
        return fireSkill(e) ? 'skill ' + sid : (glog(uname + ' rule ' + n + ' skipped: skill ' + sid + ' could not be cast (no target?)'), null);
      }
      glogOnce('act:' + a, 'action "' + a + '" is not supported yet, rule skipped');
      return null;
    }
    // true when a rule fired; false hands the unit to the generic bot
    function gambitStep() {
      if (!GAM.rules) { glogOnce('norules', 'no rules received from the app yet, generic bot'); return false; }
      var key = gKey(bunit), base = key.substring(0, 8), rules = GAM.rules[base], uname = 'unit ' + bunit + ' (' + key + ')';
      if (!key) { glogOnce('nokey' + bunit, uname + ': character id unreadable, generic bot'); return false; }
      if (!(rules instanceof Array) || !rules.length) { glogOnce('norule:' + base, uname + ' has no rules, generic bot'); return false; }
      var w = gWorld(), failed = [];
      var ent = list.filter(function (c) { return c.type === 3 || c.type === 4; }).map(function (c) { return 't' + c.type + ':' + c.id + '/' + c.usable; }).join(' ');
      var usk = list.filter(function (c) { return (c.type === 1 || c.type === 2) && c.usable; }).map(function (c) { return c.id; }).join(',');
      glog('unit ' + bunit + ' usable skills: ' + usk);
      var bfs = '?', bds = '?';
      try { bfs = gStrOf(gF.buffStr(bunit)); } catch (e) { }
      try { bds = Object.keys(gBad(w.self)).join(','); } catch (e) { }
      glog(uname + ' turn ' + w.turn + ' (game ' + w.raw + ') menu ' + ent + ' buffs ' + bfs + ' bad ' + bds + ': hp ' + Math.round(w.self.hpP) + '% mp ' + Math.round(w.self.mpP) + '% lb ' + Math.round(w.self.lbP) + '% summon ' + w.sum + '/10 foes ' + w.foes.filter(gAlive).length + ' ko ' + w.allies.filter(function (o) { return o.ko; }).length);
      var tr = OPT.dlog ? [] : null;
      var rec = null;
      if (tr) {
        try { rec = { t: Date.now(), turn: w.turn, game: w.raw, stage: gStageN, key: key, name: (GAM.rules._n && GAM.rules._n[base]) || '', hp: Math.round(w.self.hpP), mp: Math.round(w.self.mpP), lb: Math.round(w.self.lbP), sum: w.sum,
            foes: w.foes.filter(gAlive).length, ko: w.allies.filter(function (o) { return o.ko; }).length, rules: [], fired: 0 }; } catch (er) { rec = null; }
      }
      var recPush = function () { if (!rec) return; DEC.push(rec); if (DEC.length > 500) DEC.shift(); rec = null; };
      var dHead = function () { try { return 'DECISION T' + w.turn + ' (game ' + w.raw + ', stage ' + gStageN + ') ' + ((GAM.rules._n && GAM.rules._n[base]) || key) + ' [' + key + '] hp ' + Math.round(w.self.hpP) + ' mp ' + Math.round(w.self.mpP) + ' lb ' + Math.round(w.self.lbP) + ' summon ' + w.sum + '/10 foes ' + w.foes.filter(gAlive).length + ' ko ' + w.allies.filter(function (o) { return o.ko; }).length + ' | '; } catch (eh) { return 'DECISION | '; } };
      for (var i = 0; i < rules.length; i++) {
        var r = rules[i], ok = true, conds = [r].concat(r.and instanceof Array ? r.and : []);
        if (r.off) { if (tr) tr.push((i + 1) + ' off'); if (rec) rec.rules.push({ n: i + 1, off: 1 }); continue; }   // disabled in the app: not considered (rule numbers in the log stay the same)
        var parts = [], cl = [];
        try {
          for (var k = 0; k < conds.length && ok; k++) {
            gTrVals = '';
            ok = gCond(conds[k], w);
            if (tr) { var rd = gRd(conds[k], w); parts.push(gCondText(conds[k]) + (rd ? ' {' + rd + '}' : '') + (ok ? ' ok' : ' NO')); if (rec) cl.push({ r: decRule(conds[k]), val: rd, ok: ok ? 1 : 0 }); }
          }
        }
        catch (e) { ok = false; glog(uname + ' rule ' + (i + 1) + ' skipped: ' + e); if (tr) { parts.push('error ' + e); if (rec) cl.push({ r: decRule(conds[k] || r), val: 'cannot read: ' + e, ok: 0 }); } }
        if (!ok) { failed.push(i + 1); if (tr) { tr.push((i + 1) + ' ' + parts.join(' AND ') + ' -> no'); if (rec) rec.rules.push({ n: i + 1, cs: cl, a: r.a, an: r.an, res: 'no' }); } continue; }
        var did = null;
        try { did = gDo(r, uname, i + 1); } catch (e2) { glog(uname + ' rule ' + (i + 1) + ' action failed: ' + e2); }
        if (tr) { tr.push((i + 1) + ' ' + parts.join(' AND ') + (did ? ' -> FIRED' : ' -> met but could not act')); if (rec) { rec.rules.push({ n: i + 1, cs: cl, a: r.a, an: r.an, res: did ? 'fired' : 'blocked' }); if (did) { rec.fired = i + 1; rec.act = did; } } if (did) { recPush(); glog(dHead() + tr.join(' | ') + ' => ' + did + ' (target: the game picks)'); tr = null; } }
        if (did) {
          glog(uname + ' rule ' + (i + 1) + ' fired action ' + did + ' [' + conds.map(gCondText).join(' AND ') + ']' + (failed.length ? ' (rules not met first: ' + failed.join(',') + ')' : ''));
          acted[String(bunit)] = 1;
          try {
            if (OPT.onote) {
              var gn2 = GAM.rules && GAM.rules._n && GAM.rules._n[base];
              oNote('T' + w.turn + ' ' + (gn2 || key) + ': rule ' + (i + 1) + ' > ' + (r.an ? r.an : String(did).replace(/ \(command.*$/, '')));
            }
          } catch (eo) { }
          return true;
        }
        failed.push(i + 1 + 'x');
      }
      glog(uname + ' no rule fired (' + failed.join(',') + '), generic bot takes over');
      if (OPT.onote) { try { oNote('T' + w.turn + ' ' + ((GAM.rules._n && GAM.rules._n[base]) || key) + ': no rule, attack'); } catch (eN) { } }
      if (tr) { glog(dHead() + tr.join(' | ') + ' => nothing matched, generic bot (attack)'); recPush(); }
      return false;
    }
    ensureTick();
    tickFns.push(function () {
      if (!RT.bot || castDone || !scene || !bunit) return;
      if (Date.now() - menuAt < 350) return;
      // A new stage (wave) rebuilds the battle objects. The 26 Nov logs: twice the game crashed (BattleScene::loopBattle, null party) about 1 s after Gambit cast from a menu that was
      // already open while the stage changed (the cast came 10 ms after "new stage or battle"). Same 4 s wait the menu opener uses: do not cast while the objects are being swapped.
      try {
        var st0 = fState();
        if (!st0.isNull()) {
          var p0 = fParty(st0), m0 = '?';
          if (!p0.isNull()) {
            try { m0 = String(fMgr(st0)); } catch (e0) { }
            // the enemy party is the pointer that changes between waves (the 26 Nov 19:44 crash: only it changed, so party + manager alone did not catch it)
            try { m0 += '/' + String(gF.enemy(st0)); } catch (e0b) { }
            var k0 = String(p0) + '/' + m0;
            if (k0 !== lastPKey) { lastPKey = k0; pkeyAt = Date.now(); say('cast: battle objects changed (new stage), waiting 4 s before acting'); }
          }
        }
      } catch (e1) { }
      if (Date.now() - pkeyAt < 4000) return;
      // the menu fills its list a little at a time (abilities come last): wait until no new entry for 700 ms (at most 2.5 s)
      if (list.length && Date.now() - listAt < 700 && Date.now() - menuAt < 2500) return;
      if (!menu || !list.length) {
        if (Date.now() - menuAt > 3000) { castDone = true; say('cast: waiting failed (menu captured: ' + (menu ? 'yes' : 'no') + ', abilities seen: ' + list.length + ')'); }
        return;
      }
      castDone = true;
      var handled = false;
      try { handled = gambitStep(); } catch (e) { glog('error, generic bot takes over: ' + e); handled = false; }
      if (handled) return;
      try { doCast(); } catch (e) { say('cast error: ' + e); }
    });
    // Auto-open (patch switch Bot auto-open, and Bot cast On): when a unit still has no command, open its Ability menu through the
    // game's own push function. The cast code above then picks the skill and closes the menu. The game itself starts the turn when
    // every unit has finished command input (BattleParty::isFinishedCommandInput), so the skills then run together.
    if (OPT.botopen) {
      var bscene = null, bsceneAt = 0, lastTry = 0, lastDiag = 0, tried = {}, nOpen = 0, nDiag = 0, lastSkip = 0;
      var hk = function (name) {
        var a = sym(name);
        if (a) Interceptor.attach(a, { onEnter: function (x) { bscene = x[0]; bsceneAt = Date.now(); } });
      };
      hk('_ZN11BattleScene15isMenuBtnActiveEb');
      hk('_ZN11BattleScene17isAutoAnimeActiveEv');
      var fState = nf('_ZN11BattleState6sharedEv', 'pointer', []);
      var fParty = nf('_ZN11BattleState14getPlayerPartyEv', 'pointer', ['pointer']);
      var fStill = nf('_ZN11BattleParty11isStillnessEv', 'int', ['pointer']);
      var fDone = nf('_ZN10BattleUnit22isFinishedCommandInputEv', 'int', ['pointer']);
      var fEn = nf('_ZN10BattleUnit16isCmdInputEnableEv', 'int', ['pointer']);
      var fRes = nf('_ZN10BattleUnit15getReserveCmdIDEv', 'int', ['pointer']);
      var fTurn = nf('_ZN13BattleManager10getTurnCntEv', 'int', []);
      var fMenuBtn = nf('_ZN11BattleScene15isMenuBtnActiveEb', 'int', ['pointer', 'int']);
      var fPush = nf('_ZN11BattleScene20pushAbilityMenuSceneEP10PlayerUnit', 'void', ['pointer', 'pointer']);
      var fMgr = nf('_ZN11BattleState16getBattleManagerEv', 'pointer', ['pointer']);
      var fTap = nf('_ZN20MissionBattleManager18reserveTouchActionEP10PlayerUnitb', 'void', ['pointer', 'pointer', 'int']);
      var fTouch = nf('_ZN10PlayerUnit5touchEi', 'void', ['pointer', 'int']);
      var tapState = {}, epoch = 0, waitAct = false, tapDoneAt = 0, lastPKey = '', pkeyAt = 0;
      // The game's own autosave (SuspendManager::suspendWaveMission) runs on the render thread each time the actions start. Two game crashes
      // (rjfc at a stage change, Kuruchi 2026-11-26 inside this save) happened there while Gambit was active. We cannot prove it is us, so log
      // every autosave with the time since our last cast, and hold our own actions for 3 s around it.
      try {
        var sw = sym('_ZN14SuspendManager18suspendWaveMissionEv');
        // Three game crashes (0.16.5 on the tablet, two on 1.16.0 on the phone) have the same frames: this autosave -> SaveUtils::saveUserDefaults -> Java
        // SharedPreferences commit -> File.exists -> the exception the system builds when the file is missing -> ART's stack walk reads a bad frame and dies.
        // None of it is our code, and the first one happened before this hook existed. So while the battle bot / Gambit is switched on the game's wave
        // autosave is skipped (the only thing lost: resuming an interrupted battle). With the switch off the game's own autosave runs as before.
        if (sw) {
          var nsw = 0, nskip = 0, origSw = new NativeFunction(sw, 'void', ['pointer']);
          Interceptor.replace(sw, new NativeCallback(function (self) {
            pkeyAt = Date.now() - 1000;
            if (RT.bot) { if (++nskip <= 40) say('game autosave (suspendWaveMission) skipped while Gambit is on (' + (Date.now() - lastCastAt) + ' ms after our last cast)'); return; }
            if (++nsw <= 10) say('game autosave (suspendWaveMission) runs: Gambit is off');
            origSw(self);
          }, 'void', ['pointer']));
          log('Bot test: autosave guard installed');
        }
      } catch (esw) { log('Bot test: autosave watch failed: ' + esw); }
      ensureTick();
      tickFns.push(function () {
        if (!RT.bot || !bscene || Date.now() - bsceneAt > 3000) return;
        if (!castDone || Date.now() - menuAt < 900 || Date.now() - lastCastAt < 600) return;
        if (Date.now() - lastTry < 400) return;
        lastTry = Date.now();
        var st = fState();
        if (st.isNull()) return;
        var party = fParty(st);
        if (party.isNull()) return;
        // A new stage (wave) rebuilds the battle objects. Wait a few seconds after the party or manager pointer changes, so we never open a menu or touch a unit while the game swaps them.
        var mgrP = '?';
        try { mgrP = String(fMgr(st)); } catch (eg) { }
        try { mgrP += '/' + String(gF.enemy(st)); } catch (eg2) { }
        var pkey = String(party) + '/' + mgrP;
        if (pkey !== lastPKey) { lastPKey = pkey; pkeyAt = Date.now(); say('Bot open: battle objects changed (new stage), pausing 4 s'); }
        if (Date.now() - pkeyAt < 4000) return;
        var still = fStill(party) & 1;
        var arr = party.add(0x28).readPointer().add(0x30).readPointer();
        var num = arr.readS32(), data = arr.add(0x10).readPointer();
        var turn = 0;
        try { turn = fTurn(); } catch (e) { }
        var tkey = turn + '#' + epoch;   // a new stage or turn gets a new key even when the game's turn number repeats
        if (waitAct) {
          if (!still || Date.now() - tapDoneAt > 7000) waitAct = false;
          else return;
        }
        var pend = [];
        for (var i = 0; i < num && i < 8; i++) {
          var u = data.add(i * 8).readPointer();
          if (u.isNull()) continue;
          var en = fEn(u) & 1;
          if (nDiag < 12) { nDiag++; say('Bot open: unit ' + u + ' can take a command ' + en + ', finished ' + (fDone(u) & 1) + ', reserved command id ' + fRes(u)); }
          if (en && !tried[String(u) + ':' + tkey]) pend.push(u);
        }
        if (Date.now() - lastDiag > 6000 && (pend.length || nOpen < 3)) {
          lastDiag = Date.now();
          say('Bot open: units ' + num + ', waiting for a command ' + pend.length + ', still ' + still + ', turn ' + turn);
        }
        var mb = fMenuBtn ? (fMenuBtn(bscene, 1) & 1) : 1;
        if (!still || !mb) { if (Date.now() - lastSkip > 8000 && pend.length) { lastSkip = Date.now(); say('Bot open: waiting, not in the command phase (units still ' + still + ', menu buttons active ' + mb + ')'); } return; }
        for (var j = 0; j < pend.length; j++) {
          var key = String(pend[j]) + ':' + tkey;
          if (tried[key]) continue;
          tried[key] = 1; nOpen++;
          say('Bot open: opening the Ability menu for unit ' + pend[j] + ' (turn ' + turn + ')');
          // Our own call is not seen by the hook on pushAbilityMenuScene, so the menu state is set here, the same way the hook does it.
          seen = {};
          if (Date.now() - lastOpen > 60000) acted = {};
          lastOpen = Date.now();
          scene = bscene; bunit = pend[j]; menu = null; list = []; menuAt = Date.now(); castDone = false;
          fPush(bscene, pend[j]);
          return;
        }
        // every unit has its command: do what a tap on each portrait does, one unit at a time
        var ts = tapState[tkey] || (tapState[tkey] = { i: 0, list: null, at: 0 });
        if (ts.list === null) {
          ts.list = [];
          for (var q = 0; q < num && q < 8; q++) { var uq = data.add(q * 8).readPointer(); if (!uq.isNull() && tried[String(uq) + ':' + tkey]) ts.list.push(uq); }
          if (!ts.list.length) { ts.list = null; return; }
        }
        // touch every unit back to back in this one tick, so all the actions start together
        while (ts.i < ts.list.length) {
          var uu = ts.list[ts.i++];
          try {
            var rid = fRes(uu), st0 = uu.add(0x310).readS32(), fl0 = uu.add(0x1230).readU8();
            fTouch(uu, rid < 0 ? 0 : rid);
            say('Bot open: touch unit ' + uu + ' (' + ts.i + '/' + ts.list.length + ') command ' + rid + ' state ' + st0 + '->' + uu.add(0x310).readS32() + ' touched ' + fl0 + '->' + uu.add(0x1230).readU8());
          } catch (e) { say('Bot open: tap failed ' + e); }
        }
        epoch++; waitAct = true; tapDoneAt = Date.now();
        say('Bot open: all touched, waiting for the actions to run (next round ' + epoch + ')');
      });
      say('Bot auto-open installed (works only while Bot cast is On)');
      // ---- Battle probe v2 (0.19.17): live unit data once per turn (and every 15 s) so the Gambit conditions can be wired up ----
      var fEnemyParty = nf('_ZN11BattleState13getEnemyPartyEv', 'pointer', ['pointer']);
      var gt = {
        maxHp: nf('_ZN10BattleUnit13getTotalMaxHpEv', 'int', ['pointer']), maxMp: nf('_ZN10BattleUnit13getTotalMaxMpEv', 'int', ['pointer']),
        lvl: nf('_ZN10BattleUnit12getUnitLevelEv', 'int', ['pointer']), mtribe: nf('_ZN11MonsterUnit8getTribeEv', 'int', ['pointer']),
        dead: nf('_ZN10BattleUnit11isDeadStateEv', 'int', ['pointer']), lbCost: nf('_ZN10BattleUnit17getLimitBurstCostEv', 'int', ['pointer']),
        isPlayer: nf('_ZN10BattleUnit12isPlayerUnitEv', 'int', ['pointer'])
      };
      var gi = {
        elemRes: nf('_ZN10BattleUnit21getTotalElementResistEi', 'int', ['pointer', 'int']),
        statRes: nf('_ZN10BattleUnit20getTotalStatusResistEi', 'int', ['pointer', 'int']), bad: nf('_ZN10BattleUnit10isBadStateEi', 'int', ['pointer', 'int'])
      };
      var fBuffs = nf('_ZN10BattleUnit15getUnitBuffListEv', 'pointer', ['pointer']);
      var fExist = nf('_ZN18BattleUnitBuffList11existByTypeEiP10BattleUnit', 'int', ['pointer', 'int', 'pointer']);
      // current HP and MP are hidden inside the game's encrypted values; catch them when the game sets them
      var nowHp = {}, nowMp = {}, nSet = 0;
      var aSetHp = sym('_ZN10BattleUnit5setHpEx'), aSetMp = sym('_ZN10BattleUnit5setMpEx');
      if (aSetHp) Interceptor.attach(aSetHp, { onEnter: function (a) { try { nowHp[a[0].toString()] = a[1].toInt32(); if (++nSet <= 6) plog('setHp ' + a[0] + ' ' + a[1].toInt32()); } catch (e) { } } });
      if (aSetMp) Interceptor.attach(aSetMp, { onEnter: function (a) { try { nowMp[a[0].toString()] = a[1].toInt32(); } catch (e) { } } });
      var fSumG = nf('_ZN20MissionBattleManager18getSummonGagePointEv', 'int', ['pointer']);
      var fRestSum = nf('_ZN11BattleUtils18getRestSummonPointEP10BattleUnit', 'int', ['pointer']);
      var aSetSum = sym('_ZN17BasePartyInfoList13setSummonGageEi');
      var nSum = 0;
      if (aSetSum) Interceptor.attach(aSetSum, { onEnter: function (a) { if (++nSum <= 40) plog('setSummonGage ' + a[1].toInt32()); } });
      // probe v3: watch damage / heal / KO calls to learn current HP; log reserve damage
      var dmg = {}, nDm = 0;
      [['_ZN10BattleUnit14setTotalDamageEx', 'setTotalDamage'], ['_ZN10BattleUnit16incReserveDamageEx', 'incReserve'], ['_ZN10BattleUnit16decReserveDamageEx', 'decReserve'], ['_ZN10BattleUnit19incReserveHpRecoverEx', 'incResvRecover'], ['_ZN10BattleUnit13onHpZeroUnderEP10DamageData', 'onHpZeroUnder'], ['_ZN10PlayerUnit12updateHpGageEb', 'updHpGage'], ['_ZN10PlayerUnit12updateMpGageEb', 'updMpGage']].forEach(function (h) {
        var ad = sym(h[0]); if (!ad) { plog('no symbol ' + h[1]); return; }
        Interceptor.attach(ad, { onEnter: function (x) { try { var k = String(x[0]); if (h[1] === 'setTotalDamage') dmg[k] = x[1].toString(); if (++nDm <= 400) plog('call ' + h[1] + ' unit ' + k + ' arg ' + x[1].toString()); } catch (e) { } } });
      });
      var fResv = nf('_ZN10BattleUnit16getReserveDamageEv', 'int', ['pointer']);
      var pr = { n: 0, last: 0, turn: -1, raws: 0, resLogged: {} };
      function plog(t) { plogs.push(new Date().toISOString().substr(11, 8) + ' ' + t); if (plogs.length > 1500) plogs.shift(); }
      function tryc(f, u, a) { try { return f ? (a === undefined ? f(u) : f(u, a)) : 'n/a'; } catch (e) { return 'err'; } }
      function listUnits(party) {
        var res = [];
        if (!party || party.isNull()) return res;
        var arr = party.add(0x28).readPointer().add(0x30).readPointer();
        var num = arr.readS32(), data = arr.add(0x10).readPointer();
        for (var i = 0; i < num && i < 12; i++) { var u = data.add(i * 8).readPointer(); if (!u.isNull()) res.push(u); }
        return res;
      }
      // xor-pair decode: the game's encrypted ints are two neighbouring ints (value ^ key, key); list decoded values that look like HP, MP or gauges
      function pairDump(u, tag) {
        var o = [];
        for (var off = 0; off < 0x1400; off += 4) {
          try { var x = u.add(off).readS32(), y = u.add(off + 4).readS32(), v = x ^ y; if (v > 0 && v < 1000000 && x !== v && y !== v) o.push(off.toString(16) + ':' + v); } catch (e) { break; }
        }
        var o8 = [];
        for (var off8 = 0; off8 < 0x1400; off8 += 8) {
          try { var q = u.add(off8).readU64(), w = u.add(off8 + 8).readU64(), v8 = q.xor(w); if (v8.compare(0) > 0 && v8.compare(1000000) < 0 && !q.equals(v8) && !w.equals(v8)) o8.push(off8.toString(16) + ':' + v8); } catch (e) { break; }
        }
        for (var j = 0; j < o8.length; j += 30) plog('pairs64 ' + tag + ' ' + o8.slice(j, j + 30).join(' '));
        for (var i = 0; i < o.length; i += 30) plog('pairs ' + tag + ' ' + o.slice(i, i + 30).join(' '));
      }
      // probe v4 (from the game source release: the survival-state packer shows the real getters)
      // unit vtable +0xf28 = current HP, +0xf30 = current MP (cipher_value<long long>, returned in memory), +0xf40 = limit gauge (cipher int); party vtable +0x130 = summon gauge
      var T3 = ['pointer', 'pointer', 'pointer'];
      var aCipStr = sym('_ZN11CommonUtils8toStringIN4alim12cipher_valueIxNS1_10xor_cipherEEEEENSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERKT_');
      var fCipStr = aCipStr ? new NativeFunction(aCipStr, T3, ['pointer']) : null;
      function strOf(r) {
        var b = Memory.alloc(24);
        b.writePointer(r[0]); b.add(8).writePointer(r[1]); b.add(16).writePointer(r[2]);
        var f = b.readU8();
        return (f & 1) ? b.add(16).readPointer().readUtf8String(b.add(8).readU64().toNumber()) : b.add(1).readUtf8String(f >> 1);
      }
      function vcall(u, off) { return new NativeFunction(u.readPointer().add(off).readPointer(), T3, ['pointer'])(u); }
      function cipLL(u, off) {
        var r = vcall(u, off), b = Memory.alloc(24);
        b.writePointer(r[0]); b.add(8).writePointer(r[1]); b.add(16).writePointer(r[2]);
        var txt = fCipStr ? strOf(fCipStr(b)) : '?';
        return txt;
      }
      function cipInt(u, off) {
        var r = vcall(u, off), b = Memory.alloc(24);
        b.writePointer(r[0]); b.add(8).writePointer(r[1]);
        var a = b.readS32(), c = b.add(4).readS32(), d = b.add(8).readS32();
        return (d - c) ^ a;
      }
      var fBuffStr = new NativeFunction(sym('_ZN10BattleUnit17getSuspendBuffStrEv') || NULL, T3, ['pointer']);
      function sample(u, side, idx) {
        var key = String(u), s = side + idx + ' ' + u + ' hp ' + (nowHp[key] === undefined ? '?' : nowHp[key]) + '/' + tryc(gt.maxHp, u) + ' mp ' + (nowMp[key] === undefined ? '?' : nowMp[key]) + '/' + tryc(gt.maxMp, u)
          + ' lvl ' + tryc(gt.lvl, u) + ' dead ' + tryc(gt.dead, u) + ' resvDmg ' + tryc(fResv, u) + ' totDmg ' + (dmg[key] === undefined ? '?' : dmg[key]);
        if (side === 'E') s += ' tribe ' + tryc(gt.mtribe, u);
        var live = ' | LIVE hp ' + (function () { try { return cipLL(u, 0xf28); } catch (e) { return 'err ' + e; } })() + ' mp ' + (function () { try { return cipLL(u, 0xf30); } catch (e) { return 'err'; } })() + ' lb ' + (function () { try { return cipInt(u, 0xf40); } catch (e) { return 'err'; } })() + ' buffStr ' + (function () { try { return strOf(fBuffStr(u)); } catch (e) { return 'err ' + e; } })();
        plog(s + live);
        var bf = [], bd = [];
        try { var bl = fBuffs ? fBuffs(u) : null; if (bl && !bl.isNull() && fExist) for (var t = 1; t <= 300; t++) { var r = fExist(bl, t, u); if (r & 1) bf.push(t); } } catch (e) { bf.push('err'); }
        for (var k = 1; k <= 40; k++) { var b = tryc(gi.bad, u, k); if (b === 1 || b === true) bd.push(k); }
        plog(s + ' | buffTypes ' + bf.join(',') + ' | bad ' + bd.join(','));
        if (!pr.resLogged[key]) {
          pr.resLogged[key] = 1;
          var el = [], sr = [];
          for (var i = 1; i <= 8; i++) el.push(tryc(gi.elemRes, u, i));
          for (var j = 1; j <= 12; j++) sr.push(tryc(gi.statRes, u, j));
          plog(side + idx + ' resist elem ' + el.join(',') + ' | status ' + sr.join(','));
        }
      }
      tickFns.push(function () {
        if (!OPT.probe) return;   // research probe: reads every unit through the game's getters each turn, also while a wave changes. Off (it was running in every battle and may have crashed the game at wave changes)
        if (!bscene || Date.now() - bsceneAt > 3000 || Date.now() - pr.last < 1200) return;
        var turn = 0; try { turn = fTurn(); } catch (e) { }
        if (turn === pr.turn && Date.now() - pr.last < 15000) return;
        if (pr.n >= 200) return;
        pr.last = Date.now(); pr.turn = turn;
        var st = fState();
        if (st.isNull()) return;
        var pl = listUnits(fParty(st)), en = fEnemyParty ? listUnits(fEnemyParty(st)) : [];
        pr.n++;
        plog('--- sample ' + pr.n + ' turn ' + turn + ' players ' + pl.length + ' enemies ' + en.length);
        var mg = null; try { mg = fMgr(st); } catch (e) { }
        plog('summon gauge LIVE party+0x130: ' + (function () { try { return new NativeFunction(fParty(st).readPointer().add(0x130).readPointer(), 'int', ['pointer'])(fParty(st)); } catch (e) { return 'err ' + e; } })()); plog('summon gauge: manager ' + (mg ? tryc(fSumG, mg) : 'n/a') + ' | rest point of P0 ' + (pl.length ? tryc(fRestSum, pl[0]) : 'n/a'));
        for (var a = 0; a < pl.length; a++) sample(pl[a], 'P', a);
        for (var b = 0; b < en.length; b++) sample(en[b], 'E', b);
        if (pr.raws < 4 && (pr.n === 1 || pr.n === 3 || pr.n === 6 || pr.n === 9)) {
          pr.raws++;
          if (pl.length) pairDump(pl[0], 'P0#' + pr.n);
          if (en.length) pairDump(en[0], 'E0#' + pr.n);
        }
      });
      plog('probe v4 installed');
    }
    cmd.bot = function (t) { RT.bot = (t.split(' ')[1] === '1'); if (RT.bot && RT.auto && cmd.auto) cmd.auto('auto 0'); say('Gambit switched ' + (RT.bot ? 'ON' : 'OFF') + ' from the floating circle'); return 'ok'; };
    log('Battle bot test installed (starts off; switch it on in the floating circle)');
    dictHooks(say);
  }

  // Vortex (Gate) menu: show the game modes (areas) that are open first and the locked ones below. The Gate screen gets its list
  // through GateScene::setAreaList(__Array*). Before the screen builds its rows we reorder that array in place (stable: open ones
  // keep their order, locked ones keep theirs). Only done when every element is an AreaMst (checked by its class name); otherwise
  // the list is left alone. Lines 'Vortex:' in the log say what happened.
  function vortexHooks() {
    var setA = sym('_ZN9GateScene11setAreaListEPN7cocos2d7__ArrayE');
    var isOpen = nf('_ZN8GateUtil10isOpenAreaEP7AreaMst', 'int', ['pointer']);
    if (!setA || !isOpen) { log('Vortex: symbols missing, not installed'); return; }
    var nLog = 0, nCall = 0;
    function cn(p) { try { return p.readPointer().sub(8).readPointer().add(8).readPointer().readCString(); } catch (e) { return '?'; } }
    Interceptor.attach(setA, { onEnter: function (a) {
      try {
        var arr = a[1];
        if (arr.isNull()) return;
        var ca = arr.add(0x30).readPointer();
        if (ca.isNull()) return;
        var num = ca.readS32(), data = ca.add(0x10).readPointer();
        if (++nCall <= 6) log('Vortex: setAreaList called with ' + num + ' entries');
        if (num < 2 || num > 200 || data.isNull()) return;
        var items = [], i;
        for (i = 0; i < num; i++) items.push(data.add(i * 8).readPointer());
        for (i = 0; i < num; i++) {
          if (items[i].isNull() || cn(items[i]) !== '7AreaMst') { if (++nLog <= 5) log('Vortex: list has ' + (items[i].isNull() ? 'a null' : cn(items[i])) + ' at ' + i + ', left as it is'); return; }
        }
        var open = [], locked = [];
        for (i = 0; i < num; i++) ((isOpen(items[i]) & 1) ? open : locked).push(items[i]);
        if (!open.length || !locked.length) { if (++nLog <= 5) log('Vortex: ' + open.length + ' open, ' + locked.length + ' locked: nothing to reorder'); return; }
        var all = open.concat(locked);
        for (i = 0; i < num; i++) data.add(i * 8).writePointer(all[i]);
        if (++nLog <= 8) log('Vortex: reordered ' + num + ' modes, ' + open.length + ' open first, ' + locked.length + ' locked below');
      } catch (e) { log('Vortex: reorder failed, list left alone: ' + e); }
    } });
    // Chamber of the Fallen and the other dungeon lists (GateDungeonScene): the scene builds its own list of DungeonMst in
    // setDungeonMstList(). The member that holds it is unknown, so after the call we look through the scene object for a vector of
    // DungeonMst pointers (begin, end pairs), log what we find, and put the open ones first (stable).
    var setD = sym('_ZN16GateDungeonScene17setDungeonMstListEv');
    var setL = sym('_ZN16GateDungeonScene13setListLayoutEv');
    var isOpenD = nf('_ZN8GateUtil13isOpenDungeonEP10DungeonMst', 'int', ['pointer']);
    var areaListD = nf('_ZN11AreaMstList6sharedEv', 'pointer', []);
    var areaByIdD = nf('_ZN11AreaMstList17getObjectByAreaIdEi', 'pointer', ['pointer', 'int']);
    var condShD = nf('_ZN20SpDungeonCondMstList6sharedEv', 'pointer', []);
    var condGetD = nf('_ZN20SpDungeonCondMstList19getMstWithDungeonIdEii', 'pointer', ['pointer', 'int', 'int']);
    var infoShD = nf('_ZN21UserSpDungeonInfoList6sharedEv', 'pointer', []);
    var infoGetD = nf('_ZN21UserSpDungeonInfoList9getObjectEii', 'pointer', ['pointer', 'int', 'int']);
    var relRestD = nf('_ZN17UserSpDungeonInfo18getReleaseRestTimeEv', 'int', ['pointer']);
    var misD = nf('_ZN8GateUtil23getInSessionMissionListEib', 'pointer', ['int', 'int']);
    var dJLog = 0;
    // The Chamber list draws its lock from the special-dungeon release timer, not from isOpenDungeon (setListLayout never calls it).
    // Same rule as setListLayout: cond type 4 or no cond = normal (open); type 0 = timer of the dungeon itself; type 2 = timer of the first id in cond+0x80;
    // any other type = locked with a countdown. Locked = release time still above zero.
    function strAt(p) { var b0 = p.readU8(); return (b0 & 1) ? p.add(0x10).readPointer().readUtf8String() : p.add(1).readUtf8String(); }
    function judgeD(el) {
      var id = el.add(0x24).readS32(), why = '', res = 1;
      try {
        if (condShD && condGetD && infoShD && infoGetD && relRestD) {
          var cond = condGetD(condShD(), id, 0);
          if (cond.isNull() || cond.add(0x78).readS32() === 4) {
            // No special cond: setListLayout (0x219e338) shows the chain/lock when the dungeon has no mission in session right now.
            why = 'no cond';
            if (misD) {
              var ml = misD(id, 0);
              var mc = ml.isNull() ? 0 : ml.add(0x30).readPointer().readS32();
              why += ' missions ' + mc;
              if (mc === 0 && id !== 89901) res = 0;
            }
          }
          else {
            var ty = cond.add(0x78).readS32();
            why = 'type ' + ty;
            if (ty === 4) { }
            else if (ty === 0 || ty === 2) {
              var key = id;
              if (ty === 2) key = parseInt(strAt(cond.add(0x80)), 10) || 0;
              var inf = infoGetD(infoShD(), ty, key);
              if (inf.isNull()) { why += ' no info'; }
              else { var rr = relRestD(inf); why += ' rest ' + rr; if (rr > 0) res = 0; }
            } else { res = 0; }
          }
        } else {
          var ar = (areaByIdD && areaListD) ? areaByIdD(areaListD(), el.add(0x34).readS32()) : el;
          res = (!ar.isNull() && (isOpenD(el) & 1)) ? 1 : 0; why = 'area';
        }
      } catch (xj) { res = 1; why = 'error ' + xj; }
      if (dJLog < 140) { dJLog++; log('Vortex: dungeon ' + id + ' ' + why + ' -> ' + (res ? 'open' : 'locked')); }
      return res;
    }
    if ((setD || setL) && isOpenD) {
      var dLog = 0;
      function fixDungeons(sc, where) {
        try {
          var found = 0, off;
          if (dLog++ < 6) log('Vortex: dungeon scene ' + where + ' called');
          for (off = 0x28; off < 0x900 && !found; off += 8) {
            var b, e;
            try { b = sc.add(off).readPointer(); e = sc.add(off + 8).readPointer(); } catch (x) { continue; }
            if (b.isNull() || e.isNull() || e.compare(b) <= 0) continue;
            // a small number (like 0x4) sitting before a pointer looks like a vector start; its read then crashed the whole scan (log: access violation accessing 0x4)
            if (b.compare(ptr('0x100000')) < 0 || e.compare(ptr('0x100000')) < 0) continue;
            var dist = e.sub(b);
            if (dist.compare(ptr('0x640')) > 0) continue;
            var bytes = dist.toInt32();
            if (bytes < 16 || bytes > 1600 || bytes % 8) continue;
            var n = bytes / 8, els = [], ok = true, i;
            try {
              for (i = 0; i < n && ok; i++) { var q = b.add(i * 8).readPointer(); if (q.isNull() || cn(q) !== '10DungeonMst') ok = false; else els.push(q); }
            } catch (xr) { ok = false; }
            if (!ok) continue;
            // isOpenDungeon crashed (access violation at 0x4) for dungeons whose area is not in the area list: it hands a null AreaMst to
            // isOpenArea. So look the area up ourselves first (area id at +0x34); no area counts as locked. Any error counts as locked too.
            var op = [], lk = [], nErr = 0;
            for (i = 0; i < n; i++) {
              var openD = 0;
              try { openD = judgeD(els[i]); } catch (x2) { nErr++; openD = 1; }
              (openD ? op : lk).push(els[i]);
            }
            if (nErr && dLog <= 8) log('Vortex: ' + nErr + ' dungeons could not be judged, counted as locked');
            log('Vortex: dungeon list at +0x' + off.toString(16) + ' (' + where + '): ' + n + ' dungeons, ' + op.length + ' open, ' + lk.length + ' locked');
            if (op.length && lk.length) {
              var all2 = op.concat(lk);
              for (i = 0; i < n; i++) b.add(i * 8).writePointer(all2[i]);
              log('Vortex: dungeon list reordered, open first');
            }
            found = 1;
          }
          // The list may instead be a cocos2d::__Array member (the log shows several on the scene): same reorder through its ccArray.
          for (off = 0x28; off < 0x900 && !found; off += 8) {
            try {
              var pa = sc.add(off).readPointer();
              if (pa.compare(ptr('0x100000')) < 0 || cn(pa) !== 'N7cocos2d7__ArrayE') continue;
              var ca = pa.add(0x30).readPointer();
              if (ca.compare(ptr('0x100000')) < 0) continue;
              var cnum = ca.readS32(), cdat = ca.add(0x10).readPointer();
              if (cnum < 2 || cnum > 200 || cdat.compare(ptr('0x100000')) < 0) continue;
              var cels = [], cok = true, ci;
              for (ci = 0; ci < cnum && cok; ci++) { var cq = cdat.add(ci * 8).readPointer(); if (cq.isNull() || cn(cq) !== '10DungeonMst') cok = false; else cels.push(cq); }
              if (!cok) continue;
              var cop = [], clk = [];
              for (ci = 0; ci < cnum; ci++) {
                var co = 1;
                try { co = judgeD(cels[ci]); } catch (xc) { co = 1; }
                (co ? cop : clk).push(cels[ci]);
              }
              log('Vortex: dungeon array at +0x' + off.toString(16) + ' (' + where + '): ' + cnum + ' dungeons, ' + cop.length + ' open, ' + clk.length + ' locked');
              if (cop.length && clk.length) {
                var call = cop.concat(clk);
                for (ci = 0; ci < cnum; ci++) cdat.add(ci * 8).writePointer(call[ci]);
                log('Vortex: dungeon array reordered, open first');
              }
              found = 1;
            } catch (xa) { }
          }
          if (!found && dLog <= 8) {
            log('Vortex: no DungeonMst list found in the dungeon scene (' + where + ')');
            var shown = 0;
            for (off = 0x28; off < 0x900 && shown < 16; off += 8) {
              try {
                var pp = sc.add(off).readPointer();
                if (pp.isNull() || pp.compare(ptr('0x10000')) < 0) continue;
                var cname = cn(pp);
                if (cname !== '?' && /Dungeon|Array|Mst|Vector|vector/.test(cname)) { shown++; log('Vortex: scene +0x' + off.toString(16) + ' -> ' + cname); }
              } catch (x) { }
            }
          }
        } catch (e) { log('Vortex: dungeon reorder failed: ' + e); }
      }
      if (setD) Interceptor.attach(setD, { onEnter: function (a) { this.sc = a[0]; }, onLeave: function () { fixDungeons(this.sc, 'after setDungeonMstList'); } });
      if (setL) Interceptor.attach(setL, { onEnter: function (a) { fixDungeons(a[0], 'before setListLayout'); } });
      log('Vortex: dungeon list hook installed');
    } else log('Vortex: dungeon symbols missing');
    log('Vortex: unlocked-first hook installed');
  }

  // Bestiary test: what the server sends for the Monster Guide and how the game looks entries up.
  function dictHooks(say) {
    var rp = sym('_ZN29UserMonsterDictionaryResponse9readParamEiiPKcS1_b');
    var gd = sym('_ZN24MonsterDictionaryMstList25getObjectWithDictionaryIdEi');
    var shA = sym('_ZN21UserMonsterDictionary6sharedEv');
    var sh = shA ? new NativeFunction(shA, 'pointer', []) : null;
    var gi = sym('_ZN17MonsterDictionary12getItemIndexEi');
    function rstr(p) {
      var b = p.readU8();
      if (b & 1) return { n: p.add(8).readU64().toNumber(), t: p.add(16).readPointer().readUtf8String(200) };
      return { n: b >> 1, t: p.add(1).readUtf8String(Math.min(b >> 1, 200)) };
    }
    var rcnt = 0, gcnt = 0, seenId = {};
    if (rp) Interceptor.attach(rp, { onEnter: function (a) {
      try {
        var k = a[3].readUtf8String(), v = a[4].readUtf8String();
        if (++rcnt <= 20) say('Dict reply ' + k + ': len ' + v.length + ' head ' + v.substr(0, 60));
      } catch (e) { say('Dict reply error ' + e); }
    } });
    if (gd) Interceptor.attach(gd, {
      onEnter: function (a) { this.id = a[1].toInt32(); },
      onLeave: function (r) {
        try {
          var id = this.id;
          if (seenId[id] || ++gcnt > 40) return;
          seenId[id] = 1;
          var o = r, t = 'none';
          if (!o.isNull()) t = 'obj ints=' + [0x40, 0x44, 0x48, 0x4c].map(function (d) { return o.add(d).readS32(); }).join(',');
          var st = '';
          try {
            var m = sh().add(8).readPointer();
            var rr = rstr(m.add(0x28));
            st = ' | stored bitmap len ' + rr.n + ' head ' + rr.t.substr(0, 40);
          } catch (e) { st = ' | stored read err ' + e; }
          say('Dict lookup id ' + id + ' -> ' + t + st);
        } catch (e) { say('Dict lookup error ' + e); }
      }
    });
    say('Dict test installed: open the Monster Guide, then send the log');
  }

  // Monster Guide: the game keeps the Guide as a bitmap (hex text) it receives from the server. The server cannot build the right
  // one, so when the reply comes in we swap in our own. The save's list monster_dictionary (full monster ids, set in the editor's
  // Bestiary tab) decides which bits are set, using the game's own number for each monster. No list in the save: every monster.
  // Save slots (game 0.1.19+): active_save.json names the slot; the first slot stays campaign.json, others are saves/<id>.json.
  function slotFile(dir) {
    try {
      var libc = Process.getModuleByName('libc.so');
      var fo = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fr = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fc = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var st = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
      var f = fo(Memory.allocUtf8String(dir + 'active_save.json'), Memory.allocUtf8String('r'));
      if (f.isNull()) return null;
      var m = Memory.alloc(512), n = fr(m, 1, 500, f); fc(f);
      var id = n > 0 ? (/"active"\s*:\s*"([^"]*)"/.exec(m.readUtf8String(n)) || [])[1] : null;
      if (!id || !/^(campaign|save-[0-9]+)$/.test(id)) return null;
      var path = id === 'campaign' ? dir + 'campaign.json' : dir + 'saves/' + id + '.json';
      return st(Memory.allocUtf8String(path), Memory.alloc(256)) === 0 ? path : null;
    } catch (e) { return null; }
  }
  var guideKeep = [], guideMap = null;
  function guideSaveIds() {
    var libc = Process.getModuleByName('libc.so');
    var fopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
    var fread = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
    var fclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
    var stat = new NativeFunction(libc.getExportByName('stat'), 'int', ['pointer', 'pointer']);
    var sb = Memory.alloc(256);
    var base = '/data/data/com.exvius.offline.forever/files/exvius/state/';
    var path = null;
    var names = ['campaign.json', 'state.json'];
    path = slotFile(base);
    for (var i = 0; i < names.length && !path; i++) if (stat(Memory.allocUtf8String(base + names[i]), sb) === 0) path = base + names[i];
    if (!path) return null;
    var n = sb.add(48).readS64().valueOf();
    if (n <= 0 || n > 80 * 1048576) return null;
    var f = fopen(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
    if (f.isNull()) return null;
    var buf = Memory.alloc(n), got = 0;
    while (got < n) { var r = fread(buf.add(got), 1, Math.min(1048576, n - got), f); if (r <= 0) break; got += r; }
    fclose(f);
    var txt = buf.readUtf8String(got);
    var k = txt.indexOf('"monster_dictionary"');
    if (k < 0) return null;
    var a = txt.indexOf('[', k), z = txt.indexOf(']', a);
    if (a < 0 || z < 0) return null;
    return JSON.parse(txt.substring(a, z + 1));
  }
  function guideHooks() {
    var rp = sym('_ZN29UserMonsterDictionaryResponse9readParamEiiPKcS1_b');
    var lst = sym('_ZN24MonsterDictionaryMstList6sharedEv');
    var gd = sym('_ZN24MonsterDictionaryMstList25getObjectWithDictionaryIdEi');
    var shA = sym('_ZN21UserMonsterDictionary6sharedEv');
    if (!rp) return;
    var shared = lst ? new NativeFunction(lst, 'pointer', []) : null;
    var getO = gd ? new NativeFunction(gd, 'pointer', ['pointer', 'int']) : null;
    var shU = shA ? new NativeFunction(shA, 'pointer', []) : null;
    var n = 0, pendIds = null, fixTries = 0, busy = false, guideTxt = null;
    // digits of the bitmap for the ids, using the game's own bit number for each monster; null when no id resolves yet
    function digitsFor(list, ids) {
      var digits = [], maxK = 0, hit = 0;
      for (var i = 0; i < ids.length; i++) {
        var o = getO(list, parseInt(ids[i], 10));
        if (o.isNull()) continue;
        var K = o.add(0x48).readS32();
        if (K < 0 || K > 20000) continue;
        digits[K >> 2] = (digits[K >> 2] || 0) | (1 << (3 - (K & 3)));
        if (K > maxK) maxK = K;
        hit++;
      }
      var len = Math.max(1200, (maxK >> 2) + 1), out = [];
      for (var d = 0; d < len; d++) out.push((digits[d] || 0).toString(16));
      return { txt: out.join(''), hit: hit };
    }
    // The game's list of monsters is not loaded yet when the server reply arrives. So the reply gets an empty bitmap, and when the
    // game first asks for a monster we write the right bits into the stored bitmap, in place.
    function patchStored(txt) {
      var m = shU().add(8).readPointer(), s = m.add(0x28);
      if (!(s.readU8() & 1)) throw 'stored bitmap is short';
      var len = s.add(8).readU64().valueOf(), data = s.add(16).readPointer();
      for (var i = 0; i < len; i++) data.add(i).writeU8(i < txt.length ? txt.charCodeAt(i) : 48);
      return len;
    }
    function build() {
      var ids = null;
      try { ids = guideSaveIds(); } catch (e) { log('Monster Guide: could not read the save: ' + e); }
      var txt;
      if (!ids || !shared || !getO) {
        txt = new Array(1201).join('f');
        log('Monster Guide: no list in the save, every monster is shown');
      } else {
        var r = null;
        try { r = digitsFor(shared(), ids); } catch (e) { log('Monster Guide: lookup failed: ' + e); }
        if (r && (r.hit > 0 || !ids.length)) {
          txt = r.txt;
          log('Monster Guide: ' + ids.length + ' monsters in the save list, ' + r.hit + ' found in the game');
        } else {
          txt = new Array(1201).join('0');
          pendIds = ids; fixTries = 0;
          log('Monster Guide: ' + ids.length + ' monsters in the save list, game list not ready; will fill in when the game asks');
        }
      }
      return Memory.allocUtf8String(txt);
    }
    if (gd && shU) Interceptor.attach(gd, { onEnter: function (a) {
      if (!pendIds || busy || fixTries > 30) return;
      busy = true; fixTries++;
      try {
        var r = digitsFor(a[0], pendIds);
        if (r.hit > 0) {
          var len = patchStored(r.txt);
          log('Monster Guide: ' + r.hit + ' of ' + pendIds.length + ' saved monsters written into the guide (len ' + len + ')');
          guideTxt = r.txt; guideKeep.push(guideMap = Memory.allocUtf8String(r.txt));
          pendIds = null;
        } else if (fixTries === 30) log('Monster Guide: none of the saved ids found in the game list: ' + pendIds.slice(0, 5).join(','));
      } catch (e) { log('Monster Guide fill error: ' + e); pendIds = null; }
      busy = false;
    } });
    Interceptor.attach(rp, { onEnter: function (a) {
      try {
        var k = a[3].readUtf8String();
        if (k === 'KJv6V7G9' || k === 'z41pMJvC') {
          if (!guideMap) { guideMap = build(); guideKeep.push(guideMap); }
          a[4] = guideMap;
          if (++n <= 4) log('Monster Guide: bitmap replaced (' + k + ')');
        }
      } catch (e) { log('Monster Guide error: ' + e); }
    } });
    log('Monster Guide installed');
  }

  function cloudStart() {
    var libc = Process.getModuleByName('libc.so');
    function fn(n, r, a) { return new NativeFunction(libc.getExportByName(n), r, a); }
    var fopen = fn('fopen', 'pointer', ['pointer', 'pointer']), fread = fn('fread', 'int', ['pointer', 'int', 'int', 'pointer']);
    var fwrite = fn('fwrite', 'int', ['pointer', 'int', 'int', 'pointer']), fclose = fn('fclose', 'int', ['pointer']);
    var stat = fn('stat', 'int', ['pointer', 'pointer']), rename = fn('rename', 'int', ['pointer', 'pointer']), unlink = fn('unlink', 'int', ['pointer']);
    var kill = fn('kill', 'int', ['int', 'int']), getpid = fn('getpid', 'int', []);
    var sb = Memory.alloc(256);
    var base = '/data/data/com.exvius.offline.forever/files/';
    function cs(x) { return Memory.allocUtf8String(x); }
    function size(path) { return stat(cs(path), sb) === 0 ? sb.add(48).readS64().valueOf() : -1; }
    function sig(path) { return stat(cs(path), sb) === 0 ? sb.add(48).readS64().valueOf() + ':' + sb.add(88).readS64().valueOf() : ''; }
    function rd(path) {
      var n = size(path);
      if (n < 0 || n > 80 * 1048576) return null;
      var f = fopen(cs(path), cs('r'));
      if (f.isNull()) return null;
      var buf = Memory.alloc(Math.max(n, 1)), got = 0;
      while (got < n) { var r = fread(buf.add(got), 1, Math.min(1048576, n - got), f); if (r <= 0) break; got += r; }
      fclose(f);
      return got === n ? new Uint8Array(buf.readByteArray(n)) : null;
    }
    function wr(path, u8) {
      var tmp = path + '.tmp';
      var f = fopen(cs(tmp), cs('w'));
      if (f.isNull()) return false;
      var buf = Memory.alloc(Math.max(u8.length, 1));
      buf.writeByteArray(u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.length));
      var w = u8.length ? fwrite(buf, 1, u8.length, f) : 0;
      fclose(f);
      if (w !== u8.length || size(tmp) !== u8.length) { unlink(cs(tmp)); return false; }
      return rename(cs(tmp), cs(path)) === 0;
    }
    function hash(u8) { var h = 0x811c9dc5; for (var i = 0; i < u8.length; i++) { h ^= u8[i]; h = Math.imul(h, 16777619); } return (h >>> 0).toString(16) + '_' + u8.length; }
    function sane(u8) {
      var a = 0, b = u8.length - 1;
      while (a < u8.length && u8[a] <= 32) a++;
      while (b > 0 && u8[b] <= 32) b--;
      return u8.length > 100 && u8[a] === 0x7b && u8[b] === 0x7d;
    }
    var A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function b64(u) {
      var out = [], i = 0;
      for (; i + 2 < u.length; i += 3) {
        var n = (u[i] << 16) | (u[i + 1] << 8) | u[i + 2];
        out.push(A[n >> 18] + A[(n >> 12) & 63] + A[(n >> 6) & 63] + A[n & 63]);
      }
      if (i + 1 === u.length) { var m = u[i] << 16; out.push(A[m >> 18] + A[(m >> 12) & 63] + '=='); }
      else if (i + 2 === u.length) { var k = (u[i] << 16) | (u[i + 1] << 8); out.push(A[k >> 18] + A[(k >> 12) & 63] + A[(k >> 6) & 63] + '='); }
      return out.join('');
    }
    var R = {}; for (var q = 0; q < 64; q++) R[A[q]] = q;
    function unb64(s, dst, off) {
      var n = s.length, o = off;
      for (var i = 0; i + 3 < n; i += 4) {
        var a = R[s[i]], b = R[s[i + 1]], c = s[i + 2] === '=' ? 0 : R[s[i + 2]], d = s[i + 3] === '=' ? 0 : R[s[i + 3]];
        var v = (a << 18) | (b << 12) | (c << 6) | d;
        dst[o++] = (v >> 16) & 255;
        if (s[i + 2] !== '=') dst[o++] = (v >> 8) & 255;
        if (s[i + 3] !== '=') dst[o++] = v & 255;
      }
      return o;
    }
    var slotOv = null;   // set by the editor while it opens or applies a save slot that is not the active one; cleared with "-"
    function slotPathOf(id) { var d = base + 'exvius/state/'; return id === 'campaign' ? d + 'campaign.json' : d + 'saves/' + id + '.json'; }
    cmd.slotpick = function (t) {
      var id = String(t || '').trim();
      if (id === '-' || id === '') { slotOv = null; return 'ok'; }
      if (!/^(campaign|save-[0-9]+)$/.test(id) || size(slotPathOf(id)) < 0) return 'no such slot';
      slotOv = slotPathOf(id); return 'ok';
    };
    // Creates a NEW save slot from the staged file (sput first). Never replaces an existing slot, never switches to it.
    cmd.slotnew = function (t) {
      var p = t.split(' '), id = p[0];
      if (!/^save-[0-9]+$/.test(id)) return 'bad slot id';
      if (!stage || stageLen !== stage.length || hash(stage) !== p[1] || !sane(stage)) return 'bad staged file';
      var dst = slotPathOf(id);
      if (size(dst) >= 0) return 'exists';
      var libc = Process.getModuleByName('libc.so');
      var mk = new NativeFunction(libc.getExportByName('mkdir'), 'int', ['pointer', 'int']);
      mk(cs(base + 'exvius/state/saves'), 493);
      if (!wr(dst, stage)) return 'could not write';
      log('Save slot created from the cloud: ' + id);
      return 'ok';
    };
    cmd.slotact = function () {
      var sf = slotFile(base + 'exvius/state/');
      var m = sf ? /(campaign|save-[0-9]+)\.json$/.exec(sf) : null;
      return 'A ' + (m ? m[1] : 'campaign');
    };
    // Save slots (game 0.1.19+): "S <active>;<id>|<mtime>|<bytes>|<name>;..." (ASCII only).
    cmd.slots = function () {
      var d = base + 'exvius/state/', act = 'campaign';
      var sf = slotFile(d);
      var ids = ['campaign'];
      var libc = Process.getModuleByName('libc.so');
      try {   // slot files are saves/save-<number>.json (the number is a time stamp)
        var od = new NativeFunction(libc.getExportByName('opendir'), 'pointer', ['pointer']);
        var rdd = new NativeFunction(libc.getExportByName('readdir'), 'pointer', ['pointer']);
        var cd = new NativeFunction(libc.getExportByName('closedir'), 'int', ['pointer']);
        var dh = od(Memory.allocUtf8String(d + 'saves'));
        if (!dh.isNull()) {
          for (var q = 0; q < 200; q++) {
            var de = rdd(dh); if (de.isNull()) break;
            var nm = de.add(19).readUtf8String(), mm = /^(save-[0-9]+)\.json$/.exec(nm);
            if (mm && ids.length < 80) ids.push(mm[1]);
          }
          cd(dh);
        }
      } catch (e) { }
      var fo = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
      var fr = new NativeFunction(libc.getExportByName('fread'), 'int', ['pointer', 'int', 'int', 'pointer']);
      var fs = new NativeFunction(libc.getExportByName('fseek'), 'int', ['pointer', 'long', 'int']);
      var fc = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
      var out = [], CH = 200000, buf = Memory.alloc(CH + 8);
      function lab(path, n) {
        var f = fo(Memory.allocUtf8String(path), Memory.allocUtf8String('r'));
        if (f.isNull()) return '';
        var r = '', re = /"save_label"\s*:\s*"((?:[^"\\]|\\.)*)"/, re2 = /"handle_name"\s*:\s*"((?:[^"\\]|\\.)*)"/;
        try {
          var c = fr(buf, 1, CH, f), h1 = c > 0 ? buf.readUtf8String(c) : '', h2 = '';
          var m = re.exec(h1), m2 = re2.exec(h1);
          if (n > CH && (!m || !m2)) { fs(f, n - CH, 0); c = fr(buf, 1, CH, f); h2 = c > 0 ? buf.readUtf8String(c) : ''; m = m || re.exec(h2); m2 = m2 || re2.exec(h2); }
          r = m && m[1] ? m[1] : (m2 ? m2[1] : '');
        } catch (e) { }
        fc(f);
        return r.replace(/[^\x20-\x7e]/g, '?').replace(/[|;]/g, ' ').substr(0, 40);
      }
      for (var i = 0; i < ids.length; i++) {
        var pth = slotPathOf(ids[i]);
        if (stat(cs(pth), sb) !== 0) continue;
        var n = sb.add(48).readS64().valueOf(), mt = sb.add(88).readS64().valueOf();
        out.push(ids[i] + '|' + mt + '|' + n + '|' + lab(pth, n));
        if (sf === pth) act = ids[i];
      }
      return 'S ' + act + ';' + out.join(';');
    };
    function localPath() { if (slotOv && size(slotOv) >= 0) return slotOv; var sf = slotFile(base + 'exvius/state/'); if (sf) return sf; var a = base + 'exvius/state/campaign.json'; return size(a) >= 0 ? a : base + 'exvius/state/state.json'; }
    var cache = { sig: '', u8: null }, stage = null, stageLen = 0;
    function local() {
      var lp = localPath(), g = sig(lp);
      if (cache.u8 && cache.sig === g) return cache.u8;
      var u = rd(lp);
      cache = { sig: g, u8: u };
      return u;
    }
    cmd.shash = function () {
      var u = local();
      if (!u) return 'none';
      if (!sane(u)) return 'busy';
      return 'H ' + hash(u);
    };
    // File export for the table dump: list the game's own folder, and read any file inside it in pieces.
    var opendir = fn('opendir', 'pointer', ['pointer']), readdir = fn('readdir', 'pointer', ['pointer']), closedir = fn('closedir', 'int', ['pointer']);
    var fseek = fn('fseek', 'int', ['pointer', 'long', 'int']);
    var ROOT = '/data/data/com.exvius.offline.forever';
    function walk(dir, depth, out) {
      var d = opendir(cs(dir));
      if (d.isNull()) return;
      var ents = [];
      for (;;) {
        var e = readdir(d);
        if (e.isNull()) break;
        var nm = e.add(19).readUtf8String(), ty = e.add(18).readU8();
        if (nm === '.' || nm === '..') continue;
        ents.push([nm, ty]);
      }
      closedir(d);
      for (var i = 0; i < ents.length; i++) {
        var path = dir + '/' + ents[i][0];
        if (ents[i][1] === 4) { if (depth < 7 && out.length < 8000) walk(path, depth + 1, out); }
        else if (out.length < 8000) out.push(path + '\t' + size(path));
      }
    }
    // Story and esper tables for the editor's Missions and Summon tabs: a small summary built from the game's own decoded tables.
    var TBL = ROOT + '/files/exvius/data/static/';
    function lines(path) {
      var n = size(path);
      if (n <= 0 || n > 80 * 1048576) return [];
      var f = fopen(cs(path), cs('r'));
      if (f.isNull()) return [];
      var buf = Memory.alloc(n), got = 0;
      while (got < n) { var r = fread(buf.add(got), 1, Math.min(1048576, n - got), f); if (r <= 0) break; got += r; }
      fclose(f);
      return buf.readUtf8String(got).split('\n');
    }
    function rows(name, text) {
      var out = [], ls = lines(TBL + (text ? 'text_decoded/' : 'mst_decoded/') + name + '.ndjson');
      for (var i = 0; i < ls.length; i++) {
        var l = ls[i];
        if (!l || l.charAt(0) !== '{') continue;
        try { out.push(JSON.parse(l)); } catch (e) { }
      }
      return out;
    }
    cmd.tbl = function () {
      var world = {}, wr = rows('F_WORLD_MST');
      for (var a = 0; a < wr.length; a++) world[wr[a]['9Pb24aSy']] = wr[a]['dw2nvP3r'] === '1' ? 1 : 0;
      var sw = {}, swr = rows('F_SWITCH_MST');
      for (var b = 0; b < swr.length; b++) sw[swr[b]['8vAYn3IX']] = [String(swr[b]['5opcUT9t']), parseInt(swr[b]['QLfe23bu'], 10)];
      var need = {}, m = [], mr = rows('F_MISSION_MST'), want = {};
      for (var c = 0; c < mr.length; c++) {
        var id = String(mr[c]['qo3PECw6']);
        if (id.length !== 7 || !/^11[123]/.test(id)) continue;
        var ids = String(mr[c]['dIPkNn61'] || '').split(','), pos = [];
        for (var d = 0; d < ids.length; d++) { var k = ids[d].trim(); if (k && k !== '0' && sw[k]) pos.push(sw[k]); }
        m.push([id, pos, world[mr[c]['9Pb24aSy']] || 0, String(mr[c]['1x4bG2J8'] || '')]);
        want[id] = 1;
      }
      var ch = {}, cr = rows('F_CHALLENGE_MST');
      for (var e = 0; e < cr.length; e++) {
        var mid = String(cr[e]['qo3PECw6']);
        if (!want[mid] || !cr[e]['Pzn5h0Ga'] || !cr[e]['V36ygRYs']) continue;
        (ch[mid] = ch[mid] || []).push([String(cr[e]['V36ygRYs']), String(cr[e]['dX6cor8j'] || '')]);
      }
      var names = {}, nr = rows('F_TEXT_BEAST_NAME', true);
      for (var f2 = 0; f2 < nr.length; f2++) {
        var key = String(nr[f2].key || ''), v = nr[f2].v;
        names[key.substring(key.lastIndexOf('_') + 1)] = Array.isArray(v) ? String(v[0]) : String(v);
      }
      var dbg = {};
      function tx(file, ok) {
        var o = {}, t = rows(file, true);
        if (t.length) dbg[file] = JSON.stringify(t[0]).substring(0, 200) + ' | ' + JSON.stringify(t[t.length >> 1]).substring(0, 200) + ' | n=' + t.length;
        else dbg[file] = 'no rows';
        for (var i = 0; i < t.length; i++) {
          var k = String(t[i].key || ''), mm = /(\d+)\D*$/.exec(k), id = mm ? mm[1] : '', v = t[i].v;
          if (!id || !ok(id)) continue;
          var nm = Array.isArray(v) ? v[0] : v;
          if (nm !== undefined && nm !== null) o[id] = String(nm);
        }
        return o;
      }
      var mn = {}, dn = {}, an = {};
      try { mn = tx('F_TEXT_MISSION', function (i) { return want[i] === 1; }); } catch (e1) { dbg.m = String(e1); }
      try { dn = tx('F_TEXT_DUNGEON_NAME', function (i) { return /^11[123]\d\d$/.test(i); }); } catch (e2) { dbg.d = String(e2); }
      try { an = tx('F_TEXT_AREA_NAME', function (i) { return /^11[123]$/.test(i); }); } catch (e3) { dbg.a = String(e3); }
      var cp = {}, cpr = rows('F_BEAST_CP_MST');
      for (var g = 0; g < cpr.length; g++) cp[String(cpr[g]['5hqFc4ey'])] = String(cpr[g]['hbm8t3uK'] || '');
      var fix = { 4: 7, 7: 4 }, bs = [], br = rows('F_BEAST_STATUS_MST');
      for (var h = 0; h < br.length; h++) {
        var r2 = br[h], eid = String(r2['Iwfx42Wo']), rar = parseInt(r2['9fW0TePj'], 10), num = fix[parseInt(eid, 10)] || parseInt(eid, 10);
        var o = '820' + (num < 10 ? '0' : '') + num + rar + '00';
        bs.push([eid, rar, parseInt(r2['aU3o2D4t'], 10), String(r2['5hqFc4ey']), sw[o] || null, sw[String(r2['W2hq4UaG'])] || null]);
      }
      return 'T ' + JSON.stringify({ m: m, c: ch, b: bs, cp: cp, n: names, mn: mn, dn: dn, an: an, dbg: dbg }).replace(/[\u0080-\uffff]/g, function (x) { return '\\u' + ('0000' + x.charCodeAt(0).toString(16)).slice(-4); });
    };
    // Skill names for the editor's Gambit tab: ability and magic names by id, from the game's text tables.
    cmd.skn = function () {
      var out = { a: {}, m: {}, i: {}, ad: {}, md: {}, id: {}, en: {}, ed: {}, ai: {}, mi: {}, ii: {}, ib: [], eb: {}, es: {}, dbg: {} };
      function grab(file, dst, cut) {
        var t = rows(file, true);
        if (t.length) out.dbg[file] = JSON.stringify(t[0]).substring(0, 160) + ' | n=' + t.length; else out.dbg[file] = 'no rows';
        for (var i = 0; i < t.length; i++) {
          var k = String(t[i].key || ''), mm = /(\d+)\D*$/.exec(k), v = t[i].v;
          if (!mm) continue;
          var nm = Array.isArray(v) ? v[0] : v;
          if (nm !== undefined && nm !== null && nm !== '') dst[mm[1]] = cut ? String(nm).replace(/\s+/g, ' ').substring(0, 140) : String(nm);
        }
      }
      try { grab('F_TEXT_ABILITY_NAME', out.a); } catch (e1) { out.dbg.a = String(e1); }
      try { grab('F_TEXT_ABILITY_EXPLAIN_SHORT', out.ad, true); } catch (e4) { out.dbg.ad = String(e4); }
      try { grab('F_TEXT_MAGIC_EXPLAIN_SHORT', out.md, true); } catch (e5) { out.dbg.md = String(e5); }
      try { grab('F_TEXT_ITEM_EXPLAIN_SHORT', out.id, true); } catch (e6) { out.dbg.id = String(e6); }
      try { grab('F_TEXT_BEAST_SKILL_NAME', out.en); } catch (e7) { out.dbg.en = String(e7); }
      try { grab('F_TEXT_BEAST_SKILL_DES', out.ed, true); } catch (e8) { out.dbg.ed = String(e8); }
      try { grab('F_TEXT_ITEM_NAME', out.i); } catch (e3) { out.dbg.i = String(e3); }
      try { grab('F_TEXT_MAGIC_NAME', out.m); } catch (e2) { out.dbg.m = String(e2); }
      // skill id -> icon number (field 0HUPxDf1 = 7000000NN means ability_NN.png), read without parsing every row
      function icons(file, idKey, dst, idRe) {
        var ls = lines(TBL + 'mst_decoded/' + file + '.ndjson'), ik = '"' + idKey + '":"', ck = '"0HUPxDf1":"7000000';
        for (var i = 0; i < ls.length; i++) {
          var l = ls[i], a = l.indexOf(ik);
          if (a < 0) continue;
          var b = l.indexOf('"', a + ik.length), id = l.substring(a + ik.length, b);
          if (!idRe.test(id)) continue;
          var c = l.indexOf(ck);
          if (c < 0) continue;
          var n = parseInt(l.substring(c + ck.length, c + ck.length + 2), 10);
          if (!isNaN(n)) dst[id] = n;
        }
      }
      try { icons('F_ABILITY_MST', 'Ga5V42LZ', out.ai, /^2\d{5}$/); } catch (e9) { out.dbg.ai = String(e9); }
      try { icons('F_MAGIC_MST', 'QV1HE7Wq', out.mi, /^\d{5}$/); } catch (e10) { out.dbg.mi = String(e10); }
      // item id -> picture name (F_ICON_MST maps the item id to its file, e.g. item_1000) and battle-usable items
      // (F_ITEM_MST pv9q8khF flags: 1st = battle use, 3rd = thrown item)
      try {
        var pic = {}, cl = lines(TBL + 'mst_decoded/F_ICON_MST.ndjson');
        for (var c2 = 0; c2 < cl.length; c2++) {
          var cn = cl[c2], c3 = cn.indexOf('"0HUPxDf1":"');
          if (c3 < 0 || cn.charAt(c3 + 12) !== '1') continue;
          var c4 = cn.indexOf('"', c3 + 12), c5 = cn.indexOf('"qtkvrS60":"');
          if (c5 < 0) continue;
          var c6 = cn.indexOf('"', c5 + 12);
          pic[cn.substring(c3 + 12, c4)] = cn.substring(c5 + 12, c6).replace(/\.png$/, '');
        }
        var il = lines(TBL + 'mst_decoded/F_ITEM_MST.ndjson');
        for (var q = 0; q < il.length; q++) {
          var ln = il[q], a1 = ln.indexOf('"tL6G9egd":"');
          if (a1 < 0) continue;
          var b1 = ln.indexOf('"', a1 + 12), iid = ln.substring(a1 + 12, b1);
          if (pic[iid]) out.ii[iid] = pic[iid];
          var f1 = ln.indexOf('"pv9q8khF":"');
          if (f1 >= 0) {
            var fl = ln.substring(f1 + 12, ln.indexOf('"', f1 + 12)).split(',');
            if (fl[0] === '1' || fl[2] === '1') out.ib.push(iid);
          }
        }
      } catch (e11) { out.dbg.ii = String(e11); }
      // esper board: for each esper the nodes that give a spell (type 20 = magic id) or an ability (type 21 = ability id);
      // node number = position in the save's board bitmap. Summon skill per esper and star level from F_BEAST_STATUS_MST.
      try {
        var bp = rows('F_BEAST_BOARD_PIECE_MST');
        for (var z = 0; z < bp.length; z++) {
          var ty = String(bp[z]['5oQ2TBpX']);
          if (ty !== '20' && ty !== '21') continue;
          var be = String(bp[z]['Iwfx42Wo']);
          (out.eb[be] = out.eb[be] || []).push([parseInt(bp[z]['xFsTMd83'], 10), parseInt(ty, 10), String(bp[z]['Hxy8VP56'])]);
        }
        var bs2 = rows('F_BEAST_STATUS_MST');
        for (var y = 0; y < bs2.length; y++) out.es[String(bs2[y]['Iwfx42Wo']) + ':' + String(bs2[y]['9fW0TePj'])] = String(bs2[y]['76LymtEu']);
        out.dbg.ebRows = bp.length; out.dbg.ebEspers = Object.keys(out.eb).length; out.dbg.esN = Object.keys(out.es).length;
        if (bp.length) out.dbg.ebKeys = Object.keys(bp[0]).join(',');
      } catch (e12) { out.dbg.eb = String(e12); }
      return 'S ' + JSON.stringify(out).replace(/[\u0080-\uffff]/g, function (x) { return '\\u' + ('0000' + x.charCodeAt(0).toString(16)).slice(-4); });
    };
    // Table samples for finding the icon field of skills and items (Developer Only: Export table samples).
    cmd.samp = function () {
      var out = [], files = ['F_EQUIP_ITEM_MST', 'F_ITEM_MST', 'F_MATERIA_MST'];
      var probes = ['"301000100"', '"302000100"', '"404000200"', '"1500000005"', '"1500000006"', '"2000000"', '"2000250"', '"3000000"', '"3000470"', '"5028062"', '"101000400"', '"101001500"'];
      for (var f = 0; f < files.length; f++) {
        var ls;
        try { ls = lines(TBL + 'mst_decoded/' + files[f] + '.ndjson'); } catch (e) { out.push('== ' + files[f] + ' error ' + e); continue; }
        out.push('== ' + files[f] + ' rows ' + ls.length);
        var shown = 0;
        for (var i = 0; i < ls.length; i++) {
          var l = ls[i];
          if (!l || l.charAt(0) !== '{') continue;
          var hit = i < 3;
          for (var p = 0; !hit && p < probes.length; p++) if (l.indexOf(probes[p]) >= 0) hit = true;
          if (hit && shown < 14) { out.push(l.substring(0, 1800)); shown++; }
        }
      }
      try {
        var hl = lines(TBL + 'mst_decoded/F_ITEM_MST.ndjson'), hh = {};
        for (var u = 0; u < hl.length; u++) {
          var m1 = /"pv9q8khF":"([^"]*)"/.exec(hl[u]), m2 = /"tL6G9egd":"([^"]*)"/.exec(hl[u]);
          if (!m1 || !m2) continue;
          var h = hh[m1[1]] = hh[m1[1]] || { n: 0, ex: [] };
          h.n++; if (h.ex.length < 4) h.ex.push(m2[1]);
        }
        out.push('== ITEM FLAG HISTOGRAM pv9q8khF'); for (var hk in hh) out.push(hk + ' n=' + hh[hk].n + ' ex=' + hh[hk].ex.join(','));
      } catch (e2) { out.push('hist error ' + e2); }
      // Status / buff tables (for the Gambit status list): which text tables exist, and the first rows of the effect tables.
      try {
        var tn = []; walk(TBL + 'text_decoded', 0, tn);
        out.push('== TEXT TABLES with STATUS/PROCESS/BUFF/EFFECT/AILMENT in the name: ' + tn.filter(function (x) { return /STATUS|PROCESS|BUFF|EFFECT|AILMENT|BAD/i.test(String(x)); }).join(' | ').substring(0, 3000));
      } catch (e4) { out.push('text table list error ' + e4); }
      var bf = ['F_PROCESS_TYPE_MST', 'F_PROCESS_PASSIVE_TYPE_MST', 'F_EFFECT_MST', 'F_EFFECT_GROUP_MST', 'F_BEAST_MST', 'F_BEAST_BOARD_PIECE_MST', 'F_BEAST_SKILL_MST', 'F_BEAST_STATUS_MST'];
      for (var bi = 0; bi < bf.length; bi++) {
        try {
          var bl = lines(TBL + 'mst_decoded/' + bf[bi] + '.ndjson');
          out.push('== ' + bf[bi] + ' rows ' + bl.length);
          for (var bj = 0; bj < Math.min(bf[bi].indexOf('PROCESS') >= 0 ? 90 : 6, bl.length); bj++) out.push(bl[bj].substring(0, 900));
        } catch (e3) { out.push('== ' + bf[bi] + ' error ' + e3); }
      }
      return out.join('\n');
    };
    cmd.flist = function () {
      var out = [];
      walk(ROOT, 0, out);
      var txt = out.join('\n');
      var m = Memory.allocUtf8String(txt);
      var n = 0;
      while (m.add(n).readU8() !== 0) n++;
      return 'D ' + b64(new Uint8Array(m.readByteArray(n)));
    };
    cmd.fget = function (t) {
      var p = t.split(' '), off = parseInt(p[1], 10), len = parseInt(p[2], 10);
      var path = p.slice(3).join(' ');
      if (path.indexOf(ROOT + '/') !== 0 || path.indexOf('..') >= 0 || !(off >= 0) || !(len > 0)) return 'none';
      var n = size(path);
      if (n < 0 || off >= n) return 'D ';
      var f = fopen(cs(path), cs('r'));
      if (f.isNull()) return 'none';
      fseek(f, off, 0);
      var want = Math.min(len, n - off), buf = Memory.alloc(Math.max(want, 1));
      var got = fread(buf, 1, want, f);
      fclose(f);
      return 'D ' + b64(new Uint8Array(buf.readByteArray(Math.max(got, 0))));
    };
    cmd.sget = function (t) {
      var p = t.split(' '), off = parseInt(p[1], 10), len = parseInt(p[2], 10), u = local();
      if (!u || !(off >= 0) || !(len > 0)) return 'none';
      return 'D ' + b64(u.subarray(off, Math.min(u.length, off + len)));
    };
    cmd.sput = function (t) {
      var p = t.split(' '), idx = parseInt(p[1], 10), total = parseInt(p[2], 10);
      if (!(total > 0) || total > 80 * 1048576 || !(idx >= 0)) return 'bad';
      if (idx === 0 || !stage || stage.length !== total) { stage = new Uint8Array(total); stageLen = 0; }
      var end = unb64(p[3] || '', stage, idx * 196608);
      stageLen = Math.max(stageLen, end);
      return 'ok ' + end;
    };
    cmd.sapply = function (t) {
      var p = t.split(' ');
      if (!stage || stageLen !== stage.length || hash(stage) !== p[1] || !sane(stage)) return 'bad staged file';
      var cur = rd(localPath());
      if (cur && !wr(base + 'ffbe_prev_save.json', cur)) return 'could not keep the old save';
      if (!wr(localPath(), stage)) return 'could not write the save';
      log('Cloud sync: cloud save written, restarting the game');
      setTimeout(function () { kill(getpid(), 9); }, 900);
      return 'restart';
    };
    // When the phone save was last written. The game rewrites the save when it opens, which makes the time "now"; the script remembers
    // the time from the moment it started (early_patch.js). While the game is young that earlier time is the one reported.
    cmd.smtime = function () {
      var lp = localPath();
      if (stat(cs(lp), sb) !== 0) return 'none';
      var mt = sb.add(88).readS64().valueOf();
      try {
        var t0 = globalThis.__kupoT0;
        if (t0 && t0.path === lp && t0.age < 120 && t0.mt > 0 && t0.mt < mt && globalThis.__kupoAge && globalThis.__kupoAge() < 600) mt = t0.mt;
      } catch (e) { }
      return 'T ' + mt;
    };
    cmd.cloud = function (t) { return 'ok'; };
    log('Cloud sync: save hand-over ready');
  }


  // ---------- Title screen brand: mascot + gold "Kupo Forge v..." under the game logo (always on; every step is guarded and logs "Brand:") ----------
  function brandHooks() {
    // The size we draw in. CommonUtils::getWinWidth/Height gave the whole screen (rotated) on the tablet while the game ran in a smaller
    // window, so the moogle and panel landed outside the picture. Director::getWinSize is the game's real design size.
    var W0 = nf('_ZN11CommonUtils11getWinWidthEv', 'int', []), H0 = nf('_ZN11CommonUtils12getWinHeightEv', 'int', []);
    var wsA = sym('_ZNK7cocos2d8Director10getWinSizeEv'), dirW = nf('_ZN7cocos2d8Director11getInstanceEv', 'pointer', []), wsLogged = false;
    var wsFn = wsA ? new NativeFunction(wsA, ['float', 'float'], ['pointer']) : null;
    function winSz() {
      try { if (wsFn) { var r = wsFn(dirW()); if (r[0] > 100 && r[1] > 100 && r[0] < 5000 && r[1] < 8000) return r; } } catch (e) { }
      return null;
    }
    function W() { var z = winSz(); if (!wsLogged) { wsLogged = true; log('Widget: design size ' + (z ? Math.round(z[0]) + 'x' + Math.round(z[1]) : 'unknown') + ', screen size ' + W0() + 'x' + H0()); } return z ? Math.round(z[0]) : W0(); }
    function H() { var z = winSz(); return z ? Math.round(z[1]) : H0(); }
    var strT = 'RKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE';
    var mkLabel = nf('_ZN9GameScene14setStringLabelEi' + strT + 'ffRKN7cocos2d7Color3BEiii', 'pointer', ['pointer', 'int', 'pointer', 'float', 'float', 'pointer', 'int', 'int', 'int']);
    var layerFn = nf('_ZN9GameLayer6sharedEv', 'pointer', []);
    var addChild = nf('_ZN9GameLayer8addChildEiiPN7cocos2d4NodeE', 'void', ['pointer', 'int', 'int', 'pointer']);
    var opNew = nf('_Znwm', 'pointer', ['ulong']);
    var imgCtor = nf('_ZN7cocos2d5ImageC1Ev', 'void', ['pointer']);
    var imgInit = nf('_ZN7cocos2d5Image17initWithImageDataEPKhlRKNSt6__ndk112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE', 'bool', ['pointer', 'pointer', 'long', 'pointer']);
    var tcGet = nf('_ZN7cocos2d12TextureCache11getInstanceEv', 'pointer', []);
    var tcAdd = nf('_ZN7cocos2d12TextureCache8addImageEPNS_5ImageERKNSt6__ndk112basic_stringIcNS3_11char_traitsIcEENS3_9allocatorIcEEEE', 'pointer', ['pointer', 'pointer', 'pointer']);
    var mkSprite = nf('_ZN7cocos2d6Sprite17createWithTextureEPNS_9Texture2DE', 'pointer', ['pointer']);
    var setPos = nf('_ZN7cocos2d4Node11setPositionEff', 'void', ['pointer', 'float', 'float']);
    var setScale = nf('_ZN7cocos2d4Node8setScaleEff', 'void', ['pointer', 'float', 'float']);
    var onVis = sym('_ZN10TitleScene14onSceneVisibleEv');
    if (!onVis || !mkLabel || !layerFn || !addChild) { log('Brand: needed game functions are missing, skipped'); return; }
    var strKeep = [];   // long strings: keep the buffers alive (a freed buffer showed up as garbage text in the panel)
    function str(v) {   // read-only libc++ short/long string, same trick as the game developer's summon menu code
      var s = Memory.alloc(24), n = Array.from(v).length;
      s.writeByteArray(new Uint8Array(24));
      if (n <= 22) { s.writeU8(n * 2); s.add(1).writeUtf8String(v); }
      else { var b = Memory.alloc(n + 16); b.writeUtf8String(v); s.writeU64((n + 16) | 1); s.add(8).writeU64(n); s.add(16).writePointer(b); strKeep.push(b); if (strKeep.length > 400) strKeep.shift(); }
      return s;
    }
    function b64(t) {
      var k = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/', o = [], b = 0, n = 0;
      for (var i = 0; i < t.length; i++) { var v = k.indexOf(t.charAt(i)); if (v < 0) continue; b = (b << 6) | v; n += 6; if (n >= 8) { n -= 8; o.push((b >> n) & 255); } }
      return new Uint8Array(o);
    }
    var texture = null, tried = 0, done = {}, brandX = 0, textX = 0, btn = null, panel = null;
    var qtex = null;
    function quickTexture() {
      if (qtex) return qtex;
      var png = b64(QUICK_PNG), data = Memory.alloc(png.length);
      data.writeByteArray(png.buffer);
      var img = opNew(512);
      img.writeByteArray(new Uint8Array(512));
      imgCtor(img);
      var ok = imgInit(img, data, png.length, str(''));
      log('Quick party: icon decode ' + (ok ? 'ok' : 'failed'));
      if (!ok) throw new Error('quick icon decode failed');
      qtex = tcAdd(tcGet(), img, str('kupo_forge_quick'));
      if (!qtex || qtex.isNull()) throw new Error('no quick texture');
      try { var rt = nf('_ZN7cocos2d3Ref6retainEv', 'void', ['pointer']); if (rt) rt(qtex); } catch (e) { }
      return qtex;
    }
    function mascotTexture() {
      if (texture) return texture;
      if (!opNew || !imgCtor || !imgInit || !tcGet || !tcAdd) throw new Error('image functions missing');
      var png = b64(MASCOT_PNG), data = Memory.alloc(png.length);
      data.writeByteArray(png.buffer);
      var img = opNew(512);
      img.writeByteArray(new Uint8Array(512));
      imgCtor(img);
      var ok = imgInit(img, data, png.length, str(''));
      log('Brand: image decode ' + (ok ? 'ok' : 'failed'));
      if (!ok) throw new Error('png decode failed');
      texture = tcAdd(tcGet(), img, str('kupo_forge_mascot'));
      if (!texture || texture.isNull()) throw new Error('no texture');
      try { var rt = nf('_ZN7cocos2d3Ref6retainEv', 'void', ['pointer']); if (rt) rt(texture); } catch (e) { }   // the game frees unused textures when a scene changes; keep ours
      return texture;
    }
    // The title screen builds its own layers in TitleScene::setLayout. Watch which GameLayer layer ids and depths it uses
    // (logged as "Brand: title used layer ..."), then draw on the highest one, above the logo.
    var seen = { ids: {}, maxDepth: 0, n: 0, on: false }, drawn = 0, layoutAt = 0, lastScene = null;
    function watchAdd(name, idArg, depthArg) {
      var ad = sym(name); if (!ad) return;
      Interceptor.attach(ad, { onEnter: function (a) {
        if (!seen.on) return;
        try {
          var id = a[idArg].toInt32(), dp = depthArg ? a[depthArg].toInt32() : 0;
          seen.ids[id] = (seen.ids[id] || 0) + 1;
          if (dp > seen.maxDepth && dp < 100000) seen.maxDepth = dp;
          if (++seen.n <= 25) log('Brand: title used layer ' + id + ' depth ' + dp);
        } catch (e) { }
      } });
    }
    watchAdd('_ZN9GameLayer8addChildEiiPN7cocos2d4NodeE', 1, 2);
    watchAdd('_ZN9GameLayer8addChildEiPN7cocos2d4NodeE', 1, 0);
    function draw(scene) {
      var w = W(), h = H(), y = Math.round(h * 0.535) - 30, gold = Memory.alloc(3), lid = 3, dep = 265;   // layer 3 is the title screen's own layer (from the 0.19.22 log)
      gold.writeByteArray([212, 180, 106]);
      var ids = Object.keys(seen.ids).map(Number).sort(function (p, q) { return q - p; });
      if (ids.length) { lid = ids[0]; dep = Math.max(seen.maxDepth + 10, 100); }
      log('Brand: drawing on title scene ' + scene + ' size ' + w + 'x' + h + ' layer ' + lid + ' depth ' + dep + ' (layers seen: ' + ids.join(',') + ')');
      try {
        var txt = 'Kupo Forge' + (OPT.ver ? ' v' + OPT.ver : '');
        // side by side, centred: mascot (about 120 wide) + gap + text (about 10.5 units per letter at size 22; the text is left aligned)
        var tw = txt.length * 10.5, gx = Math.round((w - (120 + 8 + tw)) / 2);
        brandX = gx + 60; textX = gx + 128;
        var node = mkLabel(scene, lid, str(txt), textX, y, gold, 1, 22, dep);
        log('Brand: label ' + (node && !node.isNull() ? 'added' : 'returned null'));
      } catch (e) { log('Brand: label failed: ' + e); }
      try {
        var tex = mascotTexture();
        var spr = mkSprite(tex);
        if (!spr || spr.isNull()) throw new Error('sprite is null');
        setScale(spr, 1, 1);
        setPos(spr, brandX, h - y);
        addChild(layerFn(), lid, dep, spr);
        log('Brand: mascot added');
      } catch (e) { log('Brand: mascot failed: ' + e); }
    }
    var setLay = sym('_ZN10TitleScene9setLayoutEv');
    if (setLay) Interceptor.attach(setLay, {
      onEnter: function (a) { this.scene = a[0]; seen.on = true; drawn = 0; layoutAt = Date.now(); lastScene = a[0]; },
      onLeave: function () { seen.on = false; layoutAt = Date.now(); log('Brand: title layout built (' + seen.n + ' layer calls)'); }
    });
    // draw a moment AFTER the layout (the title may still rebuild or cover things right after setLayout)
    tickFns.push(function () {
      if (!layoutAt || drawn >= 1 || !lastScene) return;
      if (Date.now() - layoutAt < 150) return;   // layoutAt = the moment setLayout FINISHED, so the layers exist
      drawn++;
      try { draw(lastScene); } catch (e) { log('Brand failed: ' + e); }
    });
    // If our hooks came in after the title screen was already built, setLayout never fires for us. For the first 25 seconds look at
    // the scene that is running: when it is the title screen and nothing is drawn yet, draw on it now.
    var runScene = nf('_ZN7cocos2d8Director15getRunningSceneEv', 'pointer', []), dirB = nf('_ZN7cocos2d8Director11getInstanceEv', 'pointer', []);
    // The title screen runs updateEvent() every frame. If we meet it without ever seeing its setLayout, our hooks came in late: draw now.
    var titleUpd = sym('_ZN10TitleScene11updateEventEv');
    if (titleUpd) Interceptor.attach(titleUpd, { onEnter: function (a) {
      if (drawn >= 1 || layoutAt) return;
      lastScene = a[0]; layoutAt = Date.now() - 100;
      log('Brand: title already built (seen by updateEvent), drawing late');
    } });
    var lateT0 = Date.now(), lateLogs = 0;
    tickFns.push(function () {
      if (drawn >= 1 || layoutAt || Date.now() - lateT0 > 25000 || !runScene || !dirB) return;
      try {
        var rs = runScene(dirB());
        if (!rs || rs.isNull()) return;
        var nm = className(rs);
        if (lateLogs++ < 3) log('Brand: running scene is ' + nm);
        if (nm === '10TitleScene') { lastScene = rs; layoutAt = Date.now() - 200; log('Brand: title already built, drawing late'); }
      } catch (e) { if (lateLogs++ < 3) log('Brand: late check failed: ' + e); }
    });
    ensureTick();
    var offVis = sym('_ZN10TitleScene16onSceneInvisibleEv');
    if (offVis) Interceptor.attach(offVis, { onEnter: function () { lastScene = null; layoutAt = 0; btn = null; panel = null; log('Brand: title scene left'); } });   // never draw on a scene that is gone
    Interceptor.attach(onVis, {
      onEnter: function (a) { lastScene = a[0]; },   // drawing waits for setLayout (drawing before it showed nothing)
      onLeave: function () { log('Brand: title scene visible'); }
    });

    // ---------- In-game panel (test): THREE FINGERS tapped together anywhere in the game open a panel with the on/off switches ----------
    // No button on screen. Touch positions arrive in design units (640 wide), top left origin (proved by the 0.19.23 log).
    var rmChild = nf('_ZN9GameLayer11removeChildEiPN7cocos2d4NodeE', 'void', ['pointer', 'int', 'pointer']);
    var mkBox = nf('_ZN7cocos2d10LayerColor6createERKNS_7Color4BEff', 'pointer', ['pointer', 'float', 'float']);
    var tBegin = sym('_ZN7cocos2d6GLView18handleTouchesBeginEiPlPfS2_'), tEnd = sym('_ZN7cocos2d6GLView16handleTouchesEndEiPlPfS2_');
    var tCancel = sym('_ZN7cocos2d6GLView19handleTouchesCancelEiPlPfS2_'), baseVis = sym('_ZN9GameScene14onSceneVisibleEv');
    var curScene = null, curName = '', sceneLogs = 0, layerCount = {}, active = {}, nActive = 0, fired = false, swallowAll = false, tapLogs = 0;
    function className(sc) {
      try { return sc.readPointer().sub(8).readPointer().add(8).readPointer().readCString(); } catch (e) { return '?'; }
    }
    // which layer this scene uses most (learned from GameLayer::addChild calls since the scene appeared); layer 3 as the fallback
    function pickLayer() {
      var best = 3, bn = 0;
      Object.keys(layerCount).forEach(function (k) { if (layerCount[k] > bn) { bn = layerCount[k]; best = Number(k); } });
      return best;
    }
    // layer 183 is the game's top overlay (header and footer bars): drawing there keeps our button and panel above lists and popups
    // 0.19.37: the highest layer id the game has used so far (the Home screen's anniversary badge and the tutorial TAP diamond sit above 183)
    var maxLayer = 0;
    function topLayer() { return maxLayer > 0 ? maxLayer : (layerCount[183] > 0 ? 183 : pickLayer()); }
    [['_ZN9GameLayer8addChildEiiPN7cocos2d4NodeE'], ['_ZN9GameLayer8addChildEiPN7cocos2d4NodeE']].forEach(function (n) {
      var ad = sym(n[0]); if (!ad) return;
      Interceptor.attach(ad, { onEnter: function (a) { try { var id = a[1].toInt32(); if (id >= 0 && id < 1000) { layerCount[id] = (layerCount[id] || 0) + 1; if (id > maxLayer) maxLayer = id; } } catch (e) { } } });
    });
    // ---- the draggable mascot button (all screens except start-up, login and title) ----
    var DENY = { '12StartupScene': 1, '12ConnectScene': 1, '10TitleScene': 1, '15InitializeScene': 1, '10LoginScene': 1, '16NoticeMovieScene': 1 };
    var POS_FILE = '/data/data/com.exvius.offline.forever/files/kupo_btn.txt';
    var libc = Process.getModuleByName('libc.so');
    var cFopen = new NativeFunction(libc.getExportByName('fopen'), 'pointer', ['pointer', 'pointer']);
    var cFread = new NativeFunction(libc.getExportByName('fread'), 'long', ['pointer', 'long', 'long', 'pointer']);
    var cFwrite = new NativeFunction(libc.getExportByName('fwrite'), 'long', ['pointer', 'long', 'long', 'pointer']);
    var cFclose = new NativeFunction(libc.getExportByName('fclose'), 'int', ['pointer']);
    var pos = null, gnode = null, glid = 0, pend = null, drag = null, BR = 56;
    var sceneStack = [], panelReopen = false;   // live scenes, newest last; a panel that was open when the screen changed comes back on the new screen
    function loadPos() {
      try {
        var f = cFopen(Memory.allocUtf8String(POS_FILE), Memory.allocUtf8String('r'));
        if (f.isNull()) return null;
        var b = Memory.alloc(64); b.writeByteArray(new Uint8Array(64));
        var n = cFread(b, 1, 63, f); cFclose(f);
        var m = /^(-?\d+),(-?\d+)/.exec(b.readUtf8String(Number(n)));
        return m ? { x: Number(m[1]), y: Number(m[2]) } : null;
      } catch (e) { return null; }
    }
    function savePos() {
      try {
        var f = cFopen(Memory.allocUtf8String(POS_FILE), Memory.allocUtf8String('w'));
        if (f.isNull()) { log('Widget: cannot save the button position'); return; }
        var t = Math.round(pos.x) + ',' + Math.round(pos.y), m = Memory.allocUtf8String(t);
        cFwrite(m, 1, t.length, f); cFclose(f);
      } catch (e) { log('Widget: save position failed: ' + e); }
    }
    // ONE button for the whole game: layer 183 is shared by every screen, so the node stays there when the screen changes.
    // It is retained (never freed by the game) and removed here before a new one is added, so copies cannot pile up.
    // ---- Quick party: a gold button drawn by the game hook on the Manage Party screen (UnitTopScene), in the empty spot above Arena Party ----
    var qnodes = [], qlid = 0, qrect = null, qdrag = false;
    function removeQuick() {
      qnodes.forEach(function (n) { try { rmChild(layerFn(), qlid, n); } catch (e) { } });
      qnodes = []; qrect = null;
    }
    function inQ(x, y) { return !!qrect && x >= qrect.x && x <= qrect.x + qrect.w && y >= qrect.y && y <= qrect.y + qrect.h; }
    function drawQuick() {
      removeQuick();
      if (!OPT.quick || curName !== '12UnitTopScene' || !hooksOk) return;
      var h = H(), lid = topLayer(), sc = 0.55;
      // directly above the Load button (Load sits at x 454 on every screen; its row is lower on taller screens: 205 at height 1337)
      var cx = 454, cy = Math.round(205 - (1337 - h) * 0.41);
      var spr = mkSprite(quickTexture());
      if (!spr || spr.isNull()) throw new Error('quick sprite is null');
      try { var rt3 = nf('_ZN7cocos2d3Ref6retainEv', 'void', ['pointer']); if (rt3) rt3(spr); } catch (e) { }
      setScale(spr, sc, sc);
      setPos(spr, cx, h - cy);
      addChild(layerFn(), lid, 90000, spr);
      qnodes.push(spr);
      qlid = lid; qrect = { x: cx - 34, y: cy - 38, w: 68, h: 76 };
      log('Quick party: in-game button drawn on ' + curName + ' at ' + cx + ',' + cy + ' (design height ' + h + ')');
    }
    function removeBtn() {
      try { removeQuick(); } catch (e) { }
      if (!gnode) return;
      try { rmChild(layerFn(), glid, gnode); } catch (e) { }
      gnode = null;
    }
    globalThis.__kupoMoogle = function (on) {
      if (!on) {
        if (gnode) { try { rmChild(layerFn(), glid, gnode); } catch (e) { } gnode = null; }
        btn = null; drag = null;
        log('Widget: moogle button hidden');
      } else if (curScene) {
        pend = { key: String(curScene), at: Date.now() - 300 };
        log('Widget: moogle button shown');
      }
    };
    function removePanelNodes() {
      if (!panel) return;
      try { panel.nodes.forEach(function (n) { try { rmChild(layerFn(), panel.lid, n); } catch (e) { } }); } catch (e) { }
      panel = null;
    }
    function drawButton(key, scene) {
      if (OPT.mshow === 0 || OPT.mshow === false) {   // the player hid the moogle button (app switch "Show moogle in game"); Quick party stays
        if (gnode) { try { rmChild(layerFn(), glid, gnode); } catch (e) { } gnode = null; }
        btn = null;
        try { drawQuick(); } catch (e) { }
        return;
      }
      var w = W(), h = H();
      if (!pos) pos = loadPos() || { x: w - 50, y: Math.round(h * 0.53) };
      var spr = mkSprite(mascotTexture());
      if (!spr || spr.isNull()) throw new Error('sprite is null');
      try { var rt2 = nf('_ZN7cocos2d3Ref6retainEv', 'void', ['pointer']); if (rt2) rt2(spr); } catch (e) { }
      removeBtn();
      var lid = topLayer();
      setScale(spr, 0.72, 0.72);
      setPos(spr, pos.x, h - pos.y);
      addChild(layerFn(), lid, 90000, spr);
      gnode = spr; glid = lid;
      btn = { r: BR, rec: { node: spr } };
      log('Widget: button on ' + curName + ' layer ' + lid + ' at ' + pos.x + ',' + pos.y);
      try { drawQuick(); } catch (e) { log('Quick party: button failed: ' + e); }
    }
    var gsDtor = sym('_ZN9GameSceneD2Ev');
    if (gsDtor) Interceptor.attach(gsDtor, { onEnter: function (a) {
      var k = String(a[0]);
      // forget this scene; when it was the current one, the scene underneath (the battle screen, which never says "visible" again) becomes current
      for (var si = sceneStack.length - 1; si >= 0; si--) if (sceneStack[si].k === k) sceneStack.splice(si, 1);
      if (curScene && String(curScene) === k) {
        if (panel) panelReopen = true;
        removePanelNodes();
        var top = sceneStack.length ? sceneStack[sceneStack.length - 1] : null;
        if (top) { curScene = top.p; curName = top.n; pend = { key: top.k, at: Date.now() }; }
        else { curScene = null; pend = null; panelReopen = false; }
      }
    } });
    tickFns.push(function () {
      if (!pend || Date.now() - pend.at < 300) return;
      var p = pend; pend = null;
      if (!curScene || String(curScene) !== p.key) return;
      var re = panelReopen; panelReopen = false;
      try { drawButton(p.key, curScene); } catch (e) { log('Widget: button failed: ' + e); }
      // While Gambit plays, the battle screen and its ability menu swap every second. Drawing the moogle panel again on each of them left nodes on scenes that
      // were being destroyed: the 26 Nov logs show the panel redrawn 8 times in 6 s, then a native crash. So during Gambit the panel is taken down and stays down (tap the moogle to open it).
      if (re && RT.bot && (curName === '22BattleAbilityMenuScene' || curName === '15WaveBattleScene')) { re = false; log('Widget: panel not redrawn while Gambit plays'); }
      if (re) { try { openPanel(); } catch (e) { log('Widget: panel reopen failed: ' + e); } }
    });
    // when the game builds a layer above ours later (a popup, the tutorial arrow), draw the button (and an open panel) again on top
    var lastRise = 0;
    tickFns.push(function () {
      if (!gnode || pend || !curScene || glid >= maxLayer || Date.now() - lastRise < 1000) return;
      lastRise = Date.now();
      if (panel) panelReopen = true;
      pend = { key: String(curScene), at: Date.now() - 300 };
      log('Widget: a layer above ours appeared (' + maxLayer + ' over ' + glid + '), drawing again on top');
    });
    var tMove = sym('_ZN7cocos2d6GLView17handleTouchesMoveEiPlPfS2_'), tMove6 = sym('_ZN7cocos2d6GLView17handleTouchesMoveEiPlPfS2_S2_S2_');
    var moveCb = { onEnter: function (a) {
      try {
        if (!drag || !btn || a[1].toInt32() < 1) return;
        var x = a[3].readFloat() / scl.x, y = a[4].readFloat() / scl.y;
        if (Math.abs(x - drag.sx) + Math.abs(y - drag.sy) > 12) drag.moved = true;
        if (drag.moved) {
          pos.x = Math.max(30, Math.min(W() - 30, x + drag.dx)); pos.y = Math.max(60, Math.min(H() - 60, y + drag.dy));
          setPos(btn.rec.node, pos.x, H() - pos.y);
        }
        a[1] = ptr(0);
      } catch (e) { drag = null; }
    } };
    if (tMove) Interceptor.attach(tMove, moveCb);
    if (tMove6) Interceptor.attach(tMove6, moveCb);
    var ROWS = [['res', 'Skip results'], ['auto', 'Battle auto'], ['rep', 'Repeat battle'], ['bot', 'Gambit']];
    var PX = 120, PW = 400, RH = 50, PTOP = 250;
    function rowOn() { return ROWS.filter(function (r) { return OPT[r[0]] && cmd[r[0]]; }); }
    // everything the floating popup has: speeds, the four switches, Cloud sync and Edit save (these two live in the editor app: the game asks the app through its next answer)
    var subMenu = false;
    function panelItems() {
      var it = [];
      if (globalThis.FFBE_SETSPEED && OPT.speeds && OPT.speeds.length) it.push({ t: 'sp' });
      var merged = !!(OPT.bot && cmd.bot && RT.gnames && RT.gnames.length);
      if (subMenu) {
        it.push({ t: 'go', l: 'Off' });
        (RT.gnames || []).slice(0, 11).forEach(function (nm, i) { it.push({ t: 'pk', i: i, l: nm }); });
        it.push({ t: 'bk', l: '< Back' });
        return it;
      }
      rowOn().forEach(function (r) { if (!(merged && r[0] === 'bot')) it.push({ t: 'tg', k: r[0], l: r[1] }); });
      if (merged) it.push({ t: 'gp', l: RT.bot ? 'Gambit: ' + (RT.gnames[RT.gactive] || '?') + '  >' : 'Gambit: OFF  >' });
      if (OPT.cloud) it.push({ t: 'rq', n: 'cloud', l: 'Cloud sync' });
      if (OPT.onote) it.push({ t: 'rq', n: 'fnote', l: 'Gambit float log' });
      if (OPT.live) it.push({ t: 'rq', n: 'edit', l: 'Open Kupo Forge' });
      it.push({ t: 'rq', n: 'log', l: 'Export Kupo log' });
      return it;
    }
    function closePanel() {
      panelReopen = false;
      if (!panel) return;
      try { if (curScene && String(curScene) === panel.scene) panel.nodes.forEach(function (n) { try { rmChild(layerFn(), panel.lid, n); } catch (e) { } }); } catch (e) { }
      panel = null;
    }
    function openPanel() {
      if (!curScene) { log('Widget: no scene yet'); return; }
      var keepSub = subMenu; closePanel(); subMenu = keepSub;
      var items = panelItems(), lid = topLayer(), dep = 90000, nodes = [], gold = Memory.alloc(3), top = PTOP;
      gold.writeByteArray([212, 180, 106]);
      var hBox = 70 + items.length * RH + 20;
      try {
        var c4 = Memory.alloc(4); c4.writeByteArray([0, 12, 30, 255]);
        var box = mkBox(c4, PW, hBox);
        setPos(box, PX, H() - top - hBox + 20);
        addChild(layerFn(), lid, dep, box);
        nodes.push(box);
      } catch (e) { log('Widget: panel background failed: ' + e); }
      // gold border: a 3-pixel gold frame behind the dark body (body and title look as before)
      try {
        var cg = Memory.alloc(4); cg.writeByteArray([212, 180, 106, 255]);
        var fr = mkBox(cg, PW + 6, hBox + 6);
        setPos(fr, PX - 3, H() - top - hBox + 20 - 3);
        addChild(layerFn(), lid, dep - 1, fr);
        nodes.push(fr);
      } catch (e) { log('Widget: gold frame failed: ' + e); }
      try { nodes.push(mkLabel(curScene, lid, str('Kupo Forge' + (OPT.ver ? ' v' + OPT.ver : '')), PX + 14, top, gold, 1, 22, dep + 10)); } catch (e) { log('Widget: title label failed: ' + e); }
      var cur = 1, rowFail = false; try { if (globalThis.FFBE_GETSPEED) cur = globalThis.FFBE_GETSPEED(); } catch (e) { }
      items.forEach(function (r, i) {
        var y = top + 62 + i * RH;
        try {
          if (r.t === 'sp') {
            var n = OPT.speeds.length, seg = (PW - 28) / n;
            OPT.speeds.forEach(function (v, j) {
              var col = Memory.alloc(3); col.writeByteArray(Math.abs(v - cur) < 0.01 ? [120, 235, 150] : [235, 235, 235]);
              nodes.push(mkLabel(curScene, lid, str(v + 'x'), PX + 14 + j * seg, y, col, 1, 22, dep + 10));
            });
          } else if (r.t === 'tg') {
            var on = !!RT[r.k], col2 = Memory.alloc(3);
            col2.writeByteArray(on ? [120, 235, 150] : [235, 235, 235]);
            nodes.push(mkLabel(curScene, lid, str(r.l + ':  ' + (on ? 'ON' : 'OFF')), PX + 14, y, col2, 1, 22, dep + 10));
          } else if (r.t === 'gp' || r.t === 'pk' || r.t === 'bk' || r.t === 'go') {
            var colg = Memory.alloc(3), fit = Math.floor((PW - 28) / 10.5), len = Array.from(r.l).length, fs = len > fit ? Math.max(12, Math.floor(22 * fit / len)) : 22;
            colg.writeByteArray(((r.t === 'pk' && RT.bot && r.i === RT.gactive) || (r.t === 'go' && !RT.bot) || (r.t === 'gp' && RT.bot)) ? [120, 235, 150] : (r.t === 'bk' ? [200, 220, 255] : [235, 235, 235]));
            nodes.push(mkLabel(curScene, lid, str(r.l), PX + 14, y, colg, 1, fs, dep + 10));
          } else {
            var col3 = Memory.alloc(3), st = (r.n === 'cloud' && RT.cloudApp !== undefined) ? (RT.cloudApp ? ':  ON' : ':  OFF') : (r.n === 'fnote' ? (RT.fnApp ? ':  ON' : ':  OFF') : '');
            col3.writeByteArray(st === ':  ON' ? [120, 235, 150] : [200, 220, 255]);
            nodes.push(mkLabel(curScene, lid, str(r.l + st), PX + 14, y, col3, 1, 22, dep + 10));
          }
        } catch (e) { rowFail = true; log('Widget: row label failed: ' + e); }
      });
      if (rowFail) {   // a label could not be made (the scene is not ready, or is going away): take the half-drawn panel down at once instead of leaving it there
        nodes.forEach(function (n) { try { rmChild(layerFn(), lid, n); } catch (e) { } });
        panelReopen = false; panel = null; log('Widget: panel taken down after a failed label');
        return;
      }
      panel = { nodes: nodes, lid: lid, top: top, items: items, scene: String(curScene) };
      log('Widget: panel opened on ' + curName + ' layer ' + lid + ' items ' + items.length);
    }
    function panelTap(x, y) {
      if (!panel) return;
      var inside = x >= PX && x <= PX + PW && y >= panel.top && y <= panel.top + 70 + panel.items.length * RH + 20;
      if (inside) {
        var i = Math.floor((y - (panel.top + 62 - 8)) / RH);
        if (i >= 0 && i < panel.items.length) {
          var r = panel.items[i];
          if (r.t === 'sp') {
            var n = OPT.speeds.length, j = Math.floor((x - (PX + 14 + 0)) / ((PW - 28) / n));
            if (j >= 0 && j < n) { try { globalThis.FFBE_SETSPEED(OPT.speeds[j]); log('Widget: speed ' + OPT.speeds[j]); } catch (e) { log('Widget: speed failed: ' + e); } openPanel(); }
          } else if (r.t === 'tg') {
            var v = RT[r.k] ? 0 : 1;
            try { cmd[r.k](r.k + ' ' + v); log('Widget: ' + r.l + ' -> ' + (v ? 'ON' : 'OFF')); } catch (e) { log('Widget: toggle failed: ' + e); }
            openPanel();
          } else if (r.t === 'gp') { subMenu = true; openPanel();
          } else if (r.t === 'bk') { subMenu = false; openPanel();
          } else if (r.t === 'go') {
            try { cmd.bot('bot 0'); } catch (e) { }
            log('Widget: Gambit off'); subMenu = false; openPanel();
          } else if (r.t === 'pk') {
            RT.req.seq++; RT.req.name = 'pick' + r.i; RT.gactive = r.i;
            try { cmd.bot('bot 1'); } catch (e) { }
            log('Widget: Gambit preset ' + r.i + ' chosen, Gambit on');
            subMenu = false; openPanel();
          } else {
            RT.req.seq++; RT.req.name = r.n;
            log('Widget: asked the app: ' + r.n);
            if (r.n === 'cloud') { RT.cloudApp = !RT.cloudApp; openPanel(); }
            else if (r.n === 'fnote') { RT.fnApp = !RT.fnApp; if (RT.bot && /Battle/.test(curName)) closePanel(); else openPanel(); }   // no redraw in battle while Gambit is on
            else closePanel();
          }
        }
        return;
      }
      subMenu = false;
      closePanel();
    }
    // touches reach the hook in real screen pixels (the game divides by its view scale later), so turn them into design units here
    var getSX = nf('_ZNK7cocos2d6GLView9getScaleXEv', 'float', ['pointer']), getSY = nf('_ZNK7cocos2d6GLView9getScaleYEv', 'float', ['pointer']);
    var dirI = nf('_ZN7cocos2d8Director11getInstanceEv', 'pointer', []);
    var scl = { x: 1, y: 1, ok: false }, sclLogged = false;
    function refreshScale() {
      try {
        var v = dirI().add(0x110).readPointer(); if (v.isNull()) return;
        var sx = getSX(v), sy = getSY(v);
        if (sx > 0.2 && sx < 8 && sy > 0.2 && sy < 8) { scl.x = sx; scl.y = sy; scl.ok = true; }
        if (!sclLogged) { sclLogged = true; log('Widget: view scale ' + sx + ',' + sy + (scl.ok ? ' (used)' : ' (NOT used)')); }
      } catch (e) { if (!sclLogged) { sclLogged = true; log('Widget: view scale failed: ' + e); } }
    }
    function ids(a) { var n = a[0].toInt32(), o = []; for (var i = 0; i < n && i < 10; i++) o.push(String(a[1].add(i * 8).readPointer())); return o; }
    if (tBegin) Interceptor.attach(tBegin, { onEnter: function (a) {
      try {
        var n = a[1].toInt32(); if (n < 1) return;
        refreshScale();
        var list = ids([a[1], a[2]]);
        list.forEach(function (id) { if (!active[id]) { active[id] = 1; nActive++; } });
        if (++tapLogs <= 12) log('Widget: touch down ' + n + ' now ' + nActive + ' fingers, first at ' + Math.round(a[3].readFloat()) + ',' + Math.round(a[4].readFloat()) + ' -> design ' + Math.round(a[3].readFloat() / scl.x) + ',' + Math.round(a[4].readFloat() / scl.y) + (btn && pos ? ' button ' + pos.x + ',' + pos.y : ' (no button)'));
        if (!panel && qrect && nActive === 1 && inQ(a[3].readFloat() / scl.x, a[4].readFloat() / scl.y)) { qdrag = true; a[1] = ptr(0); return; }
        if (!panel && btn && pos && nActive === 1) {
          var x0 = a[3].readFloat() / scl.x, y0 = a[4].readFloat() / scl.y;
          if (Math.abs(x0 - pos.x) <= btn.r && Math.abs(y0 - pos.y) <= btn.r) { drag = { dx: pos.x - x0, dy: pos.y - y0, sx: x0, sy: y0, moved: false }; a[1] = ptr(0); return; }
        }
        if (panel || swallowAll) a[1] = ptr(0);   // swallow: the game does not see taps used by the panel
      } catch (e) { log('Widget: touch error: ' + e); }
    } });
    function endTouch(a, cancel) {
      try {
        var n = a[1].toInt32(); if (n < 1) return;
        var hadPanel = !!panel, x = a[3].readFloat() / scl.x, y = a[4].readFloat() / scl.y;
        ids([a[1], a[2]]).forEach(function (id) { if (active[id]) { delete active[id]; nActive--; } });
        if (qdrag) {
          qdrag = false; a[1] = ptr(0);
          if (nActive <= 0) { nActive = 0; active = {}; fired = false; swallowAll = false; }
          if (!cancel && inQ(x, y)) { try { log('Quick party (in game): ' + cmd.quick()); } catch (e) { log('Quick party failed: ' + e); } }
          return;
        }
        if (drag) {
          var d = drag; drag = null; a[1] = ptr(0);
          if (nActive <= 0) { nActive = 0; active = {}; fired = false; swallowAll = false; }
          if (!cancel) { if (!d.moved) openPanel(); else savePos(); }
          return;
        }
        if (panel && !swallowAll && !cancel) panelTap(x, y);
        if (panel || hadPanel || swallowAll) a[1] = ptr(0);
        if (nActive <= 0) { nActive = 0; active = {}; fired = false; swallowAll = false; }
      } catch (e) { log('Widget: touch end error: ' + e); }
    }
    if (tEnd) Interceptor.attach(tEnd, { onEnter: function (a) { endTouch(a, false); } });
    if (tCancel) Interceptor.attach(tCancel, { onEnter: function (a) { endTouch(a, true); } });
    if (baseVis) Interceptor.attach(baseVis, { onEnter: function (a) {
      try {
        if (!curScene || String(curScene) !== String(a[0])) { if (panel) panelReopen = true; removePanelNodes(); }   // the panel belongs to the old screen: take it down, then put it back on the new one
        curScene = a[0]; curName = className(a[0]);   // (a finger that is down on the moogle keeps its drag: the bot opens menus all the time)
        var sk = String(a[0]);
        for (var sj = sceneStack.length - 1; sj >= 0; sj--) if (sceneStack[sj].k === sk) sceneStack.splice(sj, 1);
        sceneStack.push({ k: sk, p: a[0], n: curName });
        if (sceneStack.length > 12) sceneStack.shift();
        if (++sceneLogs <= 200) log('Widget: scene visible ' + curName);
        var key = String(a[0]);
        if (DENY[curName]) { removeBtn(); btn = null; pend = null; panelReopen = false; }
        else { pend = { key: key, at: Date.now() }; }   // keep the old button until the new one is placed (same tick: no flicker, no copies)
      } catch (e) { }
    } });
    log('Brand: title screen hook installed');
  }
  var MASCOT_PNG = "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";
  var QUICK_PNG = "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";

  function start() {
    game = Process.findModuleByName('libgame.so');
    if (!game) { setTimeout(start, OPT.land ? 10 : 500); return; }
    try { if (OPT.sum) summonHooks(); } catch (e) { log('Skip summon install failed: ' + e); }
    try { if (OPT.auto) battleDefaults(); } catch (e) { log('Auto battle install failed: ' + e); }
    try { if (OPT.rep) repeatHooks(); } catch (e) { log('Quest repeat install failed: ' + e); }
    try { if (OPT.res) resultHooks(); } catch (e) { log('Skip results install failed: ' + e); }
    try { if (OPT.vsum) visionSummonHooks(); } catch (e) { log('Skip Vision summon install failed: ' + e); }
    try {
      if (OPT.vis) autoSkip('Skip Vision', '_ZN22VisionCardMixPlayScene14onSceneVisibleEv', '_ZN22VisionCardMixPlayScene16onSceneInvisibleEv',
        '_ZN22VisionCardMixPlayScene15onTapSkipButtonEv', 45);
    } catch (e) { log('Skip Vision install failed: ' + e); }
    try { if (OPT.land) landHooks(); } catch (e) { log('Landscape install failed: ' + e); }
    try { if (OPT.quick) quickHooks(); } catch (e) { log('Quick party install failed: ' + e); }
    try { if (OPT.rprobe) rewardProbe(); } catch (e) { log('Reward probe install failed: ' + e); }
    try { if (OPT.vortex) vortexHooks(); } catch (e) { log('Vortex install failed: ' + e); }
    try { if (OPT.guide) guideHooks(); } catch (e) { log('Monster Guide install failed: ' + e); }
    try { if (OPT.bot) botHooks(); } catch (e) { log('Battle bot test install failed: ' + e); }
    try { brandHooks(); } catch (e) { log('Brand install failed: ' + e); }
    try { if (OPT.label) labelHooks(); } catch (e) { log('Unit label install failed: ' + e); }
  }
  // When the script is already running (kept in the game since the last start, with the choices saved back then) and the app sends
  // new Patch List choices ("opt"), start the hooks of every feature that is newly on. Features turned off stay as they are until
  // the next game start. Cloud sync and Landscape also need a restart.
  globalThis.__kupoApply = function (o) {
    var done = [];
    try {
      if (!o) return 'none';
      ['onote', 'dlog', 'mshow', 'live', 'botopen', 'ver', 'speeds', 'name', 'star'].forEach(function (k) { if (o[k] !== undefined) OPT[k] = o[k]; });
      var inst = [['rprobe', rewardProbe], ['sum', summonHooks], ['auto', battleDefaults], ['rep', repeatHooks], ['res', resultHooks], ['vsum', visionSummonHooks],
        ['quick', quickHooks], ['vortex', vortexHooks], ['guide', guideHooks], ['bot', botHooks]];
      inst.forEach(function (x) {
        if (o[x[0]] && !OPT[x[0]]) {
          OPT[x[0]] = 1;
          try { x[1](); done.push(x[0]); } catch (e) { log('Late start of ' + x[0] + ' failed: ' + e); }
        }
      });
      if (o.vis && !OPT.vis) {
        OPT.vis = 1;
        try { autoSkip('Skip Vision', '_ZN22VisionCardMixPlayScene14onSceneVisibleEv', '_ZN22VisionCardMixPlayScene16onSceneInvisibleEv', '_ZN22VisionCardMixPlayScene15onTapSkipButtonEv', 45); done.push('vis'); } catch (e) { log('Late start of vis failed: ' + e); }
      }
      if ((o.cloud && !OPT.cloud) || (o.land && !OPT.land)) log('Cloud sync / Landscape choices apply at the next game start');
      log('Patch List choices applied late: ' + (done.length ? done.join(',') : 'nothing new'));
      return 'ok applied ' + done.join(',');
    } catch (e) { log('Apply failed: ' + e); return 'error ' + e; }
  };
  // Save access (read/replace the phone save when the app asks) is always installed, so Open from game and Apply to game work
  // without any switch. It only answers app commands and does nothing by itself. Auto cloud sync stays behind OPT.cloud (the app's own check).
  try { cloudStart(); } catch (e) { log('Save access install failed: ' + e); }
  start();
})();
