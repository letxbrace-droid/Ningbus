"""Aggregate angle KPIs and detect unexploited gaps."""

from __future__ import annotations

import logging
import statistics
from collections import defaultdict

logger = logging.getLogger(__name__)

# An angle is "underused" if its share is below this threshold
USAGE_THRESHOLD = 0.10  # 10%
# An angle is "viable" if its viability score exceeds this
VIABILITY_THRESHOLD = 60.0


def _viability(avg_days: float) -> float:
    """Proxy for profitability: capped at 100, linear with avg days running."""
    return min(avg_days, 100.0)


class AngleAggregator:
    """Aggregates angle statistics from a list of analysed ads."""

    def aggregate(self, analyzed_ads: list[dict]) -> list[dict]:
        """
        Build per-angle KPI objects.

        Returns a list of dicts sorted by viability_score DESC.
        """
        if not analyzed_ads:
            return []

        buckets: dict[str, list[dict]] = defaultdict(list)
        for ad in analyzed_ads:
            angle = ad.get("angle_data", {}).get("angle", "Unknown")
            buckets[angle].append(ad)

        total_ads = len(analyzed_ads)
        kpis: list[dict] = []

        for angle, ads in buckets.items():
            days_list = [a.get("days_running", 0) for a in ads]
            avg_days = statistics.mean(days_list) if days_list else 0.0
            med_days = statistics.median(days_list) if days_list else 0.0
            score = _viability(avg_days)
            usage_pct = len(ads) / total_ads

            # Top 3 examples: best by days_running
            top3 = sorted(ads, key=lambda a: a.get("days_running", 0), reverse=True)[:3]
            examples = [
                {
                    "hook": a.get("angle_data", {}).get("hook", ""),
                    "store": a.get("store_domain", ""),
                    "days_running": a.get("days_running", 0),
                    "landing_page_url": a.get("landing_page_url", ""),
                }
                for a in top3
            ]

            kpis.append(
                {
                    "angle": angle,
                    "count": len(ads),
                    "usage_pct": round(usage_pct, 4),
                    "avg_days_running": round(avg_days, 1),
                    "median_days_running": round(med_days, 1),
                    "viability_score": round(score, 1),
                    "examples": examples,
                    # Sub-angle breakdown
                    "sub_angles": list(
                        {a.get("angle_data", {}).get("sub_angle", "") for a in ads}
                    ),
                    # Most common audience
                    "primary_audience": _most_common(
                        [a.get("angle_data", {}).get("audience", "") for a in ads]
                    ),
                }
            )

        kpis.sort(key=lambda k: k["viability_score"], reverse=True)
        logger.info("Aggregated %d distinct angles from %d ads", len(kpis), total_ads)
        return kpis

    def detect_gaps(
        self,
        angle_kpis: list[dict],
        advertisers: list[dict] | None = None,
    ) -> list[dict]:
        """
        Identify angles with high viability but low market saturation.
        If advertisers are provided, attach their products as recommendations.

        Returns gaps sorted by viability DESC.
        """
        # Build a deduplicated product pool from active advertisers
        recommended_products: list[dict] = []
        if advertisers:
            seen_titles: set[str] = set()
            for adv in sorted(advertisers, key=lambda a: a.get("scaling_score", 0), reverse=True):
                for p in (adv.get("products") or [])[:3]:
                    title = p.get("title", "")
                    if title and title not in seen_titles:
                        seen_titles.add(title)
                        recommended_products.append(p)
                    if len(recommended_products) >= 8:
                        break
                if len(recommended_products) >= 8:
                    break

        gaps: list[dict] = []
        for kpi in angle_kpis:
            low_usage = kpi["usage_pct"] < USAGE_THRESHOLD
            high_viability = kpi["viability_score"] > VIABILITY_THRESHOLD
            if low_usage and high_viability:
                gaps.append(
                    {
                        "angle":               kpi["angle"],
                        "viability_score":     kpi["viability_score"],
                        "usage_count":         kpi["count"],
                        "usage_pct":           kpi["usage_pct"],
                        "avg_days_running":    kpi["avg_days_running"],
                        "examples":            kpi["examples"],
                        "primary_audience":    kpi.get("primary_audience", ""),
                        "potential":           "HIGH",
                        "recommended_products": recommended_products[:4],
                    }
                )

        gaps.sort(key=lambda g: g["viability_score"], reverse=True)
        logger.info("Detected %d gap angles", len(gaps))
        return gaps


def _most_common(items: list[str]) -> str:
    """Return the most frequently occurring non-empty string."""
    counts: dict[str, int] = defaultdict(int)
    for item in items:
        if item:
            counts[item] += 1
    if not counts:
        return ""
    return max(counts, key=lambda k: counts[k])
