package de.kalorienkompass.ai

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import de.kalorienkompass.data.repo.FoodRepository
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A database food that may correspond to an [AiFoodItem]; [score] is in 0..1. */
data class FoodCandidate(val food: FoodEntity, val score: Double)

/** Ranked candidates (best first, at most [AiFoodMatcher.MAX_CANDIDATES]). */
data class FoodMatchResult(val candidates: List<FoodCandidate>) {

    /** Default selection: the best candidate if it is good enough, otherwise null (= use the AI estimate). */
    val best: FoodEntity?
        get() = candidates.firstOrNull()?.takeIf { it.score >= AiFoodMatcher.MATCH_THRESHOLD }?.food

    companion object {
        val EMPTY = FoodMatchResult(emptyList())
    }
}

/**
 * Finds database foods (built-in, own, cached Open Food Facts) for the foods the AI recognized.
 * Searches by the AI's search term, its name and single words of both; ranks with [FoodMatchScoring].
 * Earlier AI estimates (source AI) are never offered as matches.
 */
class AiFoodMatcher internal constructor(
    private val search: suspend (query: String, limit: Int) -> List<FoodEntity>,
) {
    constructor(foodRepository: FoodRepository) : this({ query, limit -> foodRepository.searchLocal(query, limit) })

    suspend fun match(item: AiFoodItem): FoodMatchResult {
        val found = LinkedHashMap<Long, FoodEntity>()
        for (query in FoodMatchScoring.searchQueries(item)) {
            for (food in search(query, SEARCH_LIMIT)) {
                if (food.id != 0L) found.putIfAbsent(food.id, food)
            }
        }
        return FoodMatchResult(FoodMatchScoring.rank(item, found.values, MAX_CANDIDATES))
    }

    companion object {
        /** Minimum score of the automatic default match (spec: ≥ 0.5, else "KI-Schätzung"). */
        const val MATCH_THRESHOLD: Double = 0.5
        const val MAX_CANDIDATES: Int = 5
        private const val SEARCH_LIMIT = 30
    }
}

/**
 * Pure scoring of how well a database food name fits an AI food description.
 *
 * Text is normalized (lowercase, umlauts folded: ä → ae, ö → oe, ü → ue, ß → ss, accents removed)
 * and split into tokens. Preparation/state words ("gekocht", "roh", "gebraten" …) are kept but
 * weigh half as much as the food words themselves. Words in parentheses of a database name are
 * aliases/qualifiers ("Nudeln (Spaghetti, Penne), gekocht").
 *
 * score = (0.6 · coverage of the query + 0.4 · coverage of the food name)
 *         × penalty per missing head word × calorie plausibility × source preference
 */
object FoodMatchScoring {

    const val DESCRIPTOR_WEIGHT: Double = 0.5
    private const val MIN_SCORE = 0.15
    private const val MISSING_HEAD_PENALTY = 0.6
    private const val HEAD_SIMILARITY = 0.6
    private const val OPEN_FOOD_FACTS_FACTOR = 0.95

    private val STOPWORDS = setOf(
        "mit", "und", "oder", "in", "im", "ohne", "vom", "von", "der", "die", "das", "den", "dem", "des",
        "ein", "eine", "einer", "auf", "aus", "nach", "art", "zum", "zur", "fuer", "wie", "ca", "etwa",
        "a", "la", "al", "au", "de", "con", "the", "and", "of",
    )

    /** Preparation/state/size words (normalized). Inflected forms are recognized via [stem]. */
    private val DESCRIPTORS = setOf(
        "gekocht", "roh", "gebraten", "gegrillt", "gebacken", "frittiert", "geduenstet", "gedaempft", "gegart",
        "getrocknet", "frisch", "zubereitet", "natur", "abgetropft", "paniert", "geraeuchert", "tk", "dose",
        "glas", "ungesuesst", "gesuesst", "gezuckert", "gesalzen", "geroestet", "fettarm", "mager", "light",
        "klein", "gross", "mittel", "mittelgross", "warm", "kalt", "hausgemacht", "selbstgemacht", "fertig",
        "fertigprodukt", "tiefgekuehlt", "gemischt", "geschnitten", "gerieben", "gehackt", "pueriert",
        "ueberbacken", "scheibe", "scheiben", "stueck", "portion", "zerlassen", "geschmolzen",
    )

