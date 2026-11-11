package dev.lexicon.ide

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.lexicon.ide.ui.LexiconApp
import dev.lexicon.domain.CaptureLimits

class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.trim()
        if (text.isNullOrBlank() || text.length > CaptureLimits.SELECTED_TEXT) {
            Toast.makeText(this, "请选择不超过 12,000 字符的文本片段", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val repo = (application as LexiconApplication).repository
        setContent { LexiconApp(repository = repo, initialCaptureText = text, onExternalCaptureDone = { finish() }) }
    }
}
