"""Generate realistic demo data for the dashboard when scraping returns 0 results."""

import json
import random
from datetime import datetime
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
DATA_DIR.mkdir(exist_ok=True)
(DATA_DIR / "history").mkdir(exist_ok=True)

NICHES = [
    "foot wellness", "posture corrector", "sleep supplement",
    "hair loss", "joint pain", "weight loss", "gut health",
    "collagen", "anti aging", "teeth whitening"
]

ANGLE_TEMPLATES = {
    "foot wellness": [
        ("Pain Agitation", "Douleur chronique", "Vous marchez 8h/jour avec des semelles inadaptées — voici pourquoi vos pieds vous font souffrir", 87, 12),
        ("Before/After Transformation", "Avant/après usage", "J'ai testé ces semelles pendant 30 jours — résultats surprenants", 74, 8),
        ("Specific Result", "Chiffre précis", "94% des utilisateurs ressentent un soulagement en 7 jours", 91, 18),
        ("Authority / Expert", "Recommandation podologue", "Les podologues recommandent cette technique anti-douleur", 68, 6),
        ("Social Proof", "Témoignages clients", "Plus de 50 000 paires vendues — voici pourquoi", 55, 22),
        ("Curiosity Gap", "Secret méconnu", "La vraie raison pour laquelle vos pieds gonflent le soir", 80, 4),
        ("Us vs Them", "Vs semelles classiques", "Pourquoi les semelles classiques aggravent vos douleurs", 63, 7),
        ("Price Anchoring", "Comparaison coût", "Évitez 300€ chez le kiné avec cette solution à 29€", 45, 9),
    ],
    "weight loss": [
        ("Pain Agitation", "Frustration régime", "Vous avez tout essayé et rien ne fonctionne — voici pourquoi", 92, 14),
        ("Specific Result", "Résultat chiffré", "Perdre 4kg en 3 semaines sans se priver", 88, 11),
        ("Before/After Transformation", "Transformation physique", "Ma transformation en 60 jours — photos avant/après", 79, 16),
        ("Curiosity Gap", "Mécanisme inconnu", "Ce que votre médecin ne vous dit pas sur la perte de poids", 71, 5),
        ("Authority / Expert", "Validation scientifique", "Approuvé par des nutritionnistes : la méthode du jeûne intermittent", 65, 8),
        ("Social Proof", "Communauté de succès", "Rejoignez 100 000 personnes qui ont transformé leur corps", 58, 19),
        ("Contre-intuitive", "Manger plus pour maigrir", "Pourquoi manger moins vous fait grossir — la science l'explique", 83, 3),
    ],
    "anti aging": [
        ("Before/After Transformation", "Rajeunissement visible", "Ma peau à 55 ans vs 45 ans — le secret de ma routine", 95, 13),
        ("Pain Agitation", "Peur du vieillissement", "Chaque matin, ces rides vous rappellent que le temps passe", 82, 10),
        ("Authority / Expert", "Dermatologue recommande", "Ce que les dermatologues utilisent vraiment chez eux", 76, 7),
        ("Specific Result", "Résultat mesurable", "Réduction de 40% des rides en 28 jours — prouvé cliniquement", 90, 15),
        ("Naturalness", "Formule clean", "Sans parabènes, sans silicones — juste ce que votre peau demande", 61, 8),
        ("Social Proof", "Stars et célébrités", "Le secret beauté des femmes de 50 ans qui font 35 ans", 54, 21),
        ("Price Anchoring", "Luxe accessible", "L'efficacité des crèmes à 200€ pour 35€", 47, 12),
    ],
}

DEMO_HOOKS = [
    "Stop wasting money on {product} that don't work",
    "The #1 mistake people make with {product}",
    "Why your doctor won't tell you about this {product}",
    "I tried {product} for 30 days — here's what happened",
    "Scientists discover {benefit} — without side effects",
    "Join 50,000+ people who finally solved their {problem}",
]

STORES = [
    "wellness-pro.myshopify.com", "natureflex.myshopify.com",
    "healthboost.myshopify.com", "vitalcare.myshopify.com",
    "bioessence.myshopify.com", "purehealth.myshopify.com",
]


