package com.wireguard.android.activity

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class VpnGuardDaemon(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
        
    private var guardJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startGuard(activationCode: String) {
        guardJob?.cancel() 
        guardJob = scope.launch {
            while (isActive) {
                delay(30000) // 30秒循环一次
                
                val tunnelManager = Application.getTunnelManager()
                // 🌟 使用异步协程安全的 getTunnels() 提取公开的 ObservableTunnel 列表
                val tunnelsList = tunnelManager.getTunnels()
                val tunnel = tunnelsList.find { it.name == "SecureTunnel" }
                
                if (tunnel != null) {
                    val state = tunnelManager.getTunnelState(tunnel)
                    if (state == Tunnel.State.UP) {
                        checkStatusWithServer(activationCode, tunnel)
                    }
                }
            }
        }
    }

    private suspend fun checkStatusWithServer(activationCode: String, tunnel: ObservableTunnel) {
        try {
            val jsonObject = JsonObject().apply {
                addProperty("activation_code", activationCode)
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonObject.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url("http://wx.8288.uk/api/v1/check_status")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseStr = response.body?.string()

            if (!response.isSuccessful || responseStr == null) {
                if (response.code == 401) {
                    withContext(Dispatchers.Main) { handleVpnExpired(tunnel) }
                }
            } else {
                val rootJson = JsonParser.parseString(responseStr).asJsonObject
                if (rootJson.get("status").asString == "expired") {
                    withContext(Dispatchers.Main) { handleVpnExpired(tunnel) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace() 
        }
    }

    private fun handleVpnExpired(tunnel: ObservableTunnel) {
        // 🌟 将所有涉及底层管理器的挂起操作包裹在主线程协程中异步调度，彻底解决混淆类型错位
        scope.launch(Dispatchers.Main) {
            try {
                val tunnelManager = Application.getTunnelManager()
                tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN)
                tunnelManager.delete(tunnel)

                Toast.makeText(context, "⚠️ 您的加密网络授权已失效或被管理员注销！", Toast.LENGTH_LONG).show()

                val intent = Intent(context, ActivationActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("reason", "expired")
                }
                context.startActivity(intent)
                stopGuard()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopGuard() {
        guardJob?.cancel()
    }
}
