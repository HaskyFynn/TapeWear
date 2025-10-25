package com.example.tapewear

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import android.widget.Button
import android.util.Log

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Find toolbar from the <include> by its inner id (appBar)
        val appBar = findViewById<MaterialToolbar>(R.id.appBar)
        if (appBar == null) {
            Log.e("TapeWear", "AppBar not found. Is include_appbar included in activity_main?")
        } else {
            appBar.title = "TapeWear"     // optional (already set in include)
            appBar.navigationIcon = null  // no back button on landing
        }

        findViewById<Button>(R.id.btnGoRegister).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        findViewById<Button>(R.id.btnGoAuthenticate).setOnClickListener {
            startActivity(Intent(this, AuthenticateActivity::class.java))
        }
    }
}
