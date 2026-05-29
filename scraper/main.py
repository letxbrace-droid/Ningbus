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


def _now() -> str:
    return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")


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

    # 1. Scrape Meta ads
    with Timer("scrape"):
        scraper = MetaScraper(max_ads=max_ads)
        ads = await scraper.scrape_ads(niche=niche, country=country)
    logger.info("Step 1 — %d ads scraped", len(ads))

    if not ads:
        logger.warning("No Meta ads for '%s' — falling back to shop finder", niche)
        with Timer("shop_finder_fallback"):
            shops = await find_scaling_shops([], niche, country)
        advertisers = [
            {
                "name":               s["domain"],
                "domain":             s["domain"],
                "store_url":          s["store_url"],
                "scaling_score":      s["scaling_score"],
                "ads_count":          0,
                "max_days_running":   0,
                "avg_days_running":   0.0,
                "estimated_spend":    0,
                "angles_used":        [],
                "dominant_angle":     "",
                "angle_gaps":         [],
                "products":           s["products"],
                "ad_examples":        [],
                "platforms":          [],
            }
            for s in shops
        ]
        logger.info("shop_finder fallback: %d advertisers from real shops", len(advertisers))
        return {
            "niche":      niche,
            "ads":        [],
            "angle_kpis": [],
            "gaps":       [],
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

    # 4. Detect angle gaps
    with Timer("gap_detection"):
        gaps = aggregator.detect_gaps(angle_kpis)
    logger.info("Step 4 — %d gap opportunities", len(gaps))

    # 5. Find scaling shops + their product catalogs
    with Timer("shop_finder"):
        shops = await find_scaling_shops(niche, analyzed_ads, min_days=15)
    logger.info("Step 5 — %d scaling shops found", len(shops))

    # 6. Enrich each shop with gap angles they don't use
    angle_names_used_globally = {k["angle"] for k in angle_kpis}
    for shop in shops:
        used = set(shop.get("angles_used") or [])
        shop["angle_gaps"] = [
            g for g in gaps if g["angle"] not in used
        ]

    elapsed = time.perf_counter() - t0
    logger.info("Pipeline '%s' done in %.1fs", niche, elapsed)

    return {
        "niche":      niche,
        "ads":        analyzed_ads,
        "angle_kpis": angle_kpis,
        "gaps":       gaps,
        "shops":      shops,
        "stats": {
            "total_ads":     len(analyzed_ads),
            "unique_angles": len(angle_kpis),
            "gaps_found":    len(gaps),
            "shops_found":   len(shops),
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

    # Write latest.json (raw ads)
    all_ads = [ad for r in all_results for ad in r.get("ads", [])]
    _write_json(DATA_DIR / "latest.json", {"generated_at": _now(), "ads": all_ads})

    # Write latest_analysis.json (angles + shops)
    analysis = {
        "generated_at":     _now(),
        "niches_processed": [r["niche"] for r in all_results],
        "total_ads":        len(all_ads),
        "results": [
            {
                "niche":      r["niche"],
                "angle_kpis": r["angle_kpis"],
                "gaps":       r["gaps"],
                "shops":      r["shops"],
                "stats":      r["stats"],
            }
            for r in all_results
        ],
    }
    _write_json(DATA_DIR / "latest_analysis.json", analysis)

    # Archive
    stamp = datetime.utcnow().strftime("%Y-%m-%d_%H-%M")
    _write_json(HISTORY_DIR / f"{stamp}.json", analysis)

    logger.info("All done.")


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="TrendTrack scraper")
    p.add_argument("--niche",    default="")
    p.add_argument("--country",  default="")
    p.add_argument("--max-ads",  default=100, type=int)
    return p


def main() -> None:
    try:
        asyncio.run(main_async(build_arg_parser().parse_args()))
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
