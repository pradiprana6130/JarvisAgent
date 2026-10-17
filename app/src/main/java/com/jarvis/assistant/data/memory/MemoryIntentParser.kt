package com.jarvis.assistant.data.memory

import java.util.Locale

sealed class SaveMemoryResult {
    data class Success(
        val fact: String,
        val confirmation: String,
        val reminderSupported: Boolean = false,
        val reminderMessage: String? = null
    ) : SaveMemoryResult()

    data class Failure(
        val errorMessage: String
    ) : SaveMemoryResult()
}

sealed class MemoryIntent {
    data class SaveBirthday(
        val normalizedDate: String,
        val wishRequested: Boolean,
        val originalText: String,
        val dateSemantics: String = MemoryEntity.SEMANTICS_RECURRING_ANNUAL
    ) : MemoryIntent()

    data class AmbiguousDate(
        val rawDate: String,
        val candidateA: String,
        val candidateB: String,
        val clarificationPrompt: String
    ) : MemoryIntent()

    object RecallBirthday : MemoryIntent()

    data class RememberFact(
        val fact: String,
        val category: String = MemoryEntity.CATEGORY_NOTES,
        val key: String? = null,
        val value: String? = null
    ) : MemoryIntent()

    data class Forget(val query: String) : MemoryIntent()

    object WhatDoYouRemember : MemoryIntent()
    object RecallFavoriteSong : MemoryIntent()
    object RecallRoutine : MemoryIntent()

    object None : MemoryIntent()
}

object MemoryIntentParser {

    private val MONTHS_MAP = mapOf(
        "january" to 1, "jan" to 1, "जनवरी" to 1, "janvari" to 1, "janwari" to 1,
        "february" to 2, "feb" to 2, "फरवरी" to 2, "farvari" to 2,
        "march" to 3, "mar" to 3, "मार्च" to 3,
        "april" to 4, "apr" to 4, "अप्रैल" to 4, "अप्रेल" to 4, "aprel" to 4,
        "may" to 5, "मई" to 5, "mai" to 5,
        "june" to 6, "jun" to 6, "जून" to 6,
        "july" to 7, "jul" to 7, "जुलाई" to 7, "julai" to 7,
        "august" to 8, "aug" to 8, "अगस्त" to 8, "agast" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "सितंबर" to 9, "सितम्बर" to 9, "sitambar" to 9,
        "october" to 10, "oct" to 10, "अक्टूबर" to 10, "अक्तूबर" to 10, "aktubar" to 10,
        "november" to 11, "nov" to 11, "नवंबर" to 11, "नवम्बर" to 11, "navambar" to 11,
        "december" to 12, "dec" to 12, "दिसंबर" to 12, "दिसम्बर" to 12, "disambar" to 12
    )

