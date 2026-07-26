#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RADAR DÉCODÉ — Veille automatique de l'actu
--------------------------------------------
Surveille les flux RSS des grands médias, détecte les sujets qui
reviennent chez plusieurs sources (= info qui monte), et envoie une
alerte Telegram avec un post "Décodé" (généré via l'API Gemini) prêt à
copier-coller sur X.

Conçu pour tourner via GitHub Actions (cron) sans serveur.
"""

import os
import re
import json
import html
import hashlib
import time
from collections import defaultdict
from datetime import datetime, timezone

import requests
import feedparser

# ---------------------------------------------------------------------------
# CONFIG
# ---------------------------------------------------------------------------

# Secrets injectés par GitHub Actions (ne JAMAIS écrire les valeurs ici)
TELEGRAM_TOKEN = os.environ.get("TELEGRAM_TOKEN", "")
TELEGRAM_CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "")

# Nombre de sources différentes qui doivent parler d'un même sujet
# pour déclencher une alerte "info qui monte".
SEUIL_ALERTE = 2

# Nombre minimal de mots-clés communs pour considérer deux titres
# comme parlant du même sujet.
SEUIL_SIMILARITE = 2

# Fichier mémoire (évite de renvoyer deux fois la même alerte).
# Persisté entre les runs via le cache GitHub Actions ou un commit.
FICHIER_MEMOIRE = "vus.json"

# Marqueur autour du post généré dans l'alerte Telegram.
CADRE_POST = "━━━ PRÊT À PUBLIER ━━━"

# Flux RSS surveillés. Sources françaises fiables + agences, plus quelques
# médias indépendants (non détenus par un grand groupe industriel/financier).
# Tu peux en ajouter/retirer librement.
FLUX = {
    "France Info":  "https://www.franceinfo.fr/titres.rss",
    "Le Monde":     "https://www.lemonde.fr/rss/une.xml",
    "BFMTV":        "https://www.bfmtv.com/rss/news-24-7/",
    "Le Figaro":    "https://www.lefigaro.fr/rss/figaro_actualites.xml",
    "Libération":   "https://www.liberation.fr/arc/outboundfeeds/rss/?outputType=xml",
    "20 Minutes":   "https://www.20minutes.fr/feeds/rss-une.xml",
    "France 24":    "https://www.france24.com/fr/france/rss",
    "Ouest-France": "https://www.ouest-france.fr/rss/une",
    # Médias indépendants
    "Mediapart":    "https://www.mediapart.fr/articles/feed",
    "Reporterre":   "https://reporterre.net/spip.php?page=backend",
    "Basta!":       "https://basta.media/spip.php?page=backend",
    "Blast":        "https://www.blast-info.fr/rss",
    "StreetPress":  "https://www.streetpress.com/feed",
    "Disclose":     "https://disclose.ngo/fr/feed/",
}

# Mots vides à ignorer dans la détection de sujet.
STOPWORDS = {
    "le", "la", "les", "un", "une", "des", "de", "du", "et", "à", "au", "aux",
    "en", "dans", "sur", "pour", "par", "avec", "sans", "sous", "ce", "cette",
    "ces", "son", "sa", "ses", "leur", "leurs", "qui", "que", "quoi", "dont",
    "où", "est", "sont", "a", "ont", "plus", "moins", "très", "après", "avant",
    "entre", "vers", "chez", "il", "elle", "ils", "elles", "on", "nous", "vous",
    "se", "ne", "pas", "y", "d", "l", "s", "n", "c", "j", "m", "t", "face",
    "selon", "contre", "deux", "trois", "va", "vont", "fait", "font", "être",
}

# ---------------------------------------------------------------------------
# UTILITAIRES
# ---------------------------------------------------------------------------

def nettoyer(texte):
    """Enlève le HTML et normalise."""
    texte = html.unescape(texte or "")
    texte = re.sub(r"<[^>]+>", " ", texte)
    return re.sub(r"\s+", " ", texte).strip()


def mots_cles(titre):
    """Extrait les mots significatifs d'un titre (pour comparer les sujets)."""
    titre = titre.lower()
    mots = re.findall(r"[a-zàâäéèêëïîôöùûüç]{3,}", titre)
    return {m for m in mots if m not in STOPWORDS}


def charger_memoire():
    if os.path.exists(FICHIER_MEMOIRE):
        try:
            with open(FICHIER_MEMOIRE, "r", encoding="utf-8") as f:
                return set(json.load(f))
        except Exception:
            return set()
    return set()


def sauver_memoire(vus):
    # On garde les 500 dernières empreintes pour éviter que le fichier gonfle.
    with open(FICHIER_MEMOIRE, "w", encoding="utf-8") as f:
        json.dump(list(vus)[-500:], f, ensure_ascii=False)


def empreinte(sujet_mots):
    """Signature stable d'un sujet à partir de ses mots-clés triés."""
    base = "-".join(sorted(sujet_mots))
    return hashlib.md5(base.encode("utf-8")).hexdigest()[:12]


# ---------------------------------------------------------------------------
# COLLECTE
# ---------------------------------------------------------------------------

def collecter():
    """Récupère tous les articles récents de tous les flux."""
    articles = []
    for source, url in FLUX.items():
        try:
            flux = feedparser.parse(url)
            for e in flux.entries[:20]:  # 20 derniers par source
                titre = nettoyer(getattr(e, "title", ""))
                lien = getattr(e, "link", "")
                if titre and lien:
                    articles.append({
                        "source": source,
                        "titre": titre,
                        "lien": lien,
                        "mots": mots_cles(titre),
                    })
        except Exception as ex:
            print(f"[!] Erreur flux {source}: {ex}")
        time.sleep(0.3)  # politesse envers les serveurs
    print(f"[i] {len(articles)} articles collectés sur {len(FLUX)} sources.")
    return articles


# ---------------------------------------------------------------------------
# DÉTECTION DES SUJETS QUI MONTENT
# ---------------------------------------------------------------------------

def detecter_sujets(articles):
    """
    Regroupe les articles qui parlent du même sujet (mots-clés communs)
    et ne garde que ceux couverts par >= SEUIL_ALERTE sources différentes.
    """
    groupes = []

    for art in articles:
        place = False
        for g in groupes:
            communs = art["mots"] & g["mots"]
            if len(communs) >= SEUIL_SIMILARITE:
                g["articles"].append(art)
                g["sources"].add(art["source"])
                g["mots"] |= art["mots"]  # enrichit le vocabulaire du groupe
                place = True
                break
        if not place:
            groupes.append({
                "mots": set(art["mots"]),
                "articles": [art],
                "sources": {art["source"]},
            })

    # On ne garde que les sujets multi-sources = vraie tendance.
    tendances = [g for g in groupes if len(g["sources"]) >= SEUIL_ALERTE]
    # Tri par nombre de sources décroissant (le plus chaud en premier).
    tendances.sort(key=lambda g: len(g["sources"]), reverse=True)
    return tendances


# ---------------------------------------------------------------------------
# GÉNÉRATEUR DE POST "DÉCODÉ" (via l'API Gemini)
# ---------------------------------------------------------------------------
#
# Le gabarit lui-même (zone/emoji, faits, enjeu) est imposé à Gemini par un
# prompt système strict — neutralité, aucun chiffre inventé, zéro hashtag,
# jamais de flèche vide. Le radar ne fait que : extraire le texte de
# l'article, appeler l'API REST Gemini (pas de SDK), et retomber sur le
# titre + lien si l'article est inaccessible ou si l'appel échoue (quota,
# réseau) — un souci de génération ne doit jamais faire planter le radar.

GEMINI_MODELE = "gemini-2.0-flash"
GEMINI_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODELE}:generateContent"
)

