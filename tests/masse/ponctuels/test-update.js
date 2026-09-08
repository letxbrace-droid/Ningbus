/* Vérifie le cycle de mise à jour : nouveau sw.js → bandeau → « Recharger »
   → le nouveau worker prend la main et la page se recharge une seule fois. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('../contexte');
const SW = CTX.SW;
const BASE = CTX.BASE;
const orig = fs.readFileSync(SW, 'utf8');

(async () => {
  const b = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await b.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  let reloads = 0;
  p.on('framenavigated', f => { if (f === p.mainFrame()) reloads++; });
  try {
    await p.goto(BASE + '/index.html', { waitUntil: 'load' });
    await p.evaluate(() => navigator.serviceWorker.ready);
    const v1 = await p.evaluate(async () => (await caches.keys()).filter(k => /shell/.test(k))[0]);

    // nouvelle version publiée
    fs.writeFileSync(SW, orig.replace("var VERSION = 'v4'", "var VERSION = 'v5'"));

    await p.reload({ waitUntil: 'load' });
    await p.waitForSelector('#swToast.show', { timeout: 15000 });
    console.log('  ✓ bandeau « Recharger » affiché sur nouvelle version');

    const before = reloads;
    await p.click('#swToast button');
    await p.waitForTimeout(4000);
    await p.waitForLoadState('domcontentloaded');
    const v2 = await p.evaluate(async () => (await caches.keys()).filter(k => /shell/.test(k))[0]);
    const ctrl = await p.evaluate(() => !!navigator.serviceWorker.controller);
    console.log('  ' + (v2 === 'inrun-masse-shell-v5' ? '✓' : '✗') + ' cache basculé : ' + v1 + ' → ' + v2);
    console.log('  ' + (reloads - before === 1 ? '✓' : '✗') + ' rechargement unique de la page (' + (reloads - before) + ')');
    console.log('  ' + (ctrl ? '✓' : '✗') + ' page contrôlée par le nouveau worker');
    const stale = await p.evaluate(async () => (await caches.keys()).filter(k => /v3/.test(k)).length);
    console.log('  ' + (stale === 0 ? '✓' : '✗') + ' anciens caches supprimés (' + stale + ' restant)');
  } finally {
    fs.writeFileSync(SW, orig);
    await b.close();
  }
})();
