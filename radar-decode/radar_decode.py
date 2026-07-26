#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RADAR DÉCODÉ — Veille automatique de l'actu
--------------------------------------------
Surveille les flux RSS des grands médias, détecte les sujets qui
reviennent chez plusieurs sources (= info qui monte), et envoie une
alerte Telegram avec un post "Décodé" prêt à copier-coller sur X.

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

# Nombre de sources différentes qui doivent parler d'un même sujet
# pour déclencher une alerte "info qui monte".
SEUIL_ALERTE = 2

# Nombre minimal de mots-clés communs pour considérer deux titres
# comme parlant du même sujet.
SEUIL_SIMILARITE = 2

# Fichier mémoire (évite de renvoyer deux fois la même alerte).
# Persisté entre les runs via le cache GitHub Actions ou un commit.
FICHIER_MEMOIRE = "vus.json"

# Longueur cible d'un post "Décodé" (norme X classique).
LONGUEUR_POST_MAX = 280

# Marqueur autour du post généré dans l'alerte Telegram.
CADRE_POST = "━━━ PRÊT À PUBLIER ━━━"

# Valeur affichée quand un fait/enjeu n'a pas pu être extrait — on ne
# comble jamais un trou par une supposition.
A_COMPLETER = "[à compléter]"

# Flux RSS surveillés. Sources françaises fiables + agences.
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


