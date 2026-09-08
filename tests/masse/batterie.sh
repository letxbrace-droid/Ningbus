#!/bin/bash
# La batterie complète. Elle démarre son propre serveur si besoin, le coupe
# en sortant, et écrit le détail des échecs dans resultat.txt.
#
#   bash batterie.sh            toute la suite
#   bash batterie.sh test-x.js  un seul fichier
set -u
cd "$(dirname "$0")"
PORT=${PORT_TEST:-8765}
mkdir -p .sortie

# le serveur : on ne le démarre que s'il n'écoute pas déjà
NOTRE=0
if ! curl -s -o /dev/null "http://127.0.0.1:$PORT/index.html"; then
  python3 serveur.py "$PORT" & SRV=$!; NOTRE=1
  for _ in $(seq 20); do
    curl -s -o /dev/null "http://127.0.0.1:$PORT/index.html" && break; sleep .3
  done
fi
nettoyer(){ [ "$NOTRE" = 1 ] && kill $SRV 2>/dev/null; }
trap nettoyer EXIT

FICHIERS="${*:-$(ls test.js test-*.js 2>/dev/null)}"
TOT=0; FAI=0
: > resultat.txt
for f in $FICHIERS; do
  out=$(node "$f" 2>&1)
  p=$(echo "$out" | grep -o '=== PASS ([0-9]*)' | grep -o '[0-9]*'); p=${p:-0}
  b=$(echo "$out" | grep -o '=== FAIL ([0-9]*)' | grep -o '[0-9]*'); b=${b:-0}
  TOT=$((TOT+p)); FAI=$((FAI+b))
  # 0 assertion = le test a planté avant d'en produire une. Sans ce
  # garde-fou un fichier cassé passe pour un succès — c'est ce qui est
  # arrivé la première fois que la suite a tourné depuis le dépôt.
  if [ "$p" = "0" ] && [ "$b" = "0" ]; then
    echo "### $f : AUCUNE ASSERTION — le test a planté" | tee -a resultat.txt
    echo "$out" | head -8 | tee -a resultat.txt
    FAI=$((FAI+1)); continue
  fi
  if [ "$b" != "0" ]; then
    echo "### $f : $p ok / $b KO" | tee -a resultat.txt
    echo "$out" | sed -n '/=== FAIL/,$p' | head -14 | tee -a resultat.txt
  else
    echo "$f : $p ok" | tee -a resultat.txt
  fi
done
echo "-------" | tee -a resultat.txt
echo "TOTAL $TOT ok / $FAI KO" | tee -a resultat.txt
[ "$FAI" = 0 ]
