package com.begugla.snipinger

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val checker = WhitelistChecker()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var checkJob: Job? = null
    private var timerJob: Job? = null
    private var notifTickerJob: Job? = null

    private var isPaused = false
    private var isCancelled = false

    private var currentMode = CheckMode.SINGLE
    private var allResults: List<CheckResult> = emptyList()
    private var lastSingleResult: CheckResult? = null

    private var startTimeMillis: Long = 0
    private var totalItemsCount = 0
    @Volatile private var processedItemsCount = 0

    @Volatile private var currentSniDisplay: String = ""

    private var fastMode: Boolean = false
    private var fastThreads: Int = 4

    private var uiTranslator: Translator? = null
    private var isTranslatorReady = false

    private var backgroundServiceStarted = false

    private enum class VerdictType { OK, ERROR, WARNING }

    companion object {
        private const val LOW_PING_THRESHOLD = 150
        private const val KEY_FIRST_LAUNCH_DONE = "first_launch_done"
        private const val KEY_BG_PERMISSION_ASKED = "bg_permission_asked"
        private const val KEY_FAST_MODE = "fast_mode"
        private const val KEY_FAST_THREADS = "fast_threads"
    }

    // ── Activity Result Launchers ────────────────────────────────────────────

    private val txtPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadSniListFromUri(uri)
    }

    private val csvSaveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) writeCsvToUri(uri)
    }

    private val postNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _: Boolean ->
        promptBatteryIgnoreIfNeeded()
    }

    private var pendingCsvText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("snipinger_prefs", Context.MODE_PRIVATE)

        val savedLang = prefs.getString("app_lang", "") ?: ""
        if (savedLang.isNotEmpty() && AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(savedLang))
        }

        applyTheme()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupUI()
        setupSectionTitles()
        loadSavedState()

        val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifEmpty { savedLang }
        if (currentLang.isNotEmpty()) initTranslator(currentLang)

        ensureWhitelistReady()
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

        binding.btnLangToggle.setOnClickListener { showLanguageDialog() }

        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            TransitionManager.beginDelayedTransition(binding.root, AutoTransition().apply { duration = 250 })
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
            val visible = binding.terminalScrollView.visibility == View.VISIBLE
            TransitionManager.beginDelayedTransition(binding.cardTerminal, AutoTransition())
            binding.terminalScrollView.visibility = if (visible) View.GONE else View.VISIBLE
            binding.cardTerminal.layoutParams.height = if (visible) 44.toPx() else 200.toPx()
            binding.btnToggleTerminal.setImageResource(
                if (visible) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float
            )
            binding.cardTerminal.requestLayout()
        }

        binding.btnCheck.setOnClickListener {
            when {
                isPaused -> resumeCheck()
                checkJob?.isActive == true -> pauseCheck()
                else -> performCheck()
            }
        }

        binding.btnPause.setOnClickListener { if (isPaused) resumeCheck() else pauseCheck() }
        binding.btnCancel.setOnClickListener { cancelCheck() }
        binding.btnCopyRaw.setOnClickListener { copyToClipboard(binding.tvRawOutput.text.toString()) }
        binding.btnCopyAll.setOnClickListener { copyToClipboard(buildMassiveResultText(allResults)) }

        binding.btnExportCsv.setOnClickListener { exportCsv(allResults, "snipinger_list") }
        binding.btnExportCsvSingle.setOnClickListener {
            lastSingleResult?.let { exportCsv(listOf(it), "snipinger_single") }
                ?: Toast.makeText(this, R.string.csv_nothing, Toast.LENGTH_SHORT).show()
        }

        binding.tilSniList.setEndIconOnClickListener {
            txtPickerLauncher.launch("text/*")
        }

        binding.chkLowPing.setOnCheckedChangeListener { _, _ ->
            if (currentMode != CheckMode.SINGLE && allResults.isNotEmpty()) {
                displayListSummary(allResults, binding.etIp.text.toString().trim())
            }
        }

        binding.chkFastMode.setOnCheckedChangeListener { _, isChecked ->
            fastMode = isChecked
            prefs.edit().putBoolean(KEY_FAST_MODE, fastMode).apply()
            updateFastModeUI()
        }

        binding.sliderThreads.addOnChangeListener { _, value, _ ->
            fastThreads = value.toInt().coerceIn(2, 10)
            binding.tvThreadsLabel.text = getString(R.string.fast_mode_threads, fastThreads)
            prefs.edit().putInt(KEY_FAST_THREADS, fastThreads).apply()
        }
    }

    private fun updateFastModeUI() {
        val show = fastMode
        binding.sliderThreads.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvThreadsLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.tvThreadsLabel.text = getString(R.string.fast_mode_threads, fastThreads)
    }

    // ── Whitelist cache ──────────────────────────────────────────────────────

    private fun ensureWhitelistReady() {
        val firstLaunchDone = prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)
        if (!firstLaunchDone || !WhitelistCache.hasCache(this)) {
            showFirstLaunchDownloadDialog()
        } else {
            checkForWhitelistUpdate()
        }
    }

    private fun showFirstLaunchDownloadDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setIcon(android.R.drawable.stat_sys_download)
            .setTitle(R.string.whitelist_dl_title)
            .setMessage(R.string.whitelist_dl_message)
            .setCancelable(false)
            .setPositiveButton(R.string.whitelist_dl_btn, null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = getString(R.string.whitelist_dl_downloading)
            scope.launch {
                val res = withContext(Dispatchers.IO) { WhitelistCache.download(this@MainActivity) }
                when (res) {
                    is WhitelistCache.DownloadResult.Success -> {
                        prefs.edit().putBoolean(KEY_FIRST_LAUNCH_DONE, true).apply()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.whitelist_dl_success, res.entries.size),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    }
                    is WhitelistCache.DownloadResult.Failure -> {
                        btn.isEnabled = true
                        btn.text = getString(R.string.whitelist_dl_btn)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.whitelist_dl_failed, res.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun checkForWhitelistUpdate() {
        if (!isNetworkAvailable()) {
            logToTerminal(getString(R.string.whitelist_update_offline))
            return
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) { WhitelistCache.download(this@MainActivity) }
            when (res) {
                is WhitelistCache.DownloadResult.Success -> {
                    if (res.changed) {
                        logToTerminal(getString(R.string.whitelist_update_found, res.entries.size))
                    }
                }
                is WhitelistCache.DownloadResult.Failure -> {
                    logToTerminal(getString(R.string.whitelist_update_offline))
                }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Background permission flow ───────────────────────────────────────────

    private fun requestBackgroundPermissionsIfNeeded() {
        if (prefs.getBoolean(KEY_BG_PERMISSION_ASKED, false)) {
            maybeStartForegroundService()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setIcon(android.R.drawable.ic_popup_reminder)
            .setTitle(R.string.bg_permission_title)
            .setMessage(R.string.bg_permission_message)
            .setPositiveButton(R.string.bg_permission_accept) { _, _ ->
                prefs.edit().putBoolean(KEY_BG_PERMISSION_ASKED, true).apply()
                if (Build.VERSION.SDK_INT >= 33) {
                    if (ContextCompat.checkSelfPermission(
                            this, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        postNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        promptBatteryIgnoreIfNeeded()
                    }
                } else {
                    promptBatteryIgnoreIfNeeded()
                }
                maybeStartForegroundService()
            }
            .setNegativeButton(R.string.bg_permission_deny) { _, _ ->
                prefs.edit().putBoolean(KEY_BG_PERMISSION_ASKED, true).apply()
            }
            .setCancelable(true)
            .show()
    }

    private fun promptBatteryIgnoreIfNeeded() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        MaterialAlertDialogBuilder(this)
            .setIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setTitle(R.string.battery_ignore_title)
            .setMessage(R.string.battery_ignore_message)
            .setPositiveButton(R.string.bg_permission_accept) { _, _ ->
                try {
                    @Suppress("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            .setNegativeButton(R.string.bg_permission_deny, null)
            .show()
    }

    private fun maybeStartForegroundService() {
        if (backgroundServiceStarted) return
        CheckForegroundService.start(
            this,
            getString(R.string.bg_service_title),
            getString(R.string.bg_service_text)
        )
        backgroundServiceStarted = true
    }

    private fun stopForegroundService() {
        if (!backgroundServiceStarted) return
        CheckForegroundService.stop(this)
        backgroundServiceStarted = false
    }

    // ── Translator ───────────────────────────────────────────────────────────

    private fun initTranslator(targetLang: String, isRetry: Boolean = false) {
        val lang = targetLang.split("-")[0].lowercase()

        if (lang == "en" || lang == "ru") {
            isTranslatorReady = false
            uiTranslator?.close()
            uiTranslator = null
            return
        }

        if (!isRetry && prefs.getString("failed_lang", "") == lang) return

        val options = try {
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(lang)
                .build()
        } catch (e: Exception) {
            return
        }

        uiTranslator?.close()
        uiTranslator = Translation.getClient(options)
        isTranslatorReady = false
        binding.loadingIndicator.visibility = View.VISIBLE

        uiTranslator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                prefs.edit().remove("failed_lang").apply()
                isTranslatorReady = true
                binding.loadingIndicator.visibility = View.GONE
                translateUI()
            }
            ?.addOnFailureListener {
                binding.loadingIndicator.visibility = View.GONE
                prefs.edit().putString("failed_lang", lang).apply()
                Snackbar.make(binding.root, R.string.lang_error, Snackbar.LENGTH_LONG)
                    .setAction(R.string.btn_retry) { initTranslator(targetLang, isRetry = true) }
                    .show()
            }
    }

    private fun translateUI() {
        if (!isTranslatorReady || uiTranslator == null) return

        translateText(getString(R.string.mode_single)) { binding.btnModeSingle.text = it }
        translateText(getString(R.string.mode_whitelist)) { binding.btnModeWhitelist.text = it }
        translateText(getString(R.string.mode_massive)) { binding.btnModeMassive.text = it }

        translateText(getString(R.string.ip_address)) { binding.tilIp.hint = it }
        translateText(getString(R.string.sni_host)) { binding.tilSni.hint = it }
        translateText(getString(R.string.sni_list_hint)) { binding.tilSniList.hint = it }

        translateText(getString(R.string.low_ping_filter)) { binding.chkLowPing.text = it }
        translateText(getString(R.string.btn_copy_all)) { binding.btnCopyAll.text = it }
        translateText(getString(R.string.btn_check)) { binding.btnCheck.text = it }
        translateText(getString(R.string.btn_export_csv)) {
            binding.btnExportCsv.text = it
            binding.btnExportCsvSingle.text = it
        }

        translateText(getString(R.string.section_geo)) { binding.sectionGeo.tvSectionTitle.text = it }
        translateText(getString(R.string.section_tcp)) { binding.sectionTcp.tvSectionTitle.text = it }
        translateText(getString(R.string.section_tls)) { binding.sectionTls.tvSectionTitle.text = it }
        translateText(getString(R.string.section_http)) { binding.sectionHttp.tvSectionTitle.text = it }
        translateText(getString(R.string.section_dns)) { binding.sectionDns.tvSectionTitle.text = it }
    }

    private fun translateText(text: String, onResult: (String) -> Unit) {
        if (!isTranslatorReady || uiTranslator == null) return
        uiTranslator?.translate(text)
            ?.addOnSuccessListener { translated -> try { onResult(translated) } catch (_: Exception) {} }
    }

    private fun showLanguageDialog() {
        val languages = TranslateLanguage.getAllLanguages()
            .sortedBy { Locale(it).getDisplayLanguage(Locale.getDefault()) }
        val displayNames = languages
            .map { Locale(it).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { c -> c.uppercase() } }
            .toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_language)
            .setTitle(R.string.select_language)
            .setItems(displayNames) { _, which -> updateLocale(languages[which]) }
            .show()
    }

    private fun updateLocale(langCode: String) {
        uiTranslator?.close()
        uiTranslator = null
        isTranslatorReady = false
        prefs.edit()
            .putString("app_lang", langCode)
            .remove("failed_lang")
            .apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private fun setupSectionTitles() {
        binding.sectionGeo.tvSectionTitle.text = getString(R.string.section_geo)
        binding.sectionTcp.tvSectionTitle.text = getString(R.string.section_tcp)
        binding.sectionTls.tvSectionTitle.text = getString(R.string.section_tls)
        binding.sectionHttp.tvSectionTitle.text = getString(R.string.section_http)
        binding.sectionDns.tvSectionTitle.text = getString(R.string.section_dns)
    }

    private fun addRow(
        sectionBinding: ItemResultSectionBinding,
        label: String,
        value: Any?,
        isError: Boolean = false,
        translateValue: Boolean = false
    ) {
        if (value == null) return
        val container = sectionBinding.containerRows
        val rowBinding = ItemResultRowBinding.inflate(layoutInflater, container, false)

        translateText(label) { rowBinding.tvLabel.text = it }
        if (!isTranslatorReady) rowBinding.tvLabel.text = label

        val valueStr = value.toString()
        if (translateValue) {
            translateText(valueStr) { rowBinding.tvValue.text = it }
            if (!isTranslatorReady) rowBinding.tvValue.text = valueStr
        } else {
            rowBinding.tvValue.text = valueStr
        }

        when {
            isError ->
                rowBinding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.error))
            valueStr.contains("✅") || valueStr.contains("OK") ||
            valueStr.contains("CONNECTED") || valueStr.contains("Supported") ->
                rowBinding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.success))
        }

        container.addView(rowBinding.root)
        sectionBinding.root.visibility = View.VISIBLE
    }

    // ── Mode UI ───────────────────────────────────────────────────────────────

    private fun updateModeUI() {
        binding.tilSni.visibility = if (currentMode == CheckMode.SINGLE) View.VISIBLE else View.GONE
        binding.tilSniList.visibility = if (currentMode == CheckMode.MASSIVE) View.VISIBLE else View.GONE
        binding.listControls.visibility = if (currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE
    }

    private fun loadSavedState() {
        binding.etIp.setText(prefs.getString("last_ip", "1.1.1.1"))
        binding.etSni.setText(prefs.getString("last_sni", "vk.com"))
        binding.etSniList.setText(prefs.getString("last_sni_list", "google.com\nyoutube.com\nfacebook.com"))

        when (prefs.getString("last_mode", "single")) {
            "whitelist" -> binding.toggleGroup.check(R.id.btnModeWhitelist)
            "massive" -> binding.toggleGroup.check(R.id.btnModeMassive)
            else -> binding.toggleGroup.check(R.id.btnModeSingle)
        }

        fastMode = prefs.getBoolean(KEY_FAST_MODE, false)
        fastThreads = prefs.getInt(KEY_FAST_THREADS, 4).coerceIn(2, 10)
        binding.chkFastMode.isChecked = fastMode
        binding.sliderThreads.value = fastThreads.toFloat()
        updateFastModeUI()

        updateModeUI()
    }

    // ── Check flow ────────────────────────────────────────────────────────────

    private fun performCheck() {
        val host = binding.etIp.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, R.string.err_empty_ip, Toast.LENGTH_SHORT).show()
            return
        }

        requestBackgroundPermissionsIfNeeded()
        maybeStartForegroundService()

        isCancelled = false
        isPaused = false
        updateCheckButtonState(true)
        clearResults()
        startTimeMillis = System.currentTimeMillis()
        processedItemsCount = 0
        currentSniDisplay = ""
        startTimer()
        startNotificationTicker()

        prefs.edit()
            .putString("last_ip", host)
            .putString("last_sni", binding.etSni.text.toString().trim())
            .putString("last_sni_list", binding.etSniList.text.toString().trim())
            .apply()

        checkJob = scope.launch {
            try {
                when (currentMode) {
                    CheckMode.SINGLE -> {
                        val sni = binding.etSni.text.toString().trim()
                        if (sni.isEmpty()) return@launch
                        totalItemsCount = 1
                        currentSniDisplay = sni
                        val result = withContext(Dispatchers.IO) { checker.checkIp(host, sni) }
                        if (!isCancelled) {
                            lastSingleResult = result
                            displaySingleResult(result)
                        }
                        processedItemsCount = 1
                    }
                    CheckMode.WHITELIST -> {
                        logToTerminal(getString(R.string.loading_whitelist))
                        val snis = loadWhitelistSnis()
                        if (snis.isEmpty()) {
                            logToTerminal(getString(R.string.error_loading_whitelist))
                        } else {
                            runMultiple(host, snis, isWhitelist = true)
                        }
                    }
                    CheckMode.MASSIVE -> {
                        val snis = binding.etSniList.text.toString()
                            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        runMultiple(host, snis, isWhitelist = false)
                    }
                }
            } catch (e: Exception) {
                logToTerminal("Error: ${e.message}")
            } finally {
                stopTimer()
                stopNotificationTicker()
                updateCheckButtonState(false)
                stopForegroundService()
            }
        }
    }

    private suspend fun runMultiple(host: String, snis: List<String>, isWhitelist: Boolean) {
        if (fastMode && snis.size > 1) {
            checkMultipleParallel(host, snis, isWhitelist, fastThreads)
        } else {
            checkMultiple(host, snis, isWhitelist)
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
        stopNotificationTicker()
        updateCheckButtonState(false)
        stopForegroundService()
        logToTerminal("--- CANCELLED ---")
    }

    private suspend fun checkMultiple(host: String, snis: List<String>, isWhitelist: Boolean) {
        val results = mutableListOf<CheckResult>()
        totalItemsCount = snis.size

        for ((index, sni) in snis.withIndex()) {
            while (isPaused) delay(500)
            if (isCancelled) break

            processedItemsCount = index
            currentSniDisplay = sni
            val doneSoFar = index + 1
            val percent = (doneSoFar * 100 / totalItemsCount)
            withContext(Dispatchers.Main) {
                binding.tvProgress.text = sni
                binding.tvProgressCount.text = "$doneSoFar / $totalItemsCount"
                binding.tvProgressPercent.text = getString(R.string.progress_percent, percent)
                binding.progressBar.max = totalItemsCount
                binding.progressBar.progress = doneSoFar
            }

            if (isWhitelist) {
                logToTerminal("[$doneSoFar/$totalItemsCount] → $sni …")
            } else {
                logToTerminal("[$doneSoFar/$totalItemsCount] $sni")
            }

            val result = withContext(Dispatchers.IO) { checker.checkIp(host, sni) }
            results.add(result)
            processedItemsCount = doneSoFar
            allResults = results.toList()

            if (isWhitelist) {
                logToTerminal("[$doneSoFar/$totalItemsCount] ← $sni ${whitelistLogSuffix(result)}")
            }

            withContext(Dispatchers.Main) {
                displayListSummary(allResults, host)
                binding.btnCopyAll.visibility = View.VISIBLE
                binding.btnExportCsv.visibility = View.VISIBLE
            }

            binding.terminalScrollView.post { binding.terminalScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private suspend fun checkMultipleParallel(
        host: String,
        snis: List<String>,
        isWhitelist: Boolean,
        threads: Int
    ) {
        val results = Collections.synchronizedList(mutableListOf<CheckResult>())
        totalItemsCount = snis.size
        val semaphore = Semaphore(threads.coerceIn(2, 10))
        val completed = AtomicInteger(0)
        val inflight = Collections.synchronizedSet(linkedSetOf<String>())

        logToTerminal("⚡ fast × $threads started (${snis.size} SNIs)")

        coroutineScope {
            snis.map { sni ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        while (isPaused) delay(500)
                        if (isCancelled) return@withPermit

                        inflight.add(sni)
                        withContext(Dispatchers.Main) {
                            currentSniDisplay = inflight.joinToString(", ").take(60)
                            binding.tvProgress.text = currentSniDisplay
                            logToTerminal("⚡ → $sni …")
                        }

                        val result = try {
                            checker.checkIp(host, sni)
                        } catch (e: Exception) {
                            CheckResult(ip = host, sni = sni, port = 443, timeout = 5f).also {
                                it.errors.add("parallel: ${e.message}")
                            }
                        }

                        results.add(result)
                        val done = completed.incrementAndGet()
                        processedItemsCount = done
                        val percent = done * 100 / totalItemsCount

                        withContext(Dispatchers.Main) {
                            inflight.remove(sni)
                            currentSniDisplay = inflight.firstOrNull() ?: sni
                            binding.tvProgress.text = currentSniDisplay
                            binding.tvProgressCount.text = "$done / $totalItemsCount"
                            binding.tvProgressPercent.text =
                                getString(R.string.progress_percent, percent)
                            binding.progressBar.max = totalItemsCount
                            binding.progressBar.progress = done

                            allResults = results.toList()
                            displayListSummary(allResults, host)
                            binding.btnCopyAll.visibility = View.VISIBLE
                            binding.btnExportCsv.visibility = View.VISIBLE

                            if (isWhitelist) {
                                logToTerminal("[$done/$totalItemsCount] ⚡ ← $sni ${whitelistLogSuffix(result)}")
                            } else {
                                logToTerminal("[$done/$totalItemsCount] ⚡ $sni")
                            }
                            binding.terminalScrollView.post {
                                binding.terminalScrollView.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun whitelistLogSuffix(result: CheckResult): String {
        val status = when {
            result.tlsOk == true && result.httpOk == true -> "✅ OK"
            result.tlsOk == true -> "✅ TLS"
            result.tcpReachable == true -> "⚠️ TCP-only"
            else -> "❌ BLOCKED"
        }
        val ping = result.rttMs?.let { "${it.toInt()}ms" } ?: "--"
        val tlsVer = result.tlsVersion ?: "-"
        val total = String.format(Locale.US, "%.2fs", result.totalTime ?: 0.0)
        return "$status $tlsVer $ping ($total)"
    }

    private suspend fun loadWhitelistSnis(): List<String> = withContext(Dispatchers.IO) {
        // Preferred: cached file
        if (WhitelistCache.hasCache(this@MainActivity)) {
            val cached = WhitelistCache.read(this@MainActivity)
            withContext(Dispatchers.Main) {
                logToTerminal(getString(R.string.whitelist_loaded_cache, cached.size))
            }
            // Try a background refresh — if changed, log it but don't block
            try {
                val res = WhitelistCache.download(this@MainActivity)
                if (res is WhitelistCache.DownloadResult.Success && res.changed) {
                    withContext(Dispatchers.Main) {
                        logToTerminal(getString(R.string.whitelist_update_found, res.entries.size))
                    }
                    return@withContext res.entries
                }
            } catch (_: Exception) {}
            return@withContext cached
        }

        // Fallback: direct download if cache empty
        val res = WhitelistCache.download(this@MainActivity)
        if (res is WhitelistCache.DownloadResult.Success) res.entries else emptyList()
    }

    // ── Display results ───────────────────────────────────────────────────────

    private fun displaySingleResult(r: CheckResult) {
        binding.layoutResults.visibility = View.VISIBLE

        val verdictType = when (r.inWhitelist) {
            true -> VerdictType.OK
            false -> VerdictType.ERROR
            null -> VerdictType.WARNING
        }
        showVerdict(
            getVerdictText(r.verdict),
            String.format(Locale.US, getString(R.string.total_time), r.totalTime),
            verdictType
        )

        // ── Geo ──────────────────────────────────────────────────────────────
        val geo = r.ipGeoInfo
        if (geo != null) {
            addRow(binding.sectionGeo, "Source", geo.source)
            addRow(binding.sectionGeo, "Organization", geo.org, translateValue = true)
            addRow(binding.sectionGeo, "ASN", geo.asn)
            addRow(binding.sectionGeo, "City", geo.city, translateValue = true)
            addRow(binding.sectionGeo, "Region", geo.region, translateValue = true)
            addRow(binding.sectionGeo, "Country", geo.country, translateValue = true)
            addRow(binding.sectionGeo, "Country Code", geo.countryCode)
            addRow(binding.sectionGeo, "Postal Code", geo.postalCode)
            addRow(binding.sectionGeo, "Timezone", geo.timezone)
            addRow(binding.sectionGeo, "Latitude", geo.lat)
            addRow(binding.sectionGeo, "Longitude", geo.lon)
            addRow(binding.sectionGeo, "Hostname", geo.hostname)
        }
        addRow(binding.sectionGeo, "Reverse DNS", r.reverseDns)
        addRow(binding.sectionGeo, "IP Version", r.ipVersion?.let { "IPv$it" })
        addRow(binding.sectionGeo, "Private IP",
            r.ipIsPrivate?.let { if (it) "⚠️ Yes" else "No" }, isError = r.ipIsPrivate == true)
        addRow(binding.sectionGeo, "Global IP",
            r.ipIsGlobal?.let { if (it) "✅ Yes" else "No" })
        addRow(binding.sectionGeo, "Anycast", r.ipGeoInfo?.anycast?.let { if (it) "✅ Yes" else "No" })
        addRow(binding.sectionGeo, "Domain Owner", r.domainOwnerOrg, translateValue = true)
        if (r.domainResolvedIps.isNotEmpty())
            addRow(binding.sectionGeo, "Domain IPs", r.domainResolvedIps.joinToString(", "))
        if (r.cdnProvider != null)
            addRow(binding.sectionGeo, "CDN / Proxy", r.cdnProvider, isError = false)

        // ── TCP ──────────────────────────────────────────────────────────────
        addRow(binding.sectionTcp, "Port 443",
            if (r.tcpReachable == true) "✅ Open" else "❌ Blocked",
            isError = r.tcpReachable == false)
        if (r.tcpBlockType != null)
            addRow(binding.sectionTcp, "Block Type", r.tcpBlockType, isError = true)
        addRow(binding.sectionTcp, "RTT (Ping)",
            r.rttMs?.let { String.format(Locale.US, "%.0f ms", it) })
        addRow(binding.sectionTcp, "TCP Connect",
            r.tcpConnectTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(binding.sectionTcp, "Port 80",
            portStatus(r.tcp80Reachable), isError = r.tcp80Reachable == false)
        addRow(binding.sectionTcp, "Port 80 Connect",
            r.tcp80ConnectTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(binding.sectionTcp, "Port 8443",
            portStatus(r.tcp8443Reachable), isError = r.tcp8443Reachable == false)
        addRow(binding.sectionTcp, "Port 8080",
            portStatus(r.tcp8080Reachable))
        addRow(binding.sectionTcp, "Port 22 (SSH)",
            portStatus(r.tcp22Reachable))
        addRow(binding.sectionTcp, "Port 25 (SMTP)",
            portStatus(r.tcp25Reachable))
        addRow(binding.sectionTcp, "Port 465 (SMTPS)",
            portStatus(r.tcp465Reachable))
        addRow(binding.sectionTcp, "Port 53 (DNS/TCP)",
            portStatus(r.tcp53Reachable))
        addRow(binding.sectionTcp, "ICMP Ping",
            r.icmpPing?.let { String.format(Locale.US, "%.1f ms", it) })
        addRow(binding.sectionTcp, "ICMP Loss",
            r.icmpLoss?.let { String.format(Locale.US, "%.0f%%", it * 100) })

        // ── TLS ──────────────────────────────────────────────────────────────
        if (r.sniDpiBlocked == true) {
            addRow(binding.sectionTls, "⚠️ DPI SNI Block",
                "DETECTED — SNI triggers filter", isError = true)
        } else if (r.sniDpiBlocked == false && r.tlsOk == true) {
            addRow(binding.sectionTls, "DPI Check", "✅ Not detected")
        }
        addRow(binding.sectionTls, "TLS Handshake (SNI)",
            if (r.tlsOk == true) "✅ Success" else "❌ Failed",
            isError = r.tlsOk == false)
        addRow(binding.sectionTls, "TLS Time",
            r.tlsTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(binding.sectionTls, "TLS Without SNI",
            when (r.tlsWithoutSniOk) { true -> "✅ Works"; false -> "❌ Fails"; else -> null })
        addRow(binding.sectionTls, "Protocol", r.tlsVersion)
        addRow(binding.sectionTls, "Cipher Suite", r.tlsCipher)
        addRow(binding.sectionTls, "SNI Match",
            if (r.certSniMatch == true) "✅ Yes" else "❌ No",
            isError = r.certSniMatch == false)
        addRow(binding.sectionTls, "HTTP/2",
            when (r.h2Supported) { true -> "✅ Yes"; false -> "❌ No"; else -> null })
        addRow(binding.sectionTls, "TLS 1.3", tlsVerStatus(r.tls13Ok))
        addRow(binding.sectionTls, "TLS 1.2", tlsVerStatus(r.tls12Ok))
        addRow(binding.sectionTls, "TLS 1.1 (legacy)", tlsVerStatus(r.tls11Ok))
        addRow(binding.sectionTls, "TLS 1.0 (legacy)", tlsVerStatus(r.tls10Ok))
        addRow(binding.sectionTls, "Cert Subject", r.certSubject)
        addRow(binding.sectionTls, "Cert Issuer", r.certIssuer)
        addRow(binding.sectionTls, "Cert Not Before", r.certNotBefore)
        addRow(binding.sectionTls, "Cert Not After", r.certNotAfter)
        addRow(binding.sectionTls, "Cert Expiry",
            r.certDaysToExpiry?.let { days ->
                when {
                    days < 0   -> "❌ Expired (${-days}d ago)"
                    days < 14  -> "⚠️ Expires in ${days}d"
                    else       -> "✅ Valid (${days}d left)"
                }
            }, isError = (r.certDaysToExpiry ?: 999) < 0)
        if (r.certIsSelfSigned == true)
            addRow(binding.sectionTls, "Self-Signed", "⚠️ Yes", isError = true)
        if (r.certSanList.isNotEmpty()) {
            addRow(binding.sectionTls, "SAN Count", "${r.certSanList.size} entries")
            addRow(binding.sectionTls, "SAN List", r.certSanList.joinToString(", "))
        }

        // ── HTTP ─────────────────────────────────────────────────────────────
        if (r.httpStatusCode != null)
            addRow(binding.sectionHttp, "Status", "${r.httpStatusCode} ${httpStatusText(r.httpStatusCode)}")
        addRow(binding.sectionHttp, "Status Line", r.httpStatusLine)
        addRow(binding.sectionHttp, "Server", r.httpServerHeader)
        addRow(binding.sectionHttp, "Powered By", r.xPoweredBy)
        addRow(binding.sectionHttp, "Redirect", r.httpRedirectLocation)
        addRow(binding.sectionHttp, "HTTP Time",
            r.httpTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(binding.sectionHttp, "HTTP OK",
            when (r.httpOk) { true -> "✅ Yes"; false -> "❌ No"; else -> null })
        addRow(binding.sectionHttp, "HSTS max-age",
            r.hstsMaxAge?.let { s ->
                val days = s / 86400
                "✅ ${days}d (${s}s)"
            })
        addRow(binding.sectionHttp, "X-Frame-Options", r.xFrameOptions)
        addRow(binding.sectionHttp, "Alt-Svc (HTTP/3)", r.altSvc)
        addRow(binding.sectionHttp, "HEAD Request",
            when (r.httpHeadOk) { true -> "✅ OK"; false -> "❌ Failed"; else -> null })
        addRow(binding.sectionHttp, "robots.txt snippet", r.httpRobotsTxt?.take(120))

        // ── DNS ──────────────────────────────────────────────────────────────
        addRow(binding.sectionDns, "Target IP", r.ip)
        addRow(binding.sectionDns, "IP matches DNS",
            when (r.ipMatchesDns) { true -> "✅ Yes"; false -> "⚠️ No"; else -> null },
            isError = r.ipMatchesDns == false)
        addRow(binding.sectionDns, "DNS Resolve Time",
            r.dnsResolveTime?.let { String.format(Locale.US, "%.3f s", it) })
        if (r.dnsResolvesTo.isNotEmpty())
            addRow(binding.sectionDns, "System DNS", r.dnsResolvesTo.joinToString(", "))
        if (r.dnsFromGoogle.isNotEmpty())
            addRow(binding.sectionDns, "Google DoH (8.8.8.8)", r.dnsFromGoogle.joinToString(", "))
        if (r.dnsFromCloudflare.isNotEmpty())
            addRow(binding.sectionDns, "Cloudflare DoH (1.1.1.1)", r.dnsFromCloudflare.joinToString(", "))
        addRow(binding.sectionDns, "DNS Consistent",
            when (r.dnsConsistent) { true -> "✅ Yes"; false -> "⚠️ Mismatch"; else -> null },
            isError = r.dnsConsistent == false)
        if (r.dnsPoisoned == true)
            addRow(binding.sectionDns, "⚠️ DNS Poisoning", "SUSPECTED", isError = true)
        if (r.errors.isNotEmpty())
            addRow(binding.sectionDns, "Errors", r.errors.joinToString("; "), isError = true)

        binding.cardRawOutput.visibility = View.VISIBLE
        binding.tvRawOutput.text = r.toString().replace(", ", ",\n")
    }

    private fun portStatus(reachable: Boolean?): String? = when (reachable) {
        true  -> "✅ Open"
        false -> "❌ Closed"
        null  -> null
    }

    private fun tlsVerStatus(ok: Boolean?): String? = when (ok) {
        true  -> "✅ Supported"
        false -> "❌ Not supported"
        null  -> null
    }

    private fun httpStatusText(code: Int?): String = when (code) {
        200 -> "OK"; 301 -> "Moved"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        500 -> "Server Error"; 502 -> "Bad Gateway"; 503 -> "Unavailable"
        else -> ""
    }

    private fun displayListSummary(results: List<CheckResult>, host: String) {
        binding.layoutResults.visibility = View.VISIBLE
        listOf(binding.sectionTcp, binding.sectionHttp, binding.sectionDns, binding.sectionGeo)
            .forEach { it.root.visibility = View.GONE }

        val filterLowPing = binding.chkLowPing.isChecked
        val filtered = if (filterLowPing) results.filter { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD } else results
        val sorted = filtered.sortedWith(
            compareByDescending<CheckResult> { it.tlsOk == true }.thenBy { it.rttMs ?: Double.MAX_VALUE }
        )

        val workingCount = results.count { it.tlsOk == true }
        showVerdict(
            "$workingCount / ${results.size} SNI working",
            "Host: $host",
            if (workingCount > 0) VerdictType.OK else VerdictType.ERROR
        )

        val container = binding.sectionTls.containerRows
        container.removeAllViews()
        sorted.forEach { r ->
            val row = ItemResultRowBinding.inflate(layoutInflater, container, false)
            row.tvLabel.text = r.sni
            val status = when { r.tlsOk == true -> "OK"; r.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            val ping = r.rttMs?.let { "[${it.toInt()}ms]" } ?: "[---]"
            row.tvValue.text = "$status $ping"
            row.tvValue.setTextColor(
                ContextCompat.getColor(this, if (r.tlsOk == true) R.color.success else R.color.error)
            )
            container.addView(row.root)
        }
        binding.sectionTls.root.visibility = View.VISIBLE
    }

    private fun showVerdict(verdict: String, subtitle: String, type: VerdictType) {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val bgRes = when (type) {
            VerdictType.OK -> if (isNight) R.color.verdict_ok_bg_dark else R.color.verdict_ok_bg_light
            VerdictType.ERROR -> if (isNight) R.color.verdict_error_bg_dark else R.color.verdict_error_bg_light
            VerdictType.WARNING -> if (isNight) R.color.verdict_warning_bg_dark else R.color.verdict_warning_bg_light
        }
        val textRes = when (type) {
            VerdictType.OK -> if (isNight) R.color.verdict_ok_text_dark else R.color.verdict_ok_text_light
            VerdictType.ERROR -> if (isNight) R.color.verdict_error_text_dark else R.color.verdict_error_text_light
            VerdictType.WARNING -> if (isNight) R.color.verdict_warning_text_dark else R.color.verdict_warning_text_light
        }
        val icon = when (type) {
            VerdictType.OK -> "✅"
            VerdictType.ERROR -> "❌"
            VerdictType.WARNING -> "⚠️"
        }

        binding.cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, bgRes))
        val textColor = ContextCompat.getColor(this, textRes)
        binding.tvVerdictIcon.text = icon
        binding.tvVerdict.text = verdict
        binding.tvVerdict.setTextColor(textColor)
        binding.tvTotalTime.text = subtitle
        binding.tvTotalTime.setTextColor(textColor)
        binding.cardVerdict.visibility = View.VISIBLE
    }

    private fun getVerdictText(key: String): String = when (key) {
        "VERDICT_OK_FULL" -> getString(R.string.verdict_ok_full)
        "VERDICT_OK_TLS" -> getString(R.string.verdict_ok_tls)
        "VERDICT_BLOCKED_TCP" -> getString(R.string.verdict_blocked_tcp)
        "VERDICT_UNCERTAIN_DPI" -> getString(R.string.verdict_uncertain_dpi)
        else -> getString(R.string.verdict_uncertain_data)
    }

    private fun clearResults() {
        listOf(binding.sectionTcp, binding.sectionTls, binding.sectionHttp,
            binding.sectionDns, binding.sectionGeo).forEach {
            it.containerRows.removeAllViews()
            it.root.visibility = View.GONE
        }
        setupSectionTitles()
        binding.layoutResults.visibility = View.GONE
        binding.cardVerdict.visibility = View.GONE
        binding.cardRawOutput.visibility = View.GONE
        binding.tvRawOutput.text = ""
        allResults = emptyList()
        lastSingleResult = null
        binding.btnExportCsv.visibility = View.GONE
    }

    // ── Progress / Timer ──────────────────────────────────────────────────────

    private fun updateCheckButtonState(running: Boolean) {
        binding.btnCheck.text = if (running) getString(R.string.btn_pause) else getString(R.string.btn_check)
        binding.cardProgress.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnPause.visibility = if (running) View.VISIBLE else View.GONE
        binding.btnCancel.visibility = if (running) View.VISIBLE else View.GONE
        binding.cardTerminal.visibility =
            if (running || currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE

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
                    binding.tvTimer.text =
                        String.format(getString(R.string.time_elapsed), elapsed / 60, elapsed % 60)

                    if (totalItemsCount > 1 && processedItemsCount > 0) {
                        val avg = elapsed.toDouble() / processedItemsCount
                        val rem = ((totalItemsCount - processedItemsCount) * avg).toLong()
                        binding.tvRemaining.text =
                            String.format(getString(R.string.time_remaining), rem / 60, rem % 60)
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

    // ── Notification ticker ──────────────────────────────────────────────────

    private fun startNotificationTicker() {
        stopNotificationTicker()
        notifTickerJob = scope.launch {
            while (isActive) {
                pushNotificationSnapshot()
                delay(1000)
            }
        }
    }

    private fun stopNotificationTicker() {
        notifTickerJob?.cancel()
        notifTickerJob = null
    }

    private fun pushNotificationSnapshot() {
        if (!backgroundServiceStarted) return

        val elapsedSec = ((System.currentTimeMillis() - startTimeMillis) / 1000).coerceAtLeast(0)
        val done = processedItemsCount
        val total = totalItemsCount
        val etaSec: Long? = if (total > 1 && done > 0) {
            val avg = elapsedSec.toDouble() / done
            ((total - done) * avg).toLong().coerceAtLeast(0)
        } else null

        val percent = if (total > 0) (done * 100 / total) else 0
        val current = currentSniDisplay.ifEmpty { "—" }
        val elapsedStr = formatMmSs(elapsedSec)
        val etaStr = etaSec?.let { formatMmSs(it) } ?: "--:--"

        val titleMode = when (currentMode) {
            CheckMode.WHITELIST -> "Whitelist"
            CheckMode.MASSIVE -> "Massive"
            CheckMode.SINGLE -> "Single"
        }
        val modeSuffix = if (fastMode && currentMode != CheckMode.SINGLE) " · ⚡×$fastThreads" else ""
        val title = "$titleMode$modeSuffix"

        val text = getString(R.string.notif_text_fmt, current, elapsedStr, etaStr)
        val sub = if (total > 0) getString(R.string.notif_sub, done, total, percent) else null

        val bigText = buildString {
            append(getString(R.string.notif_big_current, current))
            if (total > 0) {
                append('\n')
                append(getString(R.string.notif_big_progress, done, total, percent))
            }
            append('\n')
            append(getString(R.string.notif_big_elapsed, elapsedStr))
            if (etaSec != null) {
                append('\n')
                append(getString(R.string.notif_big_remaining, etaStr))
            }
            append('\n')
            append(
                if (fastMode && currentMode != CheckMode.SINGLE)
                    getString(R.string.notif_big_mode_fast, fastThreads)
                else getString(R.string.notif_big_mode_seq)
            )
            if (isPaused) append("\n⏸ PAUSED")
        }

        CheckForegroundService.update(
            this,
            title,
            text,
            sub,
            bigText,
            done,
            total
        )
    }

    private fun formatMmSs(seconds: Long): String =
        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    // ── File upload / CSV export ─────────────────────────────────────────────

    private fun loadSniListFromUri(uri: Uri) {
        try {
            val lines = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).useLines { seq ->
                    seq.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            } ?: emptyList()
            if (lines.isEmpty()) {
                Toast.makeText(this, R.string.csv_nothing, Toast.LENGTH_SHORT).show()
                return
            }
            binding.etSniList.setText(lines.joinToString("\n"))
            Toast.makeText(this, getString(R.string.txt_loaded, lines.size), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.txt_load_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportCsv(results: List<CheckResult>, baseName: String) {
        if (results.isEmpty()) {
            Toast.makeText(this, R.string.csv_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        pendingCsvText = CsvExporter.toCsv(results)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        csvSaveLauncher.launch("${baseName}_$stamp.csv")
    }

    private fun writeCsvToUri(uri: Uri) {
        val text = pendingCsvText ?: return
        pendingCsvText = null
        try {
            contentResolver.openOutputStream(uri, "w")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            Toast.makeText(this, R.string.csv_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.csv_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Misc ──────────────────────────────────────────────────────────────────

    private fun logToTerminal(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        binding.tvTerminal.append("[$time] $msg\n")
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SNI Results", text))
        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
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
        notifTickerJob?.cancel()
        scope.cancel()
        uiTranslator?.close()
        stopForegroundService()
    }

    enum class CheckMode { SINGLE, WHITELIST, MASSIVE }
}
