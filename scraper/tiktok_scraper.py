"""
TikTok Creative Center scraper — zero token required.

Hits the public Creative Center API to get top-performing ads and
trending products by industry/keyword. Returns the same normalised
ad dict format as MetaScraper so the rest of the pipeline is unchanged.

Key advantage over Meta Playwright:
  - Returns real CTR / CVR / engagement metrics
  - Includes landing_page_url  →  domain  →  Shopify shops
  - Never IP-blocked (public, non-authed endpoint)
  - TikTok is where winning dropshipping products appear first
"""

from __future__ import annotations

import asyncio
import logging
import math
import random
from typing import Any

import aiohttp

from .utils import extract_domain

logger = logging.getLogger(__name__)

_BASE = "https://ads.tiktok.com/creative_radar_api/v1"

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://ads.tiktok.com/business/creativecenter/inspiration/topads/pc/en",
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "fr-FR,fr;q=0.9,en-US;q=0.8",
    "Origin": "https://ads.tiktok.com",
}

# TikTok industry IDs (approximate — used to pre-filter top_ads)
_INDUSTRY_MAP: dict[str, str] = {
    "health":   "20001",
    "wellness": "20001",
    "fitness":  "20001",
    "posture":  "20001",
    "sleep":    "20001",
    "joint":    "20001",
    "pain":     "20001",
    "weight":   "20001",
    "gut":      "20001",
    "foot":     "20001",
    "collagen": "20003",
    "aging":    "20003",
    "hair":     "20003",
    "teeth":    "20003",
    "skin":     "20003",
    "beauty":   "20003",
}


def _industry(niche: str) -> str | None:
    nl = niche.lower()
    for kw, iid in _INDUSTRY_MAP.items():
        if kw in nl:
            return iid
    return None


def _engagement(node: dict) -> int:
    likes    = int(node.get("like_count")    or node.get("likes")    or 0)
    comments = int(node.get("comment_count") or node.get("comments") or 0)
    shares   = int(node.get("share_count")   or node.get("shares")   or 0)
    return likes + comments * 2 + shares * 3


def _normalise(node: dict) -> dict | None:
    title = (node.get("ad_title") or node.get("ad_text") or "").strip()
    brand = (node.get("brand_name") or node.get("advertiser_name") or "").strip()
    ad_copy = " | ".join(filter(None, [title, brand]))
    if not ad_copy:
        return None

    eng = _engagement(node)
    days = min(int(math.sqrt(eng) * 4), 120) if eng > 0 else 0

    landing = (node.get("landing_page_url") or node.get("click_url") or "").strip()
    domain  = extract_domain(landing) if landing else ""

    video = node.get("video_info") or {}
    cover = video.get("cover") or node.get("video_cover") or ""

    return {
        "ad_copy":             ad_copy,
        "creative_id":         str(node.get("ad_id") or node.get("id") or ""),
        "page_name":           brand,
        "start_date":          "",
        "days_running":        days,
        "estimated_spend":     0,
        "landing_page_url":    landing,
        "store_domain":        domain,
        "country":             "",
        "platform":            "tiktok",
        "engagement_score":    eng,
        "reactions":           int(node.get("like_count") or 0),
        "publisher_platforms": ["tiktok"],
        "ctr":                 float(node.get("ctr")      or 0),
        "cvr":                 float(node.get("cvr")      or node.get("cvr_rate") or 0),
        "video_cover":         cover,
    }


class TikTokScraper:
    """Fetch trending ads from TikTok Creative Center (no API key needed)."""

    def __init__(self, max_ads: int = 100) -> None:
        self.max_ads = max_ads

    # ── Private helpers ──────────────────────────────────────────────────────

    async def _get(
        self,
        session: aiohttp.ClientSession,
        path: str,
        params: dict[str, Any],
    ) -> dict:
        try:
            async with session.get(
                f"{_BASE}/{path}",
                params=params,
                timeout=aiohttp.ClientTimeout(total=18),
            ) as resp:
                if resp.status != 200:
                    logger.debug("TikTok %s → HTTP %d", path, resp.status)
                    return {}
                return await resp.json(content_type=None)
        except Exception as exc:
            logger.debug("TikTok %s request failed: %s", path, exc)
            return {}

    async def _keyword_search(
        self,
        session: aiohttp.ClientSession,
        keyword: str,
        country: str,
        period: int = 30,
        page: int = 1,
    ) -> list[dict]:
        """Search Creative Center by keyword — most relevant results for a niche."""
        data = await self._get(session, "top_ads/v2/search", {
            "keyword":      keyword,
            "country_code": country,
            "period":       str(period),
            "page":         str(page),
            "page_size":    "20",
            "order_by":     "engagement_score",
        })
        if data.get("code") != 0:
            return []
        return data.get("data", {}).get("list") or []

    async def _top_ads(
        self,
        session: aiohttp.ClientSession,
        country: str,
        period: int = 30,
        page: int = 1,
        industry_id: str | None = None,
    ) -> list[dict]:
        """General top ads — filtered by industry when possible."""
        params: dict[str, Any] = {
            "period":       str(period),
            "country_code": country,
            "order_by":     "engagement_score",
            "page":         str(page),
            "page_size":    "20",
        }
        if industry_id:
            params["industry_id"] = industry_id
        data = await self._get(session, "top_ads/v2/list", params)
        if data.get("code") != 0:
            return []
        return data.get("data", {}).get("list") or []

    # ── Public API ───────────────────────────────────────────────────────────

    async def scrape_ads(self, niche: str, country: str = "FR") -> list[dict]:
        """
        Fetch top TikTok Creative Center ads for a niche.
        Strategy:
          1. Keyword search (most relevant)
          2. Industry-filtered top ads as supplement
        """
        raw: list[dict] = []
        industry_id = _industry(niche)

        async with aiohttp.ClientSession(headers=_HEADERS) as session:

            # ── Pass 1: keyword search (niche-specific) ───────────────────
            for page in (1, 2, 3):
                if len(raw) >= self.max_ads:
                    break
                nodes = await self._keyword_search(session, niche, country, period=30, page=page)
                raw.extend(nodes)
                if not nodes:
                    break
                await asyncio.sleep(0.4 + random.random() * 0.3)

            # Also try 7-day window (fresher, possibly different results)
            if len(raw) < 20:
                nodes = await self._keyword_search(session, niche, country, period=7, page=1)
                raw.extend(nodes)
                await asyncio.sleep(0.4)

            # ── Pass 2: industry top-ads as supplement ────────────────────
            if len(raw) < 30:
                for period in (30, 7):
                    if len(raw) >= self.max_ads:
                        break
                    for page in (1, 2):
                        nodes = await self._top_ads(session, country, period=period,
                                                    page=page, industry_id=industry_id)
                        raw.extend(nodes)
                        if not nodes:
                            break
                        await asyncio.sleep(0.4 + random.random() * 0.3)

        # Normalise + deduplicate
        seen: set[str] = set()
        ads: list[dict] = []
        for node in raw:
            ad = _normalise(node)
            if not ad:
                continue
            key = ad["creative_id"] or ad["ad_copy"][:40]
            if key not in seen:
                seen.add(key)
                ads.append(ad)

        ads.sort(key=lambda a: a["engagement_score"], reverse=True)
        result = ads[: self.max_ads]
        logger.info("TikTok Creative Center: %d unique ads for niche='%s'", len(result), niche)
        return result