def make_niche_data(niche: str) -> dict:
    templates = ANGLE_TEMPLATES.get(niche, ANGLE_TEMPLATES["foot wellness"])
    total_ads = random.randint(45, 95)

    angle_kpis = []
    gaps = []
    ads_assigned = 0

    for i, (angle, sub_angle, hook_tmpl, avg_days, base_count) in enumerate(templates):
        count = base_count + random.randint(-2, 4)
        count = max(1, count)
        ads_assigned += count
        usage_pct = round(count / total_ads, 4)
        avg_d = avg_days + random.uniform(-8, 8)
        med_d = avg_d * random.uniform(0.85, 1.05)
        viability = min(avg_d, 100.0)

        examples = [
            {
                "hook": hook_tmpl,
                "store": random.choice(STORES),
                "days_running": int(avg_d + random.uniform(-15, 20)),
                "landing_page_url": f"https://{random.choice(STORES)}/products/solution-{i+1}",
            }
            for _ in range(min(3, count))
        ]

        kpi = {
            "angle": angle,
            "count": count,
            "usage_pct": usage_pct,
            "avg_days_running": round(avg_d, 1),
            "median_days_running": round(med_d, 1),
            "viability_score": round(viability, 1),
            "examples": examples,
            "sub_angles": [sub_angle],
            "primary_audience": _audience(niche),
        }
        angle_kpis.append(kpi)

        if usage_pct < 0.10 and viability > 60:
            gaps.append({
                "angle": angle,
                "viability_score": round(viability, 1),
                "usage_count": count,
                "usage_pct": usage_pct,
                "avg_days_running": round(avg_d, 1),
                "examples": examples,
                "primary_audience": _audience(niche),
                "potential": "HIGH",
            })

    angle_kpis.sort(key=lambda k: k["viability_score"], reverse=True)
    gaps.sort(key=lambda g: g["viability_score"], reverse=True)

    reco = [
        {
            "angle": g["angle"],
            "products": [
                {
                    "title": f"{niche.title()} Solution Pro",
                    "handle": f"{niche.replace(' ','-')}-pro",
                    "url": f"https://{random.choice(STORES)}/products/{niche.replace(' ','-')}-pro",
                    "price": str(random.randint(19, 79)) + ".99",
                    "image": "",
                    "store": random.choice(STORES),
                }
            ],
            "trends": [{"product": f"{niche.title()} Solution", "trend": "rising", "volume": "medium"}],
        }
        for g in gaps[:3]
    ]

    return {
        "niche": niche,
        "angle_kpis": angle_kpis,
        "gaps": gaps,
        "recommendations": reco,
        "stats": {
            "total_ads": total_ads,
            "unique_angles": len(angle_kpis),
            "gaps_found": len(gaps),
        },
    }


def _audience(niche: str) -> str:
    mapping = {
        "foot wellness": "femmes 35-60, actives",
        "weight loss": "femmes 25-45",
        "anti aging": "femmes 45-65",
        "hair loss": "hommes 30-55",
        "joint pain": "hommes/femmes 45-70",
        "sleep supplement": "adultes 30-55, stressés",
        "gut health": "femmes 28-50",
        "collagen": "femmes 35-55",
        "posture corrector": "télétravailleurs 25-45",
        "teeth whitening": "adultes 20-40",
    }
    return mapping.get(niche, "adultes 30-55")


def generate():
    now = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    results = [make_niche_data(n) for n in NICHES]
    total_ads = sum(r["stats"]["total_ads"] for r in results)

    analysis = {
        "generated_at": now,
        "niches_processed": NICHES,
        "total_ads": total_ads,
        "results": results,
    }

    out = DATA_DIR / "latest_analysis.json"
    out.write_text(json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Demo data written → {out}  ({total_ads} ads simulés, {len(NICHES)} niches)")

    # Also write latest.json stub
    (DATA_DIR / "latest.json").write_text(
        json.dumps({"generated_at": now, "ads": [], "demo": True}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # Archive
    stamp = datetime.utcnow().strftime("%Y-%m-%d_%H-%M")
    (DATA_DIR / "history" / f"{stamp}_demo.json").write_text(
        json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    generate()