# Délai minimal entre deux appels Gemini, pour rester dans le tier gratuit.
# Le tier gratuit plafonne autour de 15 requêtes/minute : 7s de marge
# (~8.5 req/min) évite de retomber pile sur la limite si plusieurs sujets
# sont détectés dans le même run.
GEMINI_DELAI_MIN = 7

PROMPT_SYSTEME = """Tu es l'assistant éditorial du média "Décodé", un compte d'actualité
neutre et factuel. À partir de l'article fourni, génère UN post court
pour le réseau X, en respectant STRICTEMENT ce format :

🔴 [ZONE ou THÈME EN MAJUSCULES] — [accroche factuelle en une ligne].

Où on en est :
→ [fait 1, avec chiffre si disponible]
→ [fait 2]
→ [fait 3]

Pourquoi ça compte : [une phrase d'enjeu, neutre].

On suit. 🧩

RÈGLES ABSOLUES :
- Neutralité totale : aucun jugement, aucun adjectif d'opinion.
- N'invente JAMAIS un chiffre. N'utilise que ce qui est dans l'article.
- Si tu manques d'un fait, mets seulement 2 flèches au lieu de 3.
  N'écris jamais "[à compléter]".
- Zéro hashtag.
- L'emoji de tête dépend du sujet : 🔴 urgence/drame, 🌾 politique,
  🚴 sport, 💶 économie, 🌍 international, 🧩 par défaut.
- Total sous 280 caractères si possible.
- Réponds UNIQUEMENT avec le post final, sans introduction ni commentaire."""


def extraire_texte_page(url):
    """
    Récupère le corps de l'article pour Gemini : concatène les balises <p>.
    Gère timeout/403/page introuvable en renvoyant une chaîne vide — le
    post sera alors généré à partir du titre seul.
    """
    try:
        r = requests.get(
            url,
            timeout=10,
            headers={"User-Agent": "Mozilla/5.0 (compatible; RadarDecode/1.0)"},
        )
        r.raise_for_status()
    except Exception as ex:
        print(f"[!] Article inaccessible ({url}): {ex}")
        return ""

    paragraphes = re.findall(r"<p[^>]*>(.*?)</p>", r.text, re.S | re.I)
    return " ".join(nettoyer(p) for p in paragraphes).strip()


