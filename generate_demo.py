"""Generate realistic demo data — one unique template per niche, no fake shops."""

import json
import random
from datetime import datetime
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
DATA_DIR.mkdir(exist_ok=True)
(DATA_DIR / "history").mkdir(exist_ok=True)

# Seed changes every week so demo data varies
_week = datetime.utcnow().isocalendar()[1]
random.seed(_week * 137 + datetime.utcnow().year)

NICHES = [
    "foot wellness", "posture corrector", "sleep supplement",
    "hair loss", "joint pain", "weight loss", "gut health",
    "collagen", "anti aging", "teeth whitening"
]

# Unique angle templates per niche — (angle, sub_angle, hook, avg_days, base_count)
ANGLE_TEMPLATES: dict[str, list[tuple]] = {
    "foot wellness": [
        ("Pain Agitation",        "Douleur chronique",       "Vous marchez 8h/jour — voici pourquoi vos pieds souffrent", 87, 12),
        ("Before/After",          "Transformation usage",    "30 jours avec ces semelles — résultat surprenant", 74, 8),
        ("Specific Result",       "Chiffre précis",          "94% soulagés en 7 jours — testé sur 3 000 clients", 91, 18),
        ("Authority Expert",      "Podologue recommande",    "Ce que les podologues utilisent vraiment chez eux", 68, 6),
        ("Social Proof",          "Volume clients",          "50 000 paires vendues — voici pourquoi", 55, 22),
        ("Curiosity Gap",         "Cause méconnue",          "La vraie raison pour laquelle vos pieds gonflent le soir", 80, 4),
        ("Price Anchoring",       "Comparaison coût",        "Économisez 300€ de kiné avec cette solution à 29€", 45, 9),
    ],
    "posture corrector": [
        ("Pain Agitation",        "Douleur dos bureau",      "8h assis = 40kg de pression sur votre colonne", 82, 14),
        ("Specific Result",       "Amélioration mesurée",    "Posture corrigée en 21 jours — prouvé par kiné", 88, 10),
        ("Before/After",          "Transformation silhouette","Ma posture avant/après 6 semaines de port", 71, 9),
        ("Curiosity Gap",         "Habitude nocive",         "Ce geste quotidien détruit silencieusement votre dos", 77, 5),
        ("Authority Expert",      "Kinésithérapeute",        "Le kinésithérapeute que consultent les athlètes pros", 65, 7),
        ("Social Proof",          "Avis clients",            "12 000 télétravailleurs ont retrouvé le confort", 52, 19),
    ],
    "sleep supplement": [
        ("Pain Agitation",        "Insomnie chronique",      "Vous fixez le plafond à 3h du matin depuis des mois", 90, 15),
        ("Naturalness",           "Formule clean",           "Zéro somnifère — juste la mélatonine que votre corps réclame", 78, 11),
        ("Specific Result",       "Endormissement rapide",   "S'endormir en moins de 12 minutes — notre promesse", 85, 12),
        ("Social Proof",          "Communauté sommeil",      "200 000 nuits améliorées — rejoignez le mouvement", 60, 20),
        ("Authority Expert",      "Neuroscientifique",       "La formule validée par les neuroscientifiques du sommeil", 70, 6),
        ("Curiosity Gap",         "Cause méconnue",          "Pourquoi votre téléphone sabote votre sommeil profond", 73, 5),
    ],
    "hair loss": [
        ("Pain Agitation",        "Honte et perte confiance","Regarder la douche après votre shampooing vous déprime", 85, 13),
        ("Specific Result",       "Repousse chiffrée",       "67% de repousse en 90 jours — photo à l'appui", 92, 11),
        ("Before/After",          "Densification visible",   "Mes photos avant/après 3 mois de traitement", 79, 8),
        ("Authority Expert",      "Dermatologue trichologie","Le protocole des dermatologues spécialistes", 72, 6),
        ("Contre-intuitive",      "Idée reçue shampoing",    "Pourquoi les shampoings anti-chute aggravent le problème", 80, 4),
        ("Social Proof",          "Résultats hommes 40+",    "15 000 hommes ont stoppé leur chute en 8 semaines", 58, 17),
    ],
    "joint pain": [
        ("Pain Agitation",        "Limitation mobilité",     "Monter les escaliers ne devrait pas être une épreuve", 88, 14),
        ("Naturalness",           "Sans anti-douleur",        "Soulager vos articulations sans ibuprofène ni cortisone", 82, 10),
        ("Specific Result",       "Mobilité retrouvée",      "90% des utilisateurs bougent sans douleur en 4 semaines", 91, 12),
        ("Before/After",          "Retour activité sportive","J'ai recommencé à courir à 58 ans — voici comment", 76, 7),
        ("Authority Expert",      "Rhumatologue",            "Ce que les rhumatologues prescrivent en première intention", 68, 5),
        ("Social Proof",          "Seniors actifs",          "30 000 seniors ont retrouvé leur mobilité", 55, 21),
    ],
    "weight loss": [
        ("Pain Agitation",        "Frustration régime",      "Vous avez tout essayé et rien ne fonctionne — voici pourquoi", 92, 14),
        ("Specific Result",       "Résultat chiffré",        "Perdre 4kg en 3 semaines sans se priver", 88, 11),
        ("Before/After",          "Transformation physique", "Ma transformation en 60 jours — photos avant/après", 79, 16),
        ("Contre-intuitive",      "Manger plus pour maigrir","Pourquoi manger moins vous fait grossir", 83, 5),
        ("Authority Expert",      "Nutritionniste",          "Le protocole des nutritionnistes pour perdre sans reprendre", 70, 7),
        ("Social Proof",          "Communauté succès",       "100 000 personnes ont transformé leur corps", 58, 19),
    ],
    "gut health": [
        ("Pain Agitation",        "Ballonnements quotidiens","Finir chaque repas avec un ventre ballonné, c'est fini", 84, 13),
        ("Naturalness",           "Probiotiques naturels",   "5 milliards de bonnes bactéries par gélule — clean label", 79, 10),
        ("Specific Result",       "Transit régularisé",      "Transit normalisé en 10 jours — garanti ou remboursé", 87, 12),
        ("Curiosity Gap",         "Lien cerveau-intestin",   "Votre intestin fabrique 95% de votre sérotonine", 75, 4),
        ("Authority Expert",      "Gastro-entérologue",      "Le microbiome selon les gastro-entérologues en 2025", 66, 6),
        ("Before/After",          "Énergie retrouvée",       "Avant je finissais épuisée chaque soir — après 1 mois", 71, 9),
    ],
    "collagen": [
        ("Specific Result",       "Peau mesurée",            "+34% d'élasticité cutanée en 28 jours — étude clinique", 90, 13),
        ("Before/After",          "Jeunesse retrouvée",      "Ma peau à 52 ans — le secret que je gardais pour moi", 85, 9),
        ("Authority Expert",      "Dermatologue",            "Ce que les dermatologues consomment eux-mêmes", 74, 7),
        ("Naturalness",           "Collagène marin",         "Collagène marin type I — biodisponibilité maximale", 80, 11),
        ("Pain Agitation",        "Vieillissement visible",  "Chaque matin, ces rides vous rappellent que le temps passe", 77, 8),
        ("Social Proof",          "Femmes 45+",              "80 000 femmes ont retrouvé l'éclat de leurs 35 ans", 55, 20),
    ],
    "anti aging": [
        ("Before/After",          "Rajeunissement visible",  "Ma peau à 55 ans vs 45 ans — le secret de ma routine", 95, 13),
        ("Specific Result",       "Rides réduites",          "Réduction de 40% des rides en 28 jours — prouvé cliniquement", 90, 15),
        ("Authority Expert",      "Dermatologue recommande", "Ce que les dermatologues utilisent vraiment chez eux", 76, 7),
        ("Pain Agitation",        "Peur vieillissement",     "Chaque matin ces rides vous rappellent que le temps passe", 82, 10),
        ("Naturalness",           "Formule clean",           "Sans parabènes ni silicones — juste ce que votre peau demande", 61, 8),
        ("Price Anchoring",       "Luxe accessible",         "L'efficacité des crèmes à 200€ pour 35€", 47, 12),
    ],
    "teeth whitening": [
        ("Specific Result",       "Blancheur chiffrée",      "8 teintes plus blanc en 7 jours — garanti", 86, 12),
        ("Pain Agitation",        "Complexe sourire",        "Vous cachez vos dents en souriant — plus jamais", 80, 10),
        ("Before/After",          "Sourire transformé",      "Mon avant/après en 2 semaines — sans cabinet dentaire", 77, 8),
        ("Authority Expert",      "Dentiste recommande",     "La technique que les dentistes utilisent à domicile", 72, 6),
        ("Naturalness",           "Sans peroxyde",           "Blanc sans agresser l'émail — formule enzymatique", 65, 9),
        ("Price Anchoring",       "Vs blanchiment dentiste", "Le résultat du cabinet dentaire (400€) pour 29€", 58, 14),
    ],
}


