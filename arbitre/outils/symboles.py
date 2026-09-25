"""Repère les appels à des membres que le fichier ne définit plus.

Pas un compilateur : un filet, et il existe pour une raison précise. Le
module Android ne se configure pas sans le SDK, donc il ne se compile pas
sur toutes les machines — une modification s'y écrit parfois à l'aveugle,
et la seule vérification est la CI, sept minutes plus tard.

Il attrape exactement la faute qui l'a fait naître : un remplacement de bloc
qui emporte au passage trois fonctions encore appelées ailleurs. Le compilateur
l'a vue, mais après coup ; celui-ci la voit avant de pousser.

    python3 outils/symboles.py

Une liste de noms connus le tient silencieux sur tout ce qui vient d'Android,
de Kotlin ou des bibliothèques. Elle s'allonge quand un faux positif apparaît,
ce qui est le prix d'un outil de trente lignes plutôt que d'un analyseur.
"""
import re, glob, sys

BUILTINS = set("""
listOf listOfNotNull mutableListOf setOf mutableSetOf mapOf mutableMapOf arrayOf intArrayOf
emptyList emptySet emptyMap buildString require check error println let apply also run with
takeIf takeUnless mapNotNull firstNotNullOfOrNull joinToString split trim trimIndent lines
toString toInt toIntOrNull toDouble toDoubleOrNull toFloat toLong toTypedArray toSet toList toMap
first firstOrNull last lastOrNull single singleOrNull filter filterNot filterIndexed filterNotNull
map flatMap forEach forEachIndexed withIndex indices sortedBy sortedByDescending sortedWith sorted
count sumOf average max min maxByOrNull minByOrNull maxOrNull minOrNull zip zipWithNext distinct
distinctBy drop dropLast take takeLast reversed removeAll removeIf contains containsKey containsAll
startsWith endsWith replace replaceFirstChar substring indexOf isEmpty isNotEmpty isNullOrEmpty
ifEmpty orEmpty uppercase lowercase format coerceIn coerceAtLeast coerceAtMost roundToInt
copy compareBy compareByDescending thenByDescending getOrNull getOrDefault getValue put get remove
add addAll addView removeView removeAllViews findViewById getString getSystemService getColor
getDrawable setOnClickListener setOnCheckedChangeListener setPadding setText setTextColor
setTypeface setBackgroundResource setBackgroundColor setImageResource setStroke setColor setBounds
setTint setCompoundDrawables setLayerInset setPrimaryClip setContentView startActivity
obtainStyledAttributes recycle animate alpha translationY scaleX scaleY setDuration
setInterpolator start cancel withEndAction post postDelayed removeCallbacks
valueOf setAlphaComponent blendARGB checkSelfPermission requestPermissions
super onCreate onResume onPause onDraw invalidate drawArc set
Triple Pair Runnable Intent Uri LinearLayout TextView ImageView View Button CheckBox EditText
SeekBar ScrollView FrameLayout Toast ColorStateList GradientDrawable LayerDrawable Paint RectF
Canvas Color SimpleDateFormat Date Locale LinkedHashMap ArrayList JSONArray JSONObject
DecelerateInterpolator OvershootInterpolator ContextThemeWrapper LayoutInflater ComponentName
Anneau Tuile Trio Organe Champ Curseur Simulation Ligne Course Bareme Verdict Confiance Planifiee
mutate unflattenFromString getEnabledListenerPackages isIgnoringBatteryOptimizations
getPackageInfo canDrawOverlays getStringSet edit putString putBoolean putInt putFloat putLong
getBoolean getInt getFloat putStringSet apply commit getSharedPreferences applicationContext
arbitrer analyser juger comparer decouper lire dp fmt0 fmt1 fmt2 versCourse
append longArrayOf minOf has isNull optDouble optInt optLong optString optJSONObject length
startForeground stopSelf setShowBadge createNotificationChannel notify cancelAll
setOnSeekBarChangeListener application constructor it tenable getOrElse

# Membres hérités de View et consorts : ils sont définis par le cadre Android,
# jamais dans le fichier qui les appelle. L'outil ne lit qu'un fichier à la
# fois et ne peut pas les voir — les signaler noierait les vrais oublis, qui
# sont la seule chose qu'il existe pour attraper.
performHapticFeedback postInvalidateOnAnimation setWillNotDraw invalidate
performClick postOnAnimation onSizeChanged onDraw onTouchEvent onMeasure
setMeasuredDimension drawRoundRect drawCircle drawText clipRect save restore
inset setBounds setTint setAlphaComponent blendARGB coerceIn coerceAtMost
coerceAtLeast hypot abs min max obtainStyledAttributes recycle getDrawable
mutate
""".split())

problemes = []
for chemin in sorted(glob.glob('app/src/main/kotlin/fr/ningbus/arbitre/*.kt')):
    src = open(chemin).read()
    definis = set(re.findall(r'\b(?:fun|val|var|class|object|enum class)\s+([A-Za-z_][\w]*)', src))
    definis |= set(re.findall(r'^import .*\.([A-Za-z_][\w]*)$', src, re.M))
    definis |= set(re.findall(r'\bval\s+\(([^)]*)\)', src))  # déstructurations
    for groupe in re.findall(r'\bval\s+\(([^)]*)\)', src):
        definis |= {n.strip() for n in groupe.split(',')}
    definis |= set(re.findall(r'\b(?:val|var)\s+([A-Za-z_][\w]*)', src))
    definis |= set(re.findall(r'\(\s*([a-zA-Z_][\w]*)\s*:', src))          # paramètres
    definis |= set(re.findall(r',\s*([a-zA-Z_][\w]*)\s*:', src))

    for appel in set(re.findall(r'(?<![\w.$"])([a-z][A-Za-z0-9_]*)\(', src)):
        if appel in definis or appel in BUILTINS:
            continue
        problemes.append((chemin.split('/')[-1], appel))

if problemes:
    print("APPELS SANS DÉFINITION VISIBLE :")
    for f, n in sorted(set(problemes)):
        print(f"  {f}: {n}()")
    sys.exit(1)
print("aucun appel orphelin")
