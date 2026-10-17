package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.Conversation
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ChatRepository(private val preferences: AppPreferences? = null) {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    init {
        val loaded = preferences?.loadConversations() ?: emptyList()
        _conversations.value = loaded
        // Fresh chat on initial launch; past conversations are in history
        _activeConversationId.value = null
        _turns.value = emptyList()
    }

    @Synchronized
    fun startNewConversation(): String {
        _activeConversationId.value = null
        _turns.value = emptyList()
        return ""
    }

    @Synchronized
    fun selectConversation(conversationId: String) {
        val conv = _conversations.value.find { it.id == conversationId } ?: return
        _activeConversationId.value = conv.id
        _turns.value = conv.turns
    }

    @Synchronized
    fun deleteConversation(conversationId: String) {
        val updated = _conversations.value.filterNot { it.id == conversationId }
        _conversations.value = updated
        preferences?.saveConversations(updated)

        if (_activeConversationId.value == conversationId) {
            startNewConversation()
        }
    }

    @Synchronized
    fun startTurn(
        userText: String,
        attachmentName: String? = null,
        attachmentSize: String? = null,
        attachmentType: String? = null,
        attachmentThumbnailBase64: String? = null
    ): String {
        val cleanUser = userText.trim()
        val turnId = UUID.randomUUID().toString()
        val newTurn = ChatTurn(
            id = turnId,
            userTranscript = cleanUser,
            jarvisResponse = "",
            attachmentName = attachmentName,
            attachmentSize = attachmentSize,
            attachmentType = attachmentType,
            attachmentThumbnailBase64 = attachmentThumbnailBase64
        )

        var activeId = _activeConversationId.value
        val convList = _conversations.value.toMutableList()
        var currentConv = convList.find { it.id == activeId }

        if (currentConv == null) {
            val title = if (cleanUser.isNotBlank()) Conversation.generateTitle(cleanUser) else "New Chat"
            val newConv = Conversation(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                turns = listOf(newTurn)
            )
            activeId = newConv.id
            _activeConversationId.value = activeId
            convList.add(0, newConv)
            _conversations.value = convList
            _turns.value = listOf(newTurn)
            preferences?.saveConversations(convList)
            preferences?.saveChatHistory(listOf(newTurn))
            return turnId
        }

        val updatedTurns = currentConv.turns.toMutableList()
        updatedTurns.add(newTurn)
        val updatedTitle = if (currentConv.title == "New Chat" && cleanUser.isNotBlank()) {
            Conversation.generateTitle(cleanUser)
        } else {
            currentConv.title
        }

        val updatedConv = currentConv.copy(
            title = updatedTitle,
            updatedAt = System.currentTimeMillis(),
            turns = updatedTurns
        )

        val idx = convList.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            convList[idx] = updatedConv
            // Bring most recently updated conversation to top
            if (idx > 0) {
                convList.removeAt(idx)
                convList.add(0, updatedConv)
            }
        }

        _conversations.value = convList
        _turns.value = updatedTurns
        preferences?.saveConversations(convList)
        preferences?.saveChatHistory(updatedTurns)
        return turnId
    }

    @Synchronized
    fun updateUserTranscript(turnId: String, text: String) {
        val clean = text.trim()
        val currentTurns = _turns.value.toMutableList()
        val turnIdx = currentTurns.indexOfFirst { it.id == turnId }
        if (turnIdx < 0) return

        val oldTurn = currentTurns[turnIdx]
        if (oldTurn.userTranscript == clean) return

        val updatedTurn = oldTurn.copy(userTranscript = clean)
        currentTurns[turnIdx] = updatedTurn
        _turns.value = currentTurns

        val activeId = _activeConversationId.value ?: return
        val convList = _conversations.value.toMutableList()
        val convIdx = convList.indexOfFirst { it.id == activeId }
        if (convIdx >= 0) {
            val conv = convList[convIdx]
            val updatedTitle = if (conv.title == "New Chat" && clean.isNotBlank()) {
                Conversation.generateTitle(clean)
            } else conv.title

            val updatedConv = conv.copy(
                title = updatedTitle,
                updatedAt = System.currentTimeMillis(),
                turns = currentTurns
            )
            convList[convIdx] = updatedConv
            _conversations.value = convList
            preferences?.saveConversations(convList)
            preferences?.saveChatHistory(currentTurns)
        }
    }

    @Synchronized
    fun updateAssistantResponse(turnId: String, text: String, isComplete: Boolean = false) {
        val clean = text.trim()
        val currentTurns = _turns.value.toMutableList()
        val turnIdx = currentTurns.indexOfFirst { it.id == turnId }
        if (turnIdx < 0) return

        val oldTurn = currentTurns[turnIdx]
        val updatedTurn = oldTurn.copy(jarvisResponse = clean)
        currentTurns[turnIdx] = updatedTurn
        _turns.value = currentTurns

        val activeId = _activeConversationId.value ?: return
        val convList = _conversations.value.toMutableList()
        val convIdx = convList.indexOfFirst { it.id == activeId }
        if (convIdx >= 0) {
            val conv = convList[convIdx]
            val updatedConv = conv.copy(
                updatedAt = System.currentTimeMillis(),
                turns = currentTurns
            )
            convList[convIdx] = updatedConv
            _conversations.value = convList
            if (isComplete) {
                preferences?.saveConversations(convList)
                preferences?.saveChatHistory(currentTurns)
            }
        }
    }

    @Synchronized
    fun addTurn(
        userText: String,
        jarvisText: String,
        attachmentName: String? = null,
        attachmentSize: String? = null,
        attachmentType: String? = null,
        attachmentThumbnailBase64: String? = null
    ) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()

        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val currentTurns = _turns.value.toMutableList()
        if (currentTurns.isNotEmpty()) {
            val last = currentTurns.last()
            if (last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
                return
            }
        }

        val newTurn = ChatTurn(
            userTranscript = cleanUser,
            jarvisResponse = cleanJarvis,
            attachmentName = attachmentName,
            attachmentSize = attachmentSize,
            attachmentType = attachmentType,
            attachmentThumbnailBase64 = attachmentThumbnailBase64
        )

        var activeId = _activeConversationId.value
        val convList = _conversations.value.toMutableList()
        var currentConv = convList.find { it.id == activeId }

        if (currentConv == null) {
            val title = if (cleanUser.isNotBlank()) Conversation.generateTitle(cleanUser) else "New Chat"
            val newConv = Conversation(
                id = UUID.randomUUID().toString(),
                title = title,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                turns = listOf(newTurn)
            )
            activeId = newConv.id
            _activeConversationId.value = activeId
            convList.add(0, newConv)
            _conversations.value = convList
            _turns.value = listOf(newTurn)
            preferences?.saveConversations(convList)
            preferences?.saveChatHistory(listOf(newTurn))
            return
        }

        val updatedTurns = currentConv.turns.toMutableList()
        updatedTurns.add(newTurn)
        val updatedTitle = if (currentConv.title == "New Chat" && cleanUser.isNotBlank()) {
            Conversation.generateTitle(cleanUser)
        } else {
            currentConv.title
        }

        val updatedConv = currentConv.copy(
            title = updatedTitle,
            updatedAt = System.currentTimeMillis(),
            turns = updatedTurns
        )

        val idx = convList.indexOfFirst { it.id == activeId }
        if (idx >= 0) {
            convList[idx] = updatedConv
            if (idx > 0) {
                convList.removeAt(idx)
                convList.add(0, updatedConv)
            }
        }

        _conversations.value = convList
        _turns.value = updatedTurns
        preferences?.saveConversations(convList)
        preferences?.saveChatHistory(updatedTurns)
    }

    @Synchronized
    fun clearHistory() {
        _turns.value = emptyList()
        _conversations.value = emptyList()
        _activeConversationId.value = null
        preferences?.clearConversations()
    }
}
