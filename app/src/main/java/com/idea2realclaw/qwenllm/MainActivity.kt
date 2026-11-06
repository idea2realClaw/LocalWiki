package com.idea2realclaw.qwenllm

import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWindowHeight()
        setContent {
            MaterialTheme {
                ChatScreen()
            }
        }
        // DecorView 挂到 WindowManager 后系统会重算窗口尺寸，再压一次确保 900px 生效。
        window.decorView.post { applyWindowHeight() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋屏/分屏后系统可能重置 LayoutParams，重新压高度。
        window.decorView.post { applyWindowHeight() }
    }

    /**
     * 窗口高度 = [BuildConfig.WINDOW_HEIGHT_PX] **物理像素**（默认 900，非 dp），
     * 宽度铺满、顶部对齐。可用 `-PwindowHeightPx=<n>` 在构建时覆盖。
     */
    private fun applyWindowHeight() {
        val lp = window.attributes
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = BuildConfig.WINDOW_HEIGHT_PX
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        window.attributes = lp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val messages by vm.messages.collectAsState()
    val isBusy by vm.isBusy.collectAsState()
    val status by vm.status.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 即时滚动，不加动画（对齐师父的 UI 偏好）。
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalWiki · 本地 Agent") },
                actions = {
                    TextButton(onClick = { vm.selfTest() }, enabled = !isBusy) { Text("自测") }
                    TextButton(onClick = { vm.clearChat() }, enabled = !isBusy) { Text("清空") }
                    // 一键：模型不存在就下载（断点续传 + 校验），下载完自动加载；已存在直接加载。
                    TextButton(onClick = { vm.loadModel(context) }, enabled = !isBusy) { Text("加载模型") }
                }
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("问点什么，或直接让我读写 workspace 文件…") },
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                if (isBusy) {
                    Button(
                        onClick = { vm.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("停止") }
                } else {
                    Button(
                        onClick = {
                            val text = input
                            input = ""
                            vm.send(text)
                        },
                        enabled = input.isNotBlank()
                    ) { Text("发送") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                status,
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                items(messages) { m -> MessageBubble(m) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: ChatMessage) {
    when (m.role) {
        "status" -> {
            Text(
                m.content,
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        "tool" -> {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (m.meta.isNotBlank()) {
                    Text(
                        m.meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.widthIn(max = 340.dp)
                ) {
                    Text(
                        m.content,
                        Modifier.padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            return
        }
    }

    val isUser = m.role == "user"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (m.meta.isNotBlank()) {
            Text(
                m.meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.widthIn(max = 340.dp)
            ) {
                Text(m.content, Modifier.padding(10.dp))
            }
        }
    }
}
