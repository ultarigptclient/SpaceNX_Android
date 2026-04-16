package net.spacenx.messenger.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import net.spacenx.messenger.R

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 딜레이 없이 즉시 MainActivity로 전환 (Flutter 패턴)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}