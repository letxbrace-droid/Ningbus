/* Les quatre écrans de l'app, à la taille d'un téléphone, plus un contrôle
   de débordement horizontal : une échelle de tailles qui casse une mise en
   page se voit là, et nulle part dans les tests.
   usage : node appshot.js <préfixe> */
const { chromium } = require('playwright-core');
const CTX = require('../contexte');
const D = CTX.SORTIE + '/';
const P = process.argv[2] || 'app';

(async () => {
  const br = await chromium.launch({
    executablePath: CTX.CHROMIUM,
    args: ['--no-sandbox'] });
  const ctx = await br.newContext({ viewport:{width:412,height:900}, deviceScaleFactor:2 });
  const p = await ctx.newPage();
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://127.0.0.1:8765/index.html', { waitUntil:'networkidle' });
  await p.waitForTimeout(1400);

  const onglets = await p.evaluate(() =>
    [...document.querySelectorAll('.bn')].map(b => b.textContent.trim().split('\n').pop().trim()));

  const bilan = [];
  for (let i = 0; i < onglets.length; i++) {
    await p.evaluate(k => document.querySelectorAll('.bn')[k].click(), i);
    await p.waitForTimeout(700);
    const nom = onglets[i].toLowerCase().replace(/[^a-z]/g, '');
    await p.screenshot({ path:`${D}/${P}-${i}-${nom}.png`, fullPage:false });
    const m = await p.evaluate(() => ({
      deborde: document.documentElement.scrollWidth > window.innerWidth,
      largeur: document.documentElement.scrollWidth,
      hauteur: document.body.scrollHeight,
      /* un texte coupé net est le symptôme d'une taille montée d'un cran */
      tronques: [...document.querySelectorAll('*')].filter(e => {
        const cs = getComputedStyle(e);
        return cs.overflow === 'hidden' && e.scrollWidth > e.clientWidth + 1
               && e.children.length === 0 && e.textContent.trim();
      }).length,
    }));
    bilan.push(`${onglets[i]}: ${m.largeur}px ${m.deborde ? 'DÉBORDE' : 'ok'} · h ${m.hauteur} · ${m.tronques} texte(s) tronqué(s)`);
  }
  console.log(bilan.join('\n'));
  console.log('erreurs JS :', errs.length ? errs.join(' | ') : 'aucune');
  await br.close();
})();
