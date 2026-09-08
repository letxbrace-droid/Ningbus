# -*- coding: utf-8 -*-
"""Compare deux relevés de couleurs (avant / après migration).

Le contrat : une migration de jetons ne change AUCUN pixel, sauf aux
endroits où l'on a corrigé une couleur orpheline. Ce script liste tous
les écarts ; c'est à la lecture de cette liste que la migration se valide,
pas à l'œil.
    usage : python3 diff_couleurs.py avant.json apres.json
"""
import json, sys, re, collections

a = json.load(open(sys.argv[1], encoding='utf-8'))
b = json.load(open(sys.argv[2], encoding='utf-8'))

def court(v, n=88):
    v = ' '.join(str(v).split())
    return v if len(v) <= n else v[:n-1] + '…'

# Le navigateur ne rend pas color-mix() dans la même notation que rgba() :
# « color(srgb 0.686275 0.980392 0.00392157 / .12) » et
# « rgba(175, 250, 1, 0.12) » sont le MÊME pixel. On ramène donc toute
# couleur à des entiers 0-255 + alpha avant de comparer, sinon on lit un
# écart là où il n'y a qu'un changement d'écriture.
_SRGB = re.compile(r'color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)(?:\s*/\s*([\d.]+))?\s*\)')
def canon(v):
    if not isinstance(v, str): return v
    def r(m):
        c = [round(float(m.group(i)) * 255) for i in (1, 2, 3)]
        a = m.group(4)
        return (f"rgba({c[0]}, {c[1]}, {c[2]}, {float(a):g})" if a is not None
                else f"rgb({c[0]}, {c[1]}, {c[2]})")
    v = _SRGB.sub(r, v)
    # rgba(x, y, z, 1) et rgb(x, y, z) désignent la même couleur
    return re.sub(r'rgba\((\d+), (\d+), (\d+), 1\)', r'rgb(\1, \2, \3)', v)

# ---------------------------------------------------------------- le DOM
da = {i: (nom, v) for i, nom, v in a['dom']}
db = {i: (nom, v) for i, nom, v in b['dom']}
if len(da) != len(db):
    print(f"!! le DOM a changé de taille : {len(da)} -> {len(db)}")

ecarts = collections.defaultdict(list)
for i in sorted(set(da) & set(db)):
    na, va = da[i]; nb, vb = db[i]
    if na != nb:
        print(f"!! élément {i} renommé : {na} -> {nb}")
    for k in sorted(set(va) | set(vb)):
        if canon(va.get(k)) != canon(vb.get(k)):
            ecarts[(nb, k, va.get(k), vb.get(k))].append(i)

print(f"ÉLÉMENTS RENDUS : {len(da)} relevés, "
      f"{sum(len(v) for v in ecarts.values())} écarts sur {len(ecarts)} motifs\n")
for (nom, prop, av, ap), idx in sorted(ecarts.items(), key=lambda x: -len(x[1])):
    print(f"  {len(idx):4}×  {court(nom,54)}")
    print(f"        {prop}")
    print(f"        avant {court(av)}")
    print(f"        après {court(ap)}")

# ------------------------------------------------------------- les sondes
print(f"\nÉTATS SONDÉS")
n = 0
for cls in sorted(set(a['sondes']) | set(b['sondes'])):
    va, vb = a['sondes'].get(cls, {}), b['sondes'].get(cls, {})
    for k in sorted(set(va) | set(vb)):
        if canon(va.get(k)) != canon(vb.get(k)):
            n += 1
            print(f"  .{cls}")
            print(f"        {k}")
            print(f"        avant {court(va.get(k))}")
            print(f"        après {court(vb.get(k))}")
if not n: print("  aucun écart")

# ------------------------------------------------------------- les jetons
print(f"\nJETONS :root  ({len(a['jetons'])} -> {len(b['jetons'])})")
sup = sorted(set(a['jetons']) - set(b['jetons']))
add = sorted(set(b['jetons']) - set(a['jetons']))
print(f"  supprimés ({len(sup)}) : {', '.join(sup) or 'aucun'}")
print(f"  ajoutés   ({len(add)}) : {', '.join(add) or 'aucun'}")
chg = [(k, a['jetons'][k], b['jetons'][k]) for k in sorted(set(a['jetons']) & set(b['jetons']))
       if canon(a['jetons'][k]) != canon(b['jetons'][k])]
print(f"  valeur changée ({len(chg)}) :")
for k, x, y in chg: print(f"      {k:16} {x:22} -> {y}")

print(f"\nERREURS JS : avant {a['erreurs'] or 'aucune'} | après {b['erreurs'] or 'aucune'}")
