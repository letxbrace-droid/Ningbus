#!/usr/bin/env bash
#
# Le banc d'essai, dans un vrai interpréteur.
#
# L'action de l'émulateur exécute le champ « script » **ligne par ligne**,
# chacune dans son propre `sh -c`. Trois conséquences, toutes rencontrées :
#
#  - une continuation de ligne y laisse l'interpréteur attendre la suite sur
#    son entrée, et le tour s'arrête au garde-fou de quarante-cinq minutes ;
#  - les variables ne survivent pas d'une ligne à la suivante, si bien qu'un
#    code de sortie mis de côté ne vaut rien ;
#  - `set +e` ne protège que sa propre ligne, donc rien.
#
# Un script appelé en une seule ligne rend tout cela possible : relevés pris
# quoi qu'il arrive, et code de sortie des essais rendu fidèlement.
set -u

cd "$(dirname "$0")"

# Arbitre est *installé*, le simulateur sera *instrumenté* : le service
# d'accessibilité tourne alors comme sur le téléphone du chauffeur.
./gradlew :app:installDebug :simulateur:installDebug || exit $?

./gradlew :simulateur:connectedDebugAndroidTest
essais=$?

# Les relevés sont pris ici, dans la session de l'émulateur : une fois
# l'action terminée, la machine virtuelle n'existe plus et `adb` n'a plus
# personne à qui parler. Chacun est borné dans le temps — une commande adb
# qui se bloque coûterait le tour entier, ce qui est exactement ce qui est
# arrivé.
releve() {
  titre=$1
  shift
  echo "::group::$titre"
  timeout 120 "$@" || echo "(relevé interrompu ou vide)"
  echo "::endgroup::"
}

releve "dumpsys accessibility" adb shell dumpsys accessibility
releve "journal d'Arbitre" adb shell run-as fr.ningbus.arbitre \
  cat /data/data/fr.ningbus.arbitre/shared_prefs/journal.xml
releve "logcat" adb logcat -d -v time -s Arbitre:V \
  AccessibilityManagerService:V AndroidRuntime:E ActivityManager:E

exit $essais