# Produits réels-types par niche (titre, prix €, type)
NICHE_PRODUCTS: dict[str, list[dict]] = {
    "foot wellness": [
        {"title": "Semelles orthopédiques gel anti-fatigue", "price": "29.90", "image": "", "url": "#"},
        {"title": "Correcteur d'hallux valgus nuit", "price": "19.90", "image": "", "url": "#"},
        {"title": "Crème réparatrice talons fissurés 75ml", "price": "15.90", "image": "", "url": "#"},
        {"title": "Bande kinésiologique pieds x5", "price": "12.50", "image": "", "url": "#"},
    ],
    "posture corrector": [
        {"title": "Correcteur de posture magnétique dos", "price": "34.90", "image": "", "url": "#"},
        {"title": "Coussin lombaire ergonomique bureau", "price": "45.00", "image": "", "url": "#"},
        {"title": "Appui-tête cervical voiture", "price": "22.90", "image": "", "url": "#"},
    ],
    "sleep supplement": [
        {"title": "Mélatonine + Magnésium 60 gélules", "price": "24.90", "image": "", "url": "#"},
        {"title": "Spray sommeil CBD 30ml", "price": "39.90", "image": "", "url": "#"},
        {"title": "Masque yeux refroidissant + lavande", "price": "17.90", "image": "", "url": "#"},
        {"title": "Tisane sommeil profond bio x20", "price": "12.90", "image": "", "url": "#"},
    ],
    "hair loss": [
        {"title": "Sérum repousse biotine + kératine 50ml", "price": "44.90", "image": "", "url": "#"},
        {"title": "Shampooing anti-chute DHT-Block 250ml", "price": "28.90", "image": "", "url": "#"},
        {"title": "Complément alimentaire cheveux 90 cp", "price": "34.90", "image": "", "url": "#"},
        {"title": "Dermaroller scalp 0.5mm titanium", "price": "19.90", "image": "", "url": "#"},
    ],
    "joint pain": [
        {"title": "Collagène type II + glucosamine 120 cp", "price": "39.90", "image": "", "url": "#"},
        {"title": "Genouillère compression sport", "price": "24.90", "image": "", "url": "#"},
        {"title": "Baume articulaire chaud/froid 100ml", "price": "18.90", "image": "", "url": "#"},
        {"title": "Complexe curcuma + poivre noir 60 gél", "price": "29.90", "image": "", "url": "#"},
    ],
    "weight loss": [
        {"title": "Brûle-graisses thermogénique 90 cp", "price": "39.90", "image": "", "url": "#"},
        {"title": "Coupe-faim psyllium blond 200g", "price": "22.90", "image": "", "url": "#"},
        {"title": "Programme detox 21 jours tisanes", "price": "49.90", "image": "", "url": "#"},
        {"title": "Ceinture sudation néoprène mixte", "price": "29.90", "image": "", "url": "#"},
    ],
    "gut health": [
        {"title": "Probiotiques 50 milliards UFC 60 gél", "price": "34.90", "image": "", "url": "#"},
        {"title": "Bouillon os collagène intestinal 500g", "price": "27.90", "image": "", "url": "#"},
        {"title": "L-Glutamine poudre 300g", "price": "24.90", "image": "", "url": "#"},
        {"title": "Kéfir fermenté bio 1L", "price": "12.90", "image": "", "url": "#"},
    ],
    "collagen": [
        {"title": "Collagène marin hydrolysé poudre 300g", "price": "44.90", "image": "", "url": "#"},
        {"title": "Sérum vitamine C + collagène 30ml", "price": "39.90", "image": "", "url": "#"},
        {"title": "Boisson beauté collagène + acide hyaluronique", "price": "34.90", "image": "", "url": "#"},
    ],
    "anti aging": [
        {"title": "Crème rétinol nuit 0.3% 50ml", "price": "49.90", "image": "", "url": "#"},
        {"title": "Sérum acide hyaluronique multi-couches 30ml", "price": "39.90", "image": "", "url": "#"},
        {"title": "Masque LED anti-âge 7 couleurs", "price": "89.90", "image": "", "url": "#"},
        {"title": "NMN + Resvératrol 60 gélules", "price": "59.90", "image": "", "url": "#"},
    ],
    "teeth whitening": [
        {"title": "Kit blanchiment LED + 6 seringues gel", "price": "34.90", "image": "", "url": "#"},
        {"title": "Bandes blanchissantes 3D sans peroxyde x14", "price": "22.90", "image": "", "url": "#"},
        {"title": "Charbon actif poudre blanchissante 60g", "price": "14.90", "image": "", "url": "#"},
        {"title": "Dentifrice remineralisant nano-hydroxyapatite", "price": "18.90", "image": "", "url": "#"},
    ],
}


