package com.shslab.leo.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.shslab.leo.core.Logger

/**
 * ══════════════════════════════════════════
 *  DORAEMON 4D POCKET — GADGET REGISTRY
 *
 *  Maps Doraemon-style gadget names to real
 *  Android intents and accessibility actions.
 *  Used by the AI brain when it pulls a gadget.
 * ══════════════════════════════════════════
 */
object GadgetRegistry {

    data class Gadget(val key: String, val name: String, val handler: (Context, Map<String, String>) -> String)

    val ALL: List<Gadget> = listOf(
        Gadget("anywhere_door", "Anywhere Door (どこでもドア)") { ctx, p ->
            val target = p["target"] ?: return@Gadget "ERROR: missing 'target'"
            try {
                val intent = if (target.startsWith("http")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(target))
                } else {
                    ctx.packageManager.getLaunchIntentForPackage(target)
                        ?: return@Gadget "ERROR: app '$target' not installed"
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "OK: opened $target"
            } catch (t: Throwable) { "ERROR: ${t.message}" }
        },
        Gadget("translation_konjac", "Translation Konjac (ほんやくコンニャク)") { _, p ->
            val text = p["text"] ?: return@Gadget "ERROR: missing 'text'"
            "OK: translation queued for: ${text.take(50)} (handled by AI brain)"
        },
        Gadget("memory_bread", "Memory Bread (アンキパン)") { _, p ->
            val fact = p["fact"] ?: return@Gadget "ERROR: missing 'fact'"
            com.shslab.leo.memory.MemoryManager.storeFact(fact)
            "OK: memorized — '${fact.take(60)}'"
        },
        Gadget("time_machine", "Time Machine (タイムマシン)") { _, p ->
            val q = p["query"] ?: ""
            val hits = com.shslab.leo.memory.MemoryManager.recall(q, topN = 5)
            if (hits.isEmpty()) "No matching memories." else hits.joinToString("\n---\n")
        },
        Gadget("whatsapp_send", "WhatsApp Messenger") { ctx, p ->
            val phone = p["phone"]?.replace("+", "")?.replace(" ", "") ?: return@Gadget "ERROR: missing 'phone'"
            val msg   = p["message"] ?: return@Gadget "ERROR: missing 'message'"
            try {
                val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(msg)}")
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "OK: WhatsApp draft opened to +$phone — accessibility will tap send"
            } catch (t: Throwable) { "ERROR: ${t.message}" }
        },
        Gadget("sms_send", "SMS Send") { ctx, p ->
            val phone = p["phone"] ?: return@Gadget "ERROR: missing 'phone'"
            val msg   = p["message"] ?: return@Gadget "ERROR: missing 'message'"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$phone"))
                intent.putExtra("sms_body", msg)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "OK: SMS draft opened"
            } catch (t: Throwable) { "ERROR: ${t.message}" }
        },
        Gadget("dial_call", "Phone Call") { ctx, p ->
            val phone = p["phone"] ?: return@Gadget "ERROR: missing 'phone'"
            try {
                ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "OK: dialer opened for $phone"
            } catch (t: Throwable) { "ERROR: ${t.message}" }
        }
    )

    fun invoke(ctx: Context, key: String, params: Map<String, String>): String {
        val g = ALL.firstOrNull { it.key == key }
            ?: return "ERROR: unknown gadget '$key'. Available: ${ALL.joinToString { it.key }}"
        Logger.system("[Gadget] ${g.name} ← $params")
        return g.handler(ctx, params)
    }

    fun describe(): String = ALL.joinToString("\n") { "- ${it.key}: ${it.name}" }
}
