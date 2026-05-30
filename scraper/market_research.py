"""Deep market research via Gemini 2.0 Flash with Google Search grounding."""

from __future__ import annotations

import json
import logging
import os
import re

logger = logging.getLogger(__name__)

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

_COUNTRY_NAMES = {
    "FR": "France", "US": "United States", "GB": "United Kingdom",
    "DE": "Germany", "ES": "Spain", "IT": "Italy", "CA": "Canada",
}

# ── Prompts ────────────────────────────────────────────────────────────────

_NICHE_PROMPT = """\
You are an expert in e-commerce, dropshipping, and digital marketing trends.

Using your web search capabilities, research the niche "{niche}" in the {country} market RIGHT NOW.

Search for:
- Current TikTok videos, hashtags, and viral trends related to this niche
- Amazon.{tld} bestseller pages and competition level for this niche
- Google Trends direction for this niche (growing / stable / declining)
- Current Meta/Facebook/Instagram ads running in this niche
- Key buyer personas and pain points
- New emerging product angles or hooks that are gaining traction

Return ONLY a valid JSON object with this exact structure (no markdown, no explanation):
{{
  "market_trend": "growing" | "stable" | "declining",
  "trend_confidence": 0-100,
  "tiktok_activity": {{
    "level": "high" | "medium" | "low" | "none",
    "estimated_views": "e.g. 50M+",
    "top_hashtags": ["#tag1", "#tag2"],
    "notes": "brief description"
  }},
  "amazon_competition": {{
    "level": "saturated" | "high" | "medium" | "low",
    "avg_price_range": "e.g. 15-45€",
    "notes": "brief description"
  }},
  "key_audiences": ["audience 1", "audience 2", "audience 3"],
  "emerging_angles": ["angle 1", "angle 2", "angle 3"],
  "pain_points": ["pain 1", "pain 2", "pain 3"],
  "seasonality": "year-round" | "Q4" | "summer" | "spring" | "winter",
  "market_size_estimate": "e.g. 2-5M€/year",
  "opportunity_score": 0-100,
  "summary": "2-3 sentence market overview"
}}
"""

_GAP_PROMPT = """\
You are an expert in Meta Ads, e-commerce copywriting, and market research.

For the niche "{niche}" in {country}, search the web for evidence about these specific ad angles:
{angles_list}

For each angle, search for:
- Existing Facebook/Instagram ads using this angle (use Facebook Ad Library if possible)
- TikTok videos or creators using this angle
- Shopify stores or landing pages built around this angle
- Products or brands successfully monetising this angle

Return ONLY a valid JSON object (no markdown):
{{
  "validations": [
    {{
      "angle": "exact angle name",
      "market_evidence": "high" | "medium" | "low",
      "web_presence": "1-2 sentence summary of what you found",
      "competing_brands": ["brand1", "brand2"],
      "recommended_hook": "best hook suggestion based on research",
      "confidence": 0-100
    }}
  ]
}}
"""


# ── JSON extraction ────────────────────────────────────────────────────────

def _parse_json(text: str) -> dict:
    """Robustly extract a JSON object from a Gemini response."""
    text = text.strip()
    try:
        return json.loads(text)
    except Exception:
        pass
    m = re.search(r"```(?:json)?\s*([\s\S]+?)\s*```", text)
    if m:
        try:
            return json.loads(m.group(1))
        except Exception:
            pass
    m = re.search(r"\{[\s\S]+\}", text)
    if m:
        try:
            return json.loads(m.group(0))
        except Exception:
            pass
    return {}


# ── Client factory ─────────────────────────────────────────────────────────

def _make_client():
    """Lazy-import and return an async Gemini client."""
    from google import genai  # pip install google-genai
    return genai.Client(api_key=GEMINI_API_KEY)


def _make_config(model: str):
    from google.genai import types
    return (
        model,
        types.GenerateContentConfig(
            tools=[types.Tool(google_search=types.GoogleSearch())],
            temperature=0.1,
        ),
    )


# ── Public API ─────────────────────────────────────────────────────────────

async def research_niche(
    niche: str,
    country: str = "FR",
    model: str = "gemini-2.0-flash",
) -> dict:
    """
    Research a niche using Gemini + Google Search grounding.
    Returns market intelligence dict, or {} if API key not set / request fails.
    """
    if not GEMINI_API_KEY:
        logger.debug("GEMINI_API_KEY not set — skipping market research")
        return {}

    country_name = _COUNTRY_NAMES.get(country, country)
    tld = "fr" if country == "FR" else "co.uk" if country == "GB" else "com"

    prompt = _NICHE_PROMPT.format(
        niche=niche,
        country=country_name,
        tld=tld,
    )

    try:
        client = _make_client()
        model_id, cfg = _make_config(model)
        response = await client.aio.models.generate_content(
            model=model_id,
            contents=prompt,
            config=cfg,
        )
        data = _parse_json(response.text)
        if data:
            logger.info(
                "Gemini research OK for '%s': trend=%s opp=%s",
                niche, data.get("market_trend"), data.get("opportunity_score"),
            )
        return data
    except Exception as exc:
        logger.warning("Gemini research failed for '%s': %s", niche, exc)
        return {}


async def validate_gap_angles(
    niche: str,
    gaps: list[dict],
    country: str = "FR",
    model: str = "gemini-2.0-flash",
    max_angles: int = 5,
) -> list[dict]:
    """
    Validate gap angles with Gemini web search.
    Enriches each gap dict with a 'gemini_validation' key in-place.
    Returns the (mutated) gaps list.
    """
    if not GEMINI_API_KEY or not gaps:
        return gaps

    country_name = _COUNTRY_NAMES.get(country, country)
    angles = [g["angle"] for g in gaps[:max_angles]]
    angles_list = "\n".join(f"- {a}" for a in angles)

    prompt = _GAP_PROMPT.format(
        niche=niche,
        country=country_name,
        angles_list=angles_list,
    )

    try:
        client = _make_client()
        model_id, cfg = _make_config(model)
        response = await client.aio.models.generate_content(
            model=model_id,
            contents=prompt,
            config=cfg,
        )
        data = _parse_json(response.text)
        validations = {v["angle"]: v for v in data.get("validations", [])}

        for gap in gaps:
            v = validations.get(gap["angle"])
            if v:
                gap["gemini_validation"] = v

        logger.info("Gemini validated %d gap angles for '%s'", len(validations), niche)

    except Exception as exc:
        logger.warning("Gemini gap validation failed for '%s': %s", niche, exc)

    return gaps
