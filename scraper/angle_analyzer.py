"""Claude API-powered ad angle classifier."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any

import anthropic

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """Tu es un expert en copywriting e-commerce et marketing direct.
Analyse la pub suivante et identifie précisément son ANGLE MARKETING.

Un angle = la promesse principale + le levier psychologique utilisé.

Réponds UNIQUEMENT en JSON valide, sans markdown, sans explication, avec cette structure exacte :
{
  "angle": "nom court de l'angle (3-5 mots max)",
  "sub_angle": "sous-catégorie précise",
  "hook": "phrase d'accroche principale extraite ou résumée",
  "pain_point": "douleur / frustration adressée",
  "desire": "bénéfice / rêve vendu",
  "mechanism": "comment le produit résout le problème (le 'comment')",
  "social_proof_type": "testimonial | stats | authority | none",
  "urgency_type": "scarcity | time | none",
  "audience": "cible implicite (ex: femmes 35-50, hommes sportifs...)",
  "confidence": 0.9
}

Angles courants (non exhaustif) :
- Pain agitation
- Social proof
- Authority / Expert
- Before/After transformation
- Fear of missing out
- Curiosity gap
- Specific result (chiffre précis)
- Contre-intuitive claim
- Us vs them
- Price anchoring
- Naturalness / Clean label
"""


async def _call_claude(
    client: anthropic.AsyncAnthropic,
    ad_copy: str,
    semaphore: asyncio.Semaphore,
) -> dict:
    """Call Claude to classify a single ad's angle. Returns angle dict."""
    async with semaphore:
        try:
            resp = await asyncio.wait_for(
                client.messages.create(
                    model="claude-sonnet-4-6",
                    max_tokens=512,
                    system=SYSTEM_PROMPT,
                    messages=[{"role": "user", "content": f"AD COPY:\n{ad_copy}"}],
                ),
                timeout=20.0,
            )
            raw = resp.content[0].text.strip()
            # Strip markdown fences if present
            if raw.startswith("```"):
                raw = raw.split("```")[1]
                if raw.startswith("json"):
                    raw = raw[4:]
            return _parse_angle_json(raw)
        except asyncio.TimeoutError:
            logger.warning("Claude timeout — classifying as Unknown")
            return _unknown_angle()
        except Exception as exc:
            logger.warning("Claude error: %s — classifying as Unknown", exc)
            return _unknown_angle()


def _parse_angle_json(raw: str) -> dict:
    """Parse Claude's JSON output; fall back gracefully."""
    import json

    try:
        data = json.loads(raw)
        # Ensure required keys exist
        return {
            "angle": data.get("angle", "Unknown"),
            "sub_angle": data.get("sub_angle", ""),
            "hook": data.get("hook", ""),
            "pain_point": data.get("pain_point", ""),
            "desire": data.get("desire", ""),
            "mechanism": data.get("mechanism", ""),
            "social_proof_type": data.get("social_proof_type", "none"),
            "urgency_type": data.get("urgency_type", "none"),
            "audience": data.get("audience", ""),
            "confidence": float(data.get("confidence", 0.5)),
        }
    except Exception as exc:
        logger.debug("JSON parse error: %s | raw=%s", exc, raw[:200])
        return _unknown_angle()


def _unknown_angle() -> dict:
    return {
        "angle": "Unknown",
        "sub_angle": "",
        "hook": "",
        "pain_point": "",
        "desire": "",
        "mechanism": "",
        "social_proof_type": "none",
        "urgency_type": "none",
        "audience": "",
        "confidence": 0.0,
    }


class AngleAnalyzer:
    """Batch-async Claude-powered angle classifier for Meta ads."""

    def __init__(
        self,
        api_key: str | None = None,
        concurrency: int = 5,
    ) -> None:
        key = api_key or os.getenv("CLAUDE_API_KEY") or os.getenv("ANTHROPIC_API_KEY", "")
        if not key:
            raise ValueError("CLAUDE_API_KEY environment variable is required")
        self._client = anthropic.AsyncAnthropic(api_key=key)
        self._sem = asyncio.Semaphore(concurrency)

    async def batch_analyze_ads(self, ads: list[dict]) -> list[dict]:
        """
        Classify angles for a list of ad dicts.

        Each input dict gains an 'angle_data' key with the classification result.
        Returns enriched ad dicts.
        """
        if not ads:
            return []

        logger.info("Analysing %d ads with Claude…", len(ads))
        tasks = [
            _call_claude(self._client, ad.get("ad_copy", ""), self._sem)
            for ad in ads
        ]
        results: list[Any] = await asyncio.gather(*tasks, return_exceptions=True)

        enriched: list[dict] = []
        for ad, result in zip(ads, results):
            if isinstance(result, Exception):
                logger.warning("Gather exception: %s", result)
                angle_data = _unknown_angle()
            else:
                angle_data = result
            enriched.append({**ad, "angle_data": angle_data})

        known = sum(1 for a in enriched if a["angle_data"]["angle"] != "Unknown")
        logger.info("Classified %d/%d ads successfully", known, len(enriched))
        return enriched
