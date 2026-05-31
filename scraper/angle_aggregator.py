"""Aggregate angle KPIs and detect unexploited gaps."""

from __future__ import annotations

import logging
import statistics
from collections import defaultdict

logger = logging.getLogger(__name__)

# An angle is "viable" if its viability score exceeds this
VIABILITY_THRESHOLD = 30.0  # lowered: Meta often returns 0 days_running


def _usage_threshold(total_ads: int) -> float:
    """Adaptive threshold — with few ads, any angle can be a gap opportunity."""
    if total_ads < 5:   return 1.0   # 1 ad = 100% usage, still a gap
    if total_ads < 15:  return 0.60
    if total_ads < 30:  return 0.35
    return 0.20


def _normalize_angle(angle: str) -> str:
    """Normalize angle name: strip, first-letter uppercase → avoids case duplicates."""
    a = (angle or "Unknown").strip()
    return (a[0].upper() + a[1:]) if len(a) > 1 else a.upper()


def _viability(avg_days: float, count: int = 1, total: int = 1) -> float:
    """
    Proxy for profitability.
    - When days data is available: use duration directly (longer = more profitable).
    - When days = 0: use ad-volume share as signal (more ads in angle = more validated).
    """
    if avg_days > 0:
        return min(avg_days, 100.0)
    # No duration — derive from competition volume (0-70 range)
    volume_pct = count / max(total, 1)
    return round(30.0 + volume_pct * 70.0, 1)


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
            angle = _normalize_angle(ad.get("angle_data", {}).get("angle", "Unknown"))
            buckets[angle].append(ad)

        total_ads = len(analyzed_ads)
        kpis: list[dict] = []

        for angle, ads in buckets.items():
            days_list = [a.get("days_running", 0) for a in ads]
            avg_days = statistics.mean(days_list) if days_list else 0.0
            med_days = statistics.median(days_list) if days_list else 0.0
            score = _viability(avg_days, count=len(ads), total=total_ads)
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
                    # Hook patterns — filled in post-processing pass below
                    "hook_patterns": {},
                }
            )

        # Saturation index: each angle's count relative to max count across all angles
        max_count = max((k["count"] for k in kpis), default=1)
        for kpi in kpis:
            saturation = round(kpi["count"] / max_count * 100, 1)
            kpi["saturation_index"] = saturation
            # Opportunity = viability × (1 - saturation×0.4/100) × (1 + vel_bonus/100)
            vel_bonus = min(max(kpi.get("velocity_pct") or 0, 0), 100)
            kpi["opportunity_score"] = round(
                kpi["viability_score"] * (1 - saturation * 0.4 / 100) * (1 + vel_bonus / 100), 1
            )
            # Hook pattern breakdown: count per pattern
            hook_counts: dict[str, int] = {}
            for ad in buckets[kpi["angle"]]:
                hp = ad.get("angle_data", {}).get("hook_pattern", "")
                if hp:
                    hook_counts[hp] = hook_counts.get(hp, 0) + 1
            kpi["hook_patterns"] = hook_counts
            # Top hook for this angle
            kpi["dominant_hook"] = max(hook_counts, key=lambda k: hook_counts[k]) if hook_counts else ""

        kpis.sort(key=lambda k: k["viability_score"], reverse=True)
        logger.info("Aggregated %d distinct angles from %d ads", len(kpis), total_ads)
        return kpis

    def detect_gaps(
        self,
        angle_kpis: list[dict],
        advertisers: list[dict] | None = None,
        prev_advertisers: list[dict] | None = None,
        total_ads: int = 0,
    ) -> list[dict]:
        """
        Identify angles with high viability but low market saturation.
        Uses an adaptive usage threshold so few-ad runs still surface gaps.

        Returns gaps sorted by viability DESC.
        """
        usage_threshold = _usage_threshold(total_ads or sum(k.get("count", 0) for k in angle_kpis))

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
            low_usage = kpi["usage_pct"] < usage_threshold
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
                        "velocity_pct":        kpi.get("velocity_pct"),
                        "trend":               kpi.get("trend", "stable"),
                        "opportunity_score":   kpi.get("opportunity_score", kpi["viability_score"]),
                        "saturation_index":    kpi.get("saturation_index", 0),
                        "dominant_hook":       kpi.get("dominant_hook", ""),
                    }
                )

        # New entrant signal: advertisers absent from previous analysis
        prev_names = {a.get("name", "") for a in (prev_advertisers or [])}
        new_by_angle: dict[str, int] = {}
        for adv in (advertisers or []):
            if adv.get("name") and adv["name"] not in prev_names:
                for angle in adv.get("angles_used", []):
                    new_by_angle[angle] = new_by_angle.get(angle, 0) + 1

        for gap in gaps:
            n = new_by_angle.get(gap["angle"], 0)
            gap["new_entrants_7d"] = n
            gap["signal"] = "strong" if n >= 3 else "moderate" if n >= 1 else "none"
            vel_bonus = min(max(gap.get("velocity_pct") or 0, 0), 100)
            gap["priority_score"] = round(
                gap["viability_score"] * (1 + vel_bonus / 100) * (1 + n * 0.1), 1
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


def enrich_with_velocity(
    current_kpis: list[dict],
    prev_kpis: list[dict],
) -> list[dict]:
    """Add velocity_pct, trend, and priority_score to KPI dicts in place."""
    prev_map = {k["angle"]: k for k in prev_kpis}
    for kpi in current_kpis:
        prev = prev_map.get(kpi["angle"])
        if prev is None:
            kpi["velocity_pct"] = None
            kpi["trend"] = "new"
        else:
            prev_count = max(prev.get("count", 0), 1)
            delta = round((kpi["count"] - prev_count) / prev_count * 100, 1)
            kpi["velocity_pct"] = delta
            kpi["trend"] = "up" if delta > 15 else "down" if delta < -15 else "stable"
        vel_bonus = min(max(kpi.get("velocity_pct") or 0, 0), 100)
        kpi["priority_score"] = round(kpi["viability_score"] * (1 + vel_bonus / 100), 1)
        # Update opportunity_score with velocity too
        sat = kpi.get("saturation_index", 0)
        kpi["opportunity_score"] = round(
            kpi["viability_score"] * (1 - sat * 0.4 / 100) * (1 + vel_bonus / 100), 1
        )
    return current_kpis
