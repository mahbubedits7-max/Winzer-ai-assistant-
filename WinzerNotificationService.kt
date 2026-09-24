package com.example.winzeraiassistant

/*
 * ---------------------------------------------------------------
 *  AndroidManifest.xml
 * ---------------------------------------------------------------
 *  1) Inside <application> add:
 *
 *  <service
 *      android:name=".WinzerNotificationService"
 *      android:exported="true"
 *      android:label="Winzer"
 *      android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.service.notification.NotificationListenerService" />
 *      </intent-filter>
 *  </service>
 *
 *  2) Inside the existing <queries> block add (so Winzer can find Telegram):
 *
 *  <package android:name="org.telegram.messenger" />
 *  <package android:name="org.telegram.messenger.web" />
 *  <package android:name="org.telegram.plus" />
 *  <package android:name="org.thunderdog.challegram" />
 *
 *  3) After installing: Settings > Notifications > Notification access > turn ON "Winzer"
 *     (Android does not let an app switch this on by itself).
 * ---------------------------------------------------------------
 *
 *  What this does (and nothing more)
 *  - Quietly remembers new PRIVATE Telegram messages (groups and channels are skipped).
 *  - Nothing is spoken by itself. When you ask ("telegram read"), Winzer tells you
 *    who messaged you and what they said.
 *  - When you say "reply to <name> <your message>", Winzer types your message into
 *    that person's Telegram notification Reply box and sends it.
 *  - Messages are kept only in memory and are gone when the app process stops.
 */

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// ------------------------------------------------------------------
//  Inbox shared between the background service and the app screen
// ------------------------------------------------------------------
object WinzerBridge {

    val TELEGRAM_PACKAGES = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram"
    )

    class Chat(val key: String, var name: String, var action: Notification.Action) {
        val messages = ArrayList<String>()
    }

    private val chats = LinkedHashMap<String, Chat>()

    @Synchronized
    fun addMessage(key: String, name: String, text: String, action: Notification.Action) {
        val chat = chats.remove(key)?.also {
            it.name = name
            it.action = action
        } ?: Chat(key, name, action)

        // Telegram re-posts the same notification several times; keep each message once.
        if (chat.messages.lastOrNull() != text) {
            chat.messages.add(text)
            while (chat.messages.size > 10) chat.messages.removeAt(0)
        }

        chats[key] = chat
        while (chats.size > 20) chats.remove(chats.keys.first())
    }

    /** Tells you who messaged and what they said. */
    @Synchronized
    fun readInbox(): String {
        val unread = chats.values.filter { it.messages.isNotEmpty() }.reversed()
        if (unread.isEmpty()) {
            return "No new Telegram messages. If you expected some, check that Notification access is on for Winzer."
        }
        val sb = StringBuilder()
        sb.append(if (unread.size == 1) "1 person messaged you. " else "${unread.size} people messaged you. ")
        for (c in unread) {
            sb.append(c.name).append(" says: ").append(c.messages.joinToString(". ")).append(". ")
        }
        return sb.toString().trim()
    }

    @Synchronized
    fun clear(): String {
        chats.values.forEach { it.messages.clear() }
        return "Cleared."
    }

    /**
     * [spoken] is what you said after "reply", for example "to Rahim ok I'm coming".
     * The name can be the full name or just the first name.
     */
    @Synchronized
    fun replyTo(context: Context, spoken: String): String {
        val rest = spoken.trim().replaceFirst(Regex("(?i)^to\\s+"), "").trim()
        if (rest.isEmpty()) return "What should I reply?"

        // Find the person whose name (or first name) your sentence starts with.
        var best: Chat? = null
        var bestLen = 0
        var tie = false
        for (c in chats.values) {
            val names = listOf(c.name.trim(), c.name.trim().split(" ").first())
            for (n in names) {
                if (n.length >= 2 && rest.startsWith(n, ignoreCase = true)) {
                    if (n.length > bestLen) {
                        best = c; bestLen = n.length; tie = false
                    } else if (n.length == bestLen && best !== c) {
                        tie = true
                    }
                }
            }
        }

        if (best != null && !tie) {
            val text = rest.substring(bestLen).trimStart(' ', ':', ',', '-').trim()
            if (text.isBlank()) return "What should I tell ${best.name}?"
            return send(context, best, text)
        }

        // No name recognised: if exactly one person is waiting, the whole sentence is the reply.
        val waiting = chats.values.filter { it.messages.isNotEmpty() }
        return when {
            waiting.isEmpty() -> "No one has messaged you yet."
            waiting.size == 1 -> send(context, waiting[0], rest)
            else -> "Who should I reply to? Say: reply to, then the name, then your message."
        }
    }

    private fun send(context: Context, chat: Chat, text: String): String {
        return try {
            val inputs = chat.action.remoteInputs
                ?: return "This chat can't be replied to from here."
            val fill = Intent()
            val results = Bundle()
            for (input in inputs) results.putCharSequence(input.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, fill, results)
            chat.action.actionIntent.send(context, 0, fill)
            chat.messages.clear()
            "Sent to ${chat.name}: $text"
        } catch (e: Exception) {
            "I couldn't send that. The notification may have been dismissed, so ask them to write again."
        }
    }
}

// ------------------------------------------------------------------
//  The service that quietly watches Telegram notifications
// ------------------------------------------------------------------
class WinzerNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WinzerBridge.TELEGRAM_PACKAGES) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Private chats only: skip groups and channels.
        if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (n.channelId ?: "").lowercase()
        } else {
            ""
        }
        if (channelId.contains("group") || channelId.contains("channel")) return

        // Only notifications with a Reply box belong to a chat we can answer.
        val action = n.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return

        val name = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        WinzerBridge.addMessage(sbn.key, name, text, action)
    }
}
