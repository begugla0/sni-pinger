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

    private lateinit var cardVerdict: MaterialCardView
    private lateinit var tvVerdictIcon: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var layoutResults: LinearLayout
    private lateinit var sectionTcp: View
    private lateinit var sectionTls: View
    private lateinit var sectionHttp: View
    private lateinit var sectionDns: View
    private lateinit var sectionGeo: View
    private lateinit var cardRawOutput: MaterialCardView
    private lateinit var tvRawOutput: TextView
    private lateinit var btnCopyRaw: Button

    private lateinit var cardTerminal: MaterialCardView
    private lateinit var tvTerminal: TextView
    private lateinit var btnToggleTerminal: ImageButton
    private val terminalLog = StringBuilder()
    private var terminalExpanded = true

    private val checker = WhitelistChecker()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var checkJob: Job? = null
    private var isPaused = false
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
        
        val sectionGeoView = layoutInflater.inflate(R.layout.item_result_section, layoutResults, false)
        (layoutResults as LinearLayout).addView(sectionGeoView, 0)
        sectionGeo = sectionGeoView
        
        cardRawOutput = findViewById(R.id.cardRawOutput)
        tvRawOutput = findViewById(R.id.tvRawOutput)
        btnCopyRaw = findViewById(R.id.btnCopyRaw)

        cardTerminal = findViewById(R.id.cardTerminal)
        tvTerminal = findViewById(R.id.tvTerminal)
        btnToggleTerminal = findViewById(R.id.btnToggleTerminal)

        setupSection(sectionTcp, "🔌 TCP / Network")
        setupSection(sectionTls, "🔒 TLS / Certificates")
        setupSection(sectionHttp, "🌐 HTTP")
        setupSection(sectionDns, "🎯 IP / DNS")
        setupSection(sectionGeo, "🌍 Geo / Ownership")

        etIp.setText(prefs.getString("last_ip", "1.1.1.1"))
        etSni.setText(prefs.getString("last_sni", "vk.com"))
        etSniList.setText(prefs.getString("last_sni_list", "vk.com\nyandex.ru\ngoogle.com"))

        val savedMode = prefs.getString("last_mode", "single") ?: "single"
        val savedModeEnum = CheckMode.entries.find { it.name.lowercase() == savedMode } ?: CheckMode.SINGLE
        currentMode = savedModeEnum
        when (currentMode) {
            CheckMode.SINGLE -> toggleGroup.check(R.id.btnModeSingle)
            CheckMode.WHITELIST -> toggleGroup.check(R.id.btnModeWhitelist)
            CheckMode.MASSIVE -> toggleGroup.check(R.id.btnModeMassive)
        }
        updateModeUI(currentMode)

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
            if (isPaused) resumeCheck()
            else if (checkJob?.isActive == true) pauseCheck()
            else performCheck()
        }

        btnPause.setOnClickListener { if (isPaused) resumeCheck() else pauseCheck() }
        btnCancel.setOnClickListener { cancelCheck() }
        btnCopyRaw.setOnClickListener { copyToClipboard(tvRawOutput.text.toString()) }

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
                if (terminalExpanded) android.R.drawable.arrow_down_float else android.R.drawable.arrow_up_float
            )
            cardTerminal.requestLayout()
        }
    }

    private fun updateModeUI(mode: CheckMode) {
        tilSni.visibility = if (mode == CheckMode.SINGLE) View.VISIBLE else View.GONE
        tilSniList.visibility = if (mode == CheckMode.MASSIVE) View.VISIBLE else View.GONE
        listControls.visibility = if (mode != CheckMode.SINGLE) View.VISIBLE else View.GONE
        btnCopyAll.visibility = View.GONE
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
        val displayValue = value.toString()
        tvValue.text = displayValue
        if (isError) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        } else if (displayValue.contains("OK") || displayValue.contains("Доступен") || displayValue.contains("Да") || displayValue.contains("✅") || displayValue.contains("Open") || displayValue.contains("Reachable") || displayValue.contains("Success") || displayValue.contains("Supported") || displayValue.contains("Match") || displayValue.contains("Yes")) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        } else if (displayValue.contains("FAIL") || displayValue.contains("Нет") || displayValue.contains("❌") || displayValue.contains("BLOCKED") || displayValue.contains("Blocked") || displayValue.contains("Failed") || displayValue.contains("Closed") || displayValue.contains("Not supported")) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        } else if (displayValue.contains("───") || displayValue.contains("N/A") || displayValue.contains("❓")) {
            tvValue.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        }
        container.addView(rowView)
        section.visibility = View.VISIBLE
    }

    private fun clearResults() {
        listOf(sectionTcp, sectionTls, sectionHttp, sectionDns, sectionGeo).forEach {
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
            CheckMode.WHITELIST -> emptyList()
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
            try {
                if (currentMode == CheckMode.WHITELIST) {
                    performWhitelistCheck(ip)
                } else if (currentMode == CheckMode.MASSIVE) {
                    performMassiveCheck(ip, snis)
                } else {
                    val sni = snis.first()
                    val result = withContext(Dispatchers.IO) { checker.checkIp(ip, sni) }
                    if (!isCancelled) displayResult(result)
                }
            } finally {
                onCheckFinished()
            }
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
    }
    
    private fun cancelCheck() {
        isCancelled = true
        checkJob?.cancel()
        checkJob = null
        isPaused = false
        onCheckFinished()
        appendToTerminal("[CANCELLED]")
    }

    private fun onCheckFinished() {
        runOnUiThread {
            btnCheck.text = "Проверить"; controlButtons.visibility = View.GONE; cardProgress.visibility = View.GONE
            isPaused = false; checkJob = null
        }
    }

    private suspend fun waitForResumeIfNeeded() {
        while (isPaused) {
            delay(200)
            if (isCancelled) return
        }
    }

    private suspend fun performWhitelistCheck(ip: String) {
        val snis = withContext(Dispatchers.IO) {
            try {
                appendToTerminal("Loading whitelist...")
                val text = URL(whitelistUrl).readText()
                text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            } catch (e: Exception) {
                appendToTerminal("ERROR: ${e.message}")
                emptyList()
            }
        }

        if (snis.isEmpty() || isCancelled) {
            if (!isCancelled) withContext(Dispatchers.Main) { showVerdict("Ошибка загрузки списка", "Проверьте подключение", R.color.status_error) }
            return
        }
        val results = mutableListOf<CheckResult>()
        val total = snis.size

        snis.forEachIndexed { index, sni ->
            if (isCancelled) return@forEachIndexed
            waitForResumeIfNeeded()
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
            waitForResumeIfNeeded()
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
        val status = when { tlsOk == true -> "OK"; tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
        val pingStr = rttMs?.let { "${it.toInt()}ms" } ?: "---"
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return "[$ts] [$status] $pingStr $sni"
    }

    private fun displayListSummary(results: List<CheckResult>, ip: String) {
        layoutResults.visibility = View.VISIBLE
        listOf(sectionTcp, sectionHttp, sectionDns, sectionGeo).forEach { it.visibility = View.GONE }
        
        val filterLowPing = chkLowPing.isChecked
        val filteredResults = if (filterLowPing) results.filter { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD } else results
        val sortedResults = filteredResults.sortedWith(compareByDescending<CheckResult> { it.tlsOk == true }.thenByDescending { it.tcpReachable == true }.thenBy { it.rttMs ?: Double.MAX_VALUE })

        val workingCount = results.count { it.tlsOk == true }
        val lowPingCount = results.count { it.rttMs != null && it.rttMs!! <= LOW_PING_THRESHOLD }
        val pingFilterText = if (filterLowPing) " (<=${LOW_PING_THRESHOLD}ms: $lowPingCount)" else ""
        val modeLabel = if (currentMode == CheckMode.MASSIVE) "SNI" else "SNI (whitelist)"
        showVerdict("$workingCount / ${results.size} $modeLabel работают$pingFilterText", "IP: $ip", if (workingCount > 0) R.color.status_ok else R.color.status_error)

        sectionTls.findViewById<LinearLayout>(R.id.containerRows).removeAllViews()
        sortedResults.forEach { r ->
            val status = when { r.tlsOk == true -> "OK"; r.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            val ping = r.rttMs?.let { "[${it.toInt()}ms]" } ?: "[---]"
            addRow(sectionTls, r.sni, "$status $ping", r.tlsOk != true)
        }
        sectionTls.visibility = View.VISIBLE
        setupSection(sectionTls, "Результаты SNI (отсортировано)")
        scrollToBottom()
    }

    private fun showVerdict(verdict: String, subtitle: String, colorRes: Int) {
        tvVerdict.text = verdict
        tvTotalTime.text = subtitle
        cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
        cardVerdict.visibility = View.VISIBLE
    }

    /**
     * FULL VISUAL DISPLAY — MAPS ALL FIELDS FROM RAW OUTPUT
     */
    private fun displayResult(r: CheckResult) {
        layoutResults.visibility = View.VISIBLE
        listOf(sectionTcp, sectionTls, sectionHttp, sectionDns, sectionGeo).forEach { 
            it.findViewById<LinearLayout>(R.id.containerRows).removeAllViews()
            it.visibility = View.GONE 
        }

        showVerdict(r.verdict, String.format(Locale.US, "Total time: %.2fs", r.totalTime),
            when (r.inWhitelist) { true -> R.color.status_ok; false -> R.color.status_error; null -> R.color.status_warning })

        // ═══════════════════════════════════════════════════════════════
        // 🌍 GEO IP + OWNERSHIP
        // ═══════════════════════════════════════════════════════════════
        val geo = r.ipGeoInfo
        if (geo != null) {
            addRow(sectionGeo, "🏢 Organization", geo.org)
            addRow(sectionGeo, "🔢 ASN", geo.asn)
            addRow(sectionGeo, "🏷️ Hostname", geo.hostname)
            addRow(sectionGeo, "📍 IP (Geo)", geo.ip) // IP from ipinfo
            addRow(sectionGeo, "🏙️ City", geo.city)
            addRow(sectionGeo, "🗺️ Region", geo.region)
            addRow(sectionGeo, "🏳️ Country", geo.country)
            addRow(sectionGeo, "🏳️ Country Code", geo.countryCode)
            addRow(sectionGeo, "📮 Postal Code", geo.postalCode)
            addRow(sectionGeo, "🕐 Timezone", geo.timezone)
            addRow(sectionGeo, "📡 Anycast", if (geo.anycast == true) "✅ Yes" else "❌ No")
        } else {
            addRow(sectionGeo, "🌍 Geo Info", "N/A (ipinfo.io failed)")
        }
        
        if (r.domainOwnerOrg != null) addRow(sectionGeo, "🔗 SNI Owner", r.domainOwnerOrg)
        if (r.domainResolvedIps.isNotEmpty()) addRow(sectionGeo, "🌐 Domain IPs", r.domainResolvedIps.joinToString(", "))

        // ═══════════════════════════════════════════════════════════════
        // 🔌 TCP / NETWORK
        // ═══════════════════════════════════════════════════════════════
        addRow(sectionTcp, "🔌 Port 443 (TCP)", if (r.tcpReachable == true) "✅ Reachable" else "❌ Blocked", r.tcpReachable == false)
        addRow(sectionTcp, "  ⏱️ Connect Time", r.tcpConnectTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(sectionTcp, "  📶 RTT (Ping)", r.rttMs?.let { String.format(Locale.US, "%.0f ms", it) })
        
        addRow(sectionTcp, "────────────────", "─ ─ ─ ─ ─ ─ ─ ─")
        
        addRow(sectionTcp, "🔌 Port 80 (HTTP)", if (r.tcp80Reachable == true) "✅ Open" else "❌ Closed", r.tcp80Reachable == false)
        addRow(sectionTcp, "  ⏱️ Connect Time", r.tcp80ConnectTime?.let { String.format(Locale.US, "%.3f s", it) })
        
        addRow(sectionTcp, "🔌 Port 53 (DNS)", if (r.tcp53Reachable == true) "✅ Open" else "❌ Closed", r.tcp53Reachable == false)
        addRow(sectionTcp, "🔌 Port 8080 (Proxy)", if (r.tcp8080Reachable == true) "✅ Open" else "❌ Closed", r.tcp8080Reachable == false)

        // ═══════════════════════════════════════════════════════════════
        // 🔒 TLS / CERTIFICATES
        // ═══════════════════════════════════════════════════════════════
        addRow(sectionTls, "🔒 TLS Handshake", if (r.tlsOk == true) "✅ Success" else "❌ Failed", r.tlsOk == false)
        addRow(sectionTls, "  ⏱️ Handshake Time", r.tlsTime?.let { String.format(Locale.US, "%.3f s", it) })
        addRow(sectionTls, "  📋 Protocol Version", r.tlsVersion)
        addRow(sectionTls, "  🔐 Cipher Suite", r.tlsCipher)
        
        addRow(sectionTls, "────────────────", "─ ─ ─ ─ ─ ─ ─ ─")
        
        addRow(sectionTls, "TLS 1.2", if (r.tls12Ok == true) "✅ Supported" else "❌ Not supported", r.tls12Ok == false)
        addRow(sectionTls, "TLS 1.3", if (r.tls13Ok == true) "✅ Supported" else "❌ Not supported", r.tls13Ok == false)
        addRow(sectionTls, "⚡ HTTP/2 (ALPN)", when (r.h2Supported) { true -> "✅ Yes"; false -> "❌ No"; null -> "❓ N/A" })
        
        addRow(sectionTls, "────────────────", "─ ─ ─ ─ ─ ─ ─ ─")
        
        addRow(sectionTls, "🎯 SNI Match", if (r.certSniMatch == true) "✅ Yes" else "❌ No", r.certSniMatch == false)
        addRow(sectionTls, "📋 Subject", r.certSubject)
        addRow(sectionTls, "📝 Issuer", r.certIssuer)
        addRow(sectionTls, "⏩ Valid From", r.certNotBefore)
        addRow(sectionTls, "⏪ Valid Until", r.certNotAfter)
        
        if (r.certSanList.isNotEmpty()) {
            val displaySans = if (r.certSanList.size > 10)
                r.certSanList.take(10).joinToString(",\n") + "\n... (+${r.certSanList.size - 10} more)"
            else r.certSanList.joinToString(",\n")
            addRow(sectionTls, "📜 SANs (${r.certSanList.size})", displaySans)
        }

        // ═══════════════════════════════════════════════════════════════
        // 🌐 HTTP (GET + HEAD)
        // ═══════════════════════════════════════════════════════════════
        addRow(sectionHttp, "📤 HTTP GET", if (r.httpOk == true) "✅ OK" else "❌ Failed", r.httpOk == false)
        addRow(sectionHttp, "  ⏱️ GET Time", r.httpTime?.let { String.format(Locale.US, "%.3f s", it) })
        
        addRow(sectionHttp, "📥 HTTP HEAD", if (r.httpHeadOk == true) "✅ OK" else "❌ Failed", r.httpHeadOk == false)
            
        if (r.httpStatusCode != null) addRow(sectionHttp, "  🔢 Status Code", r.httpStatusCode)
        if (r.httpStatusLine != null) addRow(sectionHttp, "  📜 Status Line", r.httpStatusLine)
        addRow(sectionHttp, "🖥️ Server Header", r.httpServerHeader)
        addRow(sectionHttp, "↪️ Redirect", r.httpRedirectLocation)
        if (r.httpRobotsTxt != null) addRow(sectionHttp, "🤖 robots.txt", r.httpRobotsTxt)

        // ═══════════════════════════════════════════════════════════════
        // 🎯 IP / DNS / PING
        // ═══════════════════════════════════════════════════════════════
        addRow(sectionDns, "💻 Target (IP/Host)", r.ip)
        addRow(sectionDns, " 🌐 Target SNI", r.sni)
        addRow(sectionDns, "  📡 IP Version", "IPv${r.ipVersion}")
        addRow(sectionDns, "  🔒 Is Private", if (r.ipIsPrivate == true) "✅ Yes" else "❌ No")
        addRow(sectionDns, "  🌐 Is Global", if (r.ipIsGlobal == true) "✅ Yes" else "❌ No")
        addRow(sectionDns, "  📋 Port", r.port.toString())
        addRow(sectionDns, "  ⏱️ Timeout", r.timeout.toString() + "s")
        
        addRow(sectionDns, "────────────────", "─ ─ ─ ─ ─ ─ ─ ─")
        
        addRow(sectionDns, "🔍 DNS Resolve Time", r.dnsResolveTime?.let { String.format(Locale.US, "%.3f s", it) })
        if (r.dnsResolvesTo.isNotEmpty()) addRow(sectionDns, "🌐 Resolves To", r.dnsResolvesTo.joinToString(", "))
        addRow(sectionDns, "✅ IP matches DNS", if (r.ipMatchesDns == true) "✅ Yes" else "❌ No", r.ipMatchesDns == false)

        // ICMP (Optional)
        if (r.icmpPing != null) addRow(sectionDns, "📶 ICMP Ping", "${r.icmpPing}ms")
        if (r.icmpLoss != null) addRow(sectionDns, "   Packet Loss", "${r.icmpLoss}%")

        // ═══════════════════════════════════════════════════════════════
        // ⚠️ ERRORS
        // ═══════════════════════════════════════════════════════════════
        if (r.errors.isNotEmpty()) {
            r.errors.forEach { addRow(sectionTcp, "⚠️ Error", it, true) }
        }

        // ═══════════════════════════════════════════════════════════════
        // 📄 RAW OUTPUT
        // ═══════════════════════════════════════════════════════════════
        cardRawOutput.visibility = View.VISIBLE
        tvRawOutput.text = r.toString().replace(", ", ",\n")
    }

    private fun buildFullResultText(results: List<CheckResult>): String = buildString {
        append("=== SNI Pinger ===\n")
        append("IP: ${etIp.text}\n")
        append("Total: ${results.size} | Working: ${results.count { it.tlsOk == true }}\n\n")
        results.forEach { r ->
            val status = when { r.tlsOk == true -> "OK"; r.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            append("--- ${r.sni} [$status] ---\n")
            if (r.ipGeoInfo != null) append("Geo: ${r.ipGeoInfo?.org ?: "?"}, ${r.ipGeoInfo?.city}\n")
            if (r.rttMs != null) append("Ping: ${r.rttMs?.toInt()}ms\n")
            append("TLS: ${r.tlsOk}, Ver: ${r.tlsVersion}\n")
            append("\n")
        }
    }

    private fun buildMassiveResultText(results: List<CheckResult>): String = buildString {
        append("=== SNI Pinger Massive ===\n")
        append("IP: ${etIp.text}\n")
        append("Total: ${results.size} | Working: ${results.count { it.tlsOk == true }}\n\n")
        results.forEach { r ->
            val status = when { r.tlsOk == true -> "OK"; r.tcpReachable == true -> "TLS_FAIL"; else -> "BLOCKED" }
            append("═══════════════════════════════════\n")
            append("SNI: ${r.sni}\n")
            append("Status: $status\n")
            append("RTT: ${r.rttMs?.toInt()}ms\n")
            append("TLS: ${r.tlsOk} (${r.tlsVersion})\n")
            if (r.domainOwnerOrg != null) append("Owner: ${r.domainOwnerOrg}\n")
            if (r.ipGeoInfo != null) append("Geo: ${r.ipGeoInfo?.city}, ${r.ipGeoInfo?.country} (${r.ipGeoInfo?.org})\n")
            if (r.errors.isNotEmpty()) append("Errors: ${r.errors.joinToString("; ")}\n")
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
        (tvTerminal.parent as? NestedScrollView)?.post { tvTerminal.parent?.let { (it as? NestedScrollView)?.fullScroll(View.FOCUS_DOWN) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        checkJob?.cancel()
        scope.cancel()
    }
}
