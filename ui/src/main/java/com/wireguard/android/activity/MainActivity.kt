package com.wireguard.android.activity

import android.app.Activity
import android.content.Intent
import android.net.VpnService
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
    
    // 缓存待注入的数据，用于在拿到系统 VPN 权限后继续执行
    private var cachedConfigText: String = ""
    private var cachedCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layoutId = resources.getIdentifier("main_activity", "layout", packageName)
        if (layoutId != 0) setContentView(layoutId)

        // 稳稳拉起强制激活拦截大弹窗
        showActivationLockDialog()
    }

    private fun showActivationLockDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        
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
        builder.setCancelable(false)

        activationDialog = builder.create()
        activationDialog?.show()

        actionBtn.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length < 5) {
                statusFeedbackTv.text = "❌ 激活码格式不正确"
            } else {
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
                            
                            // 缓存通过验证的数据
                            cachedConfigText = wgConfigText
                            cachedCode = activationCode
                            
                            // 🌟 核心修改点 1：检查并主动申请系统的 VPN 物理授权拦截
                            checkVpnPermissionAndConnect()
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

    private fun checkVpnPermissionAndConnect() {
        // 🌟 核心修改点 2：拉起 Android 系统原生的 VpnService 权限握手弹窗特赦令
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 说明手机还没有给这个 App 授权过 VPN 权限，必须先拉起系统原生弹窗
            statusFeedbackTv.text = "💡 请在系统弹出的提示框中点击“允许/确定”以授信加密网络"
            startActivityForResult(intent, 518)
        } else {
            // 已经授权过了，直接顺畅连接
            proceedFinalTunnelInjection()
        }
    }

    // 🌟 核心修改点 3：专门捕获用户点击系统原生“确定”或“允许”按钮后的回调结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 518) {
            if (resultCode == Activity.RESULT_OK) {
                // 用户点击了允许，完美拿到物理特权，直接冲刺注入
                statusFeedbackTv.text = "🟢 权限同步成功，正在打通网关..."
                proceedFinalTunnelInjection()
            } else {
                // 用户拒绝了
                statusFeedbackTv.text = "❌ 授权失败：必须允许 VPN 权限才能正常打通加密网络"
            }
        }
    }

    private fun proceedFinalTunnelInjection() {
        lifecycleScope.launch {
            try {
                val bufferedReader = BufferedReader(StringReader(cachedConfigText))
                val config = Config.parse(bufferedReader)
                val tunnelManager = Application.getTunnelManager()

                val existingTunnels = tunnelManager.getTunnels()
                val oldTunnel = existingTunnels.find { it.name == "SecureTunnel" }
                if (oldTunnel != null) {
                    try {
                        Application.getBackend().setState(oldTunnel, Tunnel.State.DOWN, null)
                        tunnelManager.delete(oldTunnel)
                    } catch (e: Exception) {}
                }

                // 此时有了完整的 VpnService 物理授信，创建绝对畅通无阻，再也不会报 null！
                val tunnel = tunnelManager.create("SecureTunnel", config)
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                startDaemonPoll(cachedCode)
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
