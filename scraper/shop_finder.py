"""Find active Shopify shops via DuckDuckGo search + /products.json verification."""

from __future__ import annotations

import asyncio
import logging
import random
import re
from urllib.parse import urlparse, quote_plus

import aiohttp

logger = logging.getLogger(__name__)

REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=12)
_DDG_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
}


def _clean_domain(url: str) -> str:
    if not url:
        return ""
    url = url.strip()
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    try:
        netloc = urlparse(url).netloc.split(":")[0].lower()
        if netloc.startswith("www."):
            netloc = netloc[4:]
        return netloc
    except Exception:
        return ""


def _build_query_variants(niche: str, country: str = "FR") -> list[str]:
    """Generate diverse search query variants to discover different shops each run."""
    country_qualifier = {
        "FR": "france",
        "US": "usa",
        "GB": "uk",
        "DE": "germany",
        "ES": "spain",
    }.get(country, "")

    variants = [
        f'site:myshopify.com {niche}',
        f'site:myshopify.com "{niche}" buy',
        f'site:myshopify.com {niche} "add to cart"',
        f'site:myshopify.com {niche} "free shipping"',
        f'"{niche}" shopify store online',
        f'{niche} boutique shopify',
        f'{niche} shop dropshipping',
        f'buy {niche} online store shopify',
    ]
    if country_qualifier:
        variants += [
            f'site:myshopify.com {niche} {country_qualifier}',
            f'{niche} {country_qualifier} shopify',
        ]
    # Shuffle so different variants are tried first on each run
    random.shuffle(variants)
    return variants


async def _ddg_shopify_search(niche: str, country: str = "FR", exclude: set[str] | None = None) -> list[str]:
    """Return myshopify.com domains found via DuckDuckGo HTML search."""
    exclude = exclude or set()
    queries = _build_query_variants(niche, country)
    found: set[str] = set()

    async with aiohttp.ClientSession(headers=_DDG_HEADERS) as session:
        for query in queries:
            if len(found) >= 18:
                break
            try:
                url = f"https://html.duckduckgo.com/html/?q={quote_plus(query)}"
                async with session.get(url, timeout=REQUEST_TIMEOUT) as resp:
                    if resp.status != 200:
                        continue
                    html = await resp.text()
                    # Extract myshopify.com domains
                    matches = re.findall(r'([a-z0-9][a-z0-9\-]{1,62}\.myshopify\.com)', html, re.I)
                    for m in matches:
                        d = m.lower()
                        if d not in exclude:
                            found.add(d)
                    # Also grab custom domains linked near Shopify context
                    for m in re.findall(r'href="https?://([^/"]+)"[^>]*>[^<]*(?:shop|store|buy)', html, re.I):
                        d = _clean_domain(m)
                        if d and "." in d and d not in exclude and "duckduckgo" not in d:
                            found.add(d)
            except Exception as exc:
                logger.debug("DDG search failed for '%s': %s", query, exc)

            await asyncio.sleep(0.8 + random.random() * 0.4)  # 0.8–1.2s jitter

    return list(found)


async def _verify_shop_accessible(
    session: aiohttp.ClientSession,
    domain: str,
) -> bool:
    """HEAD request to verify the shop is live and returns 2xx/3xx."""
    try:
        async with session.head(
            f"https://{domain}",
            allow_redirects=True,
            timeout=aiohttp.ClientTimeout(total=8),
        ) as resp:
            return resp.status < 400
    except Exception:
        return False


async def _fetch_products(
    session: aiohttp.ClientSession,
    domain: str,
) -> list[dict]:
    """Fetch best-selling products from Shopify /products.json."""
    try:
        url = f"https://{domain}/products.json?limit=20&sort_by=best-selling"
        async with session.get(url, timeout=REQUEST_TIMEOUT) as resp:
            if resp.status != 200:
                return []
            data = await resp.json(content_type=None)
            products = []
            for p in data.get("products", [])[:12]:
                variants = p.get("variants") or []
                price = variants[0].get("price", "") if variants else ""
                images  = p.get("images") or []
                img     = images[0].get("src", "") if images else ""
                products.append({
                    "title": p.get("title", ""),
                    "price": price,
                    "image": img,
                    "url":   f"https://{domain}/products/{p.get('handle', '')}",
                })
            return products
    except Exception as exc:
        logger.debug("Products fetch failed for %s: %s", domain, exc)
        return []


