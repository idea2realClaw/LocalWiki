package com.idea2realclaw.qwenllm

import android.os.Bundle
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
        setContent {
            MaterialTheme {
                ChatScreen()
            }
        }
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
                    TextButton(onClick = {
                        val f = ModelDownloader.modelFile(
                            context, "qwen3.5-4b-q4_k_m.gguf"
                        )
                        vm.loadModel(f.absolutePath)
                    }, enabled = !isBusy) { Text("加载模型") }
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
