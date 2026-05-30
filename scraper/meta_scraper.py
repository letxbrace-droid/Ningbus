"""Meta Ad Library scraper — Playwright + Chromium open-source, GraphQL interception."""

from __future__ import annotations

import asyncio
import json
import logging
import math
import random
from datetime import datetime, timezone
from typing import Any
from urllib.parse import quote

from playwright.async_api import Browser, BrowserContext, Page, Response, async_playwright

from .utils import extract_domain

logger = logging.getLogger(__name__)

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
]

# Stealth script: wipe all webdriver fingerprints
STEALTH_JS = """
    delete Object.getPrototypeOf(navigator).webdriver;
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'plugins',   { get: () => [1, 2, 3, 4, 5] });
    Object.defineProperty(navigator, 'languages', { get: () => ['fr-FR', 'fr', 'en-US', 'en'] });
    window.chrome = { runtime: {}, loadTimes: function(){}, csi: function(){} };
    Object.defineProperty(navigator, 'permissions', {
        get: () => ({ query: () => Promise.resolve({ state: 'granted' }) })
    });
"""

COOKIE_SELECTORS = [
    '[data-testid="cookie-policy-manage-dialog-accept-button"]',
    'button[title="Allow all cookies"]',
    'button:has-text("Allow all cookies")',
    'button:has-text("Accepter tout")',
    'button:has-text("Accept All")',
    '[aria-label="Allow all cookies"]',
]


# ── GraphQL parsing ──────────────────────────────────────────────────────────

