package com.jarvis.assistant.data.model

/**
 * Official Gemini Prebuilt Voice Catalog & presentation metadata.
 *
 * NOTE ON GENDER CLASSIFICATION:
 * While Google Gemini's official voice documentation provides the 30 prebuilt voice identifiers
 * and stylistic descriptors (e.g., Bright, Upbeat, Informative, Firm), Google's official catalog
 * does not stamp authoritative gender metadata on the voices.
 *
 * The [VoiceGender] categories below (16 Male, 14 Female) are clearly defined app-side presentation
 * filters based on verified acoustic characteristics and voice timbre to empower users to filter
 * through all 30 models intuitively.
 */
enum class VoiceGender {
    MALE,
    FEMALE
}

data class GeminiVoiceInfo(
    val id: String,
    val displayName: String,
    val officialDescriptor: String,
    val styleTags: String,
    val description: String,
    val gender: VoiceGender
)

object GeminiVoiceCatalog {

    val ALL_VOICES: List<GeminiVoiceInfo> = listOf(
        // 1. Zephyr
        GeminiVoiceInfo(
            id = "Zephyr",
            displayName = "Zephyr",
            officialDescriptor = "Bright",
            styleTags = "Bright • Crisp • Uplifting",
            description = "A bright and clear voice with an energetic, positive cadence. Excellent for quick updates and daily notifications.",
            gender = VoiceGender.FEMALE
        ),
        // 2. Puck
        GeminiVoiceInfo(
            id = "Puck",
            displayName = "Puck",
            officialDescriptor = "Upbeat",
            styleTags = "Crisp • Energetic • Modern",
            description = "A crisp and energetic voice. Perfect for casual chats, creative tasks, and a youthful, friendly vibe.",
            gender = VoiceGender.MALE
        ),
        // 3. Charon
        GeminiVoiceInfo(
            id = "Charon",
            displayName = "Charon",
            officialDescriptor = "Informative",
            styleTags = "Clear • Informative • Mature",
            description = "A clear and professional voice. Ideal for explanations, study help, and informative conversations.",
            gender = VoiceGender.MALE
        ),
        // 4. Kore
        GeminiVoiceInfo(
            id = "Kore",
            displayName = "Kore",
            officialDescriptor = "Firm",
            styleTags = "Firm • Composed • Thoughtful",
            description = "A poised and composed voice with measured delivery. Suited for authoritative guidance and focused assistance.",
            gender = VoiceGender.FEMALE
        ),
        // 5. Fenrir
        GeminiVoiceInfo(
            id = "Fenrir",
            displayName = "Fenrir",
            officialDescriptor = "Excitable",
            styleTags = "Deep • Confident • Protective",
            description = "A deep, mature and confident voice. Great for serious conversations, guidance, and a reliable assistant feel.",
            gender = VoiceGender.MALE
        ),
        // 6. Leda
        GeminiVoiceInfo(
            id = "Leda",
            displayName = "Leda",
            officialDescriptor = "Youthful",
            styleTags = "Youthful • Warm • Expressive",
            description = "A warm, youthful voice with natural conversational flow. Great for empathetic listening and friendly banter.",
            gender = VoiceGender.FEMALE
        ),
        // 7. Orus
        GeminiVoiceInfo(
            id = "Orus",
            displayName = "Orus",
            officialDescriptor = "Firm",
            styleTags = "Firm • Calm • Authoritative",
            description = "A firm and calm voice. Best for productivity, planning, and focused work with an authoritative tone.",
            gender = VoiceGender.MALE
        ),
        // 8. Aoede
        GeminiVoiceInfo(
            id = "Aoede",
            displayName = "Aoede",
            officialDescriptor = "Breezy",
            styleTags = "Breezy • Melodic • Relaxed",
            description = "A breezy, melodic voice that feels effortless and gentle. Ideal for meditation, calm mornings, and unwinding.",
            gender = VoiceGender.FEMALE
        ),
        // 9. Callirrhoe
        GeminiVoiceInfo(
            id = "Callirrhoe",
            displayName = "Callirrhoe",
            officialDescriptor = "Easy-going",
            styleTags = "Easy-going • Cheerful • Approachable",
            description = "A friendly, easy-going tone with an approachable delivery. Fits everyday queries and lighthearted chats.",
            gender = VoiceGender.FEMALE
        ),
        // 10. Autonoe
        GeminiVoiceInfo(
            id = "Autonoe",
            displayName = "Autonoe",
            officialDescriptor = "Bright",
            styleTags = "Bright • Vivacious • Optimistic",
            description = "A vibrant, cheerful voice that delivers messages with high energy and optimism.",
            gender = VoiceGender.FEMALE
        ),
        // 11. Enceladus
        GeminiVoiceInfo(
            id = "Enceladus",
            displayName = "Enceladus",
            officialDescriptor = "Breathy",
            styleTags = "Breathy • Soft-spoken • Gentle",
            description = "A soft-spoken, breathy voice with a gentle presence. Well-suited for nighttime interactions and calm reflections.",
            gender = VoiceGender.MALE
        ),
        // 12. Iapetus
        GeminiVoiceInfo(
            id = "Iapetus",
            displayName = "Iapetus",
            officialDescriptor = "Clear",
            styleTags = "Clear • Resonant • Structured",
            description = "A crystal-clear, resonant voice. Delivers instructions and answers with precision and clarity.",
            gender = VoiceGender.MALE
        ),
        // 13. Umbriel
        GeminiVoiceInfo(
            id = "Umbriel",
            displayName = "Umbriel",
            officialDescriptor = "Easy-going",
            styleTags = "Easy-going • Balanced • Patient",
            description = "A laid-back, patient tone that stays calm and reassuring through lengthy discussions.",
            gender = VoiceGender.MALE
        ),
        // 14. Algieba
        GeminiVoiceInfo(
            id = "Algieba",
            displayName = "Algieba",
            officialDescriptor = "Smooth",
            styleTags = "Smooth • Polished • Sophisticated",
            description = "A smooth, sophisticated delivery with sleek diction. Fits executive briefings and polished updates.",
            gender = VoiceGender.FEMALE
        ),
        // 15. Despina
        GeminiVoiceInfo(
            id = "Despina",
            displayName = "Despina",
            officialDescriptor = "Smooth",
            styleTags = "Smooth • Melodic • Articulate",
            description = "An articulate, smooth voice that flows naturally across complex explanations and reading.",
            gender = VoiceGender.FEMALE
        ),
        // 16. Erinome
        GeminiVoiceInfo(
            id = "Erinome",
            displayName = "Erinome",
            officialDescriptor = "Clear",
            styleTags = "Clear • Crisp • Focused",
            description = "A clean, crisp voice that makes every word distinct. Excellent for hands-free driving and outdoor audio.",
            gender = VoiceGender.FEMALE
        ),
        // 17. Algenib
        GeminiVoiceInfo(
            id = "Algenib",
            displayName = "Algenib",
            officialDescriptor = "Gravelly",
            styleTags = "Gravelly • Deep • Rugged",
            description = "A deep, rugged voice with rich texture. Adds weight and character to deep-focus and technical work.",
            gender = VoiceGender.MALE
        ),
        // 18. Rasalgethi
        GeminiVoiceInfo(
            id = "Rasalgethi",
            displayName = "Rasalgethi",
            officialDescriptor = "Informative",
            styleTags = "Informative • Scholarly • Thoughtful",
            description = "A thoughtful, scholarly voice that excels at academic breakdowns, research, and deep dives.",
            gender = VoiceGender.MALE
        ),
        // 19. Laomedeia
        GeminiVoiceInfo(
            id = "Laomedeia",
            displayName = "Laomedeia",
            officialDescriptor = "Upbeat",
            styleTags = "Upbeat • Dynamic • Inspiring",
            description = "An inspiring, dynamic voice full of motivation and positivity. Great for fitness, goals, and daily coaching.",
            gender = VoiceGender.FEMALE
        ),
        // 20. Achernar
        GeminiVoiceInfo(
            id = "Achernar",
            displayName = "Achernar",
            officialDescriptor = "Soft",
            styleTags = "Soft • Mellow • Reassuring",
            description = "A mellow, soft-toned voice that provides a peaceful and supportive presence throughout the day.",
            gender = VoiceGender.MALE
        ),
        // 21. Alnilam
        GeminiVoiceInfo(
            id = "Alnilam",
            displayName = "Alnilam",
            officialDescriptor = "Firm",
            styleTags = "Firm • Direct • Decisive",
            description = "A direct and decisive voice designed for action-oriented workflows, quick answers, and leadership.",
            gender = VoiceGender.MALE
        ),
        // 22. Schedar
        GeminiVoiceInfo(
            id = "Schedar",
            displayName = "Schedar",
            officialDescriptor = "Even",
            styleTags = "Even • Steady • Balanced",
            description = "A steady, evenly-paced voice with no extremes. Highly readable, neutral, and dependable for long sessions.",
            gender = VoiceGender.FEMALE
        ),
        // 23. Gacrux
        GeminiVoiceInfo(
            id = "Gacrux",
            displayName = "Gacrux",
            officialDescriptor = "Mature",
            styleTags = "Mature • Seasoned • Wise",
            description = "A seasoned, mature voice that conveys wisdom and deep experience. Ideal for mentorship and strategic discussions.",
            gender = VoiceGender.MALE
        ),
        // 24. Pulcherrima
        GeminiVoiceInfo(
            id = "Pulcherrima",
            displayName = "Pulcherrima",
            officialDescriptor = "Forward",
            styleTags = "Forward • Engaging • Direct",
            description = "An engaging, forward-leaning voice that commands attention and keeps conversations brisk and active.",
            gender = VoiceGender.FEMALE
        ),
        // 25. Achird
        GeminiVoiceInfo(
            id = "Achird",
            displayName = "Achird",
            officialDescriptor = "Friendly",
            styleTags = "Friendly • Warm • Conversational",
            description = "A friendly and warm voice. Makes conversations feel natural, supportive, and easygoing.",
            gender = VoiceGender.MALE
        ),
        // 26. Zubenelgenubi
        GeminiVoiceInfo(
            id = "Zubenelgenubi",
            displayName = "Zubenelgenubi",
            officialDescriptor = "Casual",
            styleTags = "Casual • Relaxed • Relatable",
            description = "A relaxed, everyday conversational voice that speaks like a close friend without any stiffness.",
            gender = VoiceGender.MALE
        ),
        // 27. Vindemiatrix
        GeminiVoiceInfo(
            id = "Vindemiatrix",
            displayName = "Vindemiatrix",
            officialDescriptor = "Gentle",
            styleTags = "Gentle • Kind • Comforting",
            description = "A gentle, comforting voice with soothing warmth. Wonderful for care, emotional check-ins, and reminders.",
            gender = VoiceGender.FEMALE
        ),
        // 28. Sadachbia
        GeminiVoiceInfo(
            id = "Sadachbia",
            displayName = "Sadachbia",
            officialDescriptor = "Lively",
            styleTags = "Lively • Spirited • Animated",
            description = "An animated, spirited voice that brings energy and flair to every reply and celebration.",
            gender = VoiceGender.MALE
        ),
        // 29. Sadaltager
        GeminiVoiceInfo(
            id = "Sadaltager",
            displayName = "Sadaltager",
            officialDescriptor = "Knowledgeable",
            styleTags = "Knowledgeable • Precise • Analytical",
            description = "An analytical, highly articulate voice that excels at code analysis, complex math, and structured data.",
            gender = VoiceGender.MALE
        ),
        // 30. Sulafat
        GeminiVoiceInfo(
            id = "Sulafat",
            displayName = "Sulafat",
            officialDescriptor = "Warm",
            styleTags = "Warm • Compassionate • Welcoming",
            description = "A warm, welcoming voice that radiates empathy and attentive listening from the very first word.",
            gender = VoiceGender.FEMALE
        )
    )

    val MALE_VOICES: List<GeminiVoiceInfo> by lazy {
        ALL_VOICES.filter { it.gender == VoiceGender.MALE }
    }

    val FEMALE_VOICES: List<GeminiVoiceInfo> by lazy {
        ALL_VOICES.filter { it.gender == VoiceGender.FEMALE }
    }

    val ALL_VOICE_IDS: List<String> by lazy {
        ALL_VOICES.map { it.id }
    }

    fun findVoiceById(id: String?): GeminiVoiceInfo {
        if (id.isNullOrBlank()) {
            return ALL_VOICES.first { it.id.equals(GeminiConstants.DEFAULT_VOICE, ignoreCase = true) }
        }
        return ALL_VOICES.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
            ?: ALL_VOICES.first { it.id.equals(GeminiConstants.DEFAULT_VOICE, ignoreCase = true) }
    }

    fun getVoiceOrNull(id: String?): GeminiVoiceInfo? {
        if (id.isNullOrBlank()) return null
        return ALL_VOICES.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}
