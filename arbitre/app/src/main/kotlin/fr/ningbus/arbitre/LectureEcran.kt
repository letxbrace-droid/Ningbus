package fr.ningbus.arbitre

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import fr.ningbus.arbitre.moteur.Course
import fr.ningbus.arbitre.moteur.Detecteur
import fr.ningbus.arbitre.moteur.completer

/**
 * Lecture de la carte d'offre affichée par l'application chauffeur.
 *
 * C'est le chemin principal, et non un complément : quand l'application est
 * au premier plan — c'est-à-dire tout le temps où l'on travaille — Uber
 * dessine l'offre directement à l'écran **sans poster aucune notification**.
 *
 * Trois façons de déclencher la lecture :
 *
 *  - **automatiquement**, sur les changements d'écran ;
 *  - **à la demande**, par un appui sur la pastille flottante ;
 *  - **par capture**, par un appui long sur la pastille : le texte brut de
 *    l'écran part dans le journal, sans interprétation. C'est le seul moyen
 *    de savoir ce que le service a réellement vu quand une offre échappe à
 *    l'analyse — deviner à sa place coûte un aller-retour à chaque fois.
 */
class LectureEcran : AccessibilityService() {

    private val principal = Handler(Looper.getMainLooper())
    private val devoiler = Runnable { devoilerIncomplet() }

    private var derniereLecture = 0L

    /** Lecture partielle en cours de complétion. */
    private var attente: Attente? = null

    /**
     * Empreinte de l'écran ayant motivé la dernière capture.
     *
     * Un écran figé ne doit coûter qu'une capture, quel que soit le nombre
     * d'événements qu'il émet. Un délai minimal n'y suffirait pas : une
     * application chauffeur laissée en place des minutes entières
     * déclencherait une capture à chaque créneau.
     */
    private var derniereEmpreinte = 0

    /** Captures déjà dépensées sur cet écran-là. */
    private var capturesSurEcran = 0

    /** Paquets effectivement lus au dernier parcours, pour le diagnostic. */
    private var paquetLu: String? = null
    private var paquetsLus: List<String> = emptyList()

    private class Attente(
        val paquet: String,
        val course: Course,
        val instant: Long,
        /** Un bouton d'acceptation était visible : c'était bien une offre. */
        val marquee: Boolean,
    )

    override fun onServiceConnected() {
        instance = this
        appliquerFiltre()
        BoutonFlottant.synchroniser(this)
        // La liaison du service est l'un des moments où l'application a le
        // droit de démarrer un service au premier plan : c'est donc ici que
        // la veille se rattrape si le téléphone l'a arrêtée.
        ServiceVeille.synchroniser(this)
        Log.i(TAG, "lecture d'écran active")
    }

    override fun onDestroy() {
        instance = null
        BoutonFlottant.cacher()
        super.onDestroy()
    }

    /**
     * Restreint l'écoute aux applications cochées, ou l'ouvre à toutes.
     *
     * À rappeler quand l'utilisateur modifie la liste : le filtre étant
     * appliqué par le système, une application qui vient d'être cochée
     * n'enverrait sinon jamais le premier événement qui permettrait de s'en
     * apercevoir.
     */
    fun appliquerFiltre() {
        val info = serviceInfo ?: return
        val reglages = Reglages(this)
        info.packageNames = when {
            // null veut dire « toutes les applications ».
            reglages.ecouteToutesApps -> null
            // Un tableau vide voudrait dire « toutes » aussi : on préfère
            // n'écouter rien plutôt que tout par accident.
            reglages.paquets.isEmpty() -> arrayOf("")
            else -> reglages.paquets.toTypedArray()
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val paquet = e.packageName?.toString()
        if (paquet == packageName) return // nos propres fenêtres

        // Le filtre passe AVANT l'étranglement, et l'ordre inverse était un
        // vrai défaut : une offre arrive par-dessus l'application que le
        // chauffeur regarde — TikTok, un GPS — et celle-ci émet des dizaines
        // d'événements par seconde. Étrangler d'abord revenait à dépenser le
        // budget sur l'application du dessous, puis à jeter l'événement
        // d'Uber arrivé cent millisecondes plus tard.
        val reglages = Reglages(this)
        if (paquet != null && !reglages.ecoute(paquet)) return

        // Le contenu d'une carte d'offre change à chaque seconde du compte à
        // rebours. Sans ce pas minimal, on relirait tout l'arbre des vues
        // dizaines de fois par seconde pour un résultat identique.
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - derniereLecture < PAS_MINIMAL_MS) return
        derniereLecture = maintenant

        // Une fenêtre qui s'ouvre, par opposition à un contenu qui se
        // rafraîchit : c'est le seul instant où une capture d'écran vaut son
        // coût, et c'est précisément celui où une carte d'offre apparaît.
        val fenetreNouvelle = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        try {
            // L'apparition d'une fenêtre flottante est annoncée sans nom de
            // paquet : on regarde alors toutes les applications écoutées.
            lire(paquet, e.eventTime, force = false, fenetreNouvelle = fenetreNouvelle)
        } catch (ex: Exception) {
            // Un arbre de vues qui disparaît en cours de parcours ne doit pas
            // faire tomber le service : le système ne le relierait qu'au
            // prochain passage par les réglages d'accessibilité.
            Log.w(TAG, "écran illisible : ${ex.message}")
        }
    }

