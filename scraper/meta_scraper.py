"""Meta Ad Library — official Graph API scraper (no Playwright, no ban risk)."""

from __future__ import annotations

import asyncio
import logging
import os
from datetime import datetime, timezone
from typing import Any

import aiohttp

logger = logging.getLogger(__name__)

GRAPH_BASE = "https://graph.facebook.com/v19.0/ads_archive"

# Fields that give the most signal for angle analysis
AD_FIELDS = ",".join([
    "id",
    "ad_creation_time",
    "ad_delivery_start_time",
    "ad_delivery_stop_time",
    "ad_creative_bodies",
    "ad_creative_link_captions",
    "ad_creative_link_titles",
    "ad_creative_link_descriptions",
    "page_name",
    "page_id",
    "spend",
    "impressions",
    "ad_snapshot_url",
    "languages",
    "publisher_platforms",
    "target_ages",
    "target_gender",
])


def _compute_days_running(start_str: str, stop_str: str | None) -> int:
    """Compute how many days an ad has been / was running."""
    fmt = "%Y-%m-%dT%H:%M:%S%z"
    try:
        start = datetime.strptime(start_str, fmt)
    except Exception:
        return 0
    try:
        end = datetime.strptime(stop_str, fmt) if stop_str else datetime.now(timezone.utc)
    except Exception:
        end = datetime.now(timezone.utc)
    return max(0, (end - start).days)


def _parse_spend(spend: dict | None) -> int:
    """Extract upper bound spend estimate."""
    if not spend:
        return 0
    try:
        return int(spend.get("upper_bound") or spend.get("lower_bound") or 0)
    except Exception:
        return 0


def _build_ad_copy(ad: dict) -> str:
    """Concatenate all text fields into a single string for angle analysis."""
    parts: list[str] = []
    for key in ("ad_creative_bodies", "ad_creative_link_titles", "ad_creative_link_captions", "ad_creative_link_descriptions"):
        val = ad.get(key)
        if isinstance(val, list):
            parts.extend(v for v in val if v)
        elif isinstance(val, str) and val:
            parts.append(val)
    return " | ".join(parts)


def _normalise(ad: dict, country: str) -> dict | None:
    """Normalise a raw API ad object into our internal schema."""
    ad_copy = _build_ad_copy(ad)
    if not ad_copy.strip():
        return None

    start = ad.get("ad_delivery_start_time", "")
    stop  = ad.get("ad_delivery_stop_time")
    days  = _compute_days_running(start, stop)
    spend = _parse_spend(ad.get("spend"))

    # Snapshot URL contains the landing page indirectly
    snapshot = ad.get("ad_snapshot_url", "")

    return {
        "ad_copy":          ad_copy,
        "creative_id":      str(ad.get("id", "")),
        "page_name":        ad.get("page_name", ""),
        "start_date":       start,
        "days_running":     days,
        "estimated_spend":  spend,
        "landing_page_url": snapshot,
        "store_domain":     "",          # enriched later if needed
        "country":          country,
        "platform":         "meta",
        "publisher_platforms": ad.get("publisher_platforms", []),
        "languages":        ad.get("languages", []),
    }


class MetaScraper:
    """
    Scrapes Meta Ad Library via the official Graph API.

    Setup:
      1. Go to https://developers.facebook.com/ → Create App (Consumer or Business)
      2. Add "Marketing API" product
      3. Generate a User Access Token with ads_read permission
      4. Set META_ACCESS_TOKEN env var / GitHub secret
    """

    def __init__(
        self,
        access_token: str | None = None,
        max_ads: int = 100,
    ) -> None:
        self.token = access_token or os.getenv("META_ACCESS_TOKEN", "")
        self.max_ads = max_ads
        if not self.token:
            raise ValueError(
                "META_ACCESS_TOKEN is required. "
                "Get a free token at https://developers.facebook.com/"
            )

    async def scrape_ads(self, niche: str, country: str = "FR") -> list[dict]:
        """
        Fetch ads from Meta Ad Library API for a given niche keyword.

        Returns normalised ad dicts sorted by days_running DESC
        (longest-running = most proven, highest signal for angle analysis).
        """
        collected: list[dict] = []
        params = {
            "access_token":       self.token,
            "ad_type":            "ALL",
            "ad_reached_countries": country,
            "search_terms":       niche,
            "fields":             AD_FIELDS,
            "limit":              50,        # max per page
            "search_page_ids":    "",
        }

        logger.info("Fetching Meta ads for niche='%s' country='%s'", niche, country)
        async with aiohttp.ClientSession() as session:
            url: str | None = GRAPH_BASE
            page = 0
            while url and len(collected) < self.max_ads:
                page += 1
                try:
                    async with session.get(
                        url,
                        params=params if page == 1 else None,
                        timeout=aiohttp.ClientTimeout(total=30),
                    ) as resp:
                        if resp.status == 400:
                            body = await resp.json()
                            logger.error("API error: %s", body.get("error", {}).get("message"))
                            break
                        resp.raise_for_status()
                        data: dict[str, Any] = await resp.json()
                except aiohttp.ClientError as exc:
                    logger.error("HTTP error on page %d: %s", page, exc)
                    break

                raw_ads: list[dict] = data.get("data", [])
                for raw in raw_ads:
                    normalised = _normalise(raw, country)
                    if normalised:
                        collected.append(normalised)

                # Pagination cursor
                paging = data.get("paging", {})
                url = paging.get("next")  # None when last page
                params = {}               # next URL already contains all params

                logger.debug("Page %d: +%d ads (total %d)", page, len(raw_ads), len(collected))
                if not raw_ads:
                    break

                await asyncio.sleep(0.5)  # gentle rate-limiting

        # Sort by days_running descending — long-running ads = profitable
        collected.sort(key=lambda a: a["days_running"], reverse=True)
        result = collected[: self.max_ads]
        logger.info("Fetched %d ads for niche='%s'", len(result), niche)
        return result
