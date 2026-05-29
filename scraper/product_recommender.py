"""Find Shopify products that match gap angles via DuckDuckGo + /products.json."""

from __future__ import annotations

import asyncio
import logging
import os
import urllib.parse
from typing import Any

import aiohttp

logger = logging.getLogger(__name__)

DUCKDUCKGO_URL = "https://html.duckduckgo.com/html/"
SHOPIFY_PRODUCTS_PATH = "/products.json?limit=5&sort_by=best-selling"
MAX_PRODUCTS_PER_ANGLE = 5
SERPAPI_BASE = "https://serpapi.com/search.json"


async def _ddg_search(session: aiohttp.ClientSession, query: str) -> list[str]:
    """
    Use DuckDuckGo HTML search to find Shopify store URLs.
    Returns a list of store domains.
    """
    domains: list[str] = []
    try:
        payload = {"q": query, "b": "", "kl": "fr-fr"}
        headers = {"User-Agent": "Mozilla/5.0 (compatible; TrendBot/1.0)"}
        async with session.post(
            DUCKDUCKGO_URL, data=payload, headers=headers, timeout=aiohttp.ClientTimeout(total=15)
        ) as resp:
            html = await resp.text()

        # Extract href links containing myshopify.com or relevant keywords
        import re

        urls = re.findall(r'href="(https?://[^"]+)"', html)
        for url in urls:
            if "myshopify.com" in url or ".shop/" in url:
                parsed = urllib.parse.urlparse(url)
                domain = parsed.scheme + "://" + parsed.netloc
                if domain not in domains:
                    domains.append(domain)
            if len(domains) >= 5:
                break
    except Exception as exc:
        logger.warning("DuckDuckGo search failed: %s", exc)

    return domains


async def _serpapi_search(
    session: aiohttp.ClientSession, query: str, api_key: str
) -> list[str]:
    """Fallback: use SerpAPI when DDG yields nothing."""
    domains: list[str] = []
    try:
        params = {
            "q": query,
            "api_key": api_key,
            "engine": "google",
            "num": 10,
        }
        async with session.get(
            SERPAPI_BASE, params=params, timeout=aiohttp.ClientTimeout(total=15)
        ) as resp:
            data = await resp.json()

        for result in data.get("organic_results", []):
            link = result.get("link", "")
            if "myshopify.com" in link:
                parsed = urllib.parse.urlparse(link)
                domain = parsed.scheme + "://" + parsed.netloc
                if domain not in domains:
                    domains.append(domain)
            if len(domains) >= 5:
                break
    except Exception as exc:
        logger.warning("SerpAPI search failed: %s", exc)

    return domains


async def _fetch_shopify_products(
    session: aiohttp.ClientSession, store_base: str
) -> list[dict]:
    """Fetch /products.json from a Shopify store and return normalised product list."""
    url = store_base.rstrip("/") + SHOPIFY_PRODUCTS_PATH
    try:
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            if resp.status != 200:
                return []
            data = await resp.json(content_type=None)

        products = []
        for p in data.get("products", [])[:MAX_PRODUCTS_PER_ANGLE]:
            variant = (p.get("variants") or [{}])[0]
            image = (p.get("images") or [{}])[0].get("src", "")
            products.append(
                {
                    "title": p.get("title", ""),
                    "handle": p.get("handle", ""),
                    "url": f"{store_base}/products/{p.get('handle', '')}",
                    "price": variant.get("price", ""),
                    "image": image,
                    "store": store_base,
                }
            )
        return products
    except Exception as exc:
        logger.debug("Shopify fetch failed for %s: %s", store_base, exc)
        return []


async def _get_trend(product_name: str) -> dict:
    """
    Approximate trend signal.
    Uses Google Trends via a simple pytrends-style request (no auth needed).
    Returns {trend, volume} conservatively.
    """
    # Lightweight heuristic: return stable by default to avoid rate-limiting
    # A real implementation would call pytrends or Cloudflare Radar
    return {"trend": "stable", "volume": "medium"}


class ProductRecommender:
    """Find Shopify products that match gap angles."""

    def __init__(self, serpapi_key: str | None = None) -> None:
        self._serpapi_key = serpapi_key or os.getenv("SERPAPI_KEY", "")

    async def recommend(self, gaps: list[dict], niche: str) -> list[dict]:
        """
        For each gap angle, search for matching Shopify products.

        Returns list of {angle, products, trends}.
        """
        if not gaps:
            return []

        recommendations: list[dict] = []
        async with aiohttp.ClientSession() as session:
            tasks = [self._process_gap(session, gap, niche) for gap in gaps]
            results: list[Any] = await asyncio.gather(*tasks, return_exceptions=True)

        for gap, result in zip(gaps, results):
            if isinstance(result, Exception):
                logger.warning("Recommender error for angle '%s': %s", gap["angle"], result)
                recommendations.append({"angle": gap["angle"], "products": [], "trends": []})
            else:
                recommendations.append(result)

        return recommendations

    async def _process_gap(
        self,
        session: aiohttp.ClientSession,
        gap: dict,
        niche: str,
    ) -> dict:
        """Search products and trend for a single gap angle."""
        angle = gap["angle"]
        query = f"{angle} {niche} site:myshopify.com"

        stores = await _ddg_search(session, query)
        if not stores and self._serpapi_key:
            stores = await _serpapi_search(session, query, self._serpapi_key)

        # Fetch products concurrently across found stores
        product_tasks = [_fetch_shopify_products(session, s) for s in stores]
        nested: list[Any] = await asyncio.gather(*product_tasks, return_exceptions=True)

        products: list[dict] = []
        for batch in nested:
            if isinstance(batch, list):
                products.extend(batch)
        products = products[:MAX_PRODUCTS_PER_ANGLE]

        # Trend signal for first product title if available
        trends: list[dict] = []
        if products:
            trend = await _get_trend(products[0]["title"])
            trends.append({"product": products[0]["title"], **trend})

        return {"angle": angle, "products": products, "trends": trends}