    override fun onInterrupt() = Unit

    // --- Lecture ------------------------------------------------------------

    /** Pourquoi une lecture n'a pas abouti — ce qui décide de la suite. */
    private enum class Issue {
        /** Un verdict a été rendu. */
        RENDU,

        /** L'écran n'a livré aucun texte. */
        RIEN_A_LIRE,

        /** Du texte, mais rien qui ressemble à une offre. */
        PAS_UNE_OFFRE,

        /** Une offre possible, écartée par le filtre d'écrans. */
        ECARTE,

        /** Une offre reconnue, mais dont il manque de quoi conclure. */
        INCOMPLET,

        /** Tout était là sauf le montant, ou c'était un doublon. */
        SANS_SUITE,
    }

    /**
     * @param force analyse demandée par l'utilisateur : ni filtre d'écran, ni
     *   dédoublonnage — s'il appuie deux fois, il veut deux réponses.
     * @param fenetreNouvelle une fenêtre vient d'apparaître, par opposition à
     *   un simple rafraîchissement de contenu. C'est le seul moment où la
     *   reconnaissance de texte vaut son coût.
     */
    private fun lire(
        paquet: String?,
        instantEvenement: Long,
        force: Boolean,
        fenetreNouvelle: Boolean = false,
    ): Boolean {
        val reglages = Reglages(this)
        if (!reglages.actif) return false
        if (!force && paquet != null && !reglages.ecoute(paquet)) return false

        // Quelles fenêtres lire :
        //  - analyse à la main : toutes, puisqu'on ne sait pas d'où vient
        //    l'écran et que l'utilisateur, lui, le voit ;
        //  - événement d'une application nommée : les siennes, sans quoi on
        //    arbitrerait le texte d'une autre sous son nom ;
        //  - événement sans nom de paquet : toutes les applications écoutées.
        val critere: (String) -> Boolean = when {
            force -> { _ -> true }
            paquet != null -> { p -> p == paquet }
            else -> { p -> reglages.ecoute(p) }
        }
        val texte = texteEcran(critere)
        val nom = paquet ?: paquetLu ?: "écran"

        val issue = conclure(reglages, nom, texte, instantEvenement, force)
        if (issue == Issue.RENDU) return true

        // L'arbre n'a rien donné d'exploitable. Avant de conclure au silence,
        // on regarde l'écran tel qu'il est dessiné.
        if (ocrUtile(reglages, nom, texte, issue, force, fenetreNouvelle)) {
            tenterOcr(nom, texte, instantEvenement, force)
            return false
        }

        rapporter(issue, nom, texte, force)
        return false
    }