def tronquer(texte, limite):
    """Coupe proprement sur un espace plutôt qu'en plein milieu d'un mot."""
    if len(texte) <= limite:
        return texte
    if limite <= 1:
        return "…"
    return texte[:limite].rsplit(" ", 1)[0].rstrip(",.;:") + "…"


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
                # Le chapô RSS (summary/description) sert de première source
                # de "faits" pour le post Décodé, avant d'aller chercher sur
                # la page elle-même.
                resume = nettoyer(getattr(e, "summary", ""))
                if titre and lien:
                    articles.append({
                        "source": source,
                        "titre": titre,
                        "lien": lien,
                        "resume": resume,
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
# GÉNÉRATEUR DE POST "DÉCODÉ" (sans IA, sans API payante)
# ---------------------------------------------------------------------------
#
# Gabarit fixe :
#   {emoji} [ZONE] — [accroche]
#   Où on en est : → fait 1 → fait 2 → fait 3
#   Pourquoi ça compte : [enjeu]
#   On suit. 🧩
#
# - L'emoji dépend du thème détecté dans les mots-clés du sujet.
# - La ZONE est la commune/le département repéré en tête du titre, sinon
#   le mot-clé le plus saillant du sujet.
# - Les faits/l'enjeu viennent du chapô RSS puis, si besoin, d'un extrait
#   pris sur la page (meta description, sinon premier paragraphe) — jamais
#   inventés : ce qu'on ne trouve pas reste "[à compléter]".
# - Les phrases contenant un chiffre clé (%, €, km, ha, personnes...) sont
#   toujours proposées en premier comme faits.
#
# Aucun LLM, aucune API tierce : requests + regex sur du HTML déjà en
# mémoire, rien d'autre.

EMOJIS_THEME = (
    # (emoji, mots-clés déclencheurs) — testés dans cet ordre, le premier
    # thème qui matche l'emporte.
    ("🔴", {"mort", "morts", "tue", "tues", "tué", "tués", "accident", "incendie",
            "explosion", "attentat", "urgence", "drame", "blesse", "blesses",
            "blessé", "blessés", "crash", "disparu", "evacuation", "évacuation",
            "alerte", "catastrophe", "seisme", "séisme", "tempete", "tempête",
            "inondation"}),
    ("🌍", {"guerre", "ukraine", "gaza", "israel", "israël", "otan", "onu",
            "etats-unis", "chine", "russie", "international", "europe",
            "monde"}),
    ("🌾", {"gouvernement", "ministre", "president", "président", "assemblee",
            "assemblée", "senat", "sénat", "loi", "reforme", "réforme",
            "election", "élection", "vote", "politique", "maire", "depute",
            "député", "parti"}),
    ("💶", {"economie", "économie", "inflation", "prix", "salaire", "emploi",
            "chomage", "chômage", "entreprise", "bourse", "euro", "euros",
            "budget", "impot", "impôt", "croissance"}),
    ("🚴", {"match", "football", "rugby", "olympique", "equipe", "équipe",
            "championnat", "tournoi", "victoire", "defaite", "défaite",
            "sport", "cyclisme", "coupe"}),
)
EMOJI_DEFAUT = "🧩"

# Chiffres "clés" : unité qui rend un chiffre publiable tel quel (on ignore
# les nombres nus, trop souvent des dates ou des numéros d'article).
MOTIF_CHIFFRE_CLE = re.compile(
    r"\b\d[\d\s.,]*\s?"
    r"(?:%|€|km²?|ha\b|hectares?|habitants?|personnes?|morts?|blessés?|blesses?)",
    re.I,
)

# Repère une commune/un département en tête de titre : "NICE. ...",
# "Loire-Atlantique : ...", "À Marseille, ...".
MOTIFS_ZONE = (
    re.compile(r"^[ÀA]\s+([A-ZÉÈÀÂÎÔÛÇ][\wÀ-ÿ\-]{2,})\s*,\s+"),
    re.compile(
        r"^([A-ZÉÈÀÂÎÔÛÇ][\wÀ-ÿ\-]{2,}"
        r"(?:[\s\-][A-ZÉÈÀÂÎÔÛÇ][\wÀ-ÿ\-]{2,}){0,3})\s*[:.\-–]\s+"
    ),
)

MOTIFS_DESCRIPTION = (
    r'<meta[^>]+name=["\']description["\'][^>]+content=["\']([^"\']+)["\']',
    r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+name=["\']description["\']',
    r'<meta[^>]+property=["\']og:description["\'][^>]+content=["\']([^"\']+)["\']',
    r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:description["\']',
)


def extraire_texte_article(url):
    """
    Va chercher un texte de secours sur la page de l'article, seulement si
    le chapô RSS n'a rien donné : meta description, sinon premier
    paragraphe substantiel. Gère proprement timeout/403/page introuvable
    en renvoyant simplement une chaîne vide (jamais d'exception qui remonte).
    """
    try:
        r = requests.get(
            url,
            timeout=8,
            headers={"User-Agent": "Mozilla/5.0 (compatible; RadarDecode/1.0)"},
        )
        r.raise_for_status()
        page = r.text
    except Exception as ex:
        print(f"[!] Extraction impossible ({url}): {ex}")
        return ""

    for motif in MOTIFS_DESCRIPTION:
        m = re.search(motif, page, re.I)
        if m:
            texte = nettoyer(m.group(1))
            if len(texte) > 40:
                return texte

    for bloc in re.findall(r"<p[^>]*>(.*?)</p>", page, re.S | re.I):
        texte = nettoyer(bloc)
        if len(texte) > 60 and "cookie" not in texte.lower():
            return texte

    return ""


def decouper_phrases(texte):
    """Découpe un texte en phrases exploitables (on jette les débris trop courts)."""
    if not texte:
        return []
    phrases = re.split(r"(?<=[.!?])\s+", texte)
    return [p.strip() for p in phrases if len(p.strip()) > 15]


def choisir_emoji(groupe):
    """Emoji de tête selon le thème détecté dans les mots-clés du sujet."""
    for emoji, mots_theme in EMOJIS_THEME:
        if groupe["mots"] & mots_theme:
            return emoji
    return EMOJI_DEFAUT


def detecter_zone(titre):
    """Repère une commune/un département cité en tête du titre, s'il y en a un."""
    for motif in MOTIFS_ZONE:
        m = motif.match(titre)
        if m:
            return m.group(1).upper()
    return None


def zone_theme(groupe):
    """Étiquette de secours = le mot-clé le plus saillant du sujet (le plus long)."""
    mots = sorted(groupe["mots"], key=len, reverse=True)
    return mots[0].upper() if mots else "ACTU"


def extraire_faits(principal):
    """
    Construit la liste ordonnée de phrases exploitables pour un article :
    chapô RSS d'abord, puis extrait de la page si le chapô est trop maigre.
    Les phrases contenant un chiffre clé passent devant les autres.
    """
    phrases = decouper_phrases(principal.get("resume", ""))
    if len(phrases) < 3:
        phrases += decouper_phrases(extraire_texte_article(principal["lien"]))

    # dédoublonnage en gardant l'ordre
    vues = set()
    phrases_uniques = []
    for p in phrases:
        if p not in vues:
            vues.add(p)
            phrases_uniques.append(p)

    avec_chiffre = [p for p in phrases_uniques if MOTIF_CHIFFRE_CLE.search(p)]
    sans_chiffre = [p for p in phrases_uniques if p not in avec_chiffre]
    return avec_chiffre + sans_chiffre


def construire_post(emoji, bandeau, accroche, faits, enjeu):
    """Assemble le gabarit avec des lignes déjà à la bonne longueur."""
    lignes_faits = "\n".join(f"→ {f}" for f in faits)
    return (
        f"{emoji} {bandeau} — {accroche}\n\n"
        f"Où on en est :\n{lignes_faits}\n\n"
        f"Pourquoi ça compte : {enjeu}\n\n"
        f"On suit. 🧩"
    )


def generer_post_decode(groupe):
    """
    Construit un post "Décodé" prêt à copier-coller sur X à partir d'un
    sujet détecté, en suivant le gabarit maison. Aucun LLM : uniquement le
    titre, la zone repérée dans le titre et un extrait factuel de l'article
    (chapô RSS, puis page si besoin). Ce qui n'est pas trouvé reste
    "[à compléter]" — on n'invente jamais un chiffre ou un fait.
    """
    principal = groupe["articles"][0]
    emoji = choisir_emoji(groupe)
    bandeau = detecter_zone(principal["titre"]) or zone_theme(groupe)

    candidats = extraire_faits(principal)
    faits = candidats[:3]
    # L'enjeu est une phrase distincte des faits déjà utilisés, s'il en reste une.
    enjeu = candidats[3] if len(candidats) > 3 else None

    while len(faits) < 3:
        faits.append(A_COMPLETER)
    if enjeu is None:
        enjeu = A_COMPLETER

    # On essaie plusieurs longueurs de ligne décroissantes jusqu'à passer
    # sous la limite d'un post X ; au pire on garde la version la plus
    # courte obtenue.
    accroche_brute = principal["titre"]
    post = None
    for limite in (140, 100, 70, 50, 35):
        accroche = tronquer(accroche_brute, limite)
        faits_tronques = [f if f == A_COMPLETER else tronquer(f, limite) for f in faits]
        enjeu_tronque = enjeu if enjeu == A_COMPLETER else tronquer(enjeu, limite)
        post = construire_post(emoji, bandeau, accroche, faits_tronques, enjeu_tronque)
        if len(post) <= LONGUEUR_POST_MAX:
            break

    return post


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
    post_decode = generer_post_decode(groupe)

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