    private val PLURAL_SUFFIXES = setOf("n", "en", "e", "er", "s", "es", "nen")

    /** Lowercase, umlauts folded (ä → ae, ö → oe, ü → ue, ß → ss), other accents removed. */
    fun normalize(text: String): String {
        val folded = text.lowercase(Locale.GERMAN)
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
        return Normalizer.normalize(folded, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    }

    data class Token(val text: String, val weight: Double, val inParens: Boolean)

    /**
     * Tokens of [text] (stopwords and pure numbers removed, duplicates dropped). Hyphenated
     * compounds are joined like German compounds: "Curry-Ketchup" → "curryketchup" (a ketchup).
     */
    fun tokens(text: String): List<Token> {
        val result = ArrayList<Token>()
        val seen = HashSet<String>()
        val current = StringBuilder()
        var depth = 0
        fun flush() {
            if (current.isEmpty()) return
            val word = current.toString()
            current.setLength(0)
            if (word.length < 2 || word in STOPWORDS || word.all { it.isDigit() } || !seen.add(word)) return
            result += Token(word, if (isDescriptor(word)) DESCRIPTOR_WEIGHT else 1.0, depth > 0)
        }
        val normalized = normalize(text)
        for ((index, c) in normalized.withIndex()) {
            when {
                c == '(' || c == '[' -> {
                    flush()
                    depth++
                }
                c == ')' || c == ']' -> {
                    flush()
                    depth = max(0, depth - 1)
                }
                c.isLetterOrDigit() -> current.append(c)
                c == '-' && current.isNotEmpty() && normalized.getOrNull(index + 1)?.isLetter() == true -> Unit
                else -> flush()
            }
        }
        flush()
        return result
    }

    /** True for preparation/state words, also inflected ("gebratene", "frischer"). Expects normalized text. */
    fun isDescriptor(word: String): Boolean = word in DESCRIPTORS || stem(word) in DESCRIPTORS

    private fun stem(word: String): String {
        for (ending in INFLECTION_ENDINGS) {
            if (word.length > ending.length + 2 && word.endsWith(ending)) return word.dropLast(ending.length)
        }
        return word
    }

    private val INFLECTION_ENDINGS = listOf("es", "en", "er", "em", "e")

    /** Similarity of two normalized tokens (0..1); handles plurals and German compounds. */
    fun tokenSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (long.startsWith(short)) {
            val suffix = long.substring(short.length)
            if (suffix in PLURAL_SUFFIXES && (short.length >= 3 || suffix == "er")) return 0.9
        }
        if (short.length < 4) return 0.0
        val ratio = short.length.toDouble() / long.length
        // In German compounds the last part is the head: "Vollkornbrot" is a kind of "Brot",
        // but "Käsekuchen" is no "Käse" – so a common suffix counts much more than a common prefix.
        return when {
            long.endsWith(short) -> 0.7 + 0.25 * ratio
            long.startsWith(short) -> 0.35 + 0.3 * ratio
            long.contains(short) -> 0.3
            else -> 0.0
        }
    }

