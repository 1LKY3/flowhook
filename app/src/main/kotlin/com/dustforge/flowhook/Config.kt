package com.dustforge.flowhook

import android.content.Context

object Config {
    private const val PREFS = "flowhook_config"
    const val DEFAULT_SERVER = "wss://flowhook.dustforge.com/agent"

    fun getToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("agent_token", null)

    fun setToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("agent_token", token).apply()
    }

    fun getServerUrl(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server_url", DEFAULT_SERVER)!!

    fun setServerUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server_url", url).apply()
    }
}
