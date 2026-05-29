package io.theficos.ereader.core.metadata

object Isbn {
    fun toIsbn13(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().replace("-", "").replace(" ", "").uppercase()
        if (isIsbn13(s)) return s
        if (isIsbn10(s)) return isbn10to13(s)
        return null
    }

    private fun isIsbn13(s: String): Boolean {
        if (s.length != 13 || !s.all { it.isDigit() }) return false
        val sum = s.mapIndexed { i, c -> (if (i % 2 == 0) 1 else 3) * (c - '0') }.sum()
        return sum % 10 == 0
    }

    private fun isIsbn10(s: String): Boolean {
        if (s.length != 10) return false
        var total = 0
        for (i in 0 until 10) {
            val c = s[i]
            val v = when {
                c == 'X' && i == 9 -> 10
                c.isDigit() -> c - '0'
                else -> return false
            }
            total += (10 - i) * v
        }
        return total % 11 == 0
    }

    private fun isbn10to13(s: String): String {
        val core = "978" + s.substring(0, 9)
        val check = (10 - core.mapIndexed { i, c -> (if (i % 2 == 0) 1 else 3) * (c - '0') }.sum() % 10) % 10
        return core + check
    }
}
