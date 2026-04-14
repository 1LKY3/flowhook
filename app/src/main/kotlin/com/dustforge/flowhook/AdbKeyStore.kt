package com.dustforge.flowhook

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Generates and persists an RSA keypair used by this Flowhook install to authenticate
 * with the on-device adbd. First-time connection will prompt the user for "Allow debugging?"
 * with our key's fingerprint — tap Allow once, key is trusted forever (until phone factory reset).
 */
object AdbKeyStore {
    private const val TAG = "FlowhookAdbKey"

    fun keyPair(ctx: Context): AdbKeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val privFile = File(dir, "adbkey")
        val pubFile  = File(dir, "adbkey.pub")

        if (!privFile.exists() || !pubFile.exists()) {
            Log.i(TAG, "generating new ADB RSA keypair")
            AdbKeyPair.generate(privFile, pubFile)
        }
        return AdbKeyPair.read(privFile, pubFile)
    }

    fun publicKey(ctx: Context): String {
        val pubFile = File(File(ctx.filesDir, "adb"), "adbkey.pub")
        return if (pubFile.exists()) pubFile.readText().trim() else ""
    }
}
