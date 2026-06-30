package com.wireguard.android.activity

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
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
            text = "🛰️ 核心加白通道"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            paint.isFakeBoldText = true
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }

        val subTv = TextView(this).apply {
            text = "此设备网络处于加白状态\n请粘贴管理员发给您的 12 位专属授权密钥"
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
            hint = "请输入12位安全密钥 (Token)"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
            setPadding(30, 40, 30, 40)
        }

        val btnCopyCode = Button(this).apply {
            text = "📋 复制当前密钥"
            setBackgroundColor(android.graphics.Color.parseColor("#475569"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
        }

        actionBtn = Button(this).apply {
            text = "🚀 登入加白通道"
            setBackgroundColor(android.graphics.Color.parseColor("#2563EB"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            paint.isFakeBoldText = true
        }

        container.addView(titleTv)
        container.addView(subTv)
        container.addView(statusFeedbackTv)
        container.addView(etCode)
        
        val spaceBtn = View(this).apply { minimumHeight = 30 }
        container.addView(spaceBtn)
        container.addView(btnCopyCode)
        
        val space = View(this).apply { minimumHeight = 30 }
        container.addView(space)
        container.addView(actionBtn)

        scrollView.addView(container)
        builder.setView(scrollView)
        builder.setCancelable(false)

        activationDialog = builder.create()
        activationDialog?.show()

        btnCopyCode.setOnClickListener {
            val codeToCopy = etCode.text.toString().trim()
            if (codeToCopy.isBlank()) {
                Toast.makeText(this, "输入框为空，无安全密钥可复制", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Activation Code", codeToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "✨ 密钥已成功复制到剪贴板！", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        actionBtn.setOnClickListener {
            val code = etCode.text.toString().trim()
            if (code.length < 5) {
                statusFeedbackTv.text = "❌ 密钥格式不正确"
            } else {
                try {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(etCode.windowToken, 0)
                } catch (e: Exception) {}

                actionBtn.isEnabled = false
                actionBtn.text = "⏳ 正在拼命连接边缘网关..."
                statusFeedbackTv.text = "🔄 正在建立加白通道链接..."
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
                    actionBtn.text = "🚀 登入加白通道"

                    if (response.isSuccessful && responseStr != null) {
                        val rootJson = JsonParser.parseString(responseStr).asJsonObject
                        if (rootJson.get("code").asInt == 200) {
                            val dataObj = rootJson.getAsJsonObject("data")
                            val wgConfigText = dataObj.get("config").asString
                            
                            cachedConfigText = wgConfigText
                            cachedCode = activationCode
                            
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
                    actionBtn.text = "🚀 登入加白通道"
                    statusFeedbackTv.text = "底层网络阻断报错: ${e.message}"
                }
            }
        }
    }

    private fun checkVpnPermissionAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            statusFeedbackTv.text = "💡 请在系统弹出的提示框中点击“允许/确定”以授信加密网络"
            startActivityForResult(intent, 518)
        } else {
            proceedFinalTunnelInjection()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 518) {
            if (resultCode == Activity.RESULT_OK) {
                statusFeedbackTv.text = "🟢 权限同步成功，正在打通网关..."
                proceedFinalTunnelInjection()
            } else {
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

                // 物理重名去重清理
                val existingTunnels = tunnelManager.getTunnels()
                val oldTunnel = existingTunnels.find { it.name == "SecureTunnel" }
                if (oldTunnel != null) {
                    try {
                        Application.getBackend().setState(oldTunnel, Tunnel.State.DOWN, null)
                        tunnelManager.delete(oldTunnel)
                    } catch (e: Exception) {}
                }

                val tunnel = tunnelManager.create("SecureTunnel", config)
                tunnelManager.setTunnelState(tunnel, Tunnel.State.UP)

                // 🌟 核心改动：调用系统级持久化守护任务
                startPersistentDaemon(cachedCode)
                activationDialog?.dismiss()
            } catch (e: Exception) {
                statusFeedbackTv.text = "构建本地隧道失败: ${e.message}"
            }
        }
    }

    /**
     * 将过期轮询移交给系统 WorkManager 托管，应用退出、进程被杀依然能在后台定时触发
     */
    private fun startPersistentDaemon(activationCode: String) {
        val inputData = Data.Builder()
            .putString("activation_code", activationCode)
            .build()

        // 建立周期性后台任务（Android 规定 Periodic 最短周期为 15 分钟）
        val guardRequest = PeriodicWorkRequestBuilder<VpnGuardWorker>(15, TimeUnit.MINUTES)
            .setInputData(inputData)
            .build()

        // 使用 KEEP 策略，保证任务全局唯一，多次调用不会重复创建
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "VpnTunnelGuardLock",
            ExistingPeriodicWorkPolicy.KEEP,
            guardRequest
        )
    }
}

/**
 * 独立的系统后台守护 Worker，脱离 Activity 生命周期运行
 */
class VpnGuardWorker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {

    private val httpClient = OkHttpClient()

    override fun doWork(): Result {
        val activationCode = inputData.getString("activation_code") ?: return Result.failure()

        try {
            val tm = Application.getTunnelManager()
            val target = tm.getTunnels().find { it.name == "SecureTunnel" }

            // 如果本地隧道已经被手动删除或不存在，本守护任务直接结束
            if (target == null) {
                return Result.success()
            }

            // 同步向服务端校验状态
            val jsonObject = JsonObject().apply { addProperty("activation_code", activationCode) }
            val requestBody = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url("https://wx.8288.uk/api/v1/check_status")
                .post(requestBody)
                .build()
                
            val response = httpClient.newCall(request).execute()
            val responseStr = response.body?.string()

            if (!response.isSuccessful || responseStr == null || 
                JsonParser.parseString(responseStr).asJsonObject.get("status").asString == "expired") {
                
                // 判定到期：强行熔断并物理清理底层 VPN
                runBlocking {
                    try {
                        Application.getBackend().setState(target, Tunnel.State.DOWN, null)
                        tm.delete(target)
                    } catch (e: Exception) {
                        tm.delete(target)
                    }
                }
                // 执行成功后不再重试，彻底注销此定时器
                return Result.success()
            }

            // 未过期，等待下一个周期继续检查
            return Result.retry()
        } catch (e: Exception) {
            e.printStackTrace()
            // 因临时网络波动导致请求失败时，允许系统稍后进行重试
            return Result.retry()
        }
    }
}