    /**
     * Analyse un texte d'écran et rend un verdict s'il y a lieu.
     *
     * Séparée de [lire] parce qu'elle sert deux fois : sur le texte de
     * l'arbre d'accessibilité, puis, le cas échéant, sur celui qu'a reconnu
     * l'OCR. Elle ne parle pas à l'utilisateur — c'est [rapporter] qui le
     * fait, une fois seulement, quand tous les chemins ont été essayés.
     */
    private fun conclure(
        reglages: Reglages,
        nom: String,
        texte: String,
        instantEvenement: Long,
        force: Boolean,
    ): Issue {
        if (texte.isEmpty()) return Issue.RIEN_A_LIRE

        // Une lecture qui en complète une autre n'a pas à reporter de montant :
        // c'est précisément la partie qui manquait la fois d'avant.
        val enCours = attente?.takeIf { it.paquet == nom }
        if (!Arbitrage.ressembleAUneCourse(texte) && enCours == null) return Issue.PAS_UNE_OFFRE

        val course = Arbitrage.lire(nom, texte).completer(enCours?.course)

        // Le jugement remplace l'ancienne règle binaire — un bouton
        // d'acceptation *ou* deux distances. Elle tenait, mais ne savait rien
        // dire : un écran écarté l'était sans motif, et un écran accepté à
        // tort sans qu'on puisse voir ce qui avait emporté la décision.
        val jugement = Detecteur.juger(texte, course)

        if (!force && enCours == null && reglages.filtrerEcrans && !jugement.arbitrable) {
            // Écran portant un montant mais écarté : on note le calcul qui l'a
            // écarté, pas seulement son texte. C'est la différence entre
            // « pourquoi cette course est-elle passée ? » et « je vois ».
            if (course.prix != null) {
                Journal.signalerEcranIgnore(
                    this,
                    nom,
                    "${jugement.resume} · ${jugement.indices.joinToString(" ")}\n\n$texte",
                )
            }
            return Issue.ECARTE
        }

        // L'instant de référence est celui de la première lecture partielle :
        // la latence affichée reste le délai vécu depuis l'apparition de
        // l'offre, pas depuis la lecture qui a fini de la compléter.
        val debut = enCours?.instant ?: instantEvenement

        // Un écran ne se dessine pas d'un bloc. Une lecture incomplète est
        // bien plus souvent une carte à moitié construite qu'une carte
        // illisible : on laisse sa chance à la suivante plutôt que de rendre
        // un verdict creux sur ce qu'on a vu au millième de seconde près.
        if (!force && !course.exploitable) {
            if (course.prix != null || course.kmTrajet != null || course.minutesTrajet != null) {
                attente = Attente(nom, course, debut, marqueur(texte))
                principal.removeCallbacks(devoiler)
                principal.postDelayed(devoiler, DELAI_INCOMPLET_MS)
            }
            return Issue.INCOMPLET
        }

        principal.removeCallbacks(devoiler)
        attente = null

        val latence = (SystemClock.uptimeMillis() - debut).coerceAtLeast(0L)
        val rendu = Arbitrage.rendre(this, nom, course, Source.ECRAN, latence, force)
        return if (rendu) Issue.RENDU else Issue.SANS_SUITE
    }

    /**
     * Ce que l'utilisateur apprend d'une lecture qui n'a rien donné.
     *
     * Uniquement quand il l'a demandée : une analyse automatique qui ne
     * trouve rien doit se taire, sans quoi l'application passerait sa journée
     * à signaler des écrans qui ne sont pas des offres.
     */
    private fun rapporter(issue: Issue, nom: String, texte: String, force: Boolean) {
        if (!force || issue == Issue.RENDU) return
        if (texte.isNotEmpty()) Journal.signalerCapture(this, nom, texte)
        signaler(
            if (issue == Issue.SANS_SUITE) R.string.montant_introuvable else R.string.rien_a_lire
        )
    }

    // --- Le second rideau : reconnaissance de texte --------------------------

