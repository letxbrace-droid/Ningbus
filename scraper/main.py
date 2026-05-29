"""Orchestrator — entry point for the full scrape-analyse-recommend pipeline."""

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
from .product_recommender import ProductRecommender
from .utils import Timer, setup_logging

logger = logging.getLogger(__name__)

DATA_DIR = Path(__file__).parent.parent / "data"
HISTORY_DIR = DATA_DIR / "history"
CONFIG_PATH = Path(__file__).parent / "config.yaml"


# ---------------------------------------------------------------------------
# Data persistence helpers
# ---------------------------------------------------------------------------

def _ensure_dirs() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    HISTORY_DIR.mkdir(parents=True, exist_ok=True)


def _write_json(path: Path, obj: object) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    logger.info("Wrote %s (%d bytes)", path, path.stat().st_size)


def _load_config() -> dict:
    """Load scraper/config.yaml safely."""
    if not CONFIG_PATH.exists():
        logger.warning("config.yaml not found — using defaults")
        return {"niches": ["foot wellness"], "country": "FR"}
    with open(CONFIG_PATH, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


# ---------------------------------------------------------------------------
# Core pipeline
# ---------------------------------------------------------------------------

async def run_niche(
    niche: str,
    country: str,
    max_ads: int,
    analyzer: AngleAnalyzer,
    aggregator: AngleAggregator,
    recommender: ProductRecommender,
) -> dict:
    """Run the full pipeline for a single niche. Returns result dict."""
    logger.info("=" * 60)
    logger.info("NICHE: %s | COUNTRY: %s | MAX ADS: %d", niche, country, max_ads)
    logger.info("=" * 60)

    pipeline_start = time.perf_counter()

    # 1. Scrape
    with Timer("scrape"):
        scraper = MetaScraper(max_ads=max_ads)
        ads = await scraper.scrape_ads(niche=niche, country=country)
    logger.info("Step 1 — Scraped %d ads", len(ads))

    if not ads:
        logger.warning("No ads found for niche '%s' — skipping analysis", niche)
        return {"niche": niche, "ads": [], "angle_kpis": [], "gaps": [], "recommendations": []}

    # 2. Analyse angles
    with Timer("angle_analysis"):
        analyzed_ads = await analyzer.batch_analyze_ads(ads)
    logger.info("Step 2 — Analysed %d ads", len(analyzed_ads))

    # 3. Aggregate KPIs
    with Timer("aggregation"):
        angle_kpis = aggregator.aggregate(analyzed_ads)
    logger.info("Step 3 — %d distinct angles", len(angle_kpis))

    # 4. Detect gaps
    with Timer("gap_detection"):
        gaps = aggregator.detect_gaps(angle_kpis)
    logger.info("Step 4 — %d gap opportunities detected", len(gaps))

    # 5. Product recommendations
    with Timer("product_recommendation"):
        recommendations = await recommender.recommend(gaps, niche)
    logger.info("Step 5 — %d recommendation bundles", len(recommendations))

    elapsed = time.perf_counter() - pipeline_start
    logger.info("Pipeline for '%s' completed in %.1fs", niche, elapsed)

    return {
        "niche": niche,
        "ads": analyzed_ads,
        "angle_kpis": angle_kpis,
        "gaps": gaps,
        "recommendations": recommendations,
    }


async def main_async(args: argparse.Namespace) -> None:
    """Async entry point."""
    setup_logging(os.getenv("LOG_LEVEL", "INFO"))
    _ensure_dirs()

    config = _load_config()
    country = args.country or os.getenv("COUNTRY_OVERRIDE") or config.get("country", "FR")
    max_ads = int(args.max_ads or os.getenv("MAX_ADS", "100"))

    # Determine niches to process
    if args.niche:
        niches = [args.niche]
    elif os.getenv("NICHE_OVERRIDE"):
        niches = [os.environ["NICHE_OVERRIDE"]]
    else:
        niches = config.get("niches", ["foot wellness"])

    logger.info("Processing %d niche(s): %s", len(niches), niches)

    # Build shared service instances
    analyzer = AngleAnalyzer()
    aggregator = AngleAggregator()
    recommender = ProductRecommender()

    all_results: list[dict] = []
    for niche in niches:
        try:
            result = await run_niche(
                niche, country, max_ads, analyzer, aggregator, recommender
            )
            all_results.append(result)
        except Exception as exc:
            logger.error("Niche '%s' failed: %s", niche, exc, exc_info=True)

    # Merge all ads for latest.json
    all_ads = [ad for r in all_results for ad in r.get("ads", [])]
    _write_json(DATA_DIR / "latest.json", {"generated_at": _now(), "ads": all_ads})

    # Build analysis output
    analysis_output = {
        "generated_at": _now(),
        "niches_processed": [r["niche"] for r in all_results],
        "total_ads": len(all_ads),
        "results": [
            {
                "niche": r["niche"],
                "angle_kpis": r["angle_kpis"],
                "gaps": r["gaps"],
                "recommendations": r["recommendations"],
                "stats": {
                    "total_ads": len(r["ads"]),
                    "unique_angles": len(r["angle_kpis"]),
                    "gaps_found": len(r["gaps"]),
                },
            }
            for r in all_results
        ],
    }
    _write_json(DATA_DIR / "latest_analysis.json", analysis_output)

    # Archive
    stamp = datetime.utcnow().strftime("%Y-%m-%d_%H-%M")
    _write_json(HISTORY_DIR / f"{stamp}.json", analysis_output)

    logger.info("All done. Data written to %s", DATA_DIR)


def _now() -> str:
    return datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="TrendTrack Angle Intelligence scraper")
    parser.add_argument("--niche", default="", help="Single niche keyword to scrape")
    parser.add_argument("--country", default="", help="Country code (FR, US, GB…)")
    parser.add_argument("--max-ads", default=100, type=int, help="Max ads per niche")
    return parser


def main() -> None:
    parser = build_arg_parser()
    args = parser.parse_args()
    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        logger.info("Interrupted by user")
        sys.exit(0)


if __name__ == "__main__":
    main()
