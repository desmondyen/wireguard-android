package com.wireguard.android.custom

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
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

    // 启动心跳守护
    fun startGuard(activationCode: String) {
        guardJob?.cancel() 
        guardJob = scope.launch {
            while (isActive) {
                delay(30000) // 每30秒轻量级检查一次
                
                val tunnelManager = Application.getTunnelManager()
                val tunnel = tunnelManager.tunnels.find { it.name == "SecureTunnel" }
                
                if (tunnel != null) {
                    val state = tunnelManager.getTunnelState(tunnel)
                    // 仅当用户正在开启 VPN 时，才进行服务器强管控校验
                    if (state == Tunnel.State.UP) {
                        checkStatusWithServer(activationCode, tunnel)
                    }
                }
            }
        }
    }

    private suspend fun checkStatusWithServer(activationCode: String, tunnel: Tunnel) {
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
            e.printStackTrace() // 允许网络波动波动，不误杀物理连接
        }
    }

    private fun handleVpnExpired(tunnel: Tunnel) {
        val tunnelManager = Application.getTunnelManager()
        
        // 1. 瞬间强关闭手机端的物理隧道（灭活状态）
        tunnelManager.setTunnelState(tunnel, Tunnel.State.DOWN)
        
        // 2. 彻底从底层安全数据库中抹除该隧道（防止用户自己去重新打开）
        tunnelManager.delete(tunnel)

        Toast.makeText(context, "⚠️ 您的加密网络授权已失效或被管理员注销！", Toast.LENGTH_LONG).show()

        // 3. 强制关闭所有应用栈，将用户拦截回激活界面
        val intent = Intent(context, ActivationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("reason", "expired")
        }
        context.startActivity(intent)
        stopGuard()
    }

    fun stopGuard() {
        guardJob?.cancel()
    }
}
