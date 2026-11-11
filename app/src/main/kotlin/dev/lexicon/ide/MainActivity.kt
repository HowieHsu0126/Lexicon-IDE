package dev.lexicon.ide

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.lexicon.ide.ui.LexiconApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = (application as LexiconApplication).repository
        setContent { LexiconApp(repository = repo) }
    }
}
