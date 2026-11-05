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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Qwen3.5 4B 本地推理") },
                actions = {
                    TextButton(onClick = {
                        val f = ModelDownloader.modelFile(
                            context, "qwen3.5-4b-q4_k_m.gguf"
                        )
                        vm.loadModel(f.absolutePath)
                    }) { Text("加载模型") }
                }
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") }
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.send(input); input = "" },
                    enabled = !isBusy
                ) { Text("发送") }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            ) {
                items(messages) { m ->
                    val isUser = m.role == "user"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isUser) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.padding(4.dp).widthIn(max = 320.dp)
                        ) {
                            Text(m.content, Modifier.padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}
