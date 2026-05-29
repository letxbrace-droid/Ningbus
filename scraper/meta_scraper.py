"""Meta Ad Library scraper using Playwright + GraphQL interception."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import random
from typing import Any

from playwright.async_api import (
    Browser,
    BrowserContext,
    Page,
    Request,
    Response,
    async_playwright,
)

from .utils import extract_domain

logger = logging.getLogger(__name__)

# GraphQL endpoint fragment that carries ad results
GRAPHQL_PATH = "ads_by_pages_v2"

USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
]


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _parse_graphql_ads(raw: Any) -> list[dict]:
    """
    Walk a GraphQL response body and extract ad fields.
    Meta's schema can shift; we try several known paths defensively.
    """
    ads: list[dict] = []

    def _walk(node: Any) -> None:
        if isinstance(node, list):
            for item in node:
                _walk(item)
        elif isinstance(node, dict):
            # Detect an ad node by the presence of creative_id OR ad_copy
            if "creative_id" in node or "ad_copy" in node:
                ad = _extract_fields(node)
                if ad:
                    ads.append(ad)
            else:
                for v in node.values():
                    _walk(v)

    _walk(raw)
    return ads


def _extract_fields(node: dict) -> dict | None:
    """Extract normalised fields from a raw GraphQL ad node."""
    try:
        # Ad copy (may live at different depths)
        ad_copy = (
            node.get("ad_copy")
            or node.get("snapshot", {}).get("body", {}).get("text", "")
            or node.get("body_text", "")
            or ""
        )

        creative_id = str(node.get("creative_id") or node.get("id") or "")

        start_date = (
            node.get("start_date")
            or node.get("ad_delivery_start_time")
            or ""
        )

        days_running = node.get("days_running") or 0
        estimated_spend = node.get("spend", {}).get("upper_bound") or 0

        # Landing page
        snapshot = node.get("snapshot") or {}
        link_url = (
            node.get("landing_page_url")
            or snapshot.get("link_url")
            or snapshot.get("caption")
            or ""
        )

        country = (
            node.get("country")
            or (node.get("target_locations") or [{}])[0].get("name", "")
            or ""
        )

        domain = extract_domain(link_url) if link_url else ""
        platform = "meta"

        if not ad_copy and not creative_id:
            return None

        return {
            "ad_copy": ad_copy,
            "creative_id": creative_id,
            "start_date": str(start_date),
            "days_running": int(days_running),
            "estimated_spend": int(estimated_spend),
            "landing_page_url": link_url,
            "store_domain": domain,
            "country": country,
            "platform": platform,
        }
    except Exception as exc:
        logger.debug("Field extraction failed: %s", exc)
        return None


async def _random_delay(lo: float = 2.0, hi: float = 5.0) -> None:
    await asyncio.sleep(random.uniform(lo, hi))


# ---------------------------------------------------------------------------
# Main scraper class
# ---------------------------------------------------------------------------

class MetaScraper:
    """Async scraper for Meta Ad Library."""

    def __init__(
        self,
        proxy_token: str | None = None,
        max_ads: int = 100,
        headless: bool = True,
    ) -> None:
        self.proxy_token = proxy_token
        self.max_ads = max_ads
        self.headless = headless
        self._collected_ads: list[dict] = []

    def _build_proxy(self) -> dict | None:
        """Build Playwright proxy config from Bright Data token."""
        token = self.proxy_token or os.getenv("BRIGHT_DATA_TOKEN", "")
        if not token:
            logger.warning("No proxy token found — scraping without proxy (higher ban risk)")
            return None
        return {
            "server": "socks5://brd.superproxy.io:22225",
            "username": f"brd-customer-{token}",
            "password": token,
        }

    async def _handle_response(self, response: Response) -> None:
        """Intercept GraphQL responses and extract ad data."""
        if GRAPHQL_PATH not in response.url:
            return
        try:
            body = await response.json()
            ads = _parse_graphql_ads(body)
            logger.debug("Intercepted %d ads from GraphQL response", len(ads))
            self._collected_ads.extend(ads)
        except Exception as exc:
            logger.debug("GraphQL response parse error: %s", exc)

    async def _do_scroll(self, page: Page, times: int = 5) -> None:
        """Scroll down to trigger lazy-loaded ad batches."""
        for i in range(times):
            await page.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            logger.debug("Scroll %d/%d", i + 1, times)
            await _random_delay(2.0, 4.0)

    async def scrape_ads(
        self,
        niche: str,
        country: str = "FR",
    ) -> list[dict]:
        """
        Scrape Meta Ad Library for a given niche keyword.

        Returns a list of ad dicts with normalised fields.
        """
        self._collected_ads = []
        proxy = self._build_proxy()

        async with async_playwright() as pw:
            browser_args = ["--no-sandbox", "--disable-dev-shm-usage"]
            browser: Browser = await pw.chromium.launch(
                headless=self.headless,
                args=browser_args,
                proxy=proxy,
            )
            ua = random.choice(USER_AGENTS)
            context: BrowserContext = await browser.new_context(
                user_agent=ua,
                viewport={"width": 1280, "height": 800},
                locale="fr-FR",
            )

            # Apply stealth patches to avoid bot detection
            await context.add_init_script(
                """
                Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                Object.defineProperty(navigator, 'languages', {get: () => ['fr-FR', 'fr', 'en']});
                Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3]});
                """
            )

            page: Page = await context.new_page()
            page.on("response", self._handle_response)

            url = (
                f"https://www.facebook.com/ads/library/"
                f"?ad_type=all&country={country}&q={niche}&search_type=keyword_unordered"
            )

            logger.info("Navigating to Meta Ad Library for niche='%s' country='%s'", niche, country)
            try:
                await page.goto(url, wait_until="domcontentloaded", timeout=30_000)
            except Exception as exc:
                logger.error("Navigation failed: %s", exc)
                await browser.close()
                return []

            await _random_delay(3.0, 5.0)

            # Accept cookie banner if present
            try:
                accept_btn = page.locator('[data-testid="cookie-policy-manage-dialog-accept-button"]')
                if await accept_btn.count():
                    await accept_btn.click()
                    await _random_delay(1.0, 2.0)
            except Exception:
                pass

            # Scroll to load more ads (triggers GraphQL pagination)
            await self._do_scroll(page, times=5)

            await browser.close()

        # Deduplicate by creative_id
        seen: set[str] = set()
        unique: list[dict] = []
        for ad in self._collected_ads:
            cid = ad.get("creative_id", "")
            if cid not in seen:
                seen.add(cid)
                unique.append(ad)

        result = unique[: self.max_ads]
        logger.info("Scraped %d unique ads for niche='%s'", len(result), niche)
        return result
