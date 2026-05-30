"""Priority 3: external trend cross-references — Google Trends, TikTok organic, Amazon."""

from __future__ import annotations

import asyncio
import logging

import aiohttp

logger = logging.getLogger(__name__)

_DDG_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0",
    "Accept-Language": "fr-FR,fr;q=0.9",
}


async def google_trends_scores(keywords: list[str], geo: str = "FR") -> dict[str, dict]:
    """Return {keyword: {score: int 0-100, direction: 'rising'|'stable'|'falling'}}."""
    try:
        from pytrends.request import TrendReq  # optional dependency
    except ImportError:
        logger.warning("pytrends not installed — skipping Google Trends")
        return {}

    loop = asyncio.get_event_loop()
    results: dict[str, dict] = {}

    for kw in keywords[:8]:
        def _fetch(k=kw):
            pt = TrendReq(hl="fr-FR", tz=60, timeout=(10, 30))
            pt.build_payload([k], timeframe="today 3-m", geo=geo)
            df = pt.interest_over_time()
            if df.empty or k not in df.columns:
                return k, {"score": None, "direction": "stable"}
            vals = df[k].tolist()
            recent = sum(vals[-4:]) / 4 if len(vals) >= 4 else vals[-1]
            prior  = sum(vals[-8:-4]) / 4 if len(vals) >= 8 else recent
            direction = "rising" if recent > prior * 1.2 else "falling" if recent < prior * 0.8 else "stable"
            return k, {"score": round(recent), "direction": direction}

        try:
            k, v = await asyncio.wait_for(
                loop.run_in_executor(None, _fetch),
                timeout=35.0,
            )
            results[k] = v
        except (asyncio.TimeoutError, Exception) as exc:
            logger.warning("Google Trends skipped for '%s': %s", kw, exc)
            results[kw] = {"score": None, "direction": "stable"}
        await asyncio.sleep(1.0)

    return results


async def tiktok_organic_signal(keywords: list[str]) -> dict[str, dict]:
    """DDG HTML search proxy for site:tiktok.com — estimates organic TikTok presence."""
    results: dict[str, dict] = {}
    async with aiohttp.ClientSession(headers=_DDG_HEADERS) as session:
        for kw in keywords[:10]:
            try:
                url = "https://html.duckduckgo.com/html/"
                params = {"q": f"site:tiktok.com {kw}"}
                async with session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=10)) as r:
                    text = await r.text()
                    count = text.count("result__snippet")
                    level = "high" if count >= 8 else "medium" if count >= 3 else "low" if count >= 1 else "none"
                    results[kw] = {"result_count": count, "level": level}
            except Exception as exc:
                logger.warning("TikTok DDG error for '%s': %s", kw, exc)
                results[kw] = {"result_count": None, "level": "unknown"}
            await asyncio.sleep(0.8)
    return results


async def amazon_competition_signal(keywords: list[str], marketplace: str = "fr") -> dict[str, dict]:
    """DDG HTML search proxy for site:amazon.{marketplace} — estimates Amazon competition."""
    results: dict[str, dict] = {}
    async with aiohttp.ClientSession(headers=_DDG_HEADERS) as session:
        for kw in keywords[:10]:
            try:
                url = "https://html.duckduckgo.com/html/"
                params = {"q": f"site:amazon.{marketplace} {kw}"}
                async with session.get(url, params=params, timeout=aiohttp.ClientTimeout(total=10)) as r:
                    text = await r.text()
                    count = text.count("result__snippet")
                    # More results = higher competition
                    level = "saturated" if count >= 8 else "high" if count >= 4 else "medium" if count >= 2 else "low"
                    results[kw] = {"result_count": count, "competition_level": level}
            except Exception as exc:
                logger.warning("Amazon DDG error for '%s': %s", kw, exc)
                results[kw] = {"result_count": None, "competition_level": "unknown"}
            await asyncio.sleep(0.8)
    return results


async def enrich_with_trend_signals(
    angle_kpis: list[dict],
    country: str = "FR",
    top_n: int = 10,
) -> list[dict]:
    """
    Add `trend_signals` dict to top N angle KPIs (sorted by priority_score).
    Modifies dicts in place and returns the list.
    """
    top_angles = sorted(angle_kpis, key=lambda k: k.get("priority_score", 0) or 0, reverse=True)[:top_n]
    keywords = [k["angle"] for k in top_angles]
    if not keywords:
        return angle_kpis

    logger.info("Fetching external trend signals for %d angles", len(keywords))
    marketplace = "fr" if country in ("FR", "BE", "CH") else "co.uk" if country == "GB" else "com"

    gt, tiktok, amz = await asyncio.gather(
        google_trends_scores(keywords, geo=country),
        tiktok_organic_signal(keywords),
        amazon_competition_signal(keywords, marketplace),
        return_exceptions=True,
    )
    if isinstance(gt, Exception):     gt = {}
    if isinstance(tiktok, Exception): tiktok = {}
    if isinstance(amz, Exception):    amz = {}

    for kpi in angle_kpis:
        angle = kpi["angle"]
        if angle in (gt or {}) or angle in (tiktok or {}) or angle in (amz or {}):
            kpi["trend_signals"] = {
                "google_trends":  gt.get(angle),
                "tiktok_organic": tiktok.get(angle),
                "amazon":         amz.get(angle),
            }

    logger.info("Trend signals enriched for %d/%d angles", len(top_angles), len(angle_kpis))
    return angle_kpis
