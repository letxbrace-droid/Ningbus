/* Cohabitation des deux PWA sur le même domaine :
   ouvrir l'app RATP ne doit plus détruire le cache hors ligne de I&N Masse. */
const { chromium } = require('playwright-core');
const CTX = require('../contexte');
const B = 'http://127.0.0.1:8766';

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();

  // 1. l'app Masse s'installe et remplit son cache
  await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' });
  await p.evaluate(() => navigator.serviceWorker.ready);
  await p.waitForTimeout(1500);
  const avant = await p.evaluate(() => caches.keys());
  console.log('  caches après ouverture de Masse : ' + JSON.stringify(avant));

  // 2. l'app RATP s'installe à son tour (scope /Ningbus/, englobe /Ningbus/masse/)
  await p.goto(B + '/Ningbus/defauts.html', { waitUntil: 'load' });
  await p.evaluate(() => navigator.serviceWorker.ready);
  await p.waitForTimeout(2500);
  const apres = await p.evaluate(() => caches.keys());
  console.log('  caches après ouverture de RATP  : ' + JSON.stringify(apres));

  const masseVivant = apres.some(k => k.startsWith('inrun-masse-shell'));
  const ratpVivant = apres.some(k => k.startsWith('ningbus-defauts-'));
  console.log('  ' + (masseVivant ? '✓' : '✗') + ' le cache de I&N Masse survit à l\'activation du worker RATP');
  console.log('  ' + (ratpVivant ? '✓' : '✗') + ' le cache RATP est bien créé');

  // 3. les deux apps s'ouvrent hors ligne
  await c.setOffline(true);
  await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' }).catch(() => {});
  const tMasse = await p.title().catch(() => '');
  const stations = await p.evaluate(() => document.querySelectorAll('.station').length).catch(() => 0);
  console.log('  ' + (stations === 21 ? '✓' : '✗') + ' Masse hors ligne : « ' + tMasse + ' », ' + stations + ' stations');

  await p.goto(B + '/Ningbus/defauts.html', { waitUntil: 'load' }).catch(() => {});
  const tRatp = await p.title().catch(() => '');
  console.log('  ' + (/RATP|Atelier|Flotte|Suivi/i.test(tRatp) ? '✓' : '✗') + ' RATP hors ligne : « ' + tRatp + ' »');

  // 4. quel worker contrôle la page Masse ?
  await c.setOffline(false);
  await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' });
  await p.waitForTimeout(800);
  const ctrl = await p.evaluate(() => navigator.serviceWorker.controller ? navigator.serviceWorker.controller.scriptURL : null);
  console.log('  ' + (/masse\/sw\.js$/.test(ctrl || '') ? '✓' : '✗') + ' page Masse contrôlée par : ' + ctrl);

  await br.close();
})();
