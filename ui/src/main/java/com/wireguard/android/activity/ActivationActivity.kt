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
        
        // 🌟 已修复：直接调用正统内置系统的核心资源 ID 指针，100% 杜绝真机上由于 Theme 引发的闪退
        val layoutId = resources.getIdentifier("activity_activation", "layout", packageName)
        if (layoutId == 0) {
            Toast.makeText(this, "资源加载异常", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(layoutId)

        guardDaemon = VpnGuardDaemon(applicationContext)

        val etCodeId = resources.getIdentifier("et_code", "id", packageName)
        val btnActiveId = resources.getIdentifier("btn_active", "id", packageName)

        val etCode = findViewById<EditText>(etCodeId)
        val btnActive = findViewById<Button>(btnActiveId)

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
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val bufferedReader = BufferedReader(StringReader(configText))
                val config = Config.parse(bufferedReader)
                val tunnelManager = Application.getTunnelManager()

                val tunnel = tunnelManager.create("SecureTunnel", config)
                Toast.makeText(this@ActivationActivity, "授权通过！正在接通加密网络...", Toast.LENGTH_SHORT).show()
                
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)
                
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
