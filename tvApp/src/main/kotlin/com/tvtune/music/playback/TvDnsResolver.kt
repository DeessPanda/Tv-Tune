package com.tvtune.music.playback

import android.util.Log
import okhttp3.Dns
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

object TvDnsResolver : Dns {
    private const val TAG = "TvDnsResolver"

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                return systemAddresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DNS lookup failed for $hostname: ${e.message}. Attempting DNS over HTTPS fallback...")
        }

        // Fallback: Resolve via Google Public DNS over HTTPS (DoH)
        val dohIps = resolveViaGoogleDns(hostname)
        if (dohIps.isNotEmpty()) {
            return dohIps
        }

        // Second fallback: Resolve via Cloudflare DNS over HTTPS
        val cloudflareIps = resolveViaCloudflareDns(hostname)
        if (cloudflareIps.isNotEmpty()) {
            return cloudflareIps
        }

        throw UnknownHostException("Unable to resolve host '$hostname' via system or fallback DNS")
    }

    private fun resolveViaGoogleDns(hostname: String): List<InetAddress> {
        return try {
            val endpoint = "https://dns.google/resolve?name=$hostname&type=A"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(jsonStr)
            val answerArray = json.optJSONArray("Answer") ?: return emptyList()

            val addresses = mutableListOf<InetAddress>()
            for (i in 0 until answerArray.length()) {
                val item = answerArray.getJSONObject(i)
                val type = item.optInt("type")
                val data = item.optString("data")
                if (type == 1 && data.isNotBlank()) { // Type 1 = A Record (IPv4)
                    try {
                        addresses.add(InetAddress.getByName(data))
                    } catch (_: Exception) {}
                }
            }
            if (addresses.isNotEmpty()) {
                Log.i(TAG, "Google DoH resolved $hostname -> ${addresses.map { it.hostAddress }}")
            }
            addresses
        } catch (e: Exception) {
            Log.w(TAG, "Google DoH failed for $hostname: ${e.message}")
            emptyList()
        }
    }

    private fun resolveViaCloudflareDns(hostname: String): List<InetAddress> {
        return try {
            val endpoint = "https://cloudflare-dns.com/dns-query?name=$hostname&type=A"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/dns-json")

            val jsonStr = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(jsonStr)
            val answerArray = json.optJSONArray("Answer") ?: return emptyList()

            val addresses = mutableListOf<InetAddress>()
            for (i in 0 until answerArray.length()) {
                val item = answerArray.getJSONObject(i)
                val type = item.optInt("type")
                val data = item.optString("data")
                if (type == 1 && data.isNotBlank()) {
                    try {
                        addresses.add(InetAddress.getByName(data))
                    } catch (_: Exception) {}
                }
            }
            if (addresses.isNotEmpty()) {
                Log.i(TAG, "Cloudflare DoH resolved $hostname -> ${addresses.map { it.hostAddress }}")
            }
            addresses
        } catch (e: Exception) {
            Log.w(TAG, "Cloudflare DoH failed for $hostname: ${e.message}")
            emptyList()
        }
    }
}
