package com.xnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.ui.Editor
import com.xnotes.ui.Toolbar
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.XnotesTheme

class MainActivity : ComponentActivity() {

    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val palette = Palette.dark()
            XnotesTheme(palette) {
                EditorScreen(palette, onToggleFullscreen = ::toggleFullscreen)
            }
        }
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun EditorScreen(palette: Palette, onToggleFullscreen: () -> Unit) {
    val context = LocalContext.current
    val editor = remember { Editor(context, palette) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(editor.message) {
        editor.message?.let {
            snackbar.showSnackbar(it)
            editor.message = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Toolbar(editor, onToggleFullscreen = onToggleFullscreen)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { editor.view }, modifier = Modifier.fillMaxSize())
                editor.editingField?.let { field ->
                    com.xnotes.ui.TextEditorOverlay(editor, field)
                }
            }
        }
    }
}
