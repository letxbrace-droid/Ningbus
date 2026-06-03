"""Find active Shopify shops via DuckDuckGo search + /products.json verification."""

from __future__ import annotations

import asyncio
import logging
import random
import re
from datetime import datetime, timezone
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


def _strip_html(html: str) -> str:
    text = re.sub(r'<[^>]+>', ' ', html or "")
    return re.sub(r'\s+', ' ', text).strip()


async def _fetch_products(
    session: aiohttp.ClientSession,
    domain: str,
) -> list[dict]:
    """Fetch best-selling products from Shopify /products.json — full field extraction."""
    try:
        url = f"https://{domain}/products.json?limit=30&sort_by=best-selling"
        async with session.get(url, timeout=REQUEST_TIMEOUT) as resp:
            if resp.status != 200:
                return []
            data = await resp.json(content_type=None)
            products = []
            now = datetime.now(timezone.utc)

            for p in data.get("products", [])[:15]:
                variants = p.get("variants") or []
                images   = p.get("images") or []

                # ── Pricing ──────────────────────────────────────────
                prices = []
                compare_prices = []
                for v in variants:
                    try:
                        if v.get("price"):
                            prices.append(float(v["price"]))
                        if v.get("compare_at_price"):
                            compare_prices.append(float(v["compare_at_price"]))
                    except (ValueError, TypeError):
                        pass
                price_min = min(prices) if prices else 0.0
                price_max = max(prices) if prices else 0.0
                compare_at = max(compare_prices) if compare_prices else 0.0
                discount_pct = (
                    round((compare_at - price_min) / compare_at * 100)
                    if compare_at > price_min > 0 else 0
                )

                # ── Availability ─────────────────────────────────────
                available = any(v.get("available", True) for v in variants)
                in_stock_count = sum(1 for v in variants if v.get("available", True))

                # ── Product age ───────────────────────────────────────
                published_days: int | None = None
                pub_str = p.get("published_at") or p.get("created_at") or ""
                if pub_str:
                    try:
                        pub = datetime.fromisoformat(pub_str.replace("Z", "+00:00"))
                        published_days = max(0, (now - pub).days)
                    except Exception:
                        pass

                # ── Description ───────────────────────────────────────
                description = _strip_html(p.get("body_html") or "")[:400]

                # ── Variant options ────────────────────────────────────
                options = [
                    o["name"] for o in (p.get("options") or [])
                    if o.get("name") and o["name"].lower() != "title"
                ]

                # ── Tags ───────────────────────────────────────────────
                tags = [t.strip() for t in (p.get("tags") or "").split(",") if t.strip()][:12]

                products.append({
                    "title":            p.get("title", ""),
                    "vendor":           p.get("vendor", ""),
                    "product_type":     p.get("product_type", ""),
                    "tags":             tags,
                    "price":            str(round(price_min, 2)) if price_min else "",
                    "price_max":        str(round(price_max, 2)) if price_max != price_min else "",
                    "compare_at_price": str(round(compare_at, 2)) if compare_at else "",
                    "discount_pct":     discount_pct,
                    "available":        available,
                    "in_stock_variants": in_stock_count,
                    "variants_count":   len(variants),
                    "options":          options,
                    "images_count":     len(images),
                    "image":            images[0].get("src", "") if images else "",
                    "url":              f"https://{domain}/products/{p.get('handle', '')}",
                    "published_days":   published_days,
                    "description":      description,
                })
            return products
    except Exception as exc:
        logger.debug("Products fetch failed for %s: %s", domain, exc)
        return []


async def _fetch_product_reviews(
    session: aiohttp.ClientSession,
    product_url: str,
) -> dict:
    """Scrape product page for review count + rating (supports Judge.me, Yotpo, Loox, SPR, schema.org)."""
    try:
        async with session.get(
            product_url,
            timeout=aiohttp.ClientTimeout(total=8),
            allow_redirects=True,
        ) as r:
            if r.status != 200:
                return {}
            html = (await r.content.read(60000)).decode("utf-8", errors="replace")

            # Judge.me widget attributes
            m = re.search(
                r'jdgm-prev-badge[^>]*data-average-rating="([0-9.]+)"[^>]*data-number-of-reviews="(\d+)"',
                html, re.I,
            )
            if m:
                return {"star_rating": float(m.group(1)), "review_count": int(m.group(2)), "review_app": "judge.me"}

            # Yotpo
            m = re.search(r'average_score["\s:]+([0-9.]+)', html)
            n = re.search(r'total_reviews["\s:]+(\d+)', html)
            if m:
                return {"star_rating": round(float(m.group(1)), 1), "review_count": int(n.group(1)) if n else None, "review_app": "yotpo"}

            # Loox
            m = re.search(r'loox[^>]*data-rating="([0-9.]+)"[^>]*data-count="(\d+)"', html, re.I)
            if m:
                return {"star_rating": float(m.group(1)), "review_count": int(m.group(2)), "review_app": "loox"}

            # Shopify native SPR
            m = re.search(r'data-rating="([0-9.]+)"', html)
            n = re.search(r'(\d+)\s*(?:review|avis|évaluation)', html, re.I)
            if m:
                return {"star_rating": float(m.group(1)), "review_count": int(n.group(1)) if n else None, "review_app": "shopify_spr"}

            # schema.org AggregateRating (most universal)
            m = re.search(r'"ratingValue"\s*:\s*"?([0-9.]+)"?', html)
            n = re.search(r'"reviewCount"\s*:\s*"?(\d+)"?', html)
            if m:
                return {"star_rating": float(m.group(1)), "review_count": int(n.group(1)) if n else None, "review_app": "schema.org"}

            return {}
    except Exception:
        return {}