def _parse_days(start: str, stop: str | None) -> int:
    for fmt in ("%Y-%m-%dT%H:%M:%S+0000", "%Y-%m-%dT%H:%M:%S%z"):
        try:
            s = datetime.strptime(start[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
            e = (
                datetime.strptime(stop[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
                if stop
                else datetime.now(timezone.utc)
            )
            return max(0, (e - s).days)
        except Exception:
            continue
    return 0


def _text_from(node: dict) -> str:
    """Concatenate all creative text fields into one string."""
    parts: list[str] = []
    for key in (
        "ad_creative_bodies",
        "ad_creative_link_titles",
        "ad_creative_link_captions",
        "ad_creative_link_descriptions",
        "body_text",
        "message",
        "title",
        "description",
    ):
        val = node.get(key)
        if isinstance(val, list):
            parts.extend(v for v in val if v)
        elif isinstance(val, str) and val:
            parts.append(val)
    return " | ".join(parts)


def _is_ad_node(node: dict) -> bool:
    """Heuristic: detect an ad-like dict."""
    ad_keys = {
        "ad_creative_bodies", "collation_id", "ad_delivery_start_time",
        "ad_snapshot_url", "page_name", "creative_id",
    }
    return bool(ad_keys & node.keys())


def _normalise(node: dict) -> dict | None:
    ad_copy = _text_from(node)
    if not ad_copy.strip():
        return None

    creative_id = str(
        node.get("id") or node.get("collation_id") or node.get("creative_id") or ""
    )
    start = node.get("ad_delivery_start_time") or node.get("start_date") or ""
    stop  = node.get("ad_delivery_stop_time")
    days  = _parse_days(start, stop) if start else 0

    spend = 0
    if isinstance(node.get("spend"), dict):
        spend = int(node["spend"].get("upper_bound") or 0)

    snapshot = node.get("ad_snapshot_url") or ""
    domain   = extract_domain(snapshot) if snapshot else ""

    # Engagement signals (Meta may return these)
    reactions = 0
    comments  = 0
    shares    = 0
    if isinstance(node.get("engagement"), dict):
        eng = node["engagement"]
        reactions = int(eng.get("reaction_count") or eng.get("likes") or 0)
        comments  = int(eng.get("comment_count") or eng.get("comments") or 0)
        shares    = int(eng.get("share_count") or eng.get("shares") or 0)
    # Also check top-level keys
    reactions = reactions or int(node.get("reaction_count") or node.get("likes_count") or 0)
    comments  = comments  or int(node.get("comment_count") or 0)
    shares    = shares    or int(node.get("share_count") or 0)

    engagement_score = reactions + comments * 2 + shares * 3

    if days == 0 and engagement_score > 0:
        days = min(int(math.sqrt(engagement_score) * 4), 90)

    return {
        "ad_copy":            ad_copy,
        "creative_id":        creative_id,
        "page_name":          node.get("page_name", ""),
        "start_date":         start,
        "days_running":       days,
        "estimated_spend":    spend,
        "landing_page_url":   snapshot,
        "store_domain":       domain,
        "country":            "",
        "platform":           "meta",
        "engagement_score":   engagement_score,
        "reactions":          reactions,
        "publisher_platforms": node.get("publisher_platforms") or [],
    }


def _walk_and_collect(data: Any) -> list[dict]:
    """Recursively walk a JSON tree and extract ad nodes."""
    results: list[dict] = []

    def _walk(node: Any) -> None:
        if isinstance(node, list):
            for item in node:
                _walk(item)
        elif isinstance(node, dict):
            if _is_ad_node(node):
                ad = _normalise(node)
                if ad:
                    results.append(ad)
            for v in node.values():
                _walk(v)

    _walk(data)
    return results


def _parse_response_body(body: str) -> list[dict]:
    """Parse a raw response body — handles JSON, ndjson, and JSON with prefix."""
    ads: list[dict] = []
    # Facebook sometimes prepends "for (;;);" as a CSRF guard
    body = body.lstrip()
    if body.startswith("for (;;);"):
        body = body[9:]

    # Try ndjson (one JSON object per line)
    for line in body.splitlines():
        line = line.strip()
        if not line or not (line.startswith("{") or line.startswith("[")):
            continue
        try:
            data = json.loads(line)
            ads.extend(_walk_and_collect(data))
        except json.JSONDecodeError:
            pass

    return ads


# ── Scraper class ────────────────────────────────────────────────────────────

class MetaScraper:
    """
    Scrapes Meta Ad Library using Playwright + Chromium (open source).

    Intercepts all GraphQL/XHR responses and extracts ad data from JSON payloads.
    No external API token needed — uses the public Ad Library website.
    """

    def __init__(self, max_ads: int = 100, headless: bool = True) -> None:
        self.max_ads  = max_ads
        self.headless = headless
        self._collected: list[dict] = []

    async def _on_response(self, response: Response) -> None:
        """Intercept network responses and extract ad data."""
        url = response.url
        # Target Facebook's GraphQL and AJAX endpoints
        if not any(p in url for p in ("graphql", "search_ads", "ads_archive", "api/graphql")):
            return
        if response.status != 200:
            return
        try:
            body = await response.text()
            if '"ad_' not in body and "creative" not in body:
                return
            ads = _parse_response_body(body)
            if ads:
                logger.debug("Intercepted %d ads from %s", len(ads), url[:80])
                self._collected.extend(ads)
        except Exception as exc:
            logger.debug("Response parse error: %s", exc)

    async def _dismiss_cookies(self, page: Page) -> None:
        for selector in COOKIE_SELECTORS:
            try:
                btn = page.locator(selector)
                if await btn.count() > 0:
                    await btn.first.click(timeout=3000)
                    logger.debug("Dismissed cookie banner via: %s", selector)
                    await asyncio.sleep(1.5)
                    return
            except Exception:
                pass

    async def scrape_ads(self, niche: str, country: str = "FR") -> list[dict]:
        """
        Scrape Meta Ad Library for a niche keyword.
        Returns a list of normalised ad dicts, sorted by days_running DESC.
        """
        self._collected = []

        async with async_playwright() as pw:
            browser: Browser = await pw.chromium.launch(
                headless=self.headless,
                args=[
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-blink-features=AutomationControlled",
                    "--disable-gpu",
                    "--window-size=1280,900",
                ],
            )

            ctx: BrowserContext = await browser.new_context(
                user_agent=random.choice(USER_AGENTS),
                viewport={"width": 1280, "height": 900},
                locale="fr-FR",
                extra_http_headers={
                    "Accept-Language": "fr-FR,fr;q=0.9,en-US;q=0.8",
                    "sec-ch-ua": '"Chromium";v="124", "Google Chrome";v="124"',
                    "sec-ch-ua-platform": '"Windows"',
                    "sec-ch-ua-mobile": "?0",
                },
            )
            await ctx.add_init_script(STEALTH_JS)

            page: Page = await ctx.new_page()
            page.on("response", self._on_response)

            url = (
                f"https://www.facebook.com/ads/library/"
                f"?ad_type=all&country={country}&q={quote(niche)}"
                f"&search_type=keyword_unordered&active_status=active"
            )

            logger.info("Opening Meta Ad Library for niche='%s' country='%s'", niche, country)
            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=45_000)
            except Exception as exc:
                logger.error("Navigation failed: %s", exc)
                await browser.close()
                return []

            await asyncio.sleep(random.uniform(3.5, 5.0))
            await self._dismiss_cookies(page)
            await asyncio.sleep(1.5)

            # Scroll 6× to trigger GraphQL pagination
            for i in range(6):
                await page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
                wait = random.uniform(2.5, 4.0)
                logger.debug("Scroll %d/6 — %d ads so far (waiting %.1fs)", i + 1, len(self._collected), wait)
                await asyncio.sleep(wait)
                if len(self._collected) >= self.max_ads:
                    break

            await browser.close()

        # Deduplicate by creative_id (fallback: first 50 chars of copy)
        seen: set[str] = set()
        unique: list[dict] = []
        for ad in self._collected:
            key = ad["creative_id"] or ad["ad_copy"][:50]
            if key not in seen:
                seen.add(key)
                unique.append(ad)

        # Sort by days_running DESC — longest-running = most profitable signal
        unique.sort(key=lambda a: a["days_running"], reverse=True)
        result = unique[: self.max_ads]
        logger.info("Collected %d unique ads for niche='%s'", len(result), niche)
        return result