    /**
     * L'OCR mérite-t-il son coût, ici et maintenant ?
     *
     * Une capture d'écran suivie d'une reconnaissance coûte mille fois un
     * parcours de nœuds. Le déclencher à chaque rafraîchissement d'une
     * application chauffeur — qui redessine sa carte en continu — viderait la
     * batterie pour rien. Trois portes, donc, et il faut les passer toutes.
     */
    private fun ocrUtile(
        reglages: Reglages,
        nom: String,
        texte: String,
        issue: Issue,
        force: Boolean,
        fenetreNouvelle: Boolean,
    ): Boolean {
        if (!Ocr.disponible || !reglages.ocrSecours) return refuser("désactivé ou indisponible")
        if (issue == Issue.RENDU) return false

        // Demandée à la main, l'analyse doit tout essayer : c'est le chemin
        // de secours, il n'a pas à être économe.
        if (force) return true

        // Une fenêtre qui n'expose aucun nœud n'expose pas non plus son nom
        // de paquet. S'en tenir au seul nom déduit du dernier parcours
        // reviendrait à refuser la reconnaissance de texte précisément dans
        // le cas qu'elle existe pour traiter : on accepte donc aussi les
        // paquets relevés au passage précédent.
        val connu = reglages.ecoute(nom) || paquetsLus.any { reglages.ecoute(it) }
        if (!connu) return refuser("paquet inconnu ($nom, vus : $paquetsLus)")

        // Une empreinte par écran : un écran figé ne coûte qu'une capture.
        // C'est ce qui remplace la longueur du texte comme garde-fou — voir
        // plus bas pourquoi celle-ci était une mauvaise idée.
        val empreinte = (nom + texte).hashCode()
        if (empreinte == derniereEmpreinte && capturesSurEcran >= CAPTURES_PAR_ECRAN) {
            return refuser("écran déjà capturé $capturesSurEcran fois")
        }

        return when (issue) {
            // La carte était reconnue mais illisible : la moitié manquante
            // est peut-être dessinée plutôt qu'écrite.
            Issue.INCOMPLET -> true

            // Une fenêtre vient de s'ouvrir dans une application écoutée et
            // l'arbre n'en a rien tiré d'arbitrable. C'est la signature d'une
            // carte dessinée sur Canvas — et **on ne peut pas faire mieux que
            // ce soupçon**, puisqu'une carte peinte ne laisse par définition
            // aucune trace dans l'arbre.
            //
            // Le garde-fou précédent exigeait en plus que le texte lu soit
            // court, au motif qu'un écran muet devait l'être vraiment. Il
            // était faux, et le banc l'a montré chiffre en main : la carte
            // peinte cohabitait avec une autre fenêtre bavarde, l'arbre
            // rendait 324 caractères, et la capture était refusée à cause de
            // ce que disait une fenêtre voisine. Décider d'une carte
            // invisible d'après le bavardage de sa voisine n'a aucun sens.
            //
            // Le coût est tenu autrement : une seule capture par écran
            // distinct, un créneau minimal entre deux, et rien tant qu'une
            // fenêtre ne s'ouvre pas.
            Issue.RIEN_A_LIRE, Issue.PAS_UNE_OFFRE ->
                fenetreNouvelle || refuser("pas de fenêtre nouvelle")

            // Écarté par le détecteur : l'écran a bien été lu, et jugé. Le
            // relire en pixels ne dirait rien de plus.
            else -> refuser("issue $issue")
        }
    }

    /**
     * Note pourquoi la reconnaissance de texte n'a pas été tentée, et rend
     * `false`.
     *
     * Ces lignes sont le seul moyen de comprendre un silence après coup : un
     * chemin de secours qui ne part pas et n'en dit rien est indiscernable
     * d'un chemin de secours en panne. Le banc d'essai a déjà coûté un tour
     * entier faute de cette trace.
     */
    private fun refuser(motif: String): Boolean {
        Log.i(TAG, "pas de reconnaissance de texte : $motif")
        return false
    }

    private fun tenterOcr(nom: String, texteArbre: String, instantEvenement: Long, force: Boolean) {
        if (!Ocr.creneauLibre()) {
            refuser("créneau de capture déjà pris")
            rapporter(Issue.RIEN_A_LIRE, nom, texteArbre, force)
            return
        }
        Log.i(TAG, "capture d'écran pour $nom (arbre : ${texteArbre.length} car.)")
        val empreinte = (nom + texteArbre).hashCode()
        if (empreinte == derniereEmpreinte) capturesSurEcran++ else capturesSurEcran = 1
        derniereEmpreinte = empreinte

        // Nos deux fenêtres s'effacent le temps de la capture.
        //
        // Une image ne connaît pas les paquets : elle prend tout ce qui est
        // dessiné. La bulle y exposerait le verdict précédent — montant,
        // approche, distance — que l'analyseur relirait comme une offre, et
        // la pastille son « 31 €/h », qui deviendrait le prix de la course
        // puisque le montant le plus élevé l'emporte. Le banc a pris les deux
        // sur le fait.
        //
        // Absenter nos fenêtres de l'image vaut mieux qu'interdire la
        // capture : une offre qui arrive pendant qu'un verdict traîne encore
        // à l'écran est précisément une offre qu'il ne faut pas manquer.
        //
        // Le délai laisse au système le temps de dessiner une trame sans
        // elles — une capture demandée dans la foulée montrerait encore la
        // précédente.
        Bulle.eclipser()
        BoutonFlottant.eclipser()
        principal.postDelayed({ capturer(nom, texteArbre, instantEvenement, force) }, DELAI_ECLIPSE_MS)
    }