    /** How well [food] fits [query] (0..1). [aiKcalPer100g] adds a calorie plausibility check. */
    fun score(query: String, food: FoodEntity, aiKcalPer100g: Double? = null): Double {
        val queryTokens = tokens(query)
        val foodTokens = tokens(food.name)
        if (queryTokens.isEmpty() || foodTokens.isEmpty()) return 0.0

        fun bestSimilarity(token: String, others: List<Token>): Double =
            others.maxOf { tokenSimilarity(token, it.text) }

        // 1) coverage of the query (parenthesized aliases count)
        var queryTotal = 0.0
        var queryMatched = 0.0
        for (t in queryTokens) {
            queryTotal += t.weight
            queryMatched += t.weight * bestSimilarity(t.text, foodTokens)
        }
        val recall = queryMatched / queryTotal

        // 2) coverage of the food name outside parentheses; a matching alias names the head word
        val aliasMatched = foodTokens.any { it.inParens && bestSimilarity(it.text, queryTokens) >= 0.9 }
        val mainTokens = foodTokens.filter { !it.inParens }.ifEmpty { foodTokens }
        val headIndex = mainTokens.indexOfFirst { it.weight >= 1.0 }.let { if (it < 0) 0 else it }
        var foodTotal = 0.0
        var foodMatched = 0.0
        mainTokens.forEachIndexed { index, t ->
            var sim = bestSimilarity(t.text, queryTokens)
            if (index == headIndex && aliasMatched) sim = max(sim, 0.8)
            foodTotal += t.weight
            foodMatched += t.weight * sim
        }
        val precision = foodMatched / foodTotal

        var result = 0.6 * recall + 0.4 * precision

        // 3) head words: the food's ("Hering" in "Hering in Tomatensauce") must appear in the query and
        //    the query's ("Gemüsecurry" in "Gemüsecurry mit Kichererbsen") in the food name
        val head = mainTokens[headIndex]
        val foodHeadMatched = aliasMatched || bestSimilarity(head.text, queryTokens) >= HEAD_SIMILARITY
        if (!foodHeadMatched && head.weight >= 1.0) result *= MISSING_HEAD_PENALTY
        val queryHead = queryTokens.firstOrNull { it.weight >= 1.0 }
        if (queryHead != null && bestSimilarity(queryHead.text, foodTokens) < HEAD_SIMILARITY) {
            result *= MISSING_HEAD_PENALTY
        }

        // 4) calorie plausibility ("Reis" ≈ 130 kcal fits "Reis, gekocht" better than "Reis, roh")
        result *= kcalFactor(aiKcalPer100g, food.kcal)

        // 5) generic database foods fit generic AI items better than branded products
        if (food.source == FoodSource.OPEN_FOOD_FACTS) result *= OPEN_FOOD_FACTS_FACTOR

        return result.coerceIn(0.0, 1.0)
    }

    /** 0.6 (very different calories) … 1.0 (same calories); 1.0 if the AI gave no value. */
    fun kcalFactor(aiKcalPer100g: Double?, foodKcalPer100g: Double): Double {
        if (aiKcalPer100g == null || aiKcalPer100g.isNaN() || foodKcalPer100g.isNaN()) return 1.0
        val reference = max(max(aiKcalPer100g, foodKcalPer100g), 20.0)
        val similarity = (1.0 - abs(aiKcalPer100g - foodKcalPer100g) / reference).coerceIn(0.0, 1.0)
        return 0.6 + 0.4 * similarity
    }

    /** Ranks [foods] for [item] (best first); AI foods and near-zero scores are dropped. */
    fun rank(item: AiFoodItem, foods: Collection<FoodEntity>, limit: Int = AiFoodMatcher.MAX_CANDIDATES): List<FoodCandidate> {
        val queries = listOf(item.searchTerm, item.name).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (queries.isEmpty() || limit <= 0) return emptyList()
        val aiKcal = item.kcalPer100g.takeIf { it > 0.0 }
        return foods.asSequence()
            .filter { it.source != FoodSource.AI }
            .map { food -> FoodCandidate(food, queries.maxOf { score(it, food, aiKcal) }) }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(
                compareByDescending<FoodCandidate> { (it.score * 1000).roundToInt() }
                    .thenBy { sourceRank(it.food.source) }
                    .thenByDescending { it.food.useCount }
                    .thenBy { it.food.name.length },
            )
            .take(limit)
            .toList()
    }

    /** Search strings for the database: search term, name, then single words (first and last food word). */
    fun searchQueries(item: AiFoodItem): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(query: String?) {
            val q = query?.trim().orEmpty()
            if (q.length >= 2 && seen.add(q.lowercase(Locale.GERMAN))) result += q
        }
        add(item.searchTerm)
        add(item.name)
        for (source in listOf(item.searchTerm, item.name)) {
            val words = source.split(WORD_SPLIT)
                .filter { it.length >= 3 && normalize(it) !in STOPWORDS && !isDescriptor(normalize(it)) }
            add(words.firstOrNull()) // "Spaghetti gekocht" → "Spaghetti"
            add(words.lastOrNull()) // "gemischter Salat" → "Salat"
        }
        return result.take(MAX_QUERIES)
    }

    private fun sourceRank(source: FoodSource): Int = when (source) {
        FoodSource.BUILTIN -> 0
        FoodSource.CUSTOM -> 1
        FoodSource.OPEN_FOOD_FACTS -> 2
        FoodSource.AI -> 3
    }

    private const val MAX_QUERIES = 6
    private val WORD_SPLIT = Regex("[\\s,;/()\\[\\]\\-–]+")
    private val COMBINING_MARKS = Regex("\\p{M}+")
}
