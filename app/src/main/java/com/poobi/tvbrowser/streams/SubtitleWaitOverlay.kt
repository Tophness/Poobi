package com.poobi.tvbrowser.streams

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.tvSettingsFocus

@Composable
fun SubtitleWaitOverlay(
    viewModel: StreamsViewModel,
    sourceDataJson: String,
    onDismiss: () -> Unit
) {
    val subStatus by viewModel.subStatusMsg.collectAsState()
    val subProgress by viewModel.subProgress.collectAsState()
    val showSubProgressBar by viewModel.showSubProgressBar.collectAsState()

    LaunchedEffect(sourceDataJson) {
        viewModel.pendingPlayVideoSourceData = sourceDataJson
    }

    AlertDialog(
        onDismissRequest = {
            viewModel.cancelSubtitleDownloads()
            onDismiss()
        },
        title = {
            Text(
                text = "Auto-Downloading Subtitles",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        containerColor = Color(0xFF222225),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = subStatus.ifEmpty { "Searching and fetching subtitles..." },
                    color = Color.LightGray,
                    fontSize = 14.sp
                )

                if (showSubProgressBar) {
                    LinearProgressIndicator(
                        progress = { subProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFFFFC107),
                        trackColor = Color(0xFF333338)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "${(subProgress * 100).toInt()}%",
                            color = Color(0xFFFFC107),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFFFFC107),
                        trackColor = Color(0xFF333338)
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        viewModel.cancelSubtitleDownloads()
                        viewModel.resolveAndPlayInternal(sourceDataJson)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                ) {
                    Text("Skip & Play", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        viewModel.cancelSubtitleDownloads()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    modifier = Modifier.tvSettingsFocus(RoundedCornerShape(20.dp))
                ) {
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = null
    )
}