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

_ADS_ANALYSIS_PROMPT = """\
Tu es un analyste compétitif e-commerce de niveau expert — l'équivalent humain d'AdSpy + Minea + BigSpy combinés.

Je te fournis {count} publicités actives scrapées (Facebook + TikTok) pour la niche **{niche}** sur le marché **{country}**.

Format de chaque ligne : COPY | PLATEFORME | JOURS_ACTIF | ENGAGEMENT | PAGE | CTR%
---
{ads_table}
---

Analyse ces données et produis un rapport de compétition complet.
Réponds UNIQUEMENT en JSON valide, sans markdown.

{{
  "winning_hooks": [
    {{
      "rank": 1,
      "formula": "structure réutilisable (ex: 'Tu as [PROBLÈME] depuis X ans ? [SOLUTION] en Y jours')",
      "example": "copie exacte de la meilleure ad de ce type",
      "trigger": "peur|curiosité|transformation|preuve_sociale|autorité|urgence|contre_intuitif|prix",
      "why_it_works": "mécanisme psychologique en 1 phrase",
      "usage_count": 0,
      "avg_engagement": 0
    }}
  ],
  "top_angles": [
    {{
      "angle": "nom",
      "ads_count": 0,
      "avg_days_running": 0,
      "best_performing_copy": "extrait de la meilleure pub",
      "saturation": "low|medium|high|saturé",
      "verdict": "scaler_maintenant|tester|éviter",
      "why": "justification courte"
    }}
  ],
  "audience_insights": {{
    "primary_demographic": "ex: femmes 35-55, sportifs hommes 25-40",
    "secondary_demographic": "...",
    "pain_points_ranked": ["douleur 1", "douleur 2", "douleur 3"],
    "desires_ranked": ["désir 1", "désir 2", "désir 3"],
    "language_register": "aspirationnel|urgence|éducatif|emotionnel|direct",
    "cultural_triggers": ["trigger FR spécifique si pertinent"]
  }},
  "market_intelligence": {{
    "maturity_score": 0,
    "maturity_label": "vierge|émergent|croissance|mature|saturé",
    "top_spenders": ["brand1 (estimation dépense)", "brand2"],
    "emerging_brands": ["brand en montée"],
    "dominant_platform": "meta|tiktok|both",
    "avg_ad_lifespan_days": 0,
    "price_positioning": "budget <20€|mid 20-50€|premium 50€+",
    "creative_trends": ["UGC dominant", "before/after", "vidéo courte", "etc."]
  }},
  "untapped_opportunities": [
    {{
      "type": "angle|audience|format|prix|géo",
      "opportunity": "description précise et actionnable",
      "evidence": "pourquoi c'est absent des ads actuelles",
      "urgency": "now|3_months|long_term",
      "estimated_potential": "faible|moyen|fort|très_fort"
    }}
  ],
  "winning_formula": {{
    "headline_templates": [
      "Template 1 : [CHIFFRE] [BÉNÉFICE] en [DÉLAI] sans [FRICTION]",
      "Template 2 : Pourquoi [AUDIENCE] [FAIT] maintenant"
    ],
    "best_cta_patterns": ["CTA 1", "CTA 2", "CTA 3"],
    "proof_hierarchy": ["preuve 1 (la + efficace)", "preuve 2", "preuve 3"],
    "recommended_structure": "Hook → Problème aggravé → Solution unique → Preuve → Offre → CTA",
    "format_recommendation": "video_ugc|carousel|image_statique|reel"
  }},
  "competitive_alerts": [
    {{
      "alert": "description de la menace ou opportunité",
      "brand": "nom si identifiable",
      "severity": "info|warning|urgent"
    }}
  ],
  "market_verdict": "2-3 phrases : état du marché maintenant + action prioritaire + fenêtre d'opportunité"
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


async def analyze_ads(
    ads: list[dict],
    niche: str,
    country: str = "FR",
    model: str = "gemini-2.0-flash",
    max_ads: int = 60,
) -> dict:
    """
    AdSpy-style competitive analysis of scraped ads using Gemini.

    Takes the raw ad batch and produces: winning hooks, top angles,
    audience insights, market intelligence, untapped opportunities,
    winning formula, competitive alerts, and a market verdict.

    No Google Search grounding — reasons over provided ad data.
    """
    if not GEMINI_API_KEY or not ads:
        return {}

    country_name = _COUNTRY_NAMES.get(country, country)

    # Build compact table: top ads by engagement, then by days_running
    ranked = sorted(ads, key=lambda a: (a.get("engagement_score", 0) + a.get("days_running", 0) * 5), reverse=True)
    top_ads = ranked[:max_ads]

    rows: list[str] = []
    for a in top_ads:
        copy     = (a.get("ad_copy") or "")[:120].replace("\n", " ").replace("|", "/")
        platform = a.get("platform", "?")
        days     = a.get("days_running", 0)
        eng      = a.get("engagement_score", 0)
        page     = (a.get("page_name") or "")[:25].replace("|", "/")
        ctr      = f"{a.get('ctr', 0):.1f}" if a.get("ctr") else "?"
        rows.append(f"{copy} | {platform} | {days}j | {eng} | {page} | {ctr}%")

    ads_table = "\n".join(rows) if rows else "(aucune donnée)"

    prompt = _ADS_ANALYSIS_PROMPT.format(
        count=len(top_ads),
        niche=niche,
        country=country_name,
        ads_table=ads_table,
    )

    try:
        from google import genai
        from google.genai import types
        client = genai.Client(api_key=GEMINI_API_KEY)
        # No Google Search tool here — pure reasoning over provided data
        cfg = types.GenerateContentConfig(temperature=0.15, max_output_tokens=3000)
        response = await client.aio.models.generate_content(
            model=model,
            contents=prompt,
            config=cfg,
        )
        data = _parse_json(response.text)
        if data:
            logger.info(
                "Gemini ads analysis OK for '%s': %d hooks, verdict='%s'",
                niche,
                len(data.get("winning_hooks", [])),
                str(data.get("market_verdict", ""))[:60],
            )
        return data
    except Exception as exc:
        logger.warning("Gemini ads analysis failed for '%s': %s", niche, exc)
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
