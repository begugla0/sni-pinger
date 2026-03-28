package com.begugla.snipinger

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.*
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var tilSni: TextInputLayout
    private lateinit var etIp: TextInputEditText
    private lateinit var etSni: TextInputEditText
    private lateinit var btnCheck: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressContainer: View
    private lateinit var tvProgress: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    
    private lateinit var cardVerdict: MaterialCardView
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

    private val checker = WhitelistChecker()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var isWhitelistMode = false
    private val whitelistUrl = "https://raw.githubusercontent.com/hxehex/russia-mobile-internet-whitelist/refs/heads/main/whitelist.txt"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tilSni = findViewById(R.id.tilSni)
        etIp = findViewById(R.id.etIp)
        etSni = findViewById(R.id.etSni)
        btnCheck = findViewById(R.id.btnCheck)
        progressBar = findViewById(R.id.progressBar)
        progressContainer = findViewById(R.id.progressContainer)
        tvProgress = findViewById(R.id.tvProgress)
        toggleGroup = findViewById(R.id.toggleGroup)
        
        cardVerdict = findViewById(R.id.cardVerdict)
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

        setupSection(sectionTcp, "TCP Соединение")
        setupSection(sectionTls, "TLS Handshake")
        setupSection(sectionHttp, "HTTP Запрос")
        setupSection(sectionDns, "IP / DNS Инфо")

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isWhitelistMode = (checkedId == R.id.btnModeWhitelist)
                tilSni.visibility = if (isWhitelistMode) View.GONE else View.VISIBLE
            }
        }

        btnCheck.setOnClickListener {
            performCheck()
        }
        
        btnCopyRaw.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SNI Pinger RAW", tvRawOutput.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSection(view: View, title: String) {
        view.findViewById<TextView>(R.id.tvSectionTitle).text = title
    }

    private fun addRow(section: View, label: String, value: Any?, isError: Boolean = false) {
        if (value == null) return
        val container = section.findViewById<LinearLayout>(R.id.containerRows)
        val rowView = LayoutInflater.from(this).inflate(R.layout.item_result_row, container, false)
        
        val tvLabel = rowView.findViewById<TextView>(R.id.tvLabel)
        val tvValue = rowView.findViewById<TextView>(R.id.tvValue)
        
        tvLabel.text = label
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
    }

    private fun performCheck() {
        val ip = etIp.text.toString().trim()
        val singleSni = etSni.text.toString().trim()

        if (ip.isEmpty() || (!isWhitelistMode && singleSni.isEmpty())) return

        btnCheck.isEnabled = false
        progressContainer.visibility = View.VISIBLE
        tvProgress.text = "Загрузка..."
        clearResults()

        scope.launch {
            if (isWhitelistMode) {
                performWhitelistCheck(ip)
            } else {
                val result = withContext(Dispatchers.IO) {
                    checker.checkIp(ip, singleSni)
                }
                displayResult(result)
            }
            btnCheck.isEnabled = true
            progressContainer.visibility = View.INVISIBLE
        }
    }

    private suspend fun performWhitelistCheck(ip: String) {
        val snis = withContext(Dispatchers.IO) {
            try {
                URL(whitelistUrl).readText().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            } catch (e: Exception) {
                emptyList<String>()
            }
        }

        if (snis.isEmpty()) {
            tvVerdict.text = "Ошибка загрузки whitelist"
            cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_error))
            cardVerdict.visibility = View.VISIBLE
            return
        }

        val results = mutableListOf<CheckResult>()
        val total = snis.size
        
        withContext(Dispatchers.IO) {
            snis.forEachIndexed { index, sni ->
                withContext(Dispatchers.Main) {
                    tvProgress.text = "Проверка SNI: ${index + 1} / $total"
                    progressBar.isIndeterminate = false
                    progressBar.max = total
                    progressBar.progress = index + 1
                }
                results.add(checker.checkIp(ip, sni))
            }
        }

        displayWhitelistSummary(ip, results)
    }

    private fun displayWhitelistSummary(ip: String, results: List<CheckResult>) {
        layoutResults.visibility = View.VISIBLE
        cardVerdict.visibility = View.VISIBLE
        
        // Сортировка: Удачные (tlsOk), Сомнительные (tcpReachable но tlsFail), Неудачные (tcpFail)
        val sortedResults = results.sortedWith(compareByDescending<CheckResult> { it.tlsOk == true }
            .thenByDescending { it.tcpReachable == true })

        val workingCount = results.count { it.tlsOk == true }
        tvVerdict.text = "Результат: $workingCount / ${results.size} SNI работают"
        tvTotalTime.text = "Проверка IP: $ip"
        
        val color = if (workingCount > 0) R.color.status_ok else R.color.status_error
        cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, color))

        sortedResults.forEach { r ->
            val status = when {
                r.tlsOk == true -> "✅ OK"
                r.tcpReachable == true -> "⚠️ TLS FAIL"
                else -> "❌ TCP BLOCKED"
            }
            val ping = r.rttMs?.let { "[${it.toInt()}ms]" } ?: ""
            addRow(sectionTls, r.sni, "$status $ping", r.tlsOk != true)
        }
        
        sectionTls.visibility = View.VISIBLE
        setupSection(sectionTls, "Проверка по списку SNI (отсортировано)")
    }

    private fun displayResult(r: CheckResult) {
        layoutResults.visibility = View.VISIBLE
        
        // Verdict
        cardVerdict.visibility = View.VISIBLE
        tvVerdict.text = r.verdict
        tvTotalTime.text = "Время проверки: %.3f сек".format(r.totalTime)
        
        val verdictColor = when (r.inWhitelist) {
            true -> R.color.status_ok
            false -> R.color.status_error
            null -> R.color.status_warning
        }
        cardVerdict.setCardBackgroundColor(ContextCompat.getColor(this, verdictColor))

        // TCP
        addRow(sectionTcp, "Доступность", if (r.tcpReachable == true) "Доступен" else "Заблокирован", r.tcpReachable == false)
        addRow(sectionTcp, "Connect Time", r.tcpConnectTime?.let { "%.3f s".format(it) })
        addRow(sectionTcp, "RTT", r.rttMs?.let { "%.1f ms".format(it) })

        // TLS
        if (r.tlsOk != null) {
            addRow(sectionTls, "Статус", if (r.tlsOk == true) "OK" else "Ошибка", r.tlsOk == false)
            addRow(sectionTls, "Версия", r.tlsVersion)
            addRow(sectionTls, "Cipher", r.tlsCipher)
            addRow(sectionTls, "SNI Match", if (r.certSniMatch == true) "Да" else "Нет")
        }

        // HTTP
        if (r.httpStatusLine != null) {
            addRow(sectionHttp, "Статус", r.httpStatusLine)
            addRow(sectionHttp, "Server", r.httpServerHeader)
        }

        // DNS / IP
        addRow(sectionDns, "IP", r.ip)
        addRow(sectionDns, "Протокол", "IPv${r.ipVersion}")
        addRow(sectionDns, "Локальный", if (r.ipIsPrivate == true) "Да" else "Нет")
        if (r.dnsResolvesTo.isNotEmpty()) {
            addRow(sectionDns, "DNS IP", r.dnsResolvesTo.joinToString(", "))
        }

        // Errors
        if (r.errors.isNotEmpty()) {
            r.errors.forEach { addRow(sectionTcp, "Ошибка", it, true) }
        }

        // RAW Output
        cardRawOutput.visibility = View.VISIBLE
        tvRawOutput.text = r.toString().replace(", ", ",\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
