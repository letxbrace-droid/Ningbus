"""
Discover recently active Shopify shops for a niche.

Strategy (free, no API key):
1. Extract store domains from scraped Meta ads (primary — ads running 30d+ = scaling)
2. Supplement via DuckDuckGo if < 5 stores found
3. Fetch /products.json for each store
4. Return shops enriched with product catalog + ad context
"""

from __future__ import annotations

import asyncio
import logging
import re
from collections import defaultdict
from urllib.parse import urlparse, quote

import aiohttp

logger = logging.getLogger(__name__)

SHOPIFY_PRODUCTS_URL = "https://{domain}/products.json?limit=20&sort_by=best-selling"
DDG_URL = "https://html.duckduckgo.com/html/"
MAX_SHOPS = 12
REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=12)

HEADERS = {
    "User-Agent": "Mozilla/5.0 (compatible; TrendBot/1.0)",
    "Accept": "application/json",
}


# ── Shopify domain detection ─────────────────────────────────────────────────

def _is_shopify(domain: str) -> bool:
    return "myshopify.com" in domain or domain.endswith(".shop")


def _clean_domain(raw: str) -> str:
    """Normalise to scheme://domain."""
    raw = raw.strip().lower()
    if not raw.startswith("http"):
        raw = "https://" + raw
    p = urlparse(raw)
    return f"https://{p.netloc}" if p.netloc else ""


# ── DuckDuckGo shop search ───────────────────────────────────────────────────

async def _ddg_shopify_search(
    session: aiohttp.ClientSession, niche: str
) -> list[str]:
    """Search DuckDuckGo for myshopify.com stores in a niche."""
    domains: list[str] = []
    queries = [
        f"{niche} site:myshopify.com",
        f"buy {niche} myshopify.com",
    ]
    for query in queries:
        try:
            async with session.post(
                DDG_URL,
                data={"q": query, "b": "", "kl": "fr-fr"},
                headers={"User-Agent": "Mozilla/5.0 (compatible; TrendBot/1.0)"},
                timeout=REQUEST_TIMEOUT,
            ) as resp:
                html = await resp.text()
            found = re.findall(r'href="(https?://[^"]*myshopify\.com[^"]*)"', html)
            for url in found:
                p = urlparse(url)
                d = f"https://{p.netloc}"
                if d not in domains:
                    domains.append(d)
            if len(domains) >= 6:
                break
        except Exception as exc:
            logger.debug("DDG search failed: %s", exc)
    return domains[:8]


# ── Shopify product fetch ─────────────────────────────────────────────────────

async def _verify_shop_accessible(
    session: aiohttp.ClientSession, base: str
) -> bool:
    """Quick check: is the shop alive and does it have products?"""
    url = f"{base.rstrip('/')}/products.json?limit=1"
    try:
        async with session.get(url, headers=HEADERS, timeout=aiohttp.ClientTimeout(total=6)) as resp:
            if resp.status != 200:
                return False
            data = await resp.json(content_type=None)
            return len(data.get("products", [])) > 0
    except Exception:
        return False


async def _fetch_products(
    session: aiohttp.ClientSession, base: str
) -> list[dict]:
    """Fetch best-selling products from a Shopify store."""
    url = SHOPIFY_PRODUCTS_URL.format(domain=base.replace("https://", "").replace("http://", ""))
    try:
        async with session.get(url, headers=HEADERS, timeout=REQUEST_TIMEOUT) as resp:
            if resp.status != 200:
                return []
            data = await resp.json(content_type=None)
    except Exception as exc:
        logger.debug("Products fetch failed for %s: %s", base, exc)
        return []

    products = []
    for p in data.get("products", [])[:20]:
        variants = p.get("variants") or [{}]
        variant  = variants[0]
        images   = p.get("images") or [{}]
        price    = variant.get("price") or "0"

        try:
            price_f = float(price)
        except ValueError:
            price_f = 0.0

        products.append({
            "title":     p.get("title", ""),
            "handle":    p.get("handle", ""),
            "url":       f"{base}/products/{p.get('handle', '')}",
            "price":     price,
            "price_f":   price_f,
            "image":     images[0].get("src", "") if images else "",
            "tags":      p.get("tags") or [],
            "product_type": p.get("product_type") or "",
            "created_at": p.get("created_at") or "",
            "variants_count": len(variants),
        })

    # Sort by price descending — higher price = higher margin = more ad budget
    products.sort(key=lambda p: p["price_f"], reverse=True)
    return products


