package io.github.andreadp.trenolibero.data

/**
 * Alcune stazioni indicano i binari in numeri romani ("II", "IV TRONCO", "I OVEST"):
 * li si converte in numeri arabi ("2", "4 TRONCO", "1 OVEST"). Si considerano solo le parole
 * fatte di I, V, X (fino a 39), così sigle come "C", "D" o "BIS" restano invariate.
 */
fun platformLabel(raw: String?): String? {
    val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return s.split(Regex("\\s+")).joinToString(" ") { word -> romanToInt(word)?.toString() ?: word }
}

private val ROMAN = Regex("^X{0,3}(IX|IV|V?I{0,3})$")

private fun romanToInt(word: String): Int? {
    if (word.isEmpty() || !ROMAN.matches(word)) return null
    val values = word.uppercase().map { when (it) { 'I' -> 1; 'V' -> 5; else -> 10 } }
    return values.indices.sumOf { i -> if (i + 1 < values.size && values[i] < values[i + 1]) -values[i] else values[i] }
}
