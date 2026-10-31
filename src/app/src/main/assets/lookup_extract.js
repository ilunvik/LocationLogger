// LocationLogger 1.34: runs inside the lookup WebView (1881.no / gulesider.no) and returns, as JSON,
// whether the page is a Cloudflare challenge, still loading, or done, plus the name of the entry whose
// phone number is exactly the number searched for. the argument is the 8-digit number (placeholder at the end).
(function (k) {
  function key(t) {
    var d = String(t || '').replace(/[^0-9+]/g, '');
    if (d.indexOf('+47') === 0) d = d.slice(3); else if (d.indexOf('0047') === 0) d = d.slice(4);
    return d.replace(/\+/g, '');
  }
  function clean(s) { return String(s || '').replace(/\s+/g, ' ').trim(); }
  var title = document.title || '';
  var challenge = /just a moment|et øyeblikk|checking your browser|attention required|verify you are human/i.test(title) ||
    !!document.querySelector('#challenge-form, #challenge-stage, #cf-challenge-running, .cf-turnstile, #turnstile-wrapper, #cf-wrapper');
  if (challenge) return JSON.stringify({ s: 'challenge', t: title, u: location.href });
  var hits = [];
  function add(name, how) {
    name = clean(name);
    if (name.length < 2 || name.length > 120 || /gulesider|1881/i.test(name)) return;
    for (var i = 0; i < hits.length; i++) if (hits[i].n === name) return;
    hits.push({ n: name, h: how });
  }
  // 1) JSON-LD (gulesider result lists, 1881 detail pages): any object with a matching telephone
  function walk(o, depth) {
    if (!o || typeof o !== 'object' || depth > 12) return;
    if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i], depth + 1); return; }
    if (o.name && o.telephone) {
      var tels = [].concat(o.telephone);
      for (var j = 0; j < tels.length; j++) if (key(tels[j]) === k) { add(o.name, 'ld:' + (o['@type'] || '')); break; }
    }
    for (var p in o) if (Object.prototype.hasOwnProperty.call(o, p) && o[p] && typeof o[p] === 'object') walk(o[p], depth + 1);
  }
  var lds = document.querySelectorAll('script[type="application/ld+json"]');
  for (var a = 0; a < lds.length; a++) { try { walk(JSON.parse(lds[a].textContent), 0); } catch (e) {} }
  // 2) 1881 result boxes: name in .listing-name, number as a tel: link in the same box
  var boxes = document.querySelectorAll('.listing');
  var named = [];
  for (var b = 0; b < boxes.length; b++) {
    var nm = boxes[b].querySelector('.listing-name a') || boxes[b].querySelector('.listing-name');
    if (!nm) continue;
    named.push(nm);
    var tl = boxes[b].querySelectorAll('a[href^="tel:"]');
    for (var c = 0; c < tl.length; c++) if (key(tl[c].getAttribute('href').slice(4)) === k) { add(nm.textContent, '1881-liste'); break; }
  }
  // 3) 1881 detail page (a single hit redirects there): h1.details-name + a matching tel: link
  var h1 = document.querySelector('h1.details-name');
  if (h1) {
    var dl = document.querySelectorAll('a[href^="tel:"]');
    for (var e = 0; e < dl.length; e++) if (key(dl[e].getAttribute('href').slice(4)) === k) { add(h1.textContent, '1881-detalj'); break; }
  }
  // 4) Generic result cards (gulesider without JSON-LD): a heading in a card whose text contains the number
  if (!hits.length && /gulesider/i.test(location.hostname)) {
    var hs = document.querySelectorAll('main h2, main h3, article h2, article h3');
    for (var f = 0; f < hs.length && hits.length < 3; f++) {
      var card = hs[f].closest('article, li, section, div[id]') || hs[f].parentElement;
      if (card && key(card.textContent).indexOf(k) >= 0) add(hs[f].textContent, 'kort');
    }
  }
  var text = document.body ? (document.body.innerText || '') : '';
  var none = /ingen treff|ga dessverre ingen|fant ingen|0 treff|ingen resultater|no results/i.test(text);
  // A 1881 person may be listed without a tel: link: accept a single named box only
  if (!hits.length && named.length === 1 && !none && /1881/.test(location.hostname)) add(named[0].textContent, '1881-eneste');
  return JSON.stringify({ s: document.readyState === 'loading' ? 'loading' : 'done', t: title, u: location.href,
    n: hits.length ? hits[0].n : null, c: hits.length, h: hits.length ? hits[0].h : null, none: none,
    ld: lds.length, boxes: boxes.length });
})('__K__');