# ── Shop enrichment ───────────────────────────────────────────────────────────

async def _enrich_shop(
    session: aiohttp.ClientSession, shop: dict
) -> dict:
    """Add product catalog to a shop dict."""
    products = await _fetch_products(session, shop["base_url"])
    return {**shop, "products": products}


# ── Main entry point ──────────────────────────────────────────────────────────

async def find_scaling_shops(
    niche: str,
    analyzed_ads: list[dict],
    min_days: int = 15,
) -> list[dict]:
    """
    Return a list of Shopify shops that are actively scaling in this niche.

    Each shop dict contains:
      - domain, base_url
      - products (catalog from /products.json)
      - ads_count, max_days_running, total_spend
      - angles_used (from Meta ad analysis)
      - source: "meta_ads" | "search"
    """
    # ── Step 1: extract shops from real Meta ads ──────────────────────────────
    shop_map: dict[str, dict] = {}

    for ad in analyzed_ads:
        domain = ad.get("store_domain", "").strip()
        if not domain:
            continue
        if not (_is_shopify(domain) or "." in domain):
            continue
        base = _clean_domain(domain)
        if not base:
            continue

        if base not in shop_map:
            shop_map[base] = {
                "domain": domain,
                "base_url": base,
                "ads": [],
                "max_days_running": 0,
                "total_spend": 0,
                "source": "meta_ads",
                "angles_used": [],
            }

        shop_map[base]["ads"].append(ad)
        days = ad.get("days_running", 0)
        shop_map[base]["max_days_running"] = max(
            shop_map[base]["max_days_running"], days
        )
        shop_map[base]["total_spend"] += ad.get("estimated_spend", 0)

    # Keep only shops with at least min_days of ad activity
    scaling = {k: v for k, v in shop_map.items() if v["max_days_running"] >= min_days}

    # Collect angles used per shop
    for shop in scaling.values():
        angles = list({
            a.get("angle_data", {}).get("angle", "Unknown")
            for a in shop["ads"]
            if a.get("angle_data", {}).get("angle") not in (None, "Unknown")
        })
        shop["angles_used"] = angles
        shop["ads_count"] = len(shop["ads"])

    logger.info(
        "Found %d scaling shops from Meta ads (min %dd), %d total shops",
        len(scaling), min_days, len(shop_map),
    )

    # ── Step 2: supplement via DDG if < 5 shops found ────────────────────────
    async with aiohttp.ClientSession() as session:
        if len(scaling) < 5:
            logger.info("Supplementing with DDG search for '%s'", niche)
            ddg_domains = await _ddg_shopify_search(session, niche)
            for base in ddg_domains:
                if base not in scaling:
                    scaling[base] = {
                        "domain": base.replace("https://", ""),
                        "base_url": base,
                        "ads": [],
                        "ads_count": 0,
                        "max_days_running": 0,
                        "total_spend": 0,
                        "source": "search",
                        "angles_used": [],
                    }

        # ── Step 3: verify shops are accessible then fetch catalogs ──────────
        candidates = list(scaling.values())[:MAX_SHOPS]

        # Verify concurrently first (skip dead/private stores)
        verify_tasks = [_verify_shop_accessible(session, s["base_url"]) for s in candidates]
        alive_flags  = await asyncio.gather(*verify_tasks, return_exceptions=True)
        alive = [s for s, ok in zip(candidates, alive_flags) if ok is True]
        logger.info("%d/%d shops passed accessibility check", len(alive), len(candidates))

        # Fetch full product catalogs only for live stores
        tasks    = [_enrich_shop(session, s) for s in alive]
        enriched = await asyncio.gather(*tasks, return_exceptions=True)

    results = [s for s in enriched if isinstance(s, dict) and s.get("products")]

    # Sort: meta_ads with most days first, then DDG finds
    results.sort(
        key=lambda s: (s["source"] == "meta_ads", s["max_days_running"], len(s["products"])),
        reverse=True,
    )

    logger.info("Returning %d shops with product catalogs", len(results))
    return results[:MAX_SHOPS]
