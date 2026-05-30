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

from .angle_aggregator import AngleAggregator
from .angle_analyzer import AngleAnalyzer
from .meta_scraper import MetaScraper
from .shop_finder import find_scaling_shops
from .utils import Timer, setup_logging

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


def _now() -> str:
    return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")


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
        "ad_examples":        [],
        "platforms":          list({p for a in ads for p in (a.get("publisher_platforms") or [])}),
    }


async def run_niche(
    niche: str,
    country: str,
    max_ads: int,
    analyzer: AngleAnalyzer,
    aggregator: AngleAggregator,
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

    # 1. Scrape Meta ads
    with Timer("scrape"):
        scraper = MetaScraper(max_ads=max_ads)
        ads = await scraper.scrape_ads(niche=niche, country=country)
    logger.info("Step 1 — %d ads scraped", len(ads))

    if not ads:
        logger.warning("No Meta ads for '%s' — falling back to DDG shop finder", niche)
        with Timer("shop_finder_fallback"):
            shops = await find_scaling_shops([], niche, country)
        advertisers = [_shop_to_advertiser(s, []) for s in shops]
        logger.info("shop_finder fallback: %d advertisers from real shops", len(advertisers))
        return {
            "niche":       niche,
            "ads":         [],
            "angle_kpis":  [],
            "gaps":        [],
            "advertisers": advertisers,
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

    # 3.5 Enrich with external trend signals (Priority 3)
    from .trend_signals import enrich_with_trend_signals
    with Timer("trend_signals"):
        angle_kpis = await enrich_with_trend_signals(angle_kpis, country=country)
    logger.info("Step 3.5 — trend signals enriched")

    # 4. Find scaling shops (concurrent with step 3 logically, but we need angle_kpis first)
    with Timer("shop_finder"):
        shops = await find_scaling_shops(analyzed_ads, niche, country)
    logger.info("Step 4 — %d scaling shops found", len(shops))

    # 5. Convert shops → advertiser profiles (needed before gap detection for product recs)
    advertisers = [_shop_to_advertiser(s, []) for s in shops]

    # 6. Detect angle gaps — attach product recommendations from active advertisers
    with Timer("gap_detection"):
        gaps = aggregator.detect_gaps(angle_kpis, advertisers, prev_advertisers)
    logger.info("Step 6 — %d gap opportunities", len(gaps))

    # 7. Enrich advertiser profiles with their gap angles
    advertisers = [_shop_to_advertiser(s, gaps) for s in shops]

    elapsed = time.perf_counter() - t0
    logger.info("Pipeline '%s' done in %.1fs", niche, elapsed)

    return {
        "niche":       niche,
        "ads":         analyzed_ads,
        "angle_kpis":  angle_kpis,
        "gaps":        gaps,
        "advertisers": advertisers,
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

    logger.info("Processing %d niche(s): %s", len(niches), niches)

    analyzer   = AngleAnalyzer()
    aggregator = AngleAggregator()

    all_results: list[dict] = []
    for niche in niches:
        try:
            result = await run_niche(niche, country, max_ads, analyzer, aggregator)
            all_results.append(result)
        except Exception as exc:
            logger.error("Niche '%s' failed: %s", niche, exc, exc_info=True)

    all_ads = [ad for r in all_results for ad in r.get("ads", [])]
    _write_json(DATA_DIR / "latest.json", {"generated_at": _now(), "ads": all_ads})

    analysis = {
        "generated_at":     _now(),
        "niches_processed": [r["niche"] for r in all_results],
        "total_ads":        len(all_ads),
        "results": [
            {
                "niche":       r["niche"],
                "angle_kpis":  r["angle_kpis"],
                "gaps":        r["gaps"],
                "advertisers": r["advertisers"],
                "stats":       r["stats"],
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