def _appeler_gemini(prompt):
    """Appel REST direct à Gemini (pas de SDK). Renvoie None si ça échoue."""
    if not GEMINI_API_KEY:
        return None

    texte = None
    try:
        r = requests.post(
            GEMINI_URL,
            params={"key": GEMINI_API_KEY},
            json={"contents": [{"parts": [{"text": prompt}]}]},
            timeout=20,
        )
        r.raise_for_status()
        data = r.json()
        texte = data["candidates"][0]["content"]["parts"][0]["text"].strip()
    except Exception as ex:
        print(f"[!] Erreur appel Gemini: {ex}")

    time.sleep(GEMINI_DELAI_MIN)  # respecte la limite de débit du tier gratuit
    return texte


def generer_post_decode(titre, url, sources):
    """
    Génère le post "Décodé" via Gemini à partir du titre, de l'URL de
    l'article principal et des sources qui couvrent déjà le sujet.
    Retombe sur le titre + lien si l'article est inaccessible ou si
    l'appel Gemini échoue (quota, réseau, clé absente) : le radar ne doit
    jamais planter pour un souci de génération de post.
    """
    texte_article = extraire_texte_page(url)

    contexte = f"Titre : {titre}\nSources déjà couvertes : {', '.join(sorted(sources))}\n"
    if texte_article:
        contexte += f"\nTexte de l'article :\n{texte_article[:6000]}"
    else:
        contexte += "\n(Article inaccessible : génère uniquement à partir du titre.)"

    post = _appeler_gemini(f"{PROMPT_SYSTEME}\n\n{contexte}")
    if post:
        return post

    return f"🧩 {titre}\n\n{url}"


# ---------------------------------------------------------------------------
# ALERTE TELEGRAM
# ---------------------------------------------------------------------------

def formater_alerte(groupe):
    """Construit le message Telegram avec le post Décodé encadré, prêt à copier."""
    principal = groupe["articles"][0]
    nb_sources = len(groupe["sources"])
    sources = ", ".join(sorted(groupe["sources"]))

    # Top 3 mots-clés les plus parlants (les plus longs).
    themes = sorted(groupe["mots"], key=len, reverse=True)[:3]
    themes_txt = ", ".join(themes)

    heure = datetime.now(timezone.utc).astimezone().strftime("%H:%M")
    post_decode = generer_post_decode(principal["titre"], principal["lien"], groupe["sources"])

    msg = (
        f"🧩 <b>RADAR DÉCODÉ</b> — {heure}\n"
        f"🔥 Sujet qui monte ({nb_sources} sources)\n\n"
        f"<b>{html.escape(principal['titre'])}</b>\n\n"
        f"🏷️ Thèmes : {themes_txt}\n"
        f"📰 Repéré chez : {sources}\n"
        f"🔗 {principal['lien']}\n\n"
        f"{CADRE_POST}\n"
        f"<pre>{html.escape(post_decode)}</pre>\n"
        f"{CADRE_POST}\n\n"
        f"⚠️ Vérifie les chiffres avant de publier."
    )
    return msg


def envoyer_telegram(texte):
    if not TELEGRAM_TOKEN or not TELEGRAM_CHAT_ID:
        print("[!] Secrets Telegram manquants — affichage local :\n")
        print(texte)
        print("\n" + "-" * 40)
        return
    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
    data = {
        "chat_id": TELEGRAM_CHAT_ID,
        "text": texte,
        "parse_mode": "HTML",
        "disable_web_page_preview": False,
    }
    try:
        r = requests.post(url, data=data, timeout=15)
        if r.status_code == 200:
            print("[✓] Alerte envoyée.")
        else:
            print(f"[!] Telegram {r.status_code}: {r.text}")
    except Exception as ex:
        print(f"[!] Erreur envoi Telegram: {ex}")


# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------

def main():
    print("=" * 50)
    print("RADAR DÉCODÉ — démarrage")
    print("=" * 50)

    vus = charger_memoire()
    articles = collecter()
    tendances = detecter_sujets(articles)

    print(f"[i] {len(tendances)} sujet(s) multi-sources détecté(s).")

    nouvelles = 0
    for groupe in tendances:
        sig = empreinte(sorted(groupe["mots"], key=len, reverse=True)[:5])
        if sig in vus:
            continue  # déjà alerté
        envoyer_telegram(formater_alerte(groupe))
        vus.add(sig)
        nouvelles += 1
        time.sleep(1)  # évite de spammer l'API Telegram

    sauver_memoire(vus)
    print(f"[i] {nouvelles} nouvelle(s) alerte(s) envoyée(s).")
    print("RADAR DÉCODÉ — terminé.")


if __name__ == "__main__":
    main()
