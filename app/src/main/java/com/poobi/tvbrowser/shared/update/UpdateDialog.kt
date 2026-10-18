package com.poobi.tvbrowser.shared.update

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poobi.tvbrowser.shared.TvFocusableBox
import kotlinx.coroutines.delay

@Composable
fun UpdateDialog() {
    val updateInfoState by UpdateManager.updateInfo.collectAsState()
    val isUpdateDownloading by UpdateManager.isDownloading.collectAsState()
    val updateDownloadProgress by UpdateManager.downloadProgress.collectAsState()

    updateInfoState?.let { update ->
        val activity = LocalContext.current as? Activity
        val context = LocalContext.current

        val cancelFocusRequester = remember { FocusRequester() }
        val installFocusRequester = remember { FocusRequester() }

        LaunchedEffect(isUpdateDownloading) {
            delay(100)
            try {
                if (isUpdateDownloading) {
                    cancelFocusRequester.requestFocus()
                } else {
                    installFocusRequester.requestFocus()
                }
            } catch (_: Exception) {}
        }

        AlertDialog(
            onDismissRequest = {
                if (!isUpdateDownloading) {
                    UpdateManager.dismissUpdate()
                }
            },
            title = {
                Text(
                    text = "New Update Available (${update.versionName})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            containerColor = Color(0xFF222225),
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "A new version of Poobi is available for your device architecture.",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )

                    if (update.releaseNotes.isNotBlank()) {
                        Text(
                            text = update.releaseNotes,
                            color = Color.Gray,
                            fontSize = 12.sp,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isUpdateDownloading) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { updateDownloadProgress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = Color(0xFF00BCD4),
                            trackColor = Color(0xFF333338)
                        )
                        Text(
                            text = "Downloading: ${(updateDownloadProgress * 100).toInt()}%",
                            color = Color(0xFF00BCD4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            },
            confirmButton = {
                if (isUpdateDownloading) {
                    TvFocusableBox(
                        modifier = Modifier
                            .height(40.dp)
                            .focusRequester(cancelFocusRequester),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(20.dp),
                        onClick = { UpdateManager.cancelDownload() }
                    ) { isFocused ->
                        Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Cancel",
                                color = if (isFocused) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvFocusableBox(
                            modifier = Modifier.height(40.dp),
                            defaultColor = Color(0xFF333338),
                            shape = RoundedCornerShape(20.dp),
                            onClick = {
                                val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
                                prefs.edit().putBoolean("auto_update_check", false).apply()
                                android.widget.Toast.makeText(context, "Automatic update checks disabled (Re-enable in Settings)", android.widget.Toast.LENGTH_LONG).show()
                                UpdateManager.dismissUpdate()
                            }
                        ) { isFocused ->
                            Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text("Never Ask Again", color = if (isFocused) Color.Black else Color(0xFFB0BEC5), fontSize = 13.sp)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier.height(40.dp),
                            defaultColor = Color(0xFF333338),
                            shape = RoundedCornerShape(20.dp),
                            onClick = { UpdateManager.dismissUpdate() }
                        ) { isFocused ->
                            Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text("Later", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        TvFocusableBox(
                            modifier = Modifier
                                .height(40.dp)
                                .focusRequester(installFocusRequester),
                            defaultColor = Color(0xFF4CAF50),
                            shape = RoundedCornerShape(20.dp),
                            onClick = {
                                if (activity != null) {
                                    UpdateManager.startDownloadAndInstall(activity, update)
                                }
                            }
                        ) { isFocused ->
                            Box(Modifier.fillMaxHeight().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                                Text("Install", color = if (isFocused) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        )
    }
}