package com.bayt.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var codeInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView
    private lateinit var localBtn: Button
    private lateinit var localText: TextView

    private val prefs by lazy { getSharedPreferences("bayt", Context.MODE_PRIVATE) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int = 16, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(r).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F172A")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(36), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
            addView(root)
        }

        root.addView(TextView(this).apply {
            text = "📺"; textSize = 64f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "عرض الشاشة على الـ iPad"; textSize = 24f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4))
            paint.isFakeBoldText = true
        })
        root.addView(TextView(this).apply {
            text = "اكتب نفس رمز العيلة اللي على موقع المكالمات، واضغط ابدأ. الـ iPad هيرن، ولما يردوا هيشوفوا شاشتك ويسمعوك."
            textSize = 15f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        })

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 15f; setTextColor(Color.parseColor("#F1F5F9"))
            setPadding(0, dp(18), 0, dp(6))
        }
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint; textSize = 18f
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#64748B"))
            background = rounded(Color.parseColor("#0B1222"), 12, Color.parseColor("#334155"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = true
        }

        root.addView(label("رمز العيلة"))
        codeInput = field("مثال: ipad16").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            textDirection = View.TEXT_DIRECTION_LTR
            setText(prefs.getString("code", "ipad16"))
        }
        root.addView(codeInput, LinearLayout.LayoutParams(-1, -2))
        root.addView(label("اسمك (هيظهر على الـ iPad)"))
        nameInput = field("مثال: أحمد").apply { setText(prefs.getString("name", "حماده")) }
        root.addView(nameInput, LinearLayout.LayoutParams(-1, -2))

        startBtn = Button(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); isAllCaps = false
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { onStartStop() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })

        statusText = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rounded(Color.parseColor("#1E293B"), 14, Color.parseColor("#334155"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // ===== العرض المحلي بدون نت =====
        root.addView(TextView(this).apply {
            text = "— أو بدون إنترنت —"; textSize = 15f; setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER; setPadding(0, dp(26), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "لو الـ iPad جنبك: خلّي الجهازين على نفس الواي فاي، أو شغّل نقطة الاتصال (Hotspot) من موبايلك ووصّل بيها الـ iPad."
            textSize = 14f; setTextColor(Color.parseColor("#94A3B8")); gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f); setPadding(0, dp(6), 0, 0)
        })
        localBtn = Button(this).apply {
            textSize = 19f; setTextColor(Color.WHITE); isAllCaps = false
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { onLocal() }
        }
        root.addView(localBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        localText = TextView(this).apply {
            textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTextIsSelectable(true); setLineSpacing(0f, 1.3f)
            background = rounded(Color.parseColor("#1E293B"), 14, Color.parseColor("#334155"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.addView(localText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "• لازم صفحة المكالمات تكون مفتوحة على الـ iPad.\n• تقدر تقفل التطبيق ده وتفتح أي حاجة، والعرض هيفضل شغال.\n• للإيقاف: من الإشعار اضغط «إيقاف»، أو ارجع هنا."
            textSize = 14f; setTextColor(Color.parseColor("#94A3B8"))
            setLineSpacing(0f, 1.4f)
            background = rounded(Color.parseColor("#1E293B"), 12, Color.parseColor("#334155"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        ScreenService.listener = { msg, active -> showStatus(msg, active) }
        showStatus(ScreenService.status, ScreenService.running)
        LocalService.listener = { msg, active -> showLocal(msg, active) }
        showLocal(LocalService.status, LocalService.running)
    }

    override fun onPause() {
        super.onPause()
        ScreenService.listener = null
        LocalService.listener = null
    }

    private fun showLocal(msg: String, active: Boolean) {
        val on = active || LocalService.running
        localBtn.text = if (on) "⏹ إيقاف العرض المحلي" else "📡 عرض محلي بدون نت"
        localBtn.background = rounded(Color.parseColor(if (on) "#EF4444" else "#22C55E"), 14)
        if (msg.isEmpty()) localText.visibility = View.GONE
        else { localText.text = msg; localText.visibility = View.VISIBLE }
    }

    private fun onLocal() {
        if (LocalService.running) {
            startService(Intent(this, LocalService::class.java).setAction(LocalService.ACTION_STOP))
            return
        }
        if (ScreenService.running) { toast("أوقف العرض عبر الإنترنت الأول"); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
            return
        }
        askProjection(21)
    }

    private fun showStatus(msg: String, active: Boolean) {
        val on = active || ScreenService.running
        startBtn.text = if (on) "⏹ إيقاف العرض" else "▶ ابدأ عرض الشاشة"
        startBtn.background = rounded(Color.parseColor(if (on) "#EF4444" else "#0EA5E9"), 14)
        if (msg.isEmpty()) statusText.visibility = View.GONE
        else { statusText.text = msg; statusText.visibility = View.VISIBLE }
    }

    private fun onStartStop() {
        if (ScreenService.running) {
            startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
            return
        }
        if (LocalService.running) { toast("أوقف العرض المحلي الأول"); return }
        val code = codeInput.text.toString().lowercase().replace(Regex("[^a-z0-9]"), "")
        val name = nameInput.text.toString().trim().take(30)
        if (code.length < 6) { toast("اكتب رمز العيلة (6 حروف إنجليزية/أرقام على الأقل)"); return }
        prefs.edit().putString("code", code).putString("name", name).apply()

        val need = ArrayList<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 10) else askProjection(20)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                toast("بدون المايك: هيشوفوا الشاشة بس من غير صوتك")
            askProjection(20)
        }
        if (requestCode == 11) askProjection(21)
    }

    private fun askProjection(req: Int) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 && requestCode != 21) return
        if (resultCode != RESULT_OK || data == null) { toast("لازم توافق على تسجيل الشاشة"); return }
        if (requestCode == 21) {
            val li = Intent(this, LocalService::class.java).putExtra(LocalService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(li) else startService(li)
            showLocal("جارٍ التشغيل...", true)
            return
        }
        val i = Intent(this, ScreenService::class.java)
            .setAction(ScreenService.ACTION_START)
            .putExtra(ScreenService.EXTRA_DATA, data)
            .putExtra(ScreenService.EXTRA_CODE, prefs.getString("code", ""))
            .putExtra(ScreenService.EXTRA_NAME, prefs.getString("name", ""))
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        showStatus("جارٍ التحضير...", true)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
