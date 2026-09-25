package fr.ningbus.arbitre.moteur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La carte du 25/09, relevée à l'écran : un feu vert sur un prix impossible.
 *
 * L'application a affiché, en toutes lettres et en vert : **4 768,00 €** pour
 * 47,7 km, soit 90,47 €/km et 3 815 €/h, avec 100 % de confiance et 90/100 au
 * score d'offre. Tous les autres chiffres étaient justes — l'approche, le
 * trajet, les durées, le coût de roulage au centime près. Un seul ne l'était
 * pas, et il suffisait à faire dire « PRENDS » à une course que le chauffeur a
 * refusée, à raison.
 *
 * C'est le cas que la confiance était censée attraper et qu'elle a manqué,
 * pour la raison déjà rencontrée à Grigny : elle mesure si les champs sont
 * remplis, pas s'ils sont vrais. Un montant aberrant est un champ rempli.
 */
class PrixInvraisemblableTest {

    /** La course telle que l'écran l'a donnée, prix compris. */
    private val carte = Course(
        plateforme = "Uber",
        prix = 4768.0,
        kmApproche = 5.0,
        minutesApproche = 12.0,
        kmTrajet = 47.7,
        minutesTrajet = 45.0,
    )

    @Test
    fun `un prix impossible ne produit plus de feu vert`() {
        val v = Arbitre.arbitrer(carte)
        assertEquals(Decision.INCOMPLET, v.decision, "4 768 € ne peut pas être un prix")
    }

    @Test
    fun `le motif nomme le montant refuse et propose la lecture probable`() {
        val motif = assertNotNull(Arbitre.arbitrer(carte).motif)
        assertTrue(motif.contains("4768,00 €"), "le montant refusé doit être nommé : $motif")
        assertTrue(motif.contains("47,68 €"), "le séparateur perdu doit être proposé : $motif")
    }

    /**
     * La proposition reste une proposition.
     *
     * Corriger d'office « 4768 » en « 47,68 » ferait décider le moteur sur une
     * hypothèse — exactement le reproche qu'on lui fait ici. Le prix de la
     * course reste celui qui a été lu ; c'est le verdict qui se retire.
     */
    @Test
    fun `le prix lu n'est pas corrige en douce`() {
        assertEquals(4768.0, Arbitre.arbitrer(carte).course.prix)
    }

    @Test
    fun `la confiance tombe avec le prix`() {
        val v = Arbitre.arbitrer(carte)
        assertTrue(!v.confiance.fiable, "confiance restée à ${v.confiance.pourcent} %")
        assertTrue(
            v.confiance.manquants.any { it.champ == "prix" },
            "le prix doit apparaître comme non lu",
        )
    }

    // --- Les bornes : ce qui doit continuer de passer ------------------------

    @Test
    fun `une course ordinaire n'est pas inquietee`() {
        assertNull(Arbitre.prixDouteux(46.70, 30.4))
        assertNull(Arbitre.prixDouteux(8.50, 2.1))
    }

    /**
     * Une longue distance chère existe, et doit passer.
     *
     * Un Paris–Deauville à 250 € est une vraie course. C'est pourquoi le
     * plafond absolu ne suffit pas et qu'un second plafond, au kilomètre,
     * prend le relais dès que la course s'allonge.
     */
    @Test
    fun `une longue distance chere reste plausible`() {
        assertNull(Arbitre.prixDouteux(250.0, 200.0))
    }

    /**
     * Le plafond au kilomètre ne doit pas condamner les courses courtes, où
     * l'euro par kilomètre est mécaniquement très élevé : 12 € pour 1,5 km,
     * c'est 8 €/km, et c'est une excellente course.
     */
    @Test
    fun `une course tres courte et bien payee reste plausible`() {
        assertNull(Arbitre.prixDouteux(12.0, 1.5))
    }

    @Test
    fun `un facteur dix est attrape aussi`() {
        val douteux = assertNotNull(Arbitre.prixDouteux(476.80, 47.7))
        assertTrue(douteux.contains("47,68 €"), douteux)
    }

    @Test
    fun `un montant sans lecture probable est refuse sans proposition`() {
        val douteux = assertNotNull(Arbitre.prixDouteux(9000.0, 3.0))
        assertTrue(douteux.contains("9000,00 €"), douteux)
    }
}

/**
 * Le retour à vide complet : la course telle qu'elle se paie vraiment quand la
 * dépose se fait dans une zone qui ne redemande rien.
 *
 * Le chauffeur a refusé la course du 25/09 pour deux raisons, et la seconde
 * n'était pas une erreur de lecture : « dans une zone où j'aurais pas de
 * retour ». Le barème suppose un repositionnement de 35 % du trajet — juste
 * sur une journée, faux sur cette course-là.
 */
class RetourPleinTest {

    /** La même carte, avec le prix que la lecture a très probablement mangé. */
    private val carte = Course(
        plateforme = "Uber",
        prix = 47.68,
        kmApproche = 5.0,
        minutesApproche = 12.0,
        kmTrajet = 47.7,
        minutesTrajet = 45.0,
    )

    @Test
    fun `le retour complet coute la moitie de l'heure`() {
        val v = Arbitre.arbitrer(carte)
        val normal = assertNotNull(v.euroHeure)
        val plein = assertNotNull(v.euroHeureRetourPlein)

        // 26 €/h au barème moyen, 15 €/h s'il faut rentrer à vide sur 47,7 km.
        assertTrue(normal > 25.0, "attendu au-dessus de l'objectif, obtenu $normal")
        assertTrue(plein < 16.0, "attendu bien en dessous, obtenu $plein")
    }

    /**
     * Le chiffre est posé à côté du verdict ; il ne le change pas.
     *
     * Le moteur ne sait pas quelles zones sont mortes — aucune donnée de
     * demande ne lui parvient. Décider à sa place reviendrait à refuser toutes
     * les longues courses de banlieue, dont certaines sont les meilleures de
     * la journée.
     */
    @Test
    fun `le verdict ne depend pas du retour complet`() {
        val v = Arbitre.arbitrer(carte)
        assertEquals(Decision.LIMITE, v.decision)
    }

    /**
     * Quand le chauffeur a déjà réglé son barème sur un retour complet, les
     * deux chiffres se rejoignent : il n'y a plus rien à signaler.
     */
    @Test
    fun `retour deja compte en entier, plus d'ecart`() {
        val v = Arbitre.arbitrer(carte, Bareme(partRetour = 1.0))
        val normal = assertNotNull(v.euroHeure)
        val plein = assertNotNull(v.euroHeureRetourPlein)
        assertTrue(kotlin.math.abs(normal - plein) < 0.01, "$normal contre $plein")
    }
}