async def _fetch_collections_count(
    session: aiohttp.ClientSession,
    domain: str,
) -> int | None:
    """Count published collections — proxy for catalog breadth."""
    try:
        url = f"https://{domain}/collections.json?limit=250"
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=8)) as r:
            if r.status != 200:
                return None
            data = await r.json(content_type=None)
            return len(data.get("collections", []))
    except Exception:
        return None


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

    # Enrich top 4 products with review data concurrently
    _review_sem = asyncio.Semaphore(4)

    async def _add_reviews(p: dict) -> None:
        async with _review_sem:
            rev = await _fetch_product_reviews(session, p["url"])
            p.update(rev)

    reviews_tasks = [_add_reviews(p) for p in products[:4]]

    # Run reviews + scaling signals + collections count concurrently
    scaling_signals, collections_count, *_ = await asyncio.gather(
        _detect_scaling_signals(session, domain),
        _fetch_collections_count(session, domain),
        *reviews_tasks,
        return_exceptions=True,
    )
    if isinstance(scaling_signals, Exception):
        scaling_signals = []
    if isinstance(collections_count, Exception):
        collections_count = None

    # Derived shop-level stats from product data
    prices = [float(p["price"]) for p in products if p.get("price")]
    avg_product_price = round(sum(prices) / len(prices), 2) if prices else 0.0
    discounted = sum(1 for p in products if p.get("discount_pct", 0) > 0)
    reviewed = sum(1 for p in products if p.get("review_count"))
    avg_rating = None
    ratings = [p["star_rating"] for p in products if p.get("star_rating")]
    if ratings:
        avg_rating = round(sum(ratings) / len(ratings), 1)

    return {
        "domain":               domain,
        "store_url":            f"https://{domain}",
        "source":               source,
        "products":             products,
        "scaling_signals":      scaling_signals or [],
        "collections_count":    collections_count,
        "avg_product_price":    avg_product_price,
        "products_with_discount": discounted,
        "products_with_reviews":  reviewed,
        "avg_product_rating":   avg_rating,
        "angles_used":          [],
        "angle_gaps":           [],
        "ads_count":            0,
        "max_days_running":     0,
        "avg_days_running":     0.0,
        "estimated_spend":      0,
        "scaling_score":        round(len(products) * 5.0 + (collections_count or 0) * 0.5, 1),
        "dominant_angle":       "",
        "platforms":            [],
        "ad_examples":          [],
    }


async def find_scaling_shops(
    ads: list[dict],
    niche: str,
    country: str = "FR",
    exclude_domains: set[str] | None = None,
    seed_domains: set[str] | None = None,
) -> list[dict]:
    """
    Find active Shopify shops for a niche.
    1. Extract domains from ad landing pages (if any).
    2. Supplement with DuckDuckGo diversified queries.
    3. Verify each shop is accessible and has products.
    Returns up to 10 shops sorted by product count.
    seed_domains are always checked regardless of DDG results (history fallback).
    """
    exclude = exclude_domains or set()
    domains: set[str] = set()

    # ── Source 0: forced seeds (history / caller-supplied) ──────────────────
    # Seeds bypass the exclusion list — they are known-good shops from history
    # that we always want to re-verify when DDG returns nothing.
    if seed_domains:
        domains.update(seed_domains)
        logger.info("shop_finder: seeded %d domains from caller", len(seed_domains))

    # ── Source 1: landing URLs directly from ad data ────────────────────────
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

    # ── Source 4: history seed (when DDG is blocked / returns nothing) ──────
    # If we still have fewer than 3 candidates, fall back to previously verified
    # domains from the exclusion list (they were live in a previous run).
    if len(domains) < 3 and exclude:
        seed = list(exclude)[:15]
        logger.info("shop_finder: DDG returned no results — seeding with %d known domains from history", len(seed))
        domains.update(seed)

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
