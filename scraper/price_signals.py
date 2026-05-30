"""Estimate product margin by comparing Shopify price vs Aliexpress via DDG."""

from __future__ import annotations

import asyncio
import logging
import re

import aiohttp

logger = logging.getLogger(__name__)

_DDG_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0",
    "Accept-Language": "fr-FR,fr;q=0.9",
}
_RE_PRICE = re.compile(r'(\d{1,4}[.,]\d{2}|\d{1,4})\s*(?:€|\$|EUR|USD)', re.IGNORECASE)


async def _ddg_aliexpress_price(session: aiohttp.ClientSession, product_title: str) -> float | None:
    """Search DDG for Aliexpress equivalent and return estimated source price."""
    # Use first 4 words to avoid too-specific search
    kw = " ".join(product_title.split()[:4])
    try:
        url = "https://html.duckduckgo.com/html/"
        params = {"q": f"site:aliexpress.com {kw}"}
        async with session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=10)) as r:
            if r.status != 200:
                return None
            text = await r.text()
            # Extract prices from result snippets
            prices = []
            for match in _RE_PRICE.finditer(text[:20000]):
                try:
                    val = float(match.group(1).replace(',', '.'))
                    if 0.5 < val < 200:  # plausible range for dropship product
                        prices.append(val)
                except ValueError:
                    pass
            if prices:
                # Return median of lowest prices (source price estimate)
                prices.sort()
                return prices[len(prices) // 4]  # lower quartile = source price
    except Exception as exc:
        logger.debug("Aliexpress DDG price failed for '%s': %s", product_title, exc)
    return None


async def enrich_products_with_margins(
    shops: list[dict],
    max_products: int = 3,
    max_concurrent: int = 4,
) -> list[dict]:
    """
    Add `source_price_est` and `margin_multiplier` to each product in each shop.
    Modifies shops in place and returns them.
    """
    sem = asyncio.Semaphore(max_concurrent)

    async def _one(product: dict) -> None:
        async with sem:
            title = product.get("title", "")
            shopify_price = float(str(product.get("price", "0")).replace(",", ".") or 0)
            if not title or shopify_price <= 0:
                return
            ali_price = await _ddg_aliexpress_price(session, title)
            if ali_price and ali_price > 0:
                product["source_price_est"]   = round(ali_price, 2)
                product["margin_multiplier"]  = round(shopify_price / ali_price, 1)
                product["margin_signal"]      = (
                    "excellent" if shopify_price / ali_price >= 4 else
                    "good"      if shopify_price / ali_price >= 2.5 else
                    "low"
                )
            await asyncio.sleep(0.5)

    async with aiohttp.ClientSession(headers=_DDG_HEADERS) as session:
        tasks = []
        for shop in shops:
            for product in (shop.get("products") or [])[:max_products]:
                tasks.append(_one(product))
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    logger.info("Margin enrichment done for %d shops", len(shops))
    return shops
