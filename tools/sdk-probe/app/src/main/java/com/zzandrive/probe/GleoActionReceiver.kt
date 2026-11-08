package com.zzandrive.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/** Receives Gleo Actions declared with component "service" and answers with a Gleo Callback. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("ACTION_PARAM") ?: "{}"
        Log.i(GleoCallback.TAG, "GLEO  action received (broadcast): ${intent.action} params=$raw")
        GleoCallback.send(
            context,
            functionName = intent.action ?: "UNKNOWN",
            status = "success",
            message = "Probe received the request and replied with a dummy result.",
            data = """[{"probe":"ok","received":${JSONObject.quote(raw)}}]"""
        )
    }
}

/** Sends the Gleo Callback exactly as described in the Gleo Interface docs. */
object GleoCallback {
    const val TAG = "ZzanProbe"
    private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    fun send(context: Context, functionName: String, status: String, message: String, data: String? = null) {
        val json = JSONObject().apply {
            put("functionName", functionName)
            put("status", status)
            put("message", message)
            data?.let { put("data", it) }
        }
        Intent(ACTION_RESPONSE).apply {
            putExtra("packageName", context.packageName)
            putExtra("response", json.toString())
            context.sendBroadcast(this)
        }
        Log.i(TAG, "GLEO  callback sent: $json")
    }
}