    private fun capturer(nom: String, texteArbre: String, instantEvenement: Long, force: Boolean) {
        Ocr.lire(this) { reconnu ->
            Bulle.reparaitre()
            BoutonFlottant.reparaitre()
            try {
                if (reconnu.isEmpty()) {
                    rapporter(Issue.RIEN_A_LIRE, nom, texteArbre, force)
                    return@lire
                }
                val fusion = fusionner(reconnu, texteArbre)
                val issue = conclure(Reglages(this), nom, fusion, instantEvenement, force)
                if (issue == Issue.RENDU) {
                    Log.i(TAG, "offre lue par reconnaissance de texte")
                } else {
                    // L'extrait est décisif quand rien ne sort d'une
                    // capture pourtant réussie : il dit si l'OCR a vu la
                    // carte, ou seulement ce qu'il y avait autour.
                    Log.i(
                        TAG,
                        "texte reconnu (${reconnu.length} car.) mais sans suite : $issue\n" +
                            reconnu.take(400).replace('\n', '|'),
                    )
                    rapporter(issue, nom, fusion, force)
                    reprendre(nom, texteArbre, instantEvenement, force)
                }
            } catch (e: Exception) {
                Log.w(TAG, "analyse du texte reconnu impossible : ${e.message}")
            }
        }
    }

    /**
     * Redemande une capture peu après une première restée stérile.
     *
     * C'est la correction du quota de captures, qui ne servait à rien tel
     * qu'il était posé : les tentatives suivantes attendaient un **nouvel
     * événement de fenêtre**, alors qu'une carte en train de se dessiner n'en
     * produit aucun. Le banc l'a montré sans appel — la reconnaissance avait
     * lu l'écran de pilotage jusqu'à son dernier bouton, preuve que la carte
     * n'était pas encore là.
     *
     * La reprise ne repasse donc pas par le créneau global : elle est bornée
     * par le quota de l'écran, qui est la vraie limite de dépense.
     */
    private fun reprendre(
        nom: String,
        texteArbre: String,
        instantEvenement: Long,
        force: Boolean,
    ) {
        if (force || capturesSurEcran >= CAPTURES_PAR_ECRAN) return
        capturesSurEcran++
        Log.i(TAG, "reprise de capture ($capturesSurEcran/$CAPTURES_PAR_ECRAN)")
        principal.postDelayed(
            {
                Bulle.eclipser()
                BoutonFlottant.eclipser()
                principal.postDelayed(
                    { capturer(nom, texteArbre, instantEvenement, force) },
                    DELAI_ECLIPSE_MS,
                )
            },
            DELAI_REPRISE_MS,
        )
    }

    /**
     * Le texte reconnu d'abord, celui de l'arbre ensuite, sans doublon.
     *
     * L'ordre est décisif : l'analyseur se sert de la position des nombres
     * quand aucun mot ne désigne l'approche, et seul l'OCR rend l'ordre
     * *visuel* — celui que le chauffeur voit. L'arbre n'ajoute ensuite que ce
     * que lui seul savait, une description de contenu par exemple.
     */
    private fun fusionner(reconnu: String, arbre: String): String {
        val lignes = LinkedHashSet<String>(64)
        for (source in listOf(reconnu, arbre)) {
            source.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { lignes += it }
        }
        return lignes.joinToString("\n")
    }

    /**
     * Le délai est écoulé et la lecture n'a jamais été complétée : la carte a
     * fini de se dessiner, ce qui manque manque vraiment. Mieux vaut le dire
     * que se taire — un « INCOMPLET » invite à décider à la main, un silence
     * laisse croire qu'il n'y avait rien à arbitrer. Le texte part au journal
     * en même temps, puisque c'est un cas où l'analyse a échoué.
     */
    private fun devoilerIncomplet() {
        val a = attente ?: return
        attente = null

        // Le texte est gardé dans tous les cas : c'est la matière du
        // diagnostic.
        Journal.signalerCapture(this, a.paquet, a.course.texteBrut)

        // Mais on ne dérange le chauffeur que si l'écran portait un bouton
        // d'acceptation. Un écran de réglages qui mentionne « €1.00/km » n'est
        // pas une offre illisible : ce n'est pas une offre. Faire surgir un
        // « INCOMPLET » dessus revient à crier au loup.
        if (!a.marquee) return

        val latence = (SystemClock.uptimeMillis() - a.instant).coerceAtLeast(0L)
        Arbitrage.rendre(this, a.paquet, a.course, Source.ECRAN, latence)
    }

