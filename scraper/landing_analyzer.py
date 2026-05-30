"""Analyze Shopify product landing pages — extract price, guarantee, social proof, CTA, conversion score."""

from __future__ import annotations

import asyncio
import logging
import re

import aiohttp

logger = logging.getLogger(__name__)

_TIMEOUT = aiohttp.ClientTimeout(total=10)
_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0",
    "Accept-Language": "fr-FR,fr;q=0.9",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
}

# Regex patterns
_RE_PRICE    = re.compile(r'(\d{1,4}[,.]?\d{0,2})\s*[€$£]|[€$£]\s*(\d{1,4}[,.]?\d{0,2})')
_RE_GUARANTEE = re.compile(r'(\d{1,3})\s*(?:jours?|days?|jour|day)\s*(?:satisfait|garanti|remboursé|refund|money.?back|garantie)', re.IGNORECASE)
_RE_REVIEWS  = re.compile(r'([\d\s,]+)\s*(?:avis|reviews?|évaluations?|ratings?)', re.IGNORECASE)
_RE_STARS    = re.compile(r'([4-5][.,]\d)\s*(?:étoiles?|stars?|sur\s*5|out\s*of\s*5)', re.IGNORECASE)
_CTA_WORDS   = ["ajouter au panier", "commander maintenant", "acheter maintenant", "add to cart", "buy now", "order now", "get started", "essayer maintenant"]


def _parse_html(html: str) -> dict:
    html_lower = html.lower()

    # Price
    prices = _RE_PRICE.findall(html)
    price_vals = [float((a or b).replace(',', '.')) for a, b in prices if (a or b)]
    price = round(min(p for p in price_vals if p > 0), 2) if price_vals else None

    # Guarantee
    guar = _RE_GUARANTEE.search(html)
    guarantee_days = int(guar.group(1)) if guar else None

    # Social proof
    reviews = _RE_REVIEWS.search(html)
    review_count = int(reviews.group(1).replace(' ', '').replace(',', '')) if reviews else None
    stars = _RE_STARS.search(html)
    star_rating = float(stars.group(1).replace(',', '.')) if stars else None

    # CTA
    cta_found = next((w for w in _CTA_WORDS if w in html_lower), None)

    # Conversion score (0-100)
    score = 0
    if price and price > 0:          score += 20
    if guarantee_days:               score += 25
    if review_count and review_count >= 10: score += 25
    if star_rating and star_rating >= 4.0:  score += 15
    if cta_found:                    score += 15

    return {
        "price":           price,
        "guarantee_days":  guarantee_days,
        "review_count":    review_count,
        "star_rating":     star_rating,
        "cta":             cta_found,
        "conversion_score": score,
    }


async def analyze_url(session: aiohttp.ClientSession, url: str) -> dict | None:
    """Fetch and analyze a single product/landing page URL."""
    if not url or not url.startswith("http"):
        return None
    try:
        async with session.get(url, timeout=_TIMEOUT, allow_redirects=True) as r:
            if r.status != 200:
                return None
            # Only read first 80KB — enough for above-fold content
            raw = await r.content.read(81920)
            html = raw.decode("utf-8", errors="replace")
            return _parse_html(html)
    except Exception as exc:
        logger.debug("Landing analysis failed for %s: %s", url, exc)
        return None


async def analyze_shops(shops: list[dict], max_concurrent: int = 4) -> dict[str, dict]:
    """
    Analyze the first product page of each shop.
    Returns {domain: landing_analysis_dict}.
    """
    sem = asyncio.Semaphore(max_concurrent)
    results: dict[str, dict] = {}

    async def _one(domain: str, url: str) -> None:
        async with sem:
            result = await analyze_url(session, url)
            if result:
                results[domain] = result
            await asyncio.sleep(0.5)

    async with aiohttp.ClientSession(headers=_HEADERS) as session:
        tasks = []
        for shop in shops:
            domain = shop.get("domain", "")
            if not domain:
                continue
            # Use first product URL if available, else store home
            products = shop.get("products") or []
            url = products[0].get("url", "") if products else shop.get("store_url", "")
            if url:
                tasks.append(_one(domain, url))
        await asyncio.gather(*tasks)

    logger.info("Landing analysis: %d/%d shops analyzed", len(results), len(shops))
    return results
