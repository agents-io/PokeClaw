// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database for chat index + search.
 * Markdown files are source of truth; this DB is the index.
 *
 * Tables:
 * - conversations: id, title, created, model, file_path, message_count
 * - messages: id, conversation_id, role, content, timestamp
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, "pokeclaw.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created INTEGER NOT NULL,
                model TEXT,
                file_path TEXT,
                message_count INTEGER DEFAULT 0,
                last_message TEXT,
                updated INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id)
            )
        """)
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conversation_id)")
        db.execSQL("CREATE INDEX idx_messages_content ON messages(content)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    /**
     * Index a conversation from markdown messages.
     */
    fun indexConversation(id: String, title: String, created: Long, model: String, filePath: String, messages: List<ChatMessage>) {
        val db = writableDatabase

        // Upsert conversation
        val cv = ContentValues().apply {
            put("id", id)
            put("title", title)
            put("created", created)
            put("model", model)
            put("file_path", filePath)
            put("message_count", messages.size)
            put("last_message", messages.lastOrNull { it.role == ChatMessage.Role.USER }?.content?.take(100) ?: "")
            put("updated", System.currentTimeMillis())
        }
        db.insertWithOnConflict("conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        // Replace messages
        db.delete("messages", "conversation_id = ?", arrayOf(id))
        messages.forEach { msg ->
            val mcv = ContentValues().apply {
                put("conversation_id", id)
                put("role", msg.role.name)
                put("content", msg.content)
                put("timestamp", msg.timestamp)
            }
            db.insert("messages", null, mcv)
        }
    }

    /**
     * Search messages across all conversations.
     */
    fun search(query: String): List<SearchResult> {
        val db = readableDatabase
        val results = mutableListOf<SearchResult>()
        val cursor = db.rawQuery("""
            SELECT m.content, m.role, c.title, c.id, c.file_path
            FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE m.content LIKE ?
            ORDER BY m.timestamp DESC
            LIMIT 50
        """, arrayOf("%$query%"))

        while (cursor.moveToNext()) {
            results.add(SearchResult(
                content = cursor.getString(0),
                role = cursor.getString(1),
                conversationTitle = cursor.getString(2),
                conversationId = cursor.getString(3),
                filePath = cursor.getString(4)
            ))
        }
        cursor.close()
        return results
    }

    /**
     * Get all conversations ordered by last updated.
     */
    fun getConversations(): List<ConversationIndex> {
        val db = readableDatabase
        val results = mutableListOf<ConversationIndex>()
        val cursor = db.rawQuery("""
            SELECT id, title, created, model, file_path, message_count, last_message, updated
            FROM conversations
            ORDER BY updated DESC
        """, null)

        while (cursor.moveToNext()) {
            results.add(ConversationIndex(
                id = cursor.getString(0),
                title = cursor.getString(1),
                created = cursor.getLong(2),
                model = cursor.getString(3) ?: "",
                filePath = cursor.getString(4) ?: "",
                messageCount = cursor.getInt(5),
                lastMessage = cursor.getString(6) ?: "",
                updated = cursor.getLong(7)
            ))
        }
        cursor.close()
        return results
    }

    /**
     * Delete a conversation from index.
     */
    fun deleteConversation(id: String) {
        val db = writableDatabase
        db.delete("messages", "conversation_id = ?", arrayOf(id))
        db.delete("conversations", "id = ?", arrayOf(id))
    }

    data class SearchResult(
        val content: String,
        val role: String,
        val conversationTitle: String,
        val conversationId: String,
        val filePath: String
    )

    data class ConversationIndex(
        val id: String,
        val title: String,
        val created: Long,
        val model: String,
        val filePath: String,
        val messageCount: Int,
        val lastMessage: String,
        val updated: Long
    )
}