    // --- Ce qui est réellement à l'écran ------------------------------------

    /**
     * Le texte de **toutes** les fenêtres affichées, pas seulement de la
     * fenêtre active.
     *
     * C'est la correction décisive. `rootInActiveWindow` ne rend que la
     * fenêtre qui a le focus, et une offre de course n'y est presque jamais :
     * les applications chauffeur l'affichent dans une fenêtre flottante
     * par-dessus l'accueil ou par-dessus une autre application, et le focus
     * reste à celle du dessous. On lisait donc consciencieusement le mauvais
     * écran — d'où un prix attrapé au hasard et aucune distance.
     *
     * Les fenêtres de l'application qui a émis l'événement sont préférées
     * quand il y en a ; sinon on prend tout ce qui n'est pas à nous, car une
     * offre vaut mieux lue avec du bruit autour que pas lue du tout.
     */
    private fun texteEcran(critere: (String) -> Boolean): String {
        val racines = racines(critere)
        if (racines.isEmpty()) {
            paquetLu = null
            return ""
        }
        paquetLu = racines.first().packageName?.toString()
        paquetsLus = racines.mapNotNull { it.packageName?.toString() }.distinct()

        val morceaux = LinkedHashSet<String>(64)
        var budget = NOEUDS_MAX
        for (racine in racines) {
            budget = ramasser(racine, morceaux, budget)
            if (budget <= 0) break
        }
        return morceaux.joinToString("\n")
    }

    private fun racines(critere: (String) -> Boolean): List<AccessibilityNodeInfo> {
        val toutes = ArrayList<AccessibilityNodeInfo>(4)

        // De la fenêtre la plus en avant vers la plus en arrière : une offre
        // posée par-dessus le reste se lit d'abord.
        try {
            for (fenetre in windows.sortedByDescending { it.layer }) {
                val racine = fenetre.root ?: continue
                if (racine.packageName == packageName) continue
                toutes += racine
            }
        } catch (e: Exception) {
            // Certaines surcouches refusent la liste des fenêtres.
        }

        rootInActiveWindow?.let { active ->
            if (active.packageName != packageName && toutes.none { it == active }) {
                toutes += active
            }
        }

        // Pas de repli sur « toutes les fenêtres » quand une application est
        // nommée : si la sienne n'est pas lisible, il n'y a rien à lire. Lire
        // celle d'à côté produirait un verdict sur le texte d'une autre
        // application, attribué à celle-ci.
        return toutes.filter { critere(it.packageName?.toString() ?: "") }
    }

