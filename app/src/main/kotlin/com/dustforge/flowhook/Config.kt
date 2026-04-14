package com.dustforge.flowhook

import android.content.Context

object Config {
    private const val PREFS = "flowhook_config"
    const val DEFAULT_SERVER = "wss://flowhook.dustforge.com/agent"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getToken(ctx: Context): String? = prefs(ctx).getString("agent_token", null)
    fun setToken(ctx: Context, token: String) = prefs(ctx).edit().putString("agent_token", token).apply()

    fun getServerUrl(ctx: Context): String = prefs(ctx).getString("server_url", DEFAULT_SERVER)!!
    fun setServerUrl(ctx: Context, url: String) = prefs(ctx).edit().putString("server_url", url).apply()

    /** Left toggle: full services (WS, WakeLock, etc). Default on. */
    fun getServicesEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("services_enabled", true)
    fun setServicesEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("services_enabled", v).apply()

    /** Right toggle: caller-observed ADB bridge desired state. Default on. */
    fun getAdbBridgeEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("adb_bridge_enabled", true)
    fun setAdbBridgeEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("adb_bridge_enabled", v).apply()
}