_SCALING_APPS = {
    "klaviyo":    "Email marketing (Klaviyo)",
    "reconvert":  "Post-purchase upsell (ReConvert)",
    "yotpo":      "Reviews (Yotpo)",
    "judge.me":   "Reviews (Judge.me)",
    "okendo":     "Reviews (Okendo)",
    "loox":       "Photo reviews (Loox)",
    "recharge":   "Abonnements (Recharge)",
    "privy":      "Popups/Email (Privy)",
    "omnisend":   "Email marketing (Omnisend)",
    "gorgias":    "Support client (Gorgias)",
    "carthook":   "Checkout upsell (CartHook)",
    "zipify":     "Checkout upsell (Zipify)",
    "triplewhale": "Attribution (Triple Whale)",
    "northbeam":  "Attribution (Northbeam)",
}

async def _detect_scaling_signals(session: aiohttp.ClientSession, domain: str) -> list[str]:
    """Fetch store homepage and detect installed scaling apps."""
    signals = []
    try:
        url = f"https://{domain}"
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=8), allow_redirects=True) as r:
            if r.status != 200:
                return signals
            html = (await r.content.read(65536)).decode("utf-8", errors="replace").lower()
            for key, label in _SCALING_APPS.items():
                if key in html:
                    signals.append(label)
    except Exception:
        pass
    return signals


async def _build_shop_entry(
    session: aiohttp.ClientSession,
    domain: str,
    source: str = "shopify_search",
) -> dict | None:
    ok = await _verify_shop_accessible(session, domain)
    if not ok:
        return None
    products = await _fetch_products(session, domain)
    if not products:
        return None
    scaling_signals = await _detect_scaling_signals(session, domain)
    return {
        "domain":             domain,
        "store_url":          f"https://{domain}",
        "source":             source,
        "products":           products,
        "scaling_signals":    scaling_signals,
        "angles_used":        [],
        "angle_gaps":         [],
        "ads_count":          0,
        "max_days_running":   0,
        "avg_days_running":   0.0,
        "estimated_spend":    0,
        "scaling_score":      round(len(products) * 5.0, 1),
        "dominant_angle":     "",
        "platforms":          [],
        "ad_examples":        [],
    }


async def find_scaling_shops(
    ads: list[dict],
    niche: str,
    country: str = "FR",
    exclude_domains: set[str] | None = None,
) -> list[dict]:
    """
    Find active Shopify shops for a niche.
    1. Extract domains from ad landing pages (if any).
    2. Supplement with DuckDuckGo diversified queries.
    3. Verify each shop is accessible and has products.
    Returns up to 10 shops sorted by product count.
    """
    exclude = exclude_domains or set()
    domains: set[str] = set()

    for ad in ads:
        d = _clean_domain(
            ad.get("store_domain") or ad.get("landing_page_url") or ""
        )
        if d and "facebook.com" not in d and "instagram.com" not in d:
            domains.add(d)

    if len(domains) < 8:
        logger.info("shop_finder: DDG search for niche '%s' (excluding %d known domains)", niche, len(exclude))
        ddg = await _ddg_shopify_search(niche, country=country, exclude=exclude)
        # Prioritise freshly discovered domains (not in exclude) — add known ones last as fallback
        new_domains = [d for d in ddg if d not in exclude]
        old_domains = [d for d in ddg if d in exclude]
        domains.update(new_domains)
        if len(domains) < 5:
            domains.update(old_domains)
        logger.info("shop_finder: %d candidate domains (%d new, %d known-excluded)", len(domains), len(new_domains), len(old_domains))

    ad_domains = {
        _clean_domain(a.get("store_domain") or a.get("landing_page_url") or "")
        for a in ads
    }

    async with aiohttp.ClientSession(headers=_DDG_HEADERS) as session:
        tasks = [
            _build_shop_entry(session, d, "meta_ads" if d in ad_domains else "shopify_search")
            for d in list(domains)[:20]
        ]
        results = await asyncio.gather(*tasks, return_exceptions=True)

    shops = [r for r in results if isinstance(r, dict) and r]
    shops.sort(key=lambda s: len(s.get("products", [])), reverse=True)
    logger.info("shop_finder: %d verified active shops", len(shops))
    return shops[:10]
