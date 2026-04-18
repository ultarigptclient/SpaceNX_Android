package net.spacenx.messenger.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import net.spacenx.messenger.R
import net.spacenx.messenger.util.FileLogger
import java.io.File

class ConfigLogFileActivity : AppCompatActivity() {

    private lateinit var switchFileLog: Switch
    private lateinit var tvLogInfo: TextView
    private lateinit var btnShare: Button
    private lateinit var btnClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_log_file)

        switchFileLog = findViewById(R.id.switchFileLog)
        tvLogInfo = findViewById(R.id.tvLogInfo)
        btnShare = findViewById(R.id.btnShare)
        btnClose = findViewById(R.id.btnClose)

        // 현재 상태 반영
        switchFileLog.isChecked = FileLogger.isEnabled()
        updateLogInfo()

        switchFileLog.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                FileLogger.enable(this)
                Toast.makeText(this, "파일 로그 ON - 로그 기록 시작", Toast.LENGTH_SHORT).show()
            } else {
                FileLogger.disable(this)
                Toast.makeText(this, "파일 로그 OFF - 로그 파일 삭제됨", Toast.LENGTH_SHORT).show()
            }
            updateLogInfo()
        }

        btnShare.setOnClickListener { shareLogFiles() }
        btnClose.setOnClickListener { finish() }
    }

    private fun updateLogInfo() {
        val dir = FileLogger.getLogDir(this)
        val files = FileLogger.getLogFiles(this)
        val totalKb = FileLogger.getTotalLogSize(this) / 1024
        tvLogInfo.text = "경로: ${dir.absolutePath}\n파일 수: ${files.size}개  크기: ${totalKb}KB"
    }

    private fun shareLogFiles() {
        val files = FileLogger.getLogFiles(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "로그 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>()
        files.forEach { file ->
            runCatching {
                uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "공유할 파일을 준비하지 못했습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "로그 파일 공유"))
    }
}
