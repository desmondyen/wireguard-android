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
    
    // 状态反馈文本指针
    private lateinit var statusFeedbackTv: TextView
    private lateinit var actionBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layoutId = resources.getIdentifier("main_activity", "layout", packageName)
        if (layoutId != 0) setContentView(layoutId)

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
            setPadding(0, 0, 0, 40)
        }

        // 🌟 可视化反馈：新增一个专门用来看状态或报错的文本框，文字默认为空
        statusFeedbackTv = TextView(this).apply {
            text = ""
            setTextColor(android.graphics.Color.parseColor("#EF4444")) // 红色报错提示
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
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

        actionBtn = Button(this).apply {
            text = "🚀 一键打通高强安防隧道"
            setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }

        container.addView(titleTv)
        container.addView(subTv)
        container.addView(statusFeedbackTv) // 挂载反馈栏
        container.addView(etCode)
        
        val space = View(this).apply { minimumHeight = 40 }
        container.addView(space)
        container.addView(actionBtn)

        builder.setView(container)
        builder.setCancelable(false)

        activationDialog = builder.create()
        activationDialog?.show()

        actionBtn.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length < 5) {
                statusFeedbackTv.text = "❌ 激活码格式不正确"
            } else {
                // 按钮立刻变灰，并显示正在连接，给用户绝对明显的视觉反馈！
                actionBtn.isEnabled = false
                actionBtn.text = "⏳ 正在拼命连接边缘网关..."
                statusFeedbackTv.text = "🔄 正在向 https://wx.8288.uk 握手寻址..."
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
                    .url("https://wx.8288.uk/api/v1/activate")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseStr = response.body?.string()

                withContext(Dispatchers.Main) {
                    // 恢复按钮点击状态
                    actionBtn.isEnabled = true
                    actionBtn.text = "🚀 一键打通高强安防隧道"

                    if (response.isSuccessful && responseStr != null) {
                        val rootJson = JsonParser.parseString(responseStr).asJsonObject
                        if (rootJson.get("code").asInt == 200) {
                            val dataObj = rootJson.getAsJsonObject("data")
                            val wgConfigText = dataObj.get("config").asString
                            injectTunnelAndUnlock(wgConfigText, activationCode)
                        } else {
                            statusFeedbackTv.text = "服务端拒绝: " + rootJson.get("message").asString
                        }
                    } else {
                        statusFeedbackTv.text = "网关拒绝，HTTP 状态码: ${response.code}"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    actionBtn.isEnabled = true
                    actionBtn.text = "🚀 一键打通高强安防隧道"
                    // 🌟 终极核心：如果发生了任何证书链异常、网络掐断，直接在全屏屏幕中央红字打印出来！
                    statusFeedbackTv.text = "底层网络阻断报错: ${e.message}"
                }
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

                startDaemonPoll(code)
                activationDialog?.dismiss()
            } catch (e: Exception) {
                statusFeedbackTv.text = "构建本地隧道失败: ${e.message}"
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
                        
                        val request = Request.Builder().url("https://wx.8288.uk/api/v1/check_status").post(requestBody).build()
                        val response = httpClient.newCall(request).execute()
                        val responseStr = response.body?.string()

                        if (!response.isSuccessful || responseStr == null || JsonParser.parseString(responseStr).asJsonObject.get("status").asString == "expired") {
                            withContext(Dispatchers.Main) {
                                Application.getBackend().setState(target, Tunnel.State.DOWN, null)
                                tm.delete(target)
                                showActivationLockDialog()
                                statusFeedbackTv.text = "⚠️ 您的授权已到期或被注销！"
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}
