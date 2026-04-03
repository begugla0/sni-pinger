package com.begugla.snipinger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CheckMode { SINGLE, WHITELIST, MASSIVE }

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // UI Controls
    private lateinit var tilSni: TextInputLayout
    private lateinit var etIp: TextInputEditText
    private lateinit var etSni: TextInputEditText
    private lateinit var tilSniList: TextInputLayout
    private lateinit var etSniList: TextInputEditText
    private lateinit var btnCheck: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvProgressCount: TextView
    private lateinit var cardProgress: MaterialCardView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var controlButtons: LinearLayout
    private lateinit var btnPause: ImageButton
    private lateinit var btnCancel: ImageButton
    private lateinit var listControls: LinearLayout
    private lateinit var chkLowPing: MaterialCheckBox
    private lateinit var btnCopyAll: Button

    // Results
    private lateinit var cardVerdict: MaterialCardView
    private lateinit var tvVerdictIcon: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var layoutResults: LinearLayout
    private lateinit var sectionTcp: View
    private lateinit var sectionTls: View
    private lateinit var sectionHttp: View
    private lateinit var sectionDns: View
    private lateinit var cardRawOutput: MaterialCardView
    private lateinit var tvRawOutput: TextView
    private lateinit var btnCopyRaw: Button

    // Terminal
    private lateinit var cardTerminal: MaterialCardView
    private lateinit var tvTerminal: TextView
    private lateinit var btnToggleTerminal: ImageButton
    private val terminalLog = StringBuilder()
    private var terminalExpanded = true

    // Logic
    private val checker = WhitelistChecker()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var checkJob: Job? = null
    private var isPaused = false
    private val pauseLock = Object()
    private var isCancelled = false

    private var currentMode = CheckMode.SINGLE
    private val whitelistUrl = "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/whitelist.txt"
    private var allResults: List<CheckResult> = emptyList()

    companion object {
        private const val LOW_PING_THRESHOLD = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("snipinger_prefs", Context.MODE_PRIVATE)

        // Init views
        tilSni = findViewById(R.id.tilSni)
        etIp = findViewById(R.id.etIp)
        etSni = findViewById(R.id.etSni)
        tilSniList = findViewById(R.id.tilSniList)
        etSniList = findViewById(R.id.etSniList)
        btnCheck = findViewById(R.id.btnCheck)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvProgressCount = findViewById(R.id.tvProgressCount)
        cardProgress = findViewById(R.id.cardProgress)
        toggleGroup = findViewById(R.id.toggleGroup)
        controlButtons = findViewById(R.id.controlButtons)
        btnPause = findViewById(R.id.btnPause)
        btnCancel = findViewById(R.id.btnCancel)
        listControls = findViewById(R.id.listControls)
        chkLowPing = findViewById(R.id.chkLowPing)
        btnCopyAll = findViewById(R.id.btnCopyAll)

        cardVerdict = findViewById(R.id.cardVerdict)
        tvVerdictIcon = findViewById(R.id.tvVerdictIcon)
        tvVerdict = findViewById(R.id.tvVerdict)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        layoutResults = findViewById(R.id.layoutResults)
        sectionTcp = findViewById(R.id.sectionTcp)
        sectionTls = findViewById(R.id.sectionTls)
        sectionHttp = findViewById(R.id.sectionHttp)
        sectionDns = findViewById(R.id.sectionDns)
        cardRawOutput = findViewById(R.id.cardRawOutput)
        tvRawOutput = findViewById(R.id.tvRawOutput)
        btnCopyRaw = findViewById(R.id.btnCopyRaw)

        cardTerminal = findViewById(R.id.cardTerminal)
        tvTerminal = findViewById(R.id.tvTerminal)
        btnToggleTerminal = findViewById(R.id.btnToggleTerminal)

        setupSection(sectionTcp, "TCP Соединение")
        setupSection(sectionTls, "TLS Handshake")
        setupSection(sectionHttp, "HTTP Запрос")
        setupSection(sectionDns, "IP / DNS Инфо")

        // Load saved values
        etIp.setText(prefs.getString("last_ip", "1.1.1.1"))
        etSni.setText(prefs.getString("last_sni", "vk.com"))
        etSniList.setText(prefs.getString("last_sni_list", "vk.com\nyandex.ru\ngoogle.com"))

        // Restore mode state
        val savedMode = prefs.getString("last_mode", "single") ?: "single"
        val savedModeEnum = CheckMode.entries.find { it.name.lowercase() == savedMode } ?: CheckMode.SINGLE
        currentMode = savedModeEnum
        when (currentMode) {
            CheckMode.SINGLE -> toggleGroup.check(R.id.btnModeSingle)
            CheckMode.WHITELIST -> toggleGroup.check(R.id.btnModeWhitelist)
            CheckMode.MASSIVE -> toggleGroup.check(R.id.btnModeMassive)
        }
        updateModeUI(currentMode)

        // Mode toggle
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentMode = when (checkedId) {
                R.id.btnModeWhitelist -> CheckMode.WHITELIST
                R.id.btnModeMassive -> CheckMode.MASSIVE
                else -> CheckMode.SINGLE
            }
            prefs.edit().putString("last_mode", currentMode.name.lowercase()).apply()
            updateModeUI(currentMode)
        }

        btnCheck.setOnClickListener {
            if (isPaused) {
                resumeCheck()
            } else if (checkJob?.isActive == true) {
                pauseCheck()
            } else {
                performCheck()
            }
        }

        btnPause.setOnClickListener {
            if (isPaused) resumeCheck() else pauseCheck()
        }

        btnCancel.setOnClickListener { cancelCheck() }

        btnCopyRaw.setOnClickListener {
            copyToClipboard(tvRawOutput.text.toString())
        }

        btnCopyAll.setOnClickListener {
            val text = when (currentMode) {
                CheckMode.MASSIVE -> buildMassiveResultText(allResults)
                else -> buildFullResultText(allResults)
            }
            copyToClipboard(text)
        }

        chkLowPing.setOnCheckedChangeListener { _, _ ->
            if (currentMode != CheckMode.SINGLE && allResults.isNotEmpty()) {
                displayListSummary(allResults, etIp.text.toString().trim())
            }
        }

        btnToggleTerminal.setOnClickListener {
            terminalExpanded = !terminalExpanded
            cardTerminal.layoutParams.height = if (terminalExpanded) 180 else 60
            btnToggleTerminal.setImageResource(
                if (terminalExpanded) android.R.drawable.arrow_down_float
                else android.R.drawable.arrow_up_float
            )
            cardTerminal.requestLayout()
        }
    }

    private fun updateModeUI(mode: CheckMode) {
        when (mode) {
            CheckMode.SINGLE -> {
                tilSni.visibility = View.VISIBLE
                tilSniList.visibility = View.GONE
                listControls.visibility = View.GONE
            }
            CheckMode.WHITELIST -> {
                tilSni.visibility = View.GONE
                tilSniList.visibility = View.GONE
                listControls.visibility = View.VISIBLE
                btnCopyAll.visibility = View.GONE
                chkLowPing.visibility = View.VISIBLE
            }
            CheckMode.MASSIVE -> {
                tilSni.visibility = View.GONE
                tilSniList.visibility = View.VISIBLE
                listControls.visibility = View.VISIBLE
                btnCopyAll.visibility = View.GONE
                chkLowPing.visibility = View.VISIBLE
            }
        }
        terminalLog.clear()
        tvTerminal.text = ""
    }

    private fun setupSection(view: View, title: String) {
        view.findViewById<TextView>(R.id.tvSectionTitle).text = title
    }

    private fun addRow(section: View, label: String, value: Any?, isError: Boolean = false) {
        if (value == null) return
        val container = section.findViewById<LinearLayout>(R.id.containerRows)
        val rowView = LayoutInflater.from(this).inflate(R.layout.item_result_row, container, false)
        rowView.findViewById<TextView>(R.id.tvLabel).text = label
        val tvValue = rowView.findViewById<TextView>(R.id.tvValue)
        tvValue.text = value.toString()
        if (isError) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        } else if (value.toString().contains("OK") || value.toString().contains("Доступен") || value.toString().contains("Да")) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        }
        container.addView(rowView)
        section.visibility = View.VISIBLE
    }

    private fun clearResults() {
        listOf(sectionTcp, sectionTls, sectionHttp, sectionDns).forEach {
            it.findViewById<LinearLayout>(R.id.containerRows).removeAllViews()
            it.visibility = View.GONE
        }
        cardVerdict.visibility = View.GONE
        layoutResults.visibility = View.GONE
        cardRawOutput.visibility = View.GONE
        allResults = emptyList()
        btnCopyAll.visibility = View.GONE
    }

    private fun performCheck() {
        val ip = etIp.text.toString().trim()

        prefs.edit().putString("last_ip", ip).apply()

        val snis: List<String> = when (currentMode) {
            CheckMode.SINGLE -> {
                val sni = etSni.text.toString().trim()
                if (sni.isEmpty()) return
                prefs.edit().putString("last_sni", sni).apply()
                listOf(sni)
            }
            CheckMode.WHITELIST -> emptyList() // Will be loaded async
            CheckMode.MASSIVE -> {
                val listText = etSniList.text.toString().trim()
                if (listText.isEmpty()) return
                prefs.edit().putString("last_sni_list", listText).apply()
                listText.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        }

        btnCheck.text = "Проверка..."
        cardProgress.visibility = View.VISIBLE
        controlButtons.visibility = if (currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE
        cardTerminal.visibility = if (currentMode != CheckMode.SINGLE) View.VISIBLE else View.GONE
        btnPause.setImageResource(android.R.drawable.ic_media_pause)
        isPaused = false
        isCancelled = false
        clearResults()
        terminalLog.clear()

        checkJob = scope.launch {
            if (currentMode == CheckMode.WHITELIST) {
                performWhitelistCheck(ip)
            } else if (currentMode == CheckMode.MASSIVE) {
                performMassiveCheck(ip, snis)
            } else {
                val sni = snis.first()
                val result = withContext(Dispatchers.IO) { checker.checkIp(ip, sni) }
                if (!isCancelled) displayResult(result)
            }
            onCheckFinished()
        }
    }

    private fun pauseCheck() {
        isPaused = true
        btnCheck.text = "▶ Продолжить"
        btnPause.setImageResource(android.R.drawable.ic_media_play)
        appendToTerminal("[PAUSED]")
    }

    private fun resumeCheck() {
        isPaused = false
        btnCheck.text = "⏸ Пауза"
        btnPause.setImageResource(android.R.drawable.ic_media_pause)
        appendToTerminal("[RESUMED]")
        synchronized(pauseLock) { pauseLock.notifyAll() }
    }

    private fun cancelCheck() {
        isCancelled = true
        synchronized(pauseLock) { pauseLock.notifyAll() }
        checkJob?.cancel()
        checkJob = null
        isPaused = false
        onCheckFinished()
        appendToTerminal("[CANCELLED]")
    }

    private fun onCheckFinished() {
        runOnUiThread {
            btnCheck.text = "Проверить"
            controlButtons.visibility = View.GONE
            cardProgress.visibility = View.GONE
            isPaused = false
            checkJob = null
        }
    }

    private suspend fun performWhitelistCheck(ip: String) {
        val snis = withContext(Dispatchers.IO) {
            try {
                appendToTerminal("Loading whitelist from $whitelistUrl...")
                val text = URL(whitelistUrl).readText()
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                appendToTerminal("Loaded ${lines.size} SNI entries")
                lines
            } catch (e: Exception) {
                appendToTerminal("ERROR: ${e.message}")
                emptyList<String>()
            }
        }

        if (snis.isEmpty() || isCancelled) {
            if (!isCancelled) {
                withContext(Dispatchers.Main) {
                    showVerdict("Ошибка загрузки списка", "Проверьте подключение", R.color.status_error)
                }
            }
            return
        }

        val results = mutableListOf<CheckResult>()
        val total = snis.size

        snis.forEachIndexed { index, sni ->
            if (isCancelled) return@forEachIndexed
            synchronized(pauseLock) {
                if (isPaused) {
                    runOnUiThread { appendToTerminal("[WAITING...]") }
                    pauseLock.wait()
                    runOnUiThread { appendToTerminal("") }
                }
            }
            if (isCancelled) return@forEachIndexed

            withContext(Dispatchers.Main) {
                tvProgress.text = sni
                tvProgressCount.text = "${index + 1} / $total"
                progressBar.max = total
                progressBar.progress = index + 1
            }

            val result = withContext(Dispatchers.IO) { checker.checkIp(ip, sni) }
            results.add(result)
            appendToTerminal(result.toTerminalLine())

            withContext(Dispatchers.Main) {
                allResults = results.toList()
                displayListSummary(results, ip)
                btnCopyAll.visibility = View.VISIBLE
            }
        }
        if (!isCancelled) appendToTerminal("=== CHECK COMPLETE ===")
    }

    private suspend fun performMassiveCheck(ip: String, snis: List<String>) {
        val results = mutableListOf<CheckResult>()
        val total = snis.size

        snis.forEachIndexed { index, sni ->
            if (isCancelled) return@forEachIndexed
            synchronized(pauseLock) {
                if (isPaused) {
                    runOnUiThread { appendToTerminal("[WAITING...]") }
                    pauseLock.wait()
                    runOnUiThread { appendToTerminal("") }
                }
            }
            if (isCancelled) return@forEachIndexed

            withContext(Dispatchers.Main) {
                tvProgress.text = sni
                tvProgressCount.text = "${index + 1} / $total"
                progressBar.max = total
                progressBar.progress = index + 1
            }

            val result = withContext(Dispatchers.IO) { checker.checkIp(ip, sni) }
            results.add(result)
            appendToTerminal(result.toTerminalLine())

            withContext(Dispatchers.Main) {
                allResults = results.toList()
                displayListSummary(results, ip)
                btnCopyAll.visibility = View.VISIBLE
            }
        }
        if (!isCancelled) appendToTerminal("=== MASSIVE CHECK COMPLETE ===")
    }

    private fun CheckResult.toTerminalLine(): String {
        val status = when {
            tlsOk == true -> "OK"
            tcpReachable == true -> "TLS_FAIL"
            else -> "BLOCKED"
        }
        val pingStr = rttMs?.let { "${it.toInt()}ms" } ?: "---"
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return "[$ts] [$status] $pingStr $sni"
    }

    private fun displayListSummary(results: List<CheckResult>, ip: String) {
        layoutResults.visibility = View.VISIBLE
        val filterLowPing = chkLowPing.isChecked
        val filteredResults = if (filterLowPing) {
            results.filter { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD }
        } else {
            results
        }

        val sortedResults = filteredResults.sortedWith(
            compareByDescending<CheckResult> { it.tlsOk == true }
                .thenByDescending { it.tcpReachable == true }
                .thenBy { it.rttMs ?: Double.MAX_VALUE }
        )

        val workingCount = results.count { it.tlsOk == true }
        val lowPingCount = results.count { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD }

        val modeLabel = if (currentMode == CheckMode.MASSIVE) "SNI" else "SNI (whitelist)"
        val pingFilterText = if (filterLowPing) " (<=${LOW_PING_THRESHOLD}ms: $lowPingCount)" else ""

        showVerdict(
            "$workingCount / ${results.size} $modeLabel работают$pingFilterText",
            "IP: $ip",
            if (workingCount > 0) R.color.status_ok else R.color.status_error
        )

        sectionTls.findViewById<LinearLayout>(R.id.containerRows).removeAllViews()
        sortedResults.forEach { r ->
            val status = when {
                r.tlsOk == true -> "OK"
                r.tcpReachable == true -> "TLS_FAIL"
                else -> "BLOCKED"
            }
            val ping = r.rttMs?.let { "[${it.toInt()}ms]" } ?: "[---]"
            addRow(sectionTls, r.sni, "$status $ping", r.tlsOk != true)
        }

        sectionTls.visibility = View.VISIBLE
        val sectionTitle = if (currentMode == CheckMode.MASSIVE) "Массовая проверка" else "SNI Результаты"
        setupSection(sectionTls, "$sectionTitle (sorted)")
        scrollToBottom()
    }

    private fun showVerdict(verdict: String, subtitle: String, colorRes: Int) {
        tvVerdict.text = verdict
        tvTotalTime.text = subtitle
        cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
        cardVerdict.visibility = View.VISIBLE
    }

    private fun displayResult(r: CheckResult) {
        layoutResults.visibility = View.VISIBLE
        showVerdict(r.verdict, String.format(Locale.US, "Time: %.1fs", r.totalTime),
            when (r.inWhitelist) { true -> R.color.status_ok; false -> R.color.status_error; null -> R.color.status_warning })

        addRow(sectionTcp, "Доступность", if (r.tcpReachable == true) "Доступен" else "Заблокирован", r.tcpReachable == false)
        addRow(sectionTcp, "Connect", r.tcpConnectTime?.let { String.format(Locale.US, "%.3fs", it) })
        addRow(sectionTcp, "RTT", r.rttMs?.let { String.format(Locale.US, "%.0fms", it) })

        if (r.tlsOk != null) {
            addRow(sectionTls, "TLS", if (r.tlsOk == true) "OK" else "FAIL", r.tlsOk == false)
            addRow(sectionTls, "Version", r.tlsVersion)
            addRow(sectionTls, "Cipher", r.tlsCipher)
            addRow(sectionTls, "SNI Match", if (r.certSniMatch == true) "Yes" else "No")
        }

        if (r.httpStatusLine != null) {
            addRow(sectionHttp, "Status", r.httpStatusLine)
            addRow(sectionHttp, "Server", r.httpServerHeader)
        }

        addRow(sectionDns, "IP", r.ip)
        addRow(sectionDns, "Version", "IPv${r.ipVersion}")
        addRow(sectionDns, "Local", if (r.ipIsPrivate == true) "Yes" else "No")
        if (r.dnsResolvesTo.isNotEmpty()) addRow(sectionDns, "DNS", r.dnsResolvesTo.joinToString(", "))
        if (r.errors.isNotEmpty()) r.errors.forEach { addRow(sectionTcp, "Error", it, true) }

        cardRawOutput.visibility = View.VISIBLE
        tvRawOutput.text = r.toString().replace(", ", ",\n")
    }

    private fun buildFullResultText(results: List<CheckResult>): String = buildString {
        append("=== SNI Pinger Whitelist ===\n")
        append("IP: ${etIp.text}\n")
        append("Total: ${results.size} | Working: ${results.count { it.tlsOk == true }}\n\n")
        results.forEach { r ->
            val status = when {
                r.tlsOk == true -> "OK"
                r.tcpReachable == true -> "TLS_FAIL"
                else -> "BLOCKED"
            }
            append("--- ${r.sni} [$status] ---\n")
            if (r.rttMs != null) append("Ping: ${r.rttMs?.toInt()}ms\n")
            if (r.tlsVersion != null) append("TLS: ${r.tlsVersion}\n")
            if (r.tlsCipher != null) append("Cipher: ${r.tlsCipher}\n")
            if (r.httpStatusLine != null) append("HTTP: ${r.httpStatusLine}\n")
            append("\n")
        }
    }

    private fun buildMassiveResultText(results: List<CheckResult>): String = buildString {
        append("=== SNI Pinger Massive Report ===\n")
        append("IP: ${etIp.text}\n")
        append("Total: ${results.size} | Working: ${results.count { it.tlsOk == true }}\n")
        append("Failures: ${results.count { it.tcpReachable == false }}\n\n")
        results.forEach { r ->
            val status = when {
                r.tlsOk == true -> "OK"
                r.tcpReachable == true -> "TLS_FAIL"
                else -> "BLOCKED"
            }
            append("═══════════════════════════════════\n")
            append("SNI: ${r.sni}\n")
            append("Status: $status\n")
            append("RTT: ${r.rttMs?.toInt()}ms\n")
            append("TCP: ${r.tcpReachable}\n")
            append("TLS: ${r.tlsOk}\n")
            if (r.tlsVersion != null) append("TLS Ver: ${r.tlsVersion}\n")
            if (r.tlsCipher != null) append("TLS Cipher: ${r.tlsCipher}\n")
            if (r.httpStatusLine != null) append("HTTP: ${r.httpStatusLine}\n")
            if (r.httpServerHeader != null) append("Server: ${r.httpServerHeader}\n")
            if (r.errors.isNotEmpty()) {
                append("Errors: ${r.errors.joinToString("; ")})\n")
            }
            append("═══════════════════════════════════\n\n")
        }
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("SNI Pinger", text))
        Toast.makeText(this, "Скопировано!", Toast.LENGTH_SHORT).show()
    }

    private fun appendToTerminal(text: String) {
        if (isCancelled && text != "[CANCELLED]") return
        runOnUiThread {
            if (text.isNotEmpty()) terminalLog.append(text).append("\n")
            tvTerminal.text = terminalLog.toString()
            scrollToBottom()
        }
    }

    private fun scrollToBottom() {
        (tvTerminal.parent as? NestedScrollView)?.post {
            tvTerminal.parent?.let { (it as? NestedScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        synchronized(pauseLock) { pauseLock.notifyAll() }
        checkJob?.cancel()
        scope.cancel()
    }
}
