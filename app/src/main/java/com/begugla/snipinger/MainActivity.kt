package com.begugla.snipinger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.begugla.snipinger.databinding.ActivityMainBinding
import com.begugla.snipinger.databinding.ItemResultRowBinding
import com.begugla.snipinger.databinding.ItemResultSectionBinding
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    
    private val checker = WhitelistChecker()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var checkJob: Job? = null
    private var timerJob: Job? = null
    
    private var isPaused = false
    private var isCancelled = false

    private var currentMode = CheckMode.SINGLE
    private val whitelistUrl = "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/whitelist.txt"
    private var allResults: List<CheckResult> = emptyList()

    private var startTimeMillis: Long = 0
    private var totalItemsCount = 0
    private var processedItemsCount = 0

    private var uiTranslator: Translator? = null
    private var isTranslatorReady = false

    companion object {
        private const val LOW_PING_THRESHOLD = 150
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefs = getSharedPreferences("snipinger_prefs", Context.MODE_PRIVATE)
        
        // Manual check/apply for appcompat:1.7.1 stability
        val savedLang = prefs.getString("app_lang", "") ?: ""
        if (savedLang.isNotEmpty()) {
            val appLocales = LocaleListCompat.forLanguageTags(savedLang)
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(appLocales)
            }
        }
        
        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupUI()
        loadSavedState()
        
        val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { savedLang }
        if (currentLang.isNotEmpty()) initTranslator(currentLang)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.cardTerminal.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + 8.toPx()
            }
            binding.mainScrollView.updatePadding(bottom = systemBars.bottom + 220.toPx())
            insets
        }
    }

    private fun applyTheme() {
        val theme = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(theme)
    }

    private fun setupUI() {
        binding.btnThemeToggle.setOnClickListener {
            val current = AppCompatDelegate.getDefaultNightMode()
            val next = if (current == AppCompatDelegate.MODE_NIGHT_YES) 
                AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            
            prefs.edit().putInt("theme_mode", next).apply()
            binding.btnThemeToggle.animate().rotationBy(360f).setDuration(500).start()
            AppCompatDelegate.setDefaultNightMode(next)
        }

        binding.btnLangToggle.setOnClickListener {
            showLanguageDialog()
        }

        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            
            TransitionManager.beginDelayedTransition(binding.root, AutoTransition().apply {
                duration = 300
                interpolator = AnticipateOvershootInterpolator()
            })
            
            currentMode = when (checkedId) {
                R.id.btnModeWhitelist -> CheckMode.WHITELIST
                R.id.btnModeMassive -> CheckMode.MASSIVE
                else -> CheckMode.SINGLE
            }
            prefs.edit().putString("last_mode", currentMode.name.lowercase()).apply()
            
            clearResults()
            updateModeUI()
        }

        binding.btnToggleTerminal.setOnClickListener {
            val isCurrentlyVisible = binding.terminalScrollView.visibility == View.VISIBLE
            TransitionManager.beginDelayedTransition(binding.cardTerminal, AutoTransition())
            binding.terminalScrollView.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            binding.cardTerminal.layoutParams.height = if (isCurrentlyVisible) 60.toPx() else 200.toPx()
            binding.btnToggleTerminal.setImageResource(if (isCurrentlyVisible) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            binding.cardTerminal.requestLayout()
        }

        binding.btnCheck.setOnClickListener {
            if (isPaused) resumeCheck()
            else if (checkJob?.isActive == true) pauseCheck()
            else performCheck()
        }

        binding.btnPause.setOnClickListener { if (isPaused) resumeCheck() else pauseCheck() }
        binding.btnCancel.setOnClickListener { cancelCheck() }
        binding.btnCopyRaw.setOnClickListener { copyToClipboard(binding.tvRawOutput.text.toString()) }
        binding.btnCopyAll.setOnClickListener { copyToClipboard(buildMassiveResultText(allResults)) }

        binding.chkLowPing.setOnCheckedChangeListener { _, _ ->
            if (currentMode != CheckMode.SINGLE && allResults.isNotEmpty()) {
                displayListSummary(allResults, binding.etIp.text.toString().trim())
            }
        }
    }

    private fun initTranslator(targetLang: String) {
        val normalizedLang = targetLang.split("-")[0]
        if (normalizedLang == "en" || normalizedLang == "ru") {
            isTranslatorReady = false
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(normalizedLang)
            .build()
        uiTranslator = Translation.getClient(options)
        
        binding.loadingIndicator.visibility = View.VISIBLE
        val conditions = DownloadConditions.Builder().requireWifi().build()
        uiTranslator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener { 
                isTranslatorReady = true
                binding.loadingIndicator.visibility = View.GONE
                translateUI()
            }
            ?.addOnFailureListener {
                binding.loadingIndicator.visibility = View.GONE
            }
    }

    private fun translateUI() {
        if (!isTranslatorReady || uiTranslator == null) return

        translateAndSet(binding.btnCheck, getString(R.string.btn_check))
        translateAndSet(binding.btnModeSingle, getString(R.string.mode_single))
        translateAndSet(binding.btnModeWhitelist, getString(R.string.mode_whitelist))
        translateAndSet(binding.btnModeMassive, getString(R.string.mode_massive))
        
        translateHint(binding.tilIp, getString(R.string.ip_address))
        translateHint(binding.tilSni, getString(R.string.sni_host))
        translateHint(binding.tilSniList, getString(R.string.sni_list_hint))
        
        translateAndSet(binding.chkLowPing, getString(R.string.low_ping_filter))
        translateAndSet(binding.btnCopyAll, getString(R.string.btn_copy_all))
    }

    private fun showLanguageDialog() {
        val languages = TranslateLanguage.getAllLanguages().sortedBy { Locale(it).getDisplayLanguage(Locale.getDefault()) }
        val displayNames = languages.map { Locale(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.uppercase() } }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.select_language)
            .setItems(displayNames) { _, which ->
                val selectedLang = languages[which]
                updateLocale(selectedLang)
            }
            .show()
    }

    private fun updateModeUI() {
        binding.tilSni.visibility = if (currentMode == CheckMode.SINGLE) View.VISIBLE else View.GONE
        binding.tilSniList.visibility = if (currentMode == CheckMode.MASSIVE) View.VISIBLE else View.GONE
        binding.listControls.visibility = if (currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE
    }

    private fun loadSavedState() {
        binding.etIp.setText(prefs.getString("last_ip", "1.1.1.1"))
        binding.etSni.setText(prefs.getString("last_sni", "vk.com"))
        binding.etSniList.setText(prefs.getString("last_sni_list", "google.com\nyoutube.com\nfacebook.com"))
        
        val lastMode = prefs.getString("last_mode", "single")
        when (lastMode) {
            "whitelist" -> binding.toggleGroup.check(R.id.btnModeWhitelist)
            "massive" -> binding.toggleGroup.check(R.id.btnModeMassive)
            else -> binding.toggleGroup.check(R.id.btnModeSingle)
        }
        updateModeUI()
    }

    private fun updateLocale(langCode: String) {
        prefs.edit().putString("app_lang", langCode).apply()
        val appLocales = LocaleListCompat.forLanguageTags(langCode)
        AppCompatDelegate.setApplicationLocales(appLocales)
        
        // Final effort to ensure stability: small delay before recreation
        scope.launch {
            delay(150)
            recreate()
        }
    }

    private fun translateAndSet(view: View, text: String) {
        if (isTranslatorReady && uiTranslator != null) {
            uiTranslator?.translate(text)?.addOnSuccessListener { translated ->
                try {
                    when (view) {
                        is TextView -> view.text = translated
                        is Button -> view.text = translated
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun translateHint(til: TextInputLayout, text: String) {
        if (isTranslatorReady && uiTranslator != null) {
            uiTranslator?.translate(text)?.addOnSuccessListener { translated ->
                try { til.hint = translated } catch (e: Exception) {}
            }
        }
    }

    private fun addRow(sectionBinding: ItemResultSectionBinding, label: String, value: Any?, isError: Boolean = false) {
        if (value == null) return
        val container = sectionBinding.containerRows
        val rowBinding = ItemResultRowBinding.inflate(layoutInflater, container, false)
        
        if (isTranslatorReady && uiTranslator != null) {
            uiTranslator?.translate(label)?.addOnSuccessListener { 
                try { rowBinding.tvLabel.text = it } catch (e: Exception) {}
            }
        } else {
            rowBinding.tvLabel.text = label
        }
        
        val valueStr = value.toString()
        if (label == "City" || label == "Org" || label == "Status" || label == "Type" || label == "Verdict") {
            if (isTranslatorReady && uiTranslator != null) {
                uiTranslator?.translate(valueStr)?.addOnSuccessListener { 
                    try { rowBinding.tvValue.text = it } catch (e: Exception) {}
                }
            } else {
                rowBinding.tvValue.text = valueStr
            }
        } else {
            rowBinding.tvValue.text = valueStr
        }

        if (isError) {
            rowBinding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.error))
        } else if (valueStr.contains("OK") || valueStr.contains("✅") || valueStr.contains("CONNECTED") || valueStr.contains("Success") || valueStr.contains("Supported")) {
            rowBinding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.success))
        }

        container.addView(rowBinding.root)
        sectionBinding.root.visibility = View.VISIBLE
    }

    private fun clearResults() {
        listOf(binding.sectionTcp, binding.sectionTls, binding.sectionHttp, binding.sectionDns, binding.sectionGeo).forEach {
            it.containerRows.removeAllViews()
            it.root.visibility = View.GONE
        }
        binding.layoutResults.visibility = View.GONE
        binding.cardVerdict.visibility = View.GONE
        binding.cardRawOutput.visibility = View.GONE
        binding.tvRawOutput.text = ""
        allResults = emptyList()
    }

    private fun performCheck() {
        val host = binding.etIp.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.err_empty_ip, Toast.LENGTH_SHORT).show()
            return
        }

        isCancelled = false
        isPaused = false
        updateCheckButtonState(true)
        clearResults()
        
        startTimeMillis = System.currentTimeMillis()
        processedItemsCount = 0
        startTimer()

        checkJob = scope.launch {
            try {
                when (currentMode) {
                    CheckMode.SINGLE -> {
                        val sni = binding.etSni.text.toString().trim()
                        if (sni.isEmpty()) return@launch
                        totalItemsCount = 1
                        val result = withContext(Dispatchers.IO) { checker.checkIp(host, sni) }
                        if (!isCancelled) displaySingleResult(result)
                    }
                    CheckMode.WHITELIST -> {
                        val snis = fetchWhitelist()
                        checkMultiple(host, snis)
                    }
                    CheckMode.MASSIVE -> {
                        val snis = binding.etSniList.text.toString().split("\n")
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        checkMultiple(host, snis)
                    }
                }
            } catch (e: Exception) {
                logToTerminal("Error: ${e.message}")
            } finally {
                stopTimer()
                updateCheckButtonState(false)
            }
        }
    }

    private fun pauseCheck() {
        isPaused = true
        binding.btnCheck.text = getString(R.string.btn_resume)
        binding.btnPause.setImageResource(android.R.drawable.ic_media_play)
        logToTerminal("--- PAUSED ---")
    }

    private fun resumeCheck() {
        isPaused = false
        binding.btnCheck.text = getString(R.string.btn_pause)
        binding.btnPause.setImageResource(android.R.drawable.ic_media_pause)
        logToTerminal("--- RESUMED ---")
    }

    private fun cancelCheck() {
        isCancelled = true
        checkJob?.cancel()
        stopTimer()
        updateCheckButtonState(false)
        logToTerminal("--- CANCELLED ---")
    }

    private suspend fun checkMultiple(host: String, snis: List<String>) {
        val results = mutableListOf<CheckResult>()
        totalItemsCount = snis.size
        
        for ((index, sni) in snis.withIndex()) {
            while (isPaused) delay(500)
            if (isCancelled) break
            
            processedItemsCount = index + 1
            withContext(Dispatchers.Main) {
                binding.tvProgress.text = sni
                binding.tvProgressCount.text = "$processedItemsCount / $totalItemsCount"
                binding.progressBar.max = totalItemsCount
                binding.progressBar.progress = processedItemsCount
            }

            logToTerminal("Checking [$processedItemsCount/$totalItemsCount]: $sni...")
            val result = withContext(Dispatchers.IO) { checker.checkIp(host, sni) }
            results.add(result)
            allResults = results.toList()
            
            withContext(Dispatchers.Main) {
                displayListSummary(allResults, host)
                binding.btnCopyAll.visibility = View.VISIBLE
            }
            
            // Auto-scroll terminal
            binding.terminalScrollView.post { binding.terminalScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private suspend fun fetchWhitelist(): List<String> = withContext(Dispatchers.IO) {
        try {
            URL(whitelistUrl).readText().split("\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun displaySingleResult(r: CheckResult) {
        binding.layoutResults.visibility = View.VISIBLE
        
        showVerdict(r.verdict, String.format(Locale.US, "Total time: %.2fs", r.totalTime),
            when (r.inWhitelist) { 
                true -> R.color.success
                false -> R.color.error
                null -> R.color.warning 
            })

        val geo = r.ipGeoInfo
        if (geo != null) {
            addRow(binding.sectionGeo, "📡 Geo Source", geo.source)
            addRow(binding.sectionGeo, "🏢 Organization", geo.org)
            addRow(binding.sectionGeo, "🏙️ City", geo.city)
            addRow(binding.sectionGeo, "🏳️ Country", geo.country)
        }
        
        addRow(binding.sectionTcp, "🔌 Port 443 (TCP)", if (r.tcpReachable == true) "✅ Reachable" else "❌ Blocked", r.tcpReachable == false)
        addRow(binding.sectionTcp, "📶 RTT (Ping)", r.rttMs?.let { String.format(Locale.US, "%.0f ms", it) })
        addRow(binding.sectionTcp, "🔌 Port 80 (HTTP)", if (r.tcp80Reachable == true) "✅ Open" else "❌ Closed", r.tcp80Reachable == false)

        addRow(binding.sectionTls, "🔒 TLS Handshake", if (r.tlsOk == true) "✅ Success" else "❌ Failed", r.tlsOk == false)
        addRow(binding.sectionTls, "📋 Protocol Version", r.tlsVersion)
        addRow(binding.sectionTls, "🔐 Cipher Suite", r.tlsCipher)
        addRow(binding.sectionTls, "🎯 SNI Match", if (r.certSniMatch == true) "✅ Yes" else "❌ No", r.certSniMatch == false)
        addRow(binding.sectionTls, "⚡ HTTP/2", when(r.h2Supported) { true -> "✅ Yes"; false -> "❌ No"; else -> "❓ N/A" })

        if (r.httpStatusCode != null) addRow(binding.sectionHttp, "🔢 Status Code", r.httpStatusCode)
        addRow(binding.sectionHttp, "🖥️ Server Header", r.httpServerHeader)
        addRow(binding.sectionHttp, "↪️ Redirect", r.httpRedirectLocation)

        addRow(binding.sectionDns, "💻 Target IP", r.ip)
        addRow(binding.sectionDns, "🔍 DNS Resolve Time", r.dnsResolveTime?.let { String.format(Locale.US, "%.3f s", it) })
        if (r.dnsResolvesTo.isNotEmpty()) addRow(binding.sectionDns, "🌐 Resolves To", r.dnsResolvesTo.joinToString(", "))

        binding.cardRawOutput.visibility = View.VISIBLE
        binding.tvRawOutput.text = r.toString().replace(", ", ",\n")
    }

    private fun displayListSummary(results: List<CheckResult>, host: String) {
        binding.layoutResults.visibility = View.VISIBLE
        listOf(binding.sectionTcp, binding.sectionHttp, binding.sectionDns, binding.sectionGeo).forEach { it.root.visibility = View.GONE }
        
        val filterLowPing = binding.chkLowPing.isChecked
        val filtered = if (filterLowPing) results.filter { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD } else results
        val sorted = filtered.sortedWith(compareByDescending<CheckResult> { it.tlsOk == true }.thenBy { it.rttMs ?: Double.MAX_VALUE })
        
        val workingCount = results.count { it.tlsOk == true }
        showVerdict("$workingCount / ${results.size} SNI working", "Host: $host", if (workingCount > 0) R.color.success else R.color.error)

        val container = binding.sectionTls.containerRows
        container.removeAllViews()
        sorted.forEach { r ->
            val row = ItemResultRowBinding.inflate(layoutInflater, container, false)
            row.tvLabel.text = r.sni
            val status = when { r.tlsOk == true -> "OK"; r.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            val ping = r.rttMs?.let { "[${it.toInt()}ms]" } ?: "[---]"
            row.tvValue.text = "$status $ping"
            if (r.tlsOk != true) row.tvValue.setTextColor(ContextCompat.getColor(this, R.color.error))
            else row.tvValue.setTextColor(ContextCompat.getColor(this, R.color.success))
            container.addView(row.root)
        }
        binding.sectionTls.root.visibility = View.VISIBLE
    }

    private fun showVerdict(verdict: String, subtitle: String, colorRes: Int) {
        binding.tvVerdict.text = verdict
        binding.tvTotalTime.text = subtitle
        binding.cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
        binding.cardVerdict.visibility = View.VISIBLE
    }

    private fun logToTerminal(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvTerminal.append("[$time] $msg\n")
    }

    private fun updateCheckButtonState(running: Boolean) {
        binding.btnCheck.text = if (running) getString(R.string.btn_pause) else getString(R.string.btn_check)
        binding.cardProgress.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnPause.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        binding.cardTerminal.visibility = if (running || currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE
        
        binding.etIp.isEnabled = !running
        binding.etSni.isEnabled = !running
        binding.etSniList.isEnabled = !running
        binding.btnModeSingle.isEnabled = !running
        binding.btnModeWhitelist.isEnabled = !running
        binding.btnModeMassive.isEnabled = !running
    }

    private fun startTimer() {
        timerJob = scope.launch {
            while (isActive) {
                if (!isPaused) {
                    val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
                    binding.tvTimer.text = String.format("Elapsed: %02d:%02d", elapsed / 60, elapsed % 60)
                    
                    if (totalItemsCount > 1 && processedItemsCount > 0) {
                        val avgTimePerItem = elapsed.toDouble() / processedItemsCount
                        val remainingItems = totalItemsCount - processedItemsCount
                        val remainingSeconds = (remainingItems * avgTimePerItem).toLong()
                        binding.tvRemaining.text = String.format("Rem: ~%02d:%02d", remainingSeconds / 60, remainingSeconds % 60)
                    } else {
                        binding.tvRemaining.text = ""
                    }
                } else {
                    startTimeMillis += 1000 
                }
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SNI Results", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun buildMassiveResultText(results: List<CheckResult>): String = buildString {
        append("=== SNI Pinger Results ===\n")
        results.forEach { 
            val status = when { it.tlsOk == true -> "OK"; it.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            append("${it.sni}: $status (${it.rttMs?.toInt() ?: "--"}ms)\n")
        }
    }

    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        checkJob?.cancel()
        timerJob?.cancel()
        scope.cancel()
    }

    enum class CheckMode { SINGLE, WHITELIST, MASSIVE }
}
