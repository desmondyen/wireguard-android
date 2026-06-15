package com.wireguard.android.custom

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireguard.android.Application
import com.wireguard.android.R
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
        setContentView(R.layout.activity_activation) // 需准备带有 et_code 框与 btn_active 按钮的 XML

        guardDaemon = VpnGuardDaemon(applicationContext)

        val etCode = findViewById<EditText>(R.id.et_code)
        val btnActive = findViewById<Button>(R.id.btn_active)

        btnActive.setOnClickListener {
            val code = etCode.text.toString().trim()
            handleVpnActivation(code)
        }
    }

    private fun handleVpnActivation(activationCode: String) {
        if (activationCode.isBlank()) {
            Toast.makeText(this, "请输入激活码", Toast.LENGTH_SHORT).show()
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
                    showToast("激活验证失败，请检查激活码是否有效")
                }
            } catch (e: Exception) {
                showToast("连接边缘网关失败: ${e.message}")
            }
        }
    }

    private fun importAndConnect(configText: String, code: String) {
        try {
            val bufferedReader = BufferedReader(StringReader(configText))
            val config = Config.parse(bufferedReader)
            val tunnelManager = Application.getTunnelManager()

            // 在系统全局注入一根默认隧道
            tunnelManager.create("SecureTunnel", config).whenComplete { tunnel, throwable ->
                if (throwable == null) {
                    Toast.makeText(this, "🚀 激活成功！正在建立安全网络通道", Toast.LENGTH_SHORT).show()
                    
                    // 1. 全自动执行拉起开关逻辑
                    tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                    
                    // 2. 同步开启 30 秒存活轮询定时器
                    guardDaemon.startGuard(code)
                    
                    finish()
                } else {
                    Toast.makeText(this, "隧道构建失败: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "配置文件格式损坏", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@ActivationActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
