"""Send alerts when strong angle signals are detected (Discord webhook / log)."""

from __future__ import annotations

import logging
import os

import aiohttp

logger = logging.getLogger(__name__)


async def _post_discord(webhook_url: str, content: str) -> None:
    try:
        async with aiohttp.ClientSession() as session:
            await session.post(
                webhook_url,
                json={"content": content, "username": "TrendTrack"},
                timeout=aiohttp.ClientTimeout(total=8),
            )
    except Exception as exc:
        logger.warning("Discord webhook failed: %s", exc)


def _format_alert(niche: str, gap: dict) -> str:
    angle   = gap.get("angle", "?")
    signal  = gap.get("signal", "none")
    n       = gap.get("new_entrants_7d", 0)
    vscore  = gap.get("viability_score", 0)
    pscore  = gap.get("priority_score", 0)
    opp     = gap.get("opportunity_score", vscore)

    emoji = "🔥" if signal == "strong" else "📡"
    return (
        f"{emoji} **TrendTrack Alert — {niche}**\n"
        f"Angle : **{angle}**\n"
        f"Signal : {signal} · {n} nouveaux advertisers 7j\n"
        f"Viabilité {vscore} · Opportunité {opp} · Priorité {pscore}\n"
        f"→ Angle peu exploité avec forte traction"
    )


async def send_alerts(niche: str, gaps: list[dict]) -> None:
    """
    Send Discord webhook alerts for strong-signal gaps.
    Reads ALERT_WEBHOOK_URL from env. No-op if not set.
    """
    webhook_url = os.getenv("ALERT_WEBHOOK_URL", "")
    if not webhook_url:
        return

    strong_gaps = [g for g in gaps if g.get("signal") in ("strong", "moderate") and g.get("new_entrants_7d", 0) >= 1]
    if not strong_gaps:
        return

    for gap in strong_gaps[:3]:  # max 3 alerts per niche per run
        msg = _format_alert(niche, gap)
        logger.info("Sending alert for angle '%s' in niche '%s'", gap.get("angle"), niche)
        await _post_discord(webhook_url, msg)
