package de.kalorienkompass.ai

import de.kalorienkompass.data.db.FoodEntity
import de.kalorienkompass.data.model.FoodSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFoodMatcherTest {

    private var nextId = 1L

    private fun food(
        name: String,
        kcal: Double = 100.0,
        source: FoodSource = FoodSource.BUILTIN,
        brand: String? = null,
        useCount: Int = 0,
    ) = FoodEntity(
        id = nextId++,
        name = name,
        brand = brand,
        kcal = kcal,
        protein = 1.0,
        carbs = 1.0,
        fat = 1.0,
        source = source,
        useCount = useCount,
    )

    private fun item(name: String, searchTerm: String = name, kcal: Double = 0.0, grams: Double = 100.0) = AiFoodItem(
        name = name,
        searchTerm = searchTerm,
        grams = grams,
        kcalPer100g = kcal,
        proteinPer100g = 0.0,
        carbsPer100g = 0.0,
        fatPer100g = 0.0,
        confidence = "hoch",
    )

    private fun score(query: String, name: String, aiKcal: Double? = null, foodKcal: Double = 100.0) =
        FoodMatchScoring.score(query, food(name, kcal = foodKcal), aiKcal)

    // --- normalization ---------------------------------------------------------------------------

    @Test
    fun normalizeFoldsUmlautsAndAccents() {
        assertEquals("haehnchenbrust, gebraten", FoodMatchScoring.normalize("Hähnchenbrust, gebraten"))
        assertEquals("weissbrot", FoodMatchScoring.normalize("Weißbrot"))
        assertEquals("muesli oel", FoodMatchScoring.normalize("Müsli Öl"))
        assertEquals("creme fraiche 30 %", FoodMatchScoring.normalize("Crème fraîche 30 %"))
    }

    @Test
    fun tokensMarkAliasesDescriptorsAndDropNoise() {
        val tokens = FoodMatchScoring.tokens("Nudeln (Spaghetti, Penne), gekocht")
        assertEquals(listOf("nudeln", "spaghetti", "penne", "gekocht"), tokens.map { it.text })
        assertEquals(listOf(false, true, true, false), tokens.map { it.inParens })
        assertEquals(FoodMatchScoring.DESCRIPTOR_WEIGHT, tokens.last().weight, 0.0)
        assertEquals(1.0, tokens.first().weight, 0.0)

        assertEquals(listOf("joghurt", "natur"), FoodMatchScoring.tokens("Joghurt natur 3,5 %").map { it.text })
        assertEquals(listOf("hering", "tomatensauce", "dose"), FoodMatchScoring.tokens("Hering in Tomatensauce (Dose)").map { it.text })
        assertEquals(listOf("curryketchup"), FoodMatchScoring.tokens("Curry-Ketchup").map { it.text })
        assertEquals(listOf("reis", "gekocht"), FoodMatchScoring.tokens("Reis - gekocht").map { it.text })
        assertTrue("inflected descriptor", FoodMatchScoring.isDescriptor("gebratene"))
        assertFalse(FoodMatchScoring.isDescriptor("reis"))
    }

    @Test
    fun tokenSimilarityHandlesPluralsAndCompounds() {
        assertEquals(1.0, FoodMatchScoring.tokenSimilarity("reis", "reis"), 0.0)
        assertEquals(0.9, FoodMatchScoring.tokenSimilarity("kartoffel", "kartoffeln"), 0.0)
        assertEquals(0.9, FoodMatchScoring.tokenSimilarity("ei", "eier"), 0.0)
        assertEquals("'Eis' is no plural of 'Ei'", 0.0, FoodMatchScoring.tokenSimilarity("ei", "eis"), 0.0)
        val suffix = FoodMatchScoring.tokenSimilarity("brot", "vollkornbrot")
        val prefix = FoodMatchScoring.tokenSimilarity("kaese", "kaesekuchen")
        assertTrue("compound head counts more than a prefix", suffix > prefix)
        assertTrue(suffix >= 0.7)
        assertTrue(prefix < 0.5)
    }

    // --- scoring ---------------------------------------------------------------------------------

    @Test
    fun exactMatchScoresHigh() {
        assertTrue(score("Reis gekocht", "Reis, gekocht") >= 0.95)
        assertTrue(score("Apfel", "Apfel") >= 0.99)
        assertTrue(score("Spaghetti Bolognese", "Spaghetti Bolognese") >= 0.99)
    }

    @Test
    fun umlautSpellingDoesNotMatter() {
        assertTrue(score("Haehnchenbrust gebraten", "Hähnchenbrust, gebraten") >= 0.95)
        assertTrue(score("Müsli", "Muesli") >= 0.95)
    }

    @Test
    fun aliasInParenthesesMatches() {
        val cooked = score("Spaghetti gekocht", "Nudeln (Spaghetti, Penne), gekocht")
        val raw = score("Spaghetti gekocht", "Nudeln (Spaghetti, Penne), roh")
        assertTrue("alias match is good enough: $cooked", cooked >= AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue("preparation state matters: $cooked > $raw", cooked > raw)
    }

    @Test
    fun unrelatedFoodsScoreZeroOrLow() {
        assertEquals(0.0, score("Apfel", "Bratwurst (Schwein)"), 0.0)
        assertTrue("only the descriptor overlaps", score("Kartoffeln gekocht", "Spinat, gekocht") < AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue("Käsekuchen is no cheese", score("Käse", "Käsekuchen") < AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue(
            "head word 'Hering' missing",
            score("Tomatensauce", "Hering in Tomatensauce (Dose)") < AiFoodMatcher.MATCH_THRESHOLD,
        )
    }

    @Test
    fun compoundWithSameHeadMatches() {
        assertTrue(score("Brot", "Vollkornbrot") >= AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue(score("Kartoffeln", "Kartoffeln, gekocht (Salzkartoffeln)") >= AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue(score("Pommes", "Pommes frites (frittiert)") >= AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue("Curry-Ketchup is a ketchup", score("Ketchup", "Curry-Ketchup") >= AiFoodMatcher.MATCH_THRESHOLD)
        assertTrue("… but no curry", score("Gemüsecurry", "Curry-Ketchup") < AiFoodMatcher.MATCH_THRESHOLD)
    }

    @Test
    fun caloriesBreakTiesBetweenStates() {
        val cooked = score("Reis", "Reis, gekocht", aiKcal = 130.0, foodKcal = 130.0)
        val raw = score("Reis", "Reis, roh", aiKcal = 130.0, foodKcal = 349.0)
        assertTrue("$cooked > $raw", cooked > raw)
    }

    @Test
    fun kcalFactorRange() {
        assertEquals(1.0, FoodMatchScoring.kcalFactor(null, 300.0), 0.0)
        assertEquals(1.0, FoodMatchScoring.kcalFactor(130.0, 130.0), 0.0)
        assertEquals(0.6, FoodMatchScoring.kcalFactor(0.5, 900.0), 0.001)
        assertTrue(FoodMatchScoring.kcalFactor(130.0, 150.0) > 0.9)
        assertEquals("tiny values are close", 1.0, FoodMatchScoring.kcalFactor(1.0, 1.0), 0.0)
        assertTrue(FoodMatchScoring.kcalFactor(0.0, 2.0) > 0.95)
    }

    // --- ranking ---------------------------------------------------------------------------------

    @Test
    fun rankPutsBestCandidateFirstAndSkipsAiFoods() {
        val cooked = food("Reis, gekocht", kcal = 130.0)
        val foods = listOf(
            food("Reis, roh", kcal = 349.0),
            food("Vollkornreis, gekocht", kcal = 120.0),
            food("Reiswaffeln (natur)", kcal = 385.0),
            food("Reis, gekocht", kcal = 135.0, source = FoodSource.AI),
            cooked,
            food("Milchreis (Fertigprodukt)", kcal = 115.0),
        )

        val ranked = FoodMatchScoring.rank(item("Reis", "Reis gekocht", kcal = 130.0), foods)

        assertEquals(cooked.id, ranked.first().food.id)
        assertTrue(ranked.none { it.food.source == FoodSource.AI })
        assertTrue(ranked.size <= AiFoodMatcher.MAX_CANDIDATES)
        assertEquals(ranked.sortedByDescending { it.score }.map { it.food.id }, ranked.map { it.food.id })
        assertEquals(cooked, FoodMatchResult(ranked).best)
    }

    @Test
    fun builtinWinsOverBrandedProductWithSameName() {
        val branded = food("Tomatensauce", kcal = 60.0, source = FoodSource.OPEN_FOOD_FACTS, brand = "Marke")
        val builtin = food("Tomatensauce (Fertigsauce)", kcal = 60.0)
        val ranked = FoodMatchScoring.rank(item("Tomatensauce", kcal = 60.0), listOf(branded, builtin))
        assertEquals(2, ranked.size)
        assertEquals(builtin.id, ranked.first().food.id)
        assertTrue(ranked.all { it.score >= AiFoodMatcher.MATCH_THRESHOLD })
    }

    @Test
    fun noGoodCandidateMeansAiEstimate() {
        val ranked = FoodMatchScoring.rank(
            item("Gemüsecurry mit Kichererbsen", "Gemüsecurry", kcal = 120.0),
            listOf(food("Kichererbsen (Dose, abgetropft)", kcal = 120.0), food("Curry-Ketchup", kcal = 110.0)),
        )
        assertNull(FoodMatchResult(ranked).best)
    }

    @Test
    fun searchQueriesIncludeTermNameAndHeadWords() {
        val queries = FoodMatchScoring.searchQueries(item("Gemischter Salat", "gemischter Salat"))
        assertEquals("gemischter Salat", queries.first())
        assertTrue(queries.contains("Salat"))
        assertFalse("descriptor alone is no query", queries.contains("gemischter"))

        val pasta = FoodMatchScoring.searchQueries(item("Spaghetti", "Spaghetti gekocht"))
        assertEquals(listOf("Spaghetti gekocht", "Spaghetti"), pasta)
    }

    // --- matcher with a fake database ------------------------------------------------------------

    @Test
    fun matcherSearchesSeveralQueriesAndDeduplicates(): Unit = runBlocking {
        val cooked = food("Nudeln (Spaghetti, Penne), gekocht", kcal = 150.0)
        val raw = food("Nudeln (Spaghetti, Penne), roh", kcal = 359.0)
        val bolognese = food("Spaghetti Bolognese", kcal = 130.0)
        val database = listOf(cooked, raw, bolognese, food("Apfel", kcal = 52.0))
        val queries = mutableListOf<String>()
        val matcher = AiFoodMatcher { query, limit ->
            queries += query
            val words = FoodMatchScoring.tokens(query).map { it.text }
            database.filter { f -> words.all { FoodMatchScoring.normalize(f.name).contains(it) } }.take(limit)
        }

        val result = matcher.match(item("Spaghetti", "Spaghetti gekocht", kcal = 158.0))

        assertEquals(listOf("Spaghetti gekocht", "Spaghetti"), queries)
        assertEquals(cooked.id, result.best?.id)
        assertEquals(result.candidates.map { it.food.id }.distinct(), result.candidates.map { it.food.id })
        assertTrue(result.candidates.any { it.food.id == bolognese.id })
        assertFalse(result.candidates.any { it.food.name == "Apfel" })
    }

    @Test
    fun matcherWithoutResultsFallsBackToAiEstimate(): Unit = runBlocking {
        val matcher = AiFoodMatcher { _, _ -> emptyList() }
        val result = matcher.match(item("Drachenfrucht", kcal = 60.0))
        assertTrue(result.candidates.isEmpty())
        assertNull(result.best)
    }

    // --- image sizing (pure helpers of ImagePreprocessor) -----------------------------------------

    @Test
    fun imageTargetSizeKeepsAspectRatio() {
        assertEquals(1568 to 1176, ImagePreprocessor.targetSize(4000, 3000))
        assertEquals(1176 to 1568, ImagePreprocessor.targetSize(3000, 4000))
        assertEquals(1000 to 800, ImagePreprocessor.targetSize(1000, 800))
        assertEquals(1568 to 1568, ImagePreprocessor.targetSize(4096, 4096))
        assertEquals(2, ImagePreprocessor.sampleSizeFor(4000, 3000))
        assertEquals(4, ImagePreprocessor.sampleSizeFor(8000, 6000))
        assertEquals(1, ImagePreprocessor.sampleSizeFor(1600, 1200))
    }
}
