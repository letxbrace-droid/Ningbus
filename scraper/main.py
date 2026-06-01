"""Orchestrator — pipeline: scrape → analyse angles → find scaling shops → detect gaps."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import yaml

from .alert_system import send_alerts
from .angle_aggregator import AngleAggregator
from .angle_analyzer import AngleAnalyzer
from .market_research import research_niche, validate_gap_angles
from .meta_scraper import MetaScraper
from .price_signals import enrich_products_with_margins
from .shop_finder import find_scaling_shops
from .tiktok_scraper import TikTokScraper
from .utils import Timer, setup_logging
from .velocity_tracker import compute_velocity

logger = logging.getLogger(__name__)

DATA_DIR    = Path(__file__).parent.parent / "data"
HISTORY_DIR = DATA_DIR / "history"
CONFIG_PATH = Path(__file__).parent / "config.yaml"


def _ensure_dirs() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)


def _write_json(path: Path, obj: object) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    logger.info("Wrote %s (%d bytes)", path, path.stat().st_size)


def _load_config() -> dict:
    if not CONFIG_PATH.exists():
        return {"niches": ["foot wellness"], "country": "FR"}
    with open(CONFIG_PATH, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def _load_prev_niche_data(niche: str) -> dict:
    """Load angle_kpis and advertisers from the most recent history file for this niche."""
    if not HISTORY_DIR.exists():
        return {}
    files = sorted(HISTORY_DIR.glob("*.json"), reverse=True)
    for fpath in files[:15]:
        try:
            data = json.loads(fpath.read_text(encoding="utf-8"))
            for r in data.get("results", []):
                if r.get("niche") == niche:
                    return r
        except Exception:
            continue
    return {}


def _load_known_domains(niche: str, lookback: int = 5) -> set[str]:
    """Return set of domains seen in the last N history files for this niche."""
    if not HISTORY_DIR.exists():
        return set()
    known: set[str] = set()
    files = sorted(HISTORY_DIR.glob("*.json"), reverse=True)
    found_count = 0
    for fpath in files[:20]:
        try:
            data = json.loads(fpath.read_text(encoding="utf-8"))
            for r in data.get("results", []):
                if r.get("niche") == niche:
                    for adv in r.get("advertisers", []):
                        d = adv.get("domain", "")
                        if d:
                            known.add(d)
                    found_count += 1
                    if found_count >= lookback:
                        return known
        except Exception:
            continue
    return known


def _now() -> str:
    return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")


def _estimate_revenue(ads_count: int, avg_days: float, products: list, scaling_score: float) -> dict:
    # When ads_count is 0 (shop found via DDG, not Meta), use scaling_score proxy
    effective_count = ads_count or max(1, round(scaling_score / 15))
    daily_spend = effective_count * 40
    monthly_spend = round(daily_spend * 30, 0)
    monthly_revenue = round(monthly_spend * 3.5, 0)
    confidence = "high" if ads_count >= 3 and avg_days >= 20 else "medium" if effective_count >= 2 else "low"
    return {
        "monthly_spend_est":   int(monthly_spend),
        "monthly_revenue_est": int(monthly_revenue),
        "confidence":          confidence,
    }


def _build_product_angle_matrix(advertisers: list[dict], angle_kpis: list[dict]) -> list[dict]:
    """
    For each product keyword (from advertiser products), show which angles use it.
    Returns list of rows sorted by total_ads DESC.
    """
    from collections import defaultdict

    # Extract product keyword = first 3 words of product title, lowercased
    def _kw(title: str) -> str:
        return " ".join(title.lower().split()[:3]) if title else ""

    # Build matrix: product_kw -> angle -> {count, total_days}
    matrix: dict[str, dict[str, dict]] = defaultdict(lambda: defaultdict(lambda: {"count": 0, "total_days": 0}))

    for adv in advertisers:
        for product in (adv.get("products") or []):
            kw = _kw(product.get("title", ""))
            if not kw:
                continue
            for angle in (adv.get("angles_used") or []):
                matrix[kw][angle]["count"] += 1
                matrix[kw][angle]["total_days"] += adv.get("avg_days_running", 0)

    # Top angles by viability
    top_angles = [k["angle"] for k in sorted(angle_kpis, key=lambda x: x.get("viability_score", 0), reverse=True)[:6]]

    rows = []
    for product_kw, angle_data in matrix.items():
        total_ads = sum(v["count"] for v in angle_data.values())
        cells = []
        for angle in top_angles:
            cell = angle_data.get(angle, {"count": 0, "total_days": 0})
            avg_days = round(cell["total_days"] / cell["count"], 1) if cell["count"] > 0 else 0
            cells.append({
                "angle":    angle,
                "count":    cell["count"],
                "avg_days": avg_days,
            })
        rows.append({
            "product_kw": product_kw,
            "total_ads":  total_ads,
            "cells":      cells,
        })

    rows.sort(key=lambda r: r["total_ads"], reverse=True)
    return rows[:12]  # top 12 products


def _shop_to_advertiser(shop: dict, gaps: list[dict]) -> dict:
    """Convert a shop dict to an advertiser profile dict for the dashboard."""
    angles_used = shop.get("angles_used") or []
    gap_angles = [g["angle"] for g in gaps if g["angle"] not in set(angles_used)]
    products = shop.get("products") or []
    domain = shop.get("domain", "")
    store_url = shop.get("store_url") or shop.get("base_url") or (
        f"https://{domain}" if domain else ""
    )
    ads = shop.get("ads") or []
    avg_days = (
        round(sum(a.get("days_running", 0) for a in ads) / len(ads), 1) if ads else 0.0
    )
    scaling_score = shop.get("scaling_score") or round(
        len(products) * max(shop.get("max_days_running", 1), 1) * 0.1, 1
    )
    dominant = angles_used[0] if angles_used else ""
    return {
        "name":               shop.get("page_name") or domain,
        "domain":             domain,
        "store_url":          store_url,
        "scaling_score":      scaling_score,
        "ads_count":          shop.get("ads_count", len(ads)),
        "max_days_running":   shop.get("max_days_running", 0),
        "avg_days_running":   avg_days,
        "estimated_spend":    shop.get("total_spend", 0),
        "angles_used":        angles_used,
        "dominant_angle":     dominant,
        "angle_gaps":         gap_angles,
        "products":           products,
        "ad_examples":        [
            {
                "copy":         a.get("ad_copy", "")[:300],
                "angle":        (a.get("angle_data") or {}).get("angle", ""),
                "days_running": a.get("days_running", 0),
                "snapshot_url": a.get("landing_page_url", ""),
                "platform":     a.get("platform", ""),
                "ctr":          round(a.get("ctr", 0), 2) if a.get("ctr") else None,
            }
            for a in sorted(ads, key=lambda x: x.get("engagement_score", 0), reverse=True)[:5]
        ],
        "platforms":          list({p for a in ads for p in (a.get("publisher_platforms") or [])}),
        "revenue_estimate":          _estimate_revenue(
            ads_count=shop.get("ads_count", len(ads)),
            avg_days=avg_days,
            products=products,
            scaling_score=scaling_score,
        ),
        "landing_analysis":          shop.get("landing_analysis"),
        "scaling_signals":           shop.get("scaling_signals") or [],
        "collections_count":         shop.get("collections_count"),
        "avg_product_price":         shop.get("avg_product_price"),
        "products_with_discount":    shop.get("products_with_discount", 0),
        "products_with_reviews":     shop.get("products_with_reviews", 0),
        "avg_product_rating":        shop.get("avg_product_rating"),
        "velocity":                  compute_velocity(domain, products),
    }


async def run_niche(
    niche: str,
    country: str,
    max_ads: int,
    analyzer: AngleAnalyzer,
    aggregator: AngleAggregator,
    gemini_model: str = "gemini-2.0-flash",
) -> dict:
    """Full pipeline for one niche. Returns result dict."""
    logger.info("=" * 60)
    logger.info("NICHE: %s | COUNTRY: %s | MAX ADS: %d", niche, country, max_ads)
    logger.info("=" * 60)
    t0 = time.perf_counter()

    # Load previous analysis for velocity and new entrant comparison
    prev_data        = _load_prev_niche_data(niche)
    prev_kpis        = prev_data.get("angle_kpis", [])
    prev_advertisers = prev_data.get("advertisers", [])

    # Known domains from last 5 runs — used to prioritise fresh discoveries
    known_domains = _load_known_domains(niche, lookback=5)

    # G0. Start Gemini market research in background (runs concurrently with steps 1-4)
    gemini_research_task = asyncio.ensure_future(
        asyncio.wait_for(
            research_niche(niche, country, model=gemini_model),
            timeout=75.0,
        )
    )

    # 1. Scrape Meta + TikTok concurrently
    with Timer("scrape"):
        meta_scraper   = MetaScraper(max_ads=max_ads)
        tiktok_scraper = TikTokScraper(max_ads=max_ads)
        meta_ads, tiktok_ads = await asyncio.gather(
            meta_scraper.scrape_ads(niche=niche, country=country),
            tiktok_scraper.scrape_ads(niche=niche, country=country),
            return_exceptions=True,
        )
        meta_ads   = meta_ads   if isinstance(meta_ads,   list) else []
        tiktok_ads = tiktok_ads if isinstance(tiktok_ads, list) else []
        ads = meta_ads + tiktok_ads

    logger.info("Step 1 — Meta: %d ads | TikTok: %d ads", len(meta_ads), len(tiktok_ads))

    # Tag + clean: drop template placeholders and sub-10 char copies
    import re as _re
    ads = [
        a for a in ads
        if len(a.get("ad_copy", "")) > 10
        and not _re.search(r"\{\{|\}\}", a.get("ad_copy", ""))
    ]
    for a in ads:
        a["niche"] = niche

    logger.info("Step 1 — %d clean ads total (niche='%s')", len(ads), niche)

    if not ads:
        logger.warning("No Meta ads for '%s' — falling back to DDG shop finder", niche)
        with Timer("shop_finder_fallback"):
            shops = await find_scaling_shops([], niche, country, exclude_domains=known_domains)
        # Price/margin enrichment on fallback shops
        with Timer("price_signals_fallback"):
            try:
                await asyncio.wait_for(enrich_products_with_margins(shops), timeout=45.0)
            except asyncio.TimeoutError:
                logger.warning("Fallback price signals timed out, skipping")
        advertisers = [_shop_to_advertiser(s, []) for s in shops]
        logger.info("shop_finder fallback: %d advertisers from real shops", len(advertisers))

        # Collect Gemini research even in fallback path (task was started at G0)
        with Timer("gemini_research_fallback"):
            try:
                market_research = await gemini_research_task
            except (asyncio.TimeoutError, Exception) as exc:
                logger.warning("Fallback G0 — Gemini research unavailable: %s", exc)
                market_research = {}
        logger.info("Fallback G0 — market research: trend=%s, opp=%s",
                    market_research.get("market_trend"), market_research.get("opportunity_score"))

        return {
            "niche":                niche,
            "ads":                  [],
            "angle_kpis":           [],
            "gaps":                 [],
            "advertisers":          advertisers,
            "market_research":      market_research,
            "market_revenue_est":   sum(a.get("revenue_estimate", {}).get("monthly_revenue_est", 0) for a in advertisers),
            "product_angle_matrix": [],
            "stats": {
                "total_ads":         0,
                "unique_angles":     0,
                "gaps_found":        0,
                "advertisers_found": len(advertisers),
            },
        }

    # 2. Analyse angles with Groq
    with Timer("angle_analysis"):
        analyzed_ads = await analyzer.batch_analyze_ads(ads)
    logger.info("Step 2 — %d ads analysed", len(analyzed_ads))

    # 3. Aggregate angle KPIs
    with Timer("aggregation"):
        angle_kpis = aggregator.aggregate(analyzed_ads)
    logger.info("Step 3 — %d distinct angles", len(angle_kpis))

    # Enrich with velocity vs previous analysis
    from .angle_aggregator import enrich_with_velocity
    enrich_with_velocity(angle_kpis, prev_kpis)

    # 3.5 Enrich with external trend signals (Priority 3) — hard timeout 90s
    from .trend_signals import enrich_with_trend_signals
    with Timer("trend_signals"):
        try:
            angle_kpis = await asyncio.wait_for(
                enrich_with_trend_signals(angle_kpis, country=country),
                timeout=90.0,
            )
        except asyncio.TimeoutError:
            logger.warning("Step 3.5 — trend signals timed out, skipping")
    logger.info("Step 3.5 — trend signals done")

    # 4. Find scaling shops (concurrent with step 3 logically, but we need angle_kpis first)
    with Timer("shop_finder"):
        shops = await find_scaling_shops(analyzed_ads, niche, country, exclude_domains=known_domains)
    logger.info("Step 4 — %d scaling shops found", len(shops))

    # 4.5 Analyse landing pages — hard timeout 60s
    from .landing_analyzer import analyze_shops
    with Timer("landing_analysis"):
        try:
            landing_data = await asyncio.wait_for(analyze_shops(shops), timeout=60.0)
        except asyncio.TimeoutError:
            logger.warning("Step 4.5 — landing analysis timed out, skipping")
            landing_data = {}
    logger.info("Step 4.5 — %d landing pages analyzed", len(landing_data))

    # Enrich shops with landing analysis
    for shop in shops:
        domain = shop.get("domain", "")
        if domain in landing_data:
            shop["landing_analysis"] = landing_data[domain]

    # 4.6 Aliexpress margin enrichment — hard timeout 45s
    with Timer("price_signals"):
        try:
            await asyncio.wait_for(enrich_products_with_margins(shops), timeout=45.0)
        except asyncio.TimeoutError:
            logger.warning("Step 4.6 — price signals timed out, skipping")
    logger.info("Step 4.6 — margin enrichment done")

    # 5. Convert shops → advertiser profiles (needed before gap detection for product recs)
    advertisers = [_shop_to_advertiser(s, []) for s in shops]

    # 6. Detect angle gaps — attach product recommendations from active advertisers
    with Timer("gap_detection"):
        gaps = aggregator.detect_gaps(angle_kpis, advertisers, prev_advertisers, total_ads=len(analyzed_ads))
    logger.info("Step 6 — %d gap opportunities", len(gaps))

    # 6.5 Collect Gemini market research (started at G0, should be ready by now)
    with Timer("gemini_research"):
        try:
            market_research = await gemini_research_task
        except (asyncio.TimeoutError, Exception) as exc:
            logger.warning("Step G0 — Gemini research unavailable: %s", exc)
            market_research = {}
    logger.info("Step G0 — market research: trend=%s, opp=%s",
                market_research.get("market_trend"), market_research.get("opportunity_score"))

    # 6.6 Validate top gap angles with Gemini web search
    if gaps:
        with Timer("gemini_gap_validation"):
            try:
                gaps = await asyncio.wait_for(
                    validate_gap_angles(niche, gaps, country=country, model=gemini_model),
                    timeout=45.0,
                )
            except asyncio.TimeoutError:
                logger.warning("Step G1 — Gemini gap validation timed out")
    logger.info("Step G1 — gap validation done (%d gaps)", len(gaps))

    # 7. Enrich advertiser profiles with their gap angles
    advertisers = [_shop_to_advertiser(s, gaps) for s in shops]

    market_revenue_est = sum(a.get("revenue_estimate", {}).get("monthly_revenue_est", 0) for a in advertisers)

    # 8. Send Discord alerts for strong-signal gaps
    with Timer("alerts"):
        try:
            await asyncio.wait_for(send_alerts(niche, gaps), timeout=15.0)
        except asyncio.TimeoutError:
            logger.warning("Step 8 — alerts timed out")

    elapsed = time.perf_counter() - t0
    logger.info("Pipeline '%s' done in %.1fs", niche, elapsed)

    return {
        "niche":                niche,
        "ads":                  analyzed_ads,
        "angle_kpis":           angle_kpis,
        "gaps":                 gaps,
        "advertisers":          advertisers,
        "market_research":      market_research,
        "market_revenue_est":   market_revenue_est,
        "product_angle_matrix": _build_product_angle_matrix(advertisers, angle_kpis),
        "stats": {
            "total_ads":         len(analyzed_ads),
            "unique_angles":     len(angle_kpis),
            "gaps_found":        len(gaps),
            "advertisers_found": len(advertisers),
            "trending_angles":   sum(1 for k in angle_kpis if k.get("trend") == "up"),
        },
    }


async def main_async(args: argparse.Namespace) -> None:
    setup_logging(os.getenv("LOG_LEVEL", "INFO"))
    _ensure_dirs()

    config  = _load_config()
    country = args.country or os.getenv("COUNTRY_OVERRIDE") or config.get("country", "FR")
    max_ads = int(args.max_ads or os.getenv("MAX_ADS", "100"))

    if args.niche:
        niches = [args.niche]
    elif os.getenv("NICHE_OVERRIDE"):
        niches = [os.environ["NICHE_OVERRIDE"]]
    else:
        niches = config.get("niches", ["foot wellness"])

    # Multi-country: if config has `countries` list, run each niche for each country
    countries_cfg = config.get("countries", [])
    countries = countries_cfg if countries_cfg else [country]

    # Build (niche, country) task list
    tasks = [(n, c) for n in niches for c in countries]
    label = "niche" if len(countries) == 1 else "niche×country"
    logger.info("Processing %d %s(s): %s × %s", len(tasks), label, niches, countries)

    analyzer      = AngleAnalyzer()
    aggregator    = AngleAggregator()
    gemini_model  = os.getenv("GEMINI_MODEL") or config.get("gemini_model", "gemini-2.0-flash")

    # Run niches 2 at a time — parallelises external I/O without hammering Meta/DDG
    sem = asyncio.Semaphore(2)

    async def _run_with_sem(niche: str, c: str) -> dict | None:
        async with sem:
            try:
                result = await run_niche(niche, c, max_ads, analyzer, aggregator, gemini_model=gemini_model)
                if len(countries) > 1:
                    result["niche"] = f"{niche} ({c})"
                return result
            except Exception as exc:
                logger.error("Niche '%s' [%s] failed: %s", niche, c, exc, exc_info=True)
                return None

    all_results_raw = await asyncio.gather(*[_run_with_sem(n, c) for n, c in tasks])
    all_results: list[dict] = [r for r in all_results_raw if r is not None]

    all_ads = [ad for r in all_results for ad in r.get("ads", [])]
    _write_json(DATA_DIR / "latest.json", {"generated_at": _now(), "ads": all_ads})

    analysis = {
        "generated_at":     _now(),
        "niches_processed": [r["niche"] for r in all_results],
        "total_ads":        len(all_ads),
        "results": [
            {
                "niche":                r["niche"],
                "angle_kpis":           r["angle_kpis"],
                "gaps":                 r["gaps"],
                "advertisers":          r["advertisers"],
                "market_research":      r.get("market_research", {}),
                "market_revenue_est":   r.get("market_revenue_est", 0),
                "product_angle_matrix": r.get("product_angle_matrix", []),
                "stats":                r["stats"],
            }
            for r in all_results
        ],
    }
    _write_json(DATA_DIR / "latest_analysis.json", analysis)

    stamp = datetime.utcnow().strftime("%Y-%m-%d_%H-%M")
    _write_json(HISTORY_DIR / f"{stamp}.json", analysis)

    logger.info("All done.")


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="TrendTrack scraper")
    p.add_argument("--niche",   default="")
    p.add_argument("--country", default="")
    p.add_argument("--max-ads", default=100, type=int)
    return p


def main() -> None:
    try:
        asyncio.run(main_async(build_arg_parser().parse_args()))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
