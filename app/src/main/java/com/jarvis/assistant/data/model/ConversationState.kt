package com.jarvis.assistant.data.model

/**
 * Authoritative, single source of truth for the assistant conversational lifecycle.
 * Perfectly synchronized across JarvisConversationService, MainViewModel,
 * MainActivity HUD, and OrbHelper (HTML5 2D Canvas Arc Reactor).
 */
enum class ConversationState(val displayName: String, val orbKey: String) {
    OFF("OFF", "offline"),
    STANDBY("STANDBY", "sleep"),
    IDLE("IDLE", "idle"),
    WAKE_UP("WAKING UP", "wake_up"),
    LISTENING("LISTENING", "listening"),
    THINKING("THINKING", "thinking"),
    EXECUTING("EXECUTING", "executing"),
    SPEAKING("SPEAKING", "speaking"),
    ERROR("ERROR", "error");

    companion object {
        /**
         * Source-compatibility properties for external callers or legacy references.
         * These do NOT create separate enum instances, avoiding exhaustive 'when' pollution.
         */
        @Deprecated("Use OFF instead", ReplaceWith("ConversationState.OFF"))
        val OFFLINE: ConversationState get() = OFF

        @Deprecated("Use STANDBY instead", ReplaceWith("ConversationState.STANDBY"))
        val SLEEP: ConversationState get() = STANDBY
    }
}

