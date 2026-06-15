package com.wireguard.android.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.StringReader

class ActivationActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private lateinit var guardDaemon: VpnGuardDaemon

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🌟 动态构建极简纯黑 UI 容器，彻底免去配置各种 XML 布局资源导致的报错
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
            setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
        }
        val etCode = EditText(this).apply {
            hint = "请输入12位安全激活码"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
        }
        val btnActive = Button(this).apply {
            text = "🚀 一键接通加密网关"
            setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            setTextColor(android.graphics.Color.WHITE)
        }
        layout.addView(etCode)
        layout.addView(btnActive)
        setContentView(layout)

        guardDaemon = VpnGuardDaemon(applicationContext)

        btnActive.setOnClickListener {
            val code = etCode.text.toString().trim()
            handleVpnActivation(code)
        }
    }

    private fun handleVpnActivation(activationCode: String) {
        if (activationCode.isBlank()) {
            Toast.makeText(this, "请输入有效激活码", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonObject = JsonObject().apply {
                    addProperty("activation_code", activationCode)
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonObject.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("http://wx.8288.uk/api/v1/activate")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseStr = response.body?.string()

                if (response.isSuccessful && responseStr != null) {
                    val rootJson = JsonParser.parseString(responseStr).asJsonObject
                    if (rootJson.get("code").asInt == 200) {
                        val dataObj = rootJson.getAsJsonObject("data")
                        val wgConfigText = dataObj.get("config").asString

                        withContext(Dispatchers.Main) {
                            importAndConnect(wgConfigText, activationCode)
                        }
                    } else {
                        showToast(rootJson.get("message").asString)
                    }
                } else {
                    showToast("激活验证失败，请检查激活码状态")
                }
            } catch (e: Exception) {
                showToast("连接边缘网关失败: ${e.message}")
            }
        }
    }

    private fun importAndConnect(configText: String, code: String) {
        // 🌟 开启主线程协程作用域，解决直接调用 suspend 方法导致的编译中断
        lifecycleScope.launch {
            try {
                val bufferedReader = BufferedReader(StringReader(configText))
                val config = Config.parse(bufferedReader)
                val tunnelManager = Application.getTunnelManager()

                // 异步注入并直接保存隧道
                val tunnel = tunnelManager.create("SecureTunnel", config)
                Toast.makeText(this@ActivationActivity, "授权通过！正在接通加密网络...", Toast.LENGTH_SHORT).show()
                
                // 开启物理 VPN 开关
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                
                // 激活心跳常驻
                guardDaemon.startGuard(code)
                
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@ActivationActivity, "构建安全隧道失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ActivationActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
