/* Relève TOUTES les couleurs résolues par le navigateur, avant et après la
   migration. Une migration de jetons ne doit rien changer aux pixels, sauf
   là où on a corrigé une couleur orpheline : ce script est ce qui permet
   de le prouver au lieu de l'affirmer.
   usage : node couleurs.js <fichier-de-sortie.json> */
const { chromium } = require('playwright-core');
const fs = require('fs');
const CTX = require('../contexte');
const sortie = process.argv[2];

(async () => {
  const br = await chromium.launch({
    executablePath: CTX.CHROMIUM,
    args: ['--no-sandbox'] });
  const p = await (await br.newContext({ viewport:{width:420,height:900} })).newPage();
  const errs = [];
  p.on('pageerror', e => errs.push(e.message));
  await p.goto('http://127.0.0.1:8765/index.html', { waitUntil:'networkidle' });
  await p.waitForTimeout(1200);

  const releve = await p.evaluate(() => {
    const PROPS = ['color','backgroundColor','borderTopColor','borderBottomColor',
                   'borderLeftColor','borderRightColor','boxShadow','textShadow',
                   'backgroundImage','fill','stroke','outlineColor'];
    // 1. tout ce que le document rend réellement
    const dom = [];
    document.querySelectorAll('*').forEach((el, i) => {
      const cs = getComputedStyle(el);
      const v = {};
      for (const k of PROPS) { const s = cs[k]; if (s && s !== 'none') v[k] = s; }
      dom.push([i, el.tagName + (el.id ? '#'+el.id : '') +
                   (el.className && typeof el.className === 'string'
                      ? '.'+el.className.trim().split(/\s+/).join('.') : ''), v]);
    });
    // 2. les états qui n'existent qu'avec une classe : on les fabrique
    const ETATS = ['arow','arow good','arow warn','arow tip',
                   'verdict','verdict ok','verdict warn','verdict stop',
                   'coach-ic','coach-ic t-ok','coach-ic t-alerte','coach-ic t-calme',
                   'ctoast','ctoast show','prog-sel','toggle','toggle on',
                   'gauge','tgt','tgt focus','maison-cta','planbar','sessbar'];
    const hote = document.createElement('div');
    document.body.appendChild(hote);
    const sondes = {};
    for (const cls of ETATS) {
      const d = document.createElement('div');
      d.className = cls; hote.appendChild(d);
      const cs = getComputedStyle(d), v = {};
      for (const k of PROPS) { const s = cs[k]; if (s && s !== 'none') v[k] = s; }
      // le petit rond du coach et la pastille du toggle
      for (const enfant of ['.ic','.knob']) {
        const e = document.createElement('div');
        e.className = enfant.slice(1); d.appendChild(e);
        const c2 = getComputedStyle(e);
        if (c2.backgroundColor && c2.backgroundColor !== 'rgba(0, 0, 0, 0)')
          v['enfant'+enfant] = c2.backgroundColor;
      }
      sondes[cls] = v; hote.removeChild(d);
    }
    document.body.removeChild(hote);
    // 3. les jetons eux-mêmes, tels que le navigateur les résout
    const cs = getComputedStyle(document.documentElement);
    const jetons = {};
    // la feuille distante des polices lève SecurityError : on l'ignore
    for (const feuille of document.styleSheets) {
      let regles; try { regles = feuille.cssRules; } catch (e) { continue; }
      for (const f of regles)
        if (f.selectorText === ':root')
          for (const n of f.style) jetons[n] = cs.getPropertyValue(n).trim();
    }
    return { dom, sondes, jetons };
  });

  releve.erreurs = errs;
  fs.writeFileSync(sortie, JSON.stringify(releve, null, 1));
  console.log(`${releve.dom.length} éléments, ${Object.keys(releve.sondes).length} sondes, ` +
              `${Object.keys(releve.jetons).length} jetons — erreurs JS : ${errs.length || 'aucune'}`);
  await br.close();
})();
