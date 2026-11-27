(function () {
  var X = window.FFBEExport;
  if (!X) return;
  var rel = '', busy = false;
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function msg(t) { var m = $('ffx_msg'); if (m) m.textContent = t || ''; }
  function cur() { try { $('ffx_cur').textContent = 'Current folder: ' + X.current(); } catch (e) { $('ffx_cur').textContent = 'Current folder: unknown'; } }
  function show() {
    var names = [];
    try { names = JSON.parse(X.list(rel)); } catch (e) {}
    var h = '<div class="desc">Browsing: ' + esc(rel || 'Internal storage') + '</div>';
    if (rel) h += '<div style="margin:6px 0"><button class="secondary" data-a="up">Back</button></div>';
    for (var i = 0; i < names.length; i++) h += '<div style="margin:4px 0"><button class="secondary" data-a="in" data-n="' + esc(names[i]) + '">' + esc(names[i]) + '</button></div>';
    if (!names.length) h += '<div class="desc">No sub-folders here.</div>';
    if (rel) {
      h += '<div style="margin-top:8px"><input type="text" id="ffx_new" placeholder="New folder name" style="width:100%"></div>';
      h += '<div style="display:flex;gap:8px;margin-top:8px"><button class="secondary" data-a="mk">Create folder</button><button data-a="use">Use this folder</button></div>';
    }
    $('ffx_list').innerHTML = h;
  }
  function click(e) {
    var b = e.target; if (!b || !b.getAttribute) return;
    var a = b.getAttribute('data-a'); if (!a) return;
    if (a === 'in') { rel = rel ? rel + '/' + b.getAttribute('data-n') : b.getAttribute('data-n'); msg(''); show(); }
    else if (a === 'up') { var k = rel.lastIndexOf('/'); rel = k < 0 ? '' : rel.substring(0, k); msg(''); show(); }
    else if (a === 'mk') { var r = X.mkdir(rel, $('ffx_new').value); msg(r === 'OK' ? 'Folder created.' : r.replace(/^ERR /, '')); show(); }
    else if (a === 'use') { var u = X.choose(rel); if (u.indexOf('OK') === 0) { msg('Saved. Exports, logs and Import now use this folder.'); cur(); $('ffx_box').style.display = 'none'; } else msg(u.replace(/^ERR /, '')); }
  }
  function build() {
    var btn = document.querySelector('button[onclick*="importEra"]');
    var card = btn && btn.closest ? btn.closest('.card') : null;
    if (!card || $('ffx')) return;
    var html = '<div class="card" id="ffx"><h2>Export &amp; Log Folder</h2>'
      + '<div class="desc">Where save exports and logs go, and where Import looks. Pick a folder inside Download or Documents. A sync app (Drive, Dropbox, Syncthing) can sync that folder.</div>'
      + '<div class="desc" id="ffx_cur" style="margin-top:8px"></div>'
      + '<div style="display:flex;gap:8px;margin:8px 0"><button class="secondary" id="ffx_open">Choose folder</button><button class="secondary" id="ffx_reset">Use Downloads</button></div>'
      + '<div id="ffx_box" style="display:none"><div id="ffx_list"></div></div>'
      + '<div class="desc" id="ffx_msg"></div></div>';
    card.insertAdjacentHTML('afterend', html);
    cur();
    $('ffx_open').onclick = function () { var bx = $('ffx_box'); var open = bx.style.display === 'none'; bx.style.display = open ? 'block' : 'none'; if (open) { rel = ''; msg(''); show(); } };
    $('ffx_reset').onclick = function () { var r = X.reset(); msg(r.indexOf('OK') === 0 ? 'Back to the Downloads folder.' : r); cur(); };
    $('ffx_list').addEventListener('click', click);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build); else build();
})();