def make_niche_data(niche: str) -> dict:
    templates  = ANGLE_TEMPLATES.get(niche, ANGLE_TEMPLATES["foot wellness"])
    products   = NICHE_PRODUCTS.get(niche, NICHE_PRODUCTS["foot wellness"])
    total_ads  = random.randint(45, 95)
    angle_kpis = []
    gaps       = []

    for angle, sub_angle, hook, avg_days, base_count in templates:
        count     = max(1, base_count + random.randint(-2, 4))
        usage_pct = round(count / total_ads, 4)
        avg_d     = avg_days + random.uniform(-8, 8)
        med_d     = avg_d * random.uniform(0.85, 1.05)
        viability = min(avg_d, 100.0)

        examples = [
            {
                "hook":             hook,
                "store":            f"example-{niche.replace(' ','-')}-{i}.myshopify.com",
                "days_running":     int(avg_d + random.uniform(-15, 20)),
                "landing_page_url": "",
            }
            for i in range(min(3, count))
        ]

        kpi = {
            "angle":              angle,
            "count":              count,
            "usage_pct":          usage_pct,
            "avg_days_running":   round(avg_d, 1),
            "median_days_running":round(med_d, 1),
            "viability_score":    round(viability, 1),
            "examples":           examples,
            "sub_angles":         [sub_angle],
            "primary_audience":   _audience(niche),
        }
        angle_kpis.append(kpi)

        if usage_pct < 0.10 and viability > 60:
            # Recommend 2-3 products from this niche for this gap angle
            rec_products = random.sample(products, min(3, len(products)))
            gaps.append({
                "angle":                angle,
                "viability_score":      round(viability, 1),
                "usage_count":          count,
                "usage_pct":            usage_pct,
                "avg_days_running":     round(avg_d, 1),
                "examples":             examples,
                "primary_audience":     _audience(niche),
                "potential":            "HIGH",
                "recommended_products": rec_products,
            })

    angle_kpis.sort(key=lambda k: k["viability_score"], reverse=True)
    gaps.sort(key=lambda g: g["viability_score"], reverse=True)

    # Build minimal advertiser entries so demo shows product recommendations in gaps
    advertisers = [
        {
            "name":             f"Boutique {niche.title()} #{i+1}",
            "domain":           "",
            "store_url":        "",
            "scaling_score":    round(random.uniform(40, 180), 1),
            "ads_count":        random.randint(2, 8),
            "max_days_running": random.randint(20, 90),
            "avg_days_running": round(random.uniform(15, 70), 1),
            "estimated_spend":  random.randint(200, 2000),
            "angles_used":      [t[0] for t in random.sample(templates, min(3, len(templates)))],
            "dominant_angle":   templates[0][0],
            "angle_gaps":       [g["angle"] for g in gaps[:2]],
            "products":         random.sample(products, min(3, len(products))),
            "ad_examples":      [],
            "platforms":        random.sample(["facebook", "instagram", "audience_network"], 2),
        }
        for i in range(3)
    ]
    advertisers.sort(key=lambda a: a["scaling_score"], reverse=True)

    return {
        "niche":       niche,
        "angle_kpis":  angle_kpis,
        "gaps":        gaps,
        "advertisers": advertisers,
        "stats": {
            "total_ads":         total_ads,
            "unique_angles":     len(angle_kpis),
            "gaps_found":        len(gaps),
            "advertisers_found": len(advertisers),
        },
    }


