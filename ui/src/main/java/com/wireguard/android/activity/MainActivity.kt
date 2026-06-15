package com.wireguard.android.activity

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
    
    private lateinit var statusFeedbackTv: TextView
    private lateinit var actionBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 让官方原版的底层布局正常渲染加载，确保系统初始化不闪退
        val layoutId = resources.getIdentifier("main_activity", "layout", packageName)
        if (layoutId != 0) setContentView(layoutId)

        // 2. 瞬间切入全屏高强商业规管锁定弹窗
        showActivationLockDialog()
    }

    private fun showActivationLockDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
        // 🌟 防遮挡设计：最外层包裹滚动视图，键盘弹起时自动上推，确保按钮绝不丢失
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(android.graphics.Color.parseColor("#0F172A"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(80, 60, 80, 60)
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
            setPadding(0, 0, 0, 30)
        }

        // 屏幕中央的可视化状态回显器
        statusFeedbackTv = TextView(this).apply {
            text = ""
            setTextColor(android.graphics.Color.parseColor("#EF4444"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 30)
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
        container.addView(statusFeedbackTv)
        container.addView(etCode)
        
        val space = View(this).apply { minimumHeight = 60 }
        container.addView(space)
        container.addView(actionBtn)

        scrollView.addView(container)
        builder.setView(scrollView)
        builder.setCancelable(false) // 锁死强制拦截

        activationDialog = builder.create()
        activationDialog?.show()

        actionBtn.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length < 5) {
                statusFeedbackTv.text = "❌ 激活码格式不正确"
            } else {
                // 点击时强制收起键盘，防止遮挡回显
                try {
                    val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(etCode.windowToken, 0)
                } catch (e: Exception) {}

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

                // 🌟 使用合规的安全 https 加密链路请求
                val request = Request.Builder()
                    .url("https://wx.8288.uk/api/v1/activate")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseStr = response.body?.string()

                withContext(Dispatchers.Main) {
                    actionBtn.isEnabled = true
                    actionBtn.text = "🚀 一键打通高强安防隧道"

                    if (response.isSuccessful && responseStr != null) {
                        val rootJson = JsonParser.parseString(responseStr).asJsonObject
                        if (rootJson.get("code").asInt == 200) {
                            val dataObj = rootJson.getAsJsonObject("data")
                            val wgConfigText = dataObj.get("config").asString
                            
                            // 校验成功，执行本地装载
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

                // 🌟 核心去重清理：防止抛出“SecureTunnel已存在”的安卓底层冲突断言
                val existingTunnels = tunnelManager.getTunnels()
                val oldTunnel = existingTunnels.find { it.name == "SecureTunnel" }
                if (oldTunnel != null) {
                    try {
                        Application.getBackend().setState(oldTunnel, Tunnel.State.DOWN, null)
                        tunnelManager.delete(oldTunnel)
                    } catch (e: Exception) {}
                }

                // 注入全新下发的网关配置
                val tunnel = tunnelManager.create("SecureTunnel", config)
                // 强制合上手机物理 VPN 开关
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                // 启动 30 秒断网守护心跳
                startDaemonPoll(code)
                
                // 完美关闭拦截锁屏，允许用户使用
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
                kotlinx.coroutines.delay(30000) // 30秒无缝强管控
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
                                // 💥 热熔断：一键切断物理流，强行删除凭证，重新全屏锁定！
                                Application.getBackend().setState(target, Tunnel.State.DOWN, null)
                                tm.delete(target)
                                showActivationLockDialog()
                                statusFeedbackTv.text = "⚠️ 您的授权已到期或被管理员注销！"
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }
}
