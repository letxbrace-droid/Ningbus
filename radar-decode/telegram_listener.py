#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ÉCOUTEUR TELEGRAM — commande /scan pour Radar Décodé
------------------------------------------------------
Radar Décodé n'a pas de serveur, donc pas de webhook Telegram possible.
Ce script fait le pont autrement : une deuxième Action GitHub le lance
toutes les ~5 minutes, il regarde s'il y a un nouveau message "/scan" dans
le chat autorisé, et si oui déclenche un run immédiat du workflow principal
via l'API GitHub (workflow_dispatch).
"""

import os
import re
import json

import requests

TELEGRAM_TOKEN = os.environ.get("TELEGRAM_TOKEN", "")
TELEGRAM_CHAT_ID = os.environ.get("TELEGRAM_CHAT_ID", "")
GH_DISPATCH_TOKEN = os.environ.get("GH_DISPATCH_TOKEN", "")
GH_REPO = os.environ.get("GITHUB_REPOSITORY", "")  # "owner/repo", fourni par Actions

WORKFLOW_CIBLE = "radar_decode.yml"
FICHIER_OFFSET = "telegram_offset.json"

COMMANDE_SCAN = re.compile(r"^/scan(@\S+)?\b", re.I)


def charger_offset():
    if os.path.exists(FICHIER_OFFSET):
        try:
            with open(FICHIER_OFFSET, "r", encoding="utf-8") as f:
                return json.load(f).get("offset", 0)
        except Exception:
            return 0
    return 0


def sauver_offset(offset):
    with open(FICHIER_OFFSET, "w", encoding="utf-8") as f:
        json.dump({"offset": offset}, f)


def recuperer_messages(offset):
    """Récupère les messages Telegram non encore vus (offset = curseur)."""
    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/getUpdates"
    try:
        r = requests.get(url, params={"offset": offset, "timeout": 0}, timeout=15)
        r.raise_for_status()
        return r.json().get("result", [])
    except Exception as ex:
        print(f"[!] Erreur getUpdates: {ex}")
        return []


def envoyer_message(texte):
    url = f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage"
    try:
        requests.post(url, data={"chat_id": TELEGRAM_CHAT_ID, "text": texte}, timeout=15)
    except Exception as ex:
        print(f"[!] Erreur sendMessage: {ex}")


def declencher_scan():
    """Déclenche un run du workflow principal via l'API GitHub (workflow_dispatch)."""
    url = (
        f"https://api.github.com/repos/{GH_REPO}/actions/workflows/"
        f"{WORKFLOW_CIBLE}/dispatches"
    )
    headers = {
        "Authorization": f"Bearer {GH_DISPATCH_TOKEN}",
        "Accept": "application/vnd.github+json",
    }
    try:
        r = requests.post(url, headers=headers, json={"ref": "main"}, timeout=15)
        return r.status_code == 204
    except Exception as ex:
        print(f"[!] Erreur déclenchement workflow: {ex}")
        return False


def main():
    if not TELEGRAM_TOKEN or not TELEGRAM_CHAT_ID:
        print("[!] Secrets Telegram manquants, arrêt.")
        return

    offset = charger_offset()
    messages = recuperer_messages(offset)

    nouvel_offset = offset
    scan_demande = False

    for maj in messages:
        nouvel_offset = max(nouvel_offset, maj["update_id"] + 1)
        message = maj.get("message") or {}
        texte = (message.get("text") or "").strip()
        chat_id = str(message.get("chat", {}).get("id", ""))

        # On ignore tout ce qui ne vient pas du chat autorisé : n'importe
        # qui peut écrire à un bot Telegram, seul le propriétaire commande.
        if chat_id != str(TELEGRAM_CHAT_ID):
            continue

        if COMMANDE_SCAN.match(texte):
            scan_demande = True

    if scan_demande:
        print("[i] Commande /scan reçue.")
        if not GH_DISPATCH_TOKEN:
            envoyer_message("⚠️ /scan reçu mais GH_DISPATCH_TOKEN n'est pas configuré.")
        elif declencher_scan():
            envoyer_message("🔄 Scan lancé, résultat dans une minute environ.")
        else:
            envoyer_message("⚠️ Échec du déclenchement du scan (vérifie GH_DISPATCH_TOKEN).")

    sauver_offset(nouvel_offset)


if __name__ == "__main__":
    main()
