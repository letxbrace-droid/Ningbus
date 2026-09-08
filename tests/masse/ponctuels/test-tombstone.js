/* Retrait de la PWA RATP : un téléphone qui l'avait installée doit se
   nettoyer tout seul, et l'app Masse ne doit pas être touchée. */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('../contexte');
const D = require('path').join(CTX.RACINE, 'docs');
const OLD = require('path').join(CTX.SORTIE, 'old');
const B = 'http://127.0.0.1:8766';

const tombstone = fs.readFileSync(D + '/defauts-sw.js', 'utf8');

(async () => {
  const br = await chromium.launch({ executablePath: CTX.CHROMIUM, args: ['--no-sandbox'] });
  const c = await br.newContext({ viewport: { width: 390, height: 844 } });
  const p = await c.newPage();
  try {
    // --- état « avant » : l'app RATP est publiée et installée sur le téléphone
    const RATP = ['defauts-sw.js', 'defauts.html', 'defauts-manifest.json', 'logo-ratpcap.png',
                  'icons/defauts-192.png', 'icons/defauts-512.png'];
    fs.mkdirSync(D + '/icons', { recursive: true });
    RATP.forEach(f => fs.writeFileSync(D + '/' + f, fs.readFileSync(OLD + '/' + f)));

    await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' });
    await p.evaluate(() => navigator.serviceWorker.ready);
    await p.waitForTimeout(1200);

    await p.goto(B + '/Ningbus/defauts.html', { waitUntil: 'load' });
    await p.evaluate(() => navigator.serviceWorker.ready);
    await p.waitForTimeout(2000);
    console.log('  [étape] app RATP installée');
    const avant = await p.evaluate(async () => ({
      caches: await caches.keys(),
      regs: (await navigator.serviceWorker.getRegistrations()).map(r => r.scope)
    }));
    console.log('  avant : caches ' + JSON.stringify(avant.caches));
    console.log('          workers ' + JSON.stringify(avant.regs));

    // --- publication du retrait : page supprimée, worker remplacé par la pierre tombale
    RATP.filter(f => f !== 'defauts-sw.js').forEach(f => fs.unlinkSync(D + '/' + f));
    fs.rmdirSync(D + '/icons');
    fs.writeFileSync(D + '/defauts-sw.js', tombstone);

    await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' });
    await p.evaluate(async () => {
      const regs = await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map(r => r.update().catch(() => {})));   // le navigateur fait ça tout seul
    });
    await p.waitForTimeout(4000);

    console.log('  [étape] retrait publié');
    const apres = await p.evaluate(async () => ({
      caches: await caches.keys(),
      regs: (await navigator.serviceWorker.getRegistrations()).map(r => r.scope)
    }));
    console.log('  après : caches ' + JSON.stringify(apres.caches));
    console.log('          workers ' + JSON.stringify(apres.regs));

    const ratpParti = !apres.regs.some(s => /\/Ningbus\/$/.test(s)) && !apres.caches.some(k => k.startsWith('ningbus-defauts-'));
    const masseIntact = apres.caches.some(k => k.startsWith('inrun-masse-shell')) && apres.regs.some(s => /\/masse\/$/.test(s));
    console.log('  ' + (ratpParti ? '✓' : '✗') + ' worker et caches RATP nettoyés du téléphone');
    console.log('  ' + (masseIntact ? '✓' : '✗') + ' worker et cache de I&N Masse intacts');

    // --- l'app Masse marche toujours, y compris hors ligne
    await c.setOffline(true);
    await p.goto(B + '/Ningbus/masse/index.html', { waitUntil: 'load' }).catch(() => {});
    const stations = await p.evaluate(() => document.querySelectorAll('.station').length).catch(() => 0);
    console.log('  ' + (stations === 25 ? '✓' : '✗') + ' Masse hors ligne : ' + stations + ' stations');
    await c.setOffline(false);

    // --- l'ancienne URL RATP ne répond plus
    const r = await p.request.get(B + '/Ningbus/defauts.html');
    console.log('  ' + (r.status() === 404 ? '✓' : '✗') + ' /Ningbus/defauts.html → ' + r.status());
  } finally {
    fs.writeFileSync(D + '/defauts-sw.js', tombstone);
    ['defauts.html', 'defauts-manifest.json', 'logo-ratpcap.png',
     'icons/defauts-192.png', 'icons/defauts-512.png']
      .forEach(f => { if (fs.existsSync(D + '/' + f)) fs.unlinkSync(D + '/' + f); });
    if (fs.existsSync(D + '/icons')) fs.rmdirSync(D + '/icons');
    await br.close();
  }
})();