    private val MONTH_NAMES = arrayOf(
        "", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    fun parse(rawInput: String): MemoryIntent {
        val clean = rawInput.trim()
        if (clean.isBlank()) return MemoryIntent.None

        val lower = clean.lowercase(Locale.ROOT)
            .replace(Regex("(?i)^(hey jarvis|jarvis|please|kripya)?\\s*"), "")
            .trim()

        // 1. Birthday Recall Intent
        if (isBirthdayRecall(lower)) {
            return MemoryIntent.RecallBirthday
        }

        // 2. Favorite Song Recall Intent
        if (isFavoriteSongRecall(lower)) {
            return MemoryIntent.RecallFavoriteSong
        }

        // 3. Routine Recall Intent
        if (isRoutineRecall(lower)) {
            return MemoryIntent.RecallRoutine
        }

        // 4. "What do you remember" Recall Intent
        if (isWhatDoYouRemember(lower)) {
            return MemoryIntent.WhatDoYouRemember
        }

        // 5. Forget Intent
        val forgetTarget = extractForgetQuery(lower, clean)
        if (forgetTarget != null) {
            return MemoryIntent.Forget(forgetTarget)
        }

        // 6. Birthday Save / Remember Intent (English, Hindi, Hinglish)
        val birthdayIntent = extractBirthdaySaveIntent(lower, clean)
        if (birthdayIntent != null) {
            return birthdayIntent
        }

        // 7. General Explicit Remember Intent ("remember that...", "yaad rakhna ki...", "save this: ...")
        val explicitRemember = extractExplicitRemember(lower, clean)
        if (explicitRemember != null) {
            return MemoryIntent.RememberFact(explicitRemember)
        }

        return MemoryIntent.None
    }

    private fun isBirthdayRecall(lower: String): Boolean {
        // If it contains explicit save or date declaration markers, it's NOT a recall request
        if (lower.contains("save") ||
            lower.contains("सेव") ||
            lower.contains("rakhna") ||
            lower.contains("रखना") ||
            lower.contains("रखो") ||
            lower.contains("ko hai") ||
            lower.contains("को है") ||
            lower.contains("is on")
        ) {
            return false
        }

        if (lower == "when is my birthday" ||
            lower == "when's my birthday" ||
            lower == "what is my birthday" ||
            lower == "what's my birthday" ||
            lower == "tell me my birthday" ||
            lower == "do you know my birthday" ||
            lower == "do you remember my birthday" ||
            lower == "mera birthday kab hai" ||
            lower == "mera birthday kab aata hai" ||
            lower == "mera birthday kab h" ||
            lower == "mera janamdin kab hai" ||
            lower == "mera bday kab hai" ||
            lower == "birthday kab hai mera" ||
            lower == "bday kab hai mera" ||
            lower == "janamdin kab hai mera"
        ) return true

        val pattern = Regex(".*\\b(?:when\\s+is|what\\s+is|when's|what's|tell\\s+me|do\\s+you\\s+know|do\\s+you\\s+remember)\\s+(?:my\\s+)?(?:birthday|bday|janamdin)\\b.*")
        val patternHindi = Regex(".*\\b(?:mera\\s+)?(?:birthday|bday|janamdin)\\s+(?:kab\\s+hai|kab\\s+aata\\s+hai|kab\\s+h|batao)\\b.*")
        if (pattern.matches(lower) || patternHindi.matches(lower)) return true

        if (lower.contains("जन्मदिन") || lower.contains("बर्थडे")) {
            if (lower.contains("कब") || lower.contains("बताओ") || lower.contains("याद है") || lower.contains("क्या है")) {
                return true
            }
        }
        return false
    }

    private fun isFavoriteSongRecall(lower: String): Boolean {
        return lower == "what is my favorite song" ||
                lower == "what's my favorite song" ||
                lower == "what is my favourite song" ||
                lower == "what's my favourite song" ||
                lower == "mera favorite gana kaun sa hai" ||
                lower == "mera favourite gana kaun sa hai" ||
                lower == "mera pasandida gana kaun sa hai" ||
                lower.matches(Regex(".*\\b(?:what\\s+is|what's|tell\\s+me)\\s+(?:my\\s+)?favou?rite\\s+(?:song|music|gana|track)\\b.*")) ||
                lower.matches(Regex(".*\\b(?:mera\\s+)?favou?rite\\s+(?:gana|song)\\s+(?:kaun\\s+sa\\s+hai|kya\\s+hai)\\b.*"))
    }

    private fun isRoutineRecall(lower: String): Boolean {
        return lower == "what is my routine" ||
                lower == "what's my routine" ||
                lower == "what is my daily routine" ||
                lower == "mera routine kya hai" ||
                lower == "daily routine kya hai" ||
                lower.matches(Regex(".*\\b(?:what\\s+is|what's|tell\\s+me)\\s+(?:my\\s+)?(?:daily\\s+)?routine\\b.*")) ||
                lower.matches(Regex(".*\\b(?:mera\\s+)?(?:daily\\s+)?routine\\s+(?:kya\\s+hai|batao)\\b.*"))
    }

    private fun isWhatDoYouRemember(lower: String): Boolean {
        return lower == "what do you remember about me" ||
                lower == "what do you remember" ||
                lower == "tell me what you remember" ||
                lower == "tumhe mere baare mein kya yaad hai" ||
                lower == "kya yaad hai tumhe" ||
                lower.matches(Regex(".*\\b(?:what\\s+do\\s+you\\s+remember|tell\\s+me\\s+what\\s+you\\s+remember)\\b.*")) ||
                lower.matches(Regex(".*\\b(?:kya\\s+yaad\\s+hai\\s+tumhe|mere\\s+baare\\s+mein\\s+kya\\s+yaad\\s+hai)\\b.*"))
    }

    private fun extractForgetQuery(lower: String, clean: String): String? {
        val forgetMatch = Regex("(?i)^(?:hey\\s+jarvis|jarvis|please)?\\s*(?:forget\\s+that|forget\\s+my|forget\\s+about|forget|bhool\\s+jao|भूल\\s+जाओ|हटा\\s+दो)\\s+(.+)$").find(clean)
            ?: Regex("(?i)^(.+?)\\s+(?:bhool\\s+jao|भूल\\s+जाओ|हटा\\s+दो)$").find(clean)
        return forgetMatch?.groupValues?.get(1)?.trim()
    }

    private fun extractBirthdaySaveIntent(lower: String, rawClean: String): MemoryIntent? {
        val hasBirthdayContext = lower.contains("birthday") ||
                lower.contains("bday") ||
                lower.contains("janamdin") ||
                lower.contains("जन्मदिन") ||
                lower.contains("जन्म दिन") ||
                lower.contains("बर्थडे") ||
                lower.contains("born on") ||
                lower.contains("paida hua") ||
                lower.contains("wish me") ||
                lower.contains("wish karna") ||
                lower.contains("ko wish")

        if (!hasBirthdayContext) return null

        val wishRequested = lower.contains("wish me") ||
                lower.contains("wish karna") ||
                lower.contains("ko wish") ||
                lower.contains("remind me") ||
                lower.contains("remind karna") ||
                lower.contains("yaad dilana") ||
                lower.contains("विश") ||
                lower.contains("विश करना") ||
                lower.contains("याद दिलाना")

        // 1. Try finding ambiguous numeric dates like 10/11 or 05/06
        val ambiguousResult = checkAmbiguousNumericDate(rawClean)
        if (ambiguousResult != null) {
            return ambiguousResult
        }

        // 2. Extract unambiguous date (Day Month [Year] or Month Day [Year] or Day/Month/Year with day>12)
        val extractedDate = extractDateString(rawClean) ?: return null

        val semantics = if (extractedDate.year != null) {
            MemoryEntity.SEMANTICS_EXACT_DATE
        } else {
            MemoryEntity.SEMANTICS_RECURRING_ANNUAL
        }

        val normalizedDateStr = if (extractedDate.year != null) {
            "${extractedDate.day} ${MONTH_NAMES[extractedDate.month]} ${extractedDate.year}"
        } else {
            "${extractedDate.day} ${MONTH_NAMES[extractedDate.month]}"
        }

        return MemoryIntent.SaveBirthday(
            normalizedDate = normalizedDateStr,
            wishRequested = wishRequested,
            originalText = rawClean,
            dateSemantics = semantics
        )
    }

    data class ParsedDate(val day: Int, val month: Int, val year: Int? = null)

    private fun checkAmbiguousNumericDate(text: String): MemoryIntent.AmbiguousDate? {
        // Pattern like 10/11 or 05-06
        val regex = Regex("\\b(\\d{1,2})[/.\\-](\\d{1,2})(?:[/.\\-](\\d{2,4}))?\\b")
        val match = regex.find(text) ?: return null

        val num1 = match.groupValues[1].toIntOrNull() ?: return null
        val num2 = match.groupValues[2].toIntOrNull() ?: return null

        // If both are <= 12 and different, it's ambiguous between Day/Month and Month/Day
        if (num1 in 1..12 && num2 in 1..12 && num1 != num2) {
            val candidateA = "$num1 ${MONTH_NAMES[num2]}"
            val candidateB = "$num2 ${MONTH_NAMES[num1]}"
            val prompt = "Sir, could you please clarify if your birthday is $candidateA or $candidateB (e.g. October 11 or November 10)?"
            return MemoryIntent.AmbiguousDate(
                rawDate = match.value,
                candidateA = candidateA,
                candidateB = candidateB,
                clarificationPrompt = prompt
            )
        }
        return null
    }

    private fun extractDateString(text: String): ParsedDate? {
        val lower = text.lowercase(Locale.ROOT)

        // Case A: <Day> <MonthName> [<Year>] (e.g. "10 November", "10th November", "10 नवंबर")
        for ((mName, mNum) in MONTHS_MAP) {
            val esc = Regex.escape(mName)
            val patternA = Regex("(?i)(?:^|\\D)(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$esc(?:\\s+(\\d{4}))?(?:$|[^\\p{L}\\p{Nd}])")
            val matchA = patternA.find(lower)
            if (matchA != null) {
                val day = matchA.groupValues[1].toIntOrNull() ?: continue
                val year = matchA.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
                if (isValidDayForMonth(day, mNum)) {
                    return ParsedDate(day, mNum, year)
                }
            }

            // Case B: <MonthName> <Day> [<Year>] (e.g. "November 10", "November 10th", "Nov 10, 1998")
            val patternB = Regex("(?i)(?:^|[^\\p{L}\\p{Nd}])$esc\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?(?:$|\\D)")
            val matchB = patternB.find(lower)
            if (matchB != null) {
                val day = matchB.groupValues[1].toIntOrNull() ?: continue
                val year = matchB.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull()
                if (isValidDayForMonth(day, mNum)) {
                    return ParsedDate(day, mNum, year)
                }
            }
        }

        // Case C: Unambiguous numeric dates (e.g. 25/11/1998 or 25-11 or 11/25)
        val numRegex = Regex("\\b(\\d{1,2})[/.\\-](\\d{1,2})(?:[/.\\-](\\d{2,4}))?\\b")
        val numMatch = numRegex.find(lower)
        if (numMatch != null) {
            val n1 = numMatch.groupValues[1].toIntOrNull() ?: return null
            val n2 = numMatch.groupValues[2].toIntOrNull() ?: return null
            val year = numMatch.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()

            if (n1 > 12 && n2 in 1..12) {
                // n1 is Day, n2 is Month
                if (isValidDayForMonth(n1, n2)) return ParsedDate(n1, n2, year)
            } else if (n2 > 12 && n1 in 1..12) {
                // n2 is Day, n1 is Month
                if (isValidDayForMonth(n2, n1)) return ParsedDate(n2, n1, year)
            } else if (n1 in 1..12 && n1 == n2) {
                // e.g. 5/5
                if (isValidDayForMonth(n1, n2)) return ParsedDate(n1, n2, year)
            }
        }

        return null
    }

    private fun isValidDayForMonth(day: Int, month: Int): Boolean {
        if (day < 1) return false
        val maxDays = when (month) {
            2 -> 29
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDays
    }

    private fun extractExplicitRemember(lower: String, clean: String): String? {
        val rememberPrefix = Regex("(?i)^(?:hey\\s+jarvis|jarvis|please)?\\s*(?:remember\\s+that|remember\\s+to|remember|yaad\\s+rakhna\\s+ki|yaad\\s+rakhna|save\\s+this:?|save|note\\s+this\\s+down:?|note\\s+down:?)\\s+(.+)$").find(clean)
        if (rememberPrefix != null) {
            val fact = rememberPrefix.groupValues[1].trim()
            if (fact.length > 3) return fact
        }

        val saveSuffix = Regex("(?i)^(.+?)(?:,|;)?\\s*(?:save\\s+kar\\s+lo|save\\s+kar\\s+lena|save\\s+karo|save\\s+it|yaad\\s+rakhna|note\\s+kar\\s+lo)$").find(clean)
        if (saveSuffix != null) {
            val fact = saveSuffix.groupValues[1].trim()
            if (fact.length > 3) return fact
        }

        return null
    }
}