def _audience(niche: str) -> str:
    return {
        "foot wellness":     "femmes 35-60, actives",
        "posture corrector": "télétravailleurs 25-45",
        "sleep supplement":  "adultes 30-55, stressés",
        "hair loss":         "hommes 30-55",
        "joint pain":        "hommes/femmes 45-70",
        "weight loss":       "femmes 25-45",
        "gut health":        "femmes 28-50",
        "collagen":          "femmes 35-55",
        "anti aging":        "femmes 45-65",
        "teeth whitening":   "adultes 20-40",
    }.get(niche, "adultes 30-55")


def generate() -> None:
    now     = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    results = [make_niche_data(n) for n in NICHES]
    total   = sum(r["stats"]["total_ads"] for r in results)

    analysis = {
        "generated_at":     now,
        "niches_processed": NICHES,
        "total_ads":        total,
        "demo":             True,
        "results":          results,
    }

    out = DATA_DIR / "latest_analysis.json"
    out.write_text(json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Demo data → {out}  ({total} ads, {len(NICHES)} niches, 0 faux shops)")

    (DATA_DIR / "latest.json").write_text(
        json.dumps({"generated_at": now, "ads": [], "demo": True}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    stamp = datetime.utcnow().strftime("%Y-%m-%d_%H-%M")
    (DATA_DIR / "history" / f"{stamp}_demo.json").write_text(
        json.dumps(analysis, ensure_ascii=False, indent=2), encoding="utf-8"
    )


if __name__ == "__main__":
    generate()