    /**
     * Ramasse les textes d'un arbre de vues, en ordre de lecture.
     *
     * L'ordre compte : quand aucun mot ne désigne l'approche, c'est la
     * position qui tranche, une offre annonçant toujours l'approche avant la
     * course. Le parcours est donc en profondeur d'abord, et borné pour ne
     * jamais peser sur l'application du dessous.
     *
     * @return le budget de nœuds restant
     */
    private fun ramasser(
        racine: AccessibilityNodeInfo,
        morceaux: MutableSet<String>,
        budgetInitial: Int,
    ): Int {
        var budget = budgetInitial
        val pile = ArrayDeque<AccessibilityNodeInfo>()
        pile.addLast(racine)

        while (pile.isNotEmpty() && budget > 0) {
            val noeud = pile.removeLast()
            budget--
            noeud.text?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            noeud.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) morceaux += it }
            for (i in noeud.childCount - 1 downTo 0) {
                noeud.getChild(i)?.let { pile.addLast(it) }
            }
        }
        return budget
    }

    /** Le bouton qui accepte la course est visible à l'écran. */
    private fun marqueur(texte: String): Boolean {
        val minuscules = texte.lowercase()
        return MARQUEURS.any { minuscules.contains(it) }
    }

    private fun signaler(message: Int) {
        principal.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun signaler(message: String) {
        principal.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "Arbitre"

        /** Pas minimal entre deux lectures de l'arbre des vues. */
        private const val PAS_MINIMAL_MS = 400L

        /**
         * Borne de parcours, tous écrans confondus. Généreuse à dessein :
         * l'écran d'une application chauffeur porte une carte entière, dont
         * les milliers de nœuds précèdent la carte d'offre dans l'arbre des
         * vues. Une borne trop basse épuise le budget avant d'atteindre les
         * lignes du trajet, et l'offre paraît illisible alors qu'elle est
         * simplement plus loin.
         */
        private const val NOEUDS_MAX = 3000

        /** Attente maximale avant de rendre un verdict sur données partielles. */
        private const val DELAI_INCOMPLET_MS = 2500L

        /**
         * Temps laissé au système pour dessiner une image sans notre pastille.
         *
         * Deux trames à soixante hertz, arrondies vers le haut : une capture
         * demandée dans la foulée d'un changement de transparence montrerait
         * encore la trame précédente, pastille comprise.
         */
        private const val DELAI_ECLIPSE_MS = 120L

        /**
         * Captures autorisées sur un même écran.
         *
         * Une seule ne suffit pas, et c'est le banc qui l'a montré : une
         * carte peinte apparaît une trame ou deux après l'événement qui
         * l'annonce, si bien que la première capture peut la manquer. Or
         * l'empreinte porte sur le texte de l'arbre — lequel, par définition,
         * ne change pas quand une carte peinte s'affiche. Une seule tentative
         * fermait donc définitivement la porte sur un simple décalage de
         * quelques millisecondes.
         */
        private const val CAPTURES_PAR_ECRAN = 3

        /**
         * Délai avant de redemander une capture restée stérile.
         *
         * Assez long pour qu'une carte ait fini de se dessiner, assez court
         * pour qu'une offre de quinze secondes soit encore à l'écran.
         */
        private const val DELAI_REPRISE_MS = 800L

        /** Textes du bouton qui accepte la course, selon les plateformes. */
        private val MARQUEURS = listOf(
            "mise en relation", "accepter", "accept", "j'accepte",
            "correspondre", "prendre la course",
        )

        @Volatile
        private var instance: LectureEcran? = null

        /** Vrai quand le système a réellement lié le service, pas seulement autorisé. */
        val lie: Boolean
            get() = instance != null

        /** À appeler après une modification de la liste des applications. */
        fun rafraichirFiltre() {
            instance?.appliquerFiltre()
        }

        /**
         * Analyse l'écran courant à la demande. Le chemin de secours : il ne
         * dépend ni du nom du paquet, ni de la forme de l'écran.
         */
        fun analyserMaintenant(contexte: Context) {
            val service = instance ?: return refuser(contexte)
            try {
                service.lire(null, SystemClock.uptimeMillis(), force = true)
            } catch (e: Exception) {
                Log.w(TAG, "analyse manuelle impossible : ${e.message}")
            }
        }

        /**
         * Relit l'écran après un délai.
         *
         * Sert quand une notification annonce une course : la carte
         * correspondante est en train d'apparaître, et le texte de la
         * notification est souvent plus pauvre que celui de l'écran.
         */
        fun analyserApres(delaiMs: Long) {
            val service = instance ?: return
            service.principal.postDelayed({
                try {
                    // Une carte est en train d'apparaître : c'est exactement
                    // le cas où la reconnaissance de texte doit être permise.
                    service.lire(
                        null,
                        SystemClock.uptimeMillis(),
                        force = false,
                        fenetreNouvelle = true,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "relecture impossible : ${e.message}")
                }
            }, delaiMs)
        }

        /**
         * Envoie le texte brut de l'écran au journal, sans l'interpréter.
         *
         * Quand une offre échappe à l'analyse, c'est la seule donnée qui
         * permette de comprendre pourquoi plutôt que de supposer.
         */
        fun capturer(contexte: Context) {
            val service = instance ?: return refuser(contexte)
            try {
                // Toutes les fenêtres, sans exception. Filtrer ici sur
                // l'application de devant faisait relever TikTok pendant
                // qu'une offre Uber flottait par-dessus : exactement ce que la
                // capture est censée révéler.
                val texte = service.texteEcran { true }
                val paquets = service.paquetsLus.joinToString(", ").ifEmpty { "aucune fenêtre" }
                Journal.signalerCapture(contexte, paquets, texte, force = true)
                service.signaler(
                    contexte.getString(R.string.ecran_capture, paquets, texte.length)
                )
            } catch (e: Exception) {
                Log.w(TAG, "capture impossible : ${e.message}")
            }
        }

        private fun refuser(contexte: Context) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    contexte.applicationContext,
                    R.string.lecture_ecran_requise,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
