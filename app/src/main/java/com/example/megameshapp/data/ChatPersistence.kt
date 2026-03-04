package com.example.megameshapp.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Handles persistent storage of chat messages using SharedPreferences + Gson.
 * Messages are stored per conversation (nodeId or "broadcast").
 */
class ChatPersistence(context: Context) {

    companion object {
        private const val PREFS_NAME = "megamesh_chats"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_MESSAGES_PREFIX = "messages_"
        private const val MAX_MESSAGES_PER_CHAT = 500
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Returns the set of conversation IDs (node IDs or "broadcast")
     */
    fun getConversationIds(): Set<String> {
        return prefs.getStringSet(KEY_CONVERSATIONS, emptySet()) ?: emptySet()
    }

    /**
     * Load all messages for a given conversation
     */
    fun loadMessages(conversationId: String): List<MeshMessage> {
        val json = prefs.getString(KEY_MESSAGES_PREFIX + conversationId, null) ?: return emptyList()
        val type = object : TypeToken<List<MeshMessage>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Load all messages across all conversations
     */
    fun loadAllMessages(): List<MeshMessage> {
        val allMessages = mutableListOf<MeshMessage>()
        for (convId in getConversationIds()) {
            allMessages.addAll(loadMessages(convId))
        }
        return allMessages.sortedBy { it.timestamp }
    }

    /**
     * Save a message to the appropriate conversation
     */
    fun saveMessage(message: MeshMessage) {
        val conversationId = getConversationId(message)
        val messages = loadMessages(conversationId).toMutableList()
        messages.add(message)

        // Limit stored messages
        val trimmed = if (messages.size > MAX_MESSAGES_PER_CHAT) {
            messages.takeLast(MAX_MESSAGES_PER_CHAT)
        } else {
            messages
        }

        val json = gson.toJson(trimmed)
        prefs.edit()
            .putString(KEY_MESSAGES_PREFIX + conversationId, json)
            .apply()

        // Update conversation list
        val conversations = getConversationIds().toMutableSet()
        conversations.add(conversationId)
        prefs.edit()
            .putStringSet(KEY_CONVERSATIONS, conversations)
            .apply()
    }

    /**
     * Save multiple messages at once for a conversation
     */
    fun saveMessages(conversationId: String, messages: List<MeshMessage>) {
        val trimmed = if (messages.size > MAX_MESSAGES_PER_CHAT) {
            messages.takeLast(MAX_MESSAGES_PER_CHAT)
        } else {
            messages
        }

        val json = gson.toJson(trimmed)
        prefs.edit()
            .putString(KEY_MESSAGES_PREFIX + conversationId, json)
            .apply()

        val conversations = getConversationIds().toMutableSet()
        conversations.add(conversationId)
        prefs.edit()
            .putStringSet(KEY_CONVERSATIONS, conversations)
            .apply()
    }

    /**
     * Clear all messages for a conversation
     */
    fun clearConversation(conversationId: String) {
        prefs.edit()
            .remove(KEY_MESSAGES_PREFIX + conversationId)
            .apply()

        val conversations = getConversationIds().toMutableSet()
        conversations.remove(conversationId)
        prefs.edit()
            .putStringSet(KEY_CONVERSATIONS, conversations)
            .apply()
    }

    /**
     * Clear all stored chats
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Determine the conversation ID for a message.
     * Broadcast messages go to "broadcast", DMs go to the other party's node ID.
     */
    private fun getConversationId(message: MeshMessage): String {
        return if (message.destination == "broadcast" || message.destination == "0xFFFF") {
            "broadcast"
        } else if (message.isOutgoing) {
            message.destination
        } else {
            message.origin
        }
    }
}

