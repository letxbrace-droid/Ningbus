"""
Shopify product velocity — detect launches, failures, and price scaling
by comparing /products.json snapshots stored in history files.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

logger = logging.getLogger(__name__)

HISTORY_DIR = Path(__file__).parent.parent / "data" / "history"


def _key(p: dict) -> str:
    return p.get("title", "").strip().lower()


def _price(p: dict) -> float:
    try:
        return float(p.get("price") or 0)
    except (ValueError, TypeError):
        return 0.0


def _load_domain_snapshots(domain: str, lookback: int = 10) -> list[tuple[str, list[dict]]]:
    """
    Return [(timestamp, products), ...] from oldest to newest,
    for the given domain, across the last `lookback` history files.
    """
    if not HISTORY_DIR.exists():
        return []

    files = sorted(HISTORY_DIR.glob("*.json"), reverse=True)[:lookback]
    snapshots: list[tuple[str, list[dict]]] = []

    for fpath in reversed(files):  # oldest → newest
        try:
            data = json.loads(fpath.read_text(encoding="utf-8"))
            ts = data.get("generated_at", fpath.stem)
            for result in data.get("results", []):
                for adv in result.get("advertisers", []):
                    if adv.get("domain") == domain:
                        snapshots.append((ts, adv.get("products") or []))
                        break
        except Exception:
            continue

    return snapshots


def compute_velocity(domain: str, current_products: list[dict], lookback: int = 10) -> dict:
    """
    Compare current product catalogue against historical snapshots.

    Returns a dict with:
        data_points      — number of history files with data for this domain
        new_products     — titles added since oldest snapshot
        removed_products — titles removed since oldest snapshot
        price_changes    — [{title, from, to, change_pct, direction}, ...]
        signals          — human-readable signal strings (LAUNCH, SCALING, etc.)
        launch_score     — 0-100 composite velocity score
    """
    snapshots = _load_domain_snapshots(domain, lookback=lookback)

    empty = {
        "data_points":       len(snapshots),
        "new_products":      [],
        "removed_products":  [],
        "price_changes":     [],
        "signals":           [],
        "launch_score":      0,
    }

    if not snapshots:
        return empty

    _, oldest_products = snapshots[0]
    oldest_map = {_key(p): p for p in oldest_products}
    current_map = {_key(p): p for p in current_products}

    new_products = [
        p["title"] for k, p in current_map.items() if k not in oldest_map
    ]
    removed_products = [
        p["title"] for k, p in oldest_map.items() if k not in current_map
    ]

    price_changes: list[dict] = []
    for k in oldest_map:
        if k not in current_map:
            continue
        old_price = _price(oldest_map[k])
        new_price = _price(current_map[k])
        if old_price > 0 and new_price > 0:
            change_pct = round((new_price - old_price) / old_price * 100, 1)
            if abs(change_pct) >= 10:
                price_changes.append({
                    "title":      oldest_map[k]["title"],
                    "from":       old_price,
                    "to":         new_price,
                    "change_pct": change_pct,
                    "direction":  "up" if change_pct > 0 else "down",
                })

    # ── Signals ────────────────────────────────────────────────────────────
    signals: list[str] = []
    if len(new_products) >= 3:
        signals.append(f"LAUNCH +{len(new_products)} produits")
    elif new_products:
        signals.append(f"+{len(new_products)} produit(s) ajouté(s)")

    if removed_products:
        signals.append(f"{len(removed_products)} produit(s) retiré(s)")

    scaling_up = [c for c in price_changes if c["direction"] == "up"]
    if scaling_up:
        signals.append(f"SCALING PRIX +{len(scaling_up)} hausse(s)")

    price_drops = [c for c in price_changes if c["direction"] == "down"]
    if price_drops:
        signals.append(f"{len(price_drops)} baisse(s) de prix")

    # ── Launch score (0-100) ───────────────────────────────────────────────
    score = 0
    score += min(len(new_products) * 12, 40)        # new products → up to 40pts
    score += min(len(scaling_up) * 15, 30)          # price hikes → up to 30pts
    score += min(len(snapshots) * 5, 20)            # historical depth → up to 20pts
    score -= min(len(removed_products) * 5, 20)     # removals → negative signal
    score = max(0, min(100, score))

    return {
        "data_points":       len(snapshots),
        "new_products":      new_products[:10],
        "removed_products":  removed_products[:10],
        "price_changes":     price_changes[:10],
        "signals":           signals,
        "launch_score":      score,
    }
