package com.wireguard.android.activity

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private var guardJob: kotlinx.coroutines.Job? = null
    private var activationDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layoutId = resources.getIdentifier("main_activity", "layout", packageName)
        if (layoutId != 0) setContentView(layoutId)

        // 强力全屏拦截
        showActivationLockDialog()
    }

    private fun showActivationLockDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
            setPadding(80, 80, 80, 80)
        }

        val titleTv = TextView(this).apply {
            text = "🛰️ 核心高强加密网络网关"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val subTv = TextView(this).apply {
            text = "此设备网络处于强力规管管控状态\n请粘贴管理员发给您的 12 位专属授权激活码"
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }

        val etCode = EditText(this).apply {
            hint = "请输入12位安全激活码 (Token)"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
            setPadding(30, 40, 30, 40)
        }

        val btnActive = Button(this).apply {
            text = "🚀 一键打通高强安防隧道"
            setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }

        container.addView(titleTv)
        container.addView(subTv)
        container.addView(etCode)
        
        val space = View(this).apply { minimumHeight = 40 }
        container.addView(space)
        container.addView(btnActive)

        builder.setView(container)
        builder.setCancelable(false)

        activationDialog = builder.create()
        activationDialog?.show()

        btnActive.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length < 5) {
                Toast.makeText(this, "请输入合规的授权激活码", Toast.LENGTH_SHORT).show()
            } else {
                executeCloudActivation(code)
            }
        }
    }

    private fun executeCloudActivation(activationCode: String) {
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
                            injectTunnelAndUnlock(wgConfigText, activationCode)
                        }
                    } else {
                        showToast(rootJson.get("message").asString)
                    }
                } else {
                    showToast("激活授权无对应资产，请重新向管理员索要")
                }
            } catch (e: Exception) {
                showToast("连通网关失败，请检查手机网络: ${e.message}")
            }
        }
    }

    private fun injectTunnelAndUnlock(configText: String, code: String) {
        lifecycleScope.launch {
            try {
                val bufferedReader = BufferedReader(StringReader(configText))
                val config = Config.parse(bufferedReader)
                val tunnelManager = Application.getTunnelManager()

                val tunnel = tunnelManager.create("SecureTunnel", config)
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                Toast.makeText(this@MainActivity, "✨ 加密网络全线打通！安全守护已常驻", Toast.LENGTH_SHORT).show()

                startDaemonPoll(code)
                activationDialog?.dismiss()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "隧道装载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDaemonPoll(activationCode: String) {
        guardJob?.cancel()
        guardJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(30000)
                try {
                    val tm = Application.getTunnelManager()
                    val target = tm.getTunnels().find { it.name == "SecureTunnel" }
                    
                    if (target != null) {
                        val jsonObject = JsonObject().apply { addProperty("activation_code", activationCode) }
                        val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                        val request = Request.Builder().url("http://wx.8288.uk/api/v1/check_status").post(requestBody).build()
                        val response = httpClient.newCall(request).execute()
                        val responseStr = response.body?.string()

                        if (!response.isSuccessful || responseStr == null || JsonParser.parseString(responseStr).asJsonObject.get("status").asString == "expired") {
                            withContext(Dispatchers.Main) {
                                // 🌟 完美修复：直接传递 target 隧道实体，完美契合官方底层的 setState 安全规管限制
                                Application.getBackend().setState(target, Tunnel.State.DOWN, null)
                                tm.delete(target)
                                
                                Toast.makeText(applicationContext, "⚠️ 您的授权已到期或被注销！", Toast.LENGTH_LONG).show()
                                showActivationLockDialog()
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
