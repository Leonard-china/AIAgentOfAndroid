package com.aiagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.model.StorageStat
import com.aiagent.android.model.formatBytes

@Composable
fun StorageScreen(
    storageStat: StorageStat?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "手机存储空间分析",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            }
        }

        // Main Storage Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("已用存储空间", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                            val usedStr = storageStat?.let { formatBytes(it.usedBytes) } ?: "计算中..."
                            val totalStr = storageStat?.let { formatBytes(it.totalBytes) } ?: "--"
                            Text(
                                text = "$usedStr / $totalStr",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        storageStat?.let {
                            Text(
                                text = "${String.format("%.1f", it.usedPercent)}%",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = if (it.usedPercent > 85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val progress = storageStat?.let { it.usedBytes.toFloat() / it.totalBytes.toFloat() } ?: 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = if (progress > 0.85f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "可用空间：${storageStat?.let { formatBytes(it.availableBytes) } ?: "--"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Category Breakdown Header
        item {
            Text(
                text = "各类别物理空间占用",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Category Grid
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryItemCard(
                        title = "视频媒体",
                        bytes = storageStat?.videosBytes ?: 0L,
                        icon = Icons.Default.Movie,
                        color = Color(0xFFEF5350),
                        modifier = Modifier.weight(1f)
                    )
                    CategoryItemCard(
                        title = "图片相册",
                        bytes = storageStat?.imagesBytes ?: 0L,
                        icon = Icons.Default.Image,
                        color = Color(0xFF42A5F5),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryItemCard(
                        title = "音频与音乐",
                        bytes = storageStat?.audioBytes ?: 0L,
                        icon = Icons.Default.Audiotrack,
                        color = Color(0xFFAB47BC),
                        modifier = Modifier.weight(1f)
                    )
                    CategoryItemCard(
                        title = "文档与压缩包",
                        bytes = storageStat?.documentsBytes ?: 0L,
                        icon = Icons.Default.Description,
                        color = Color(0xFFFFA726),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryItemCard(
                        title = "安装包 (.apk)",
                        bytes = storageStat?.apksBytes ?: 0L,
                        icon = Icons.Default.Android,
                        color = Color(0xFF66BB6A),
                        modifier = Modifier.weight(1f)
                    )
                    CategoryItemCard(
                        title = "系统与其他数据",
                        bytes = storageStat?.othersBytes ?: 0L,
                        icon = Icons.Default.Widgets,
                        color = Color(0xFF78909C),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Optimization Recommendations
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "智能清理与整理建议",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            OptimizationCard(
                title = "排查 >500MB 大视频",
                description = "筛选占用闪存空间极大的高清长视频，腾出大额空间",
                icon = Icons.Default.VideoLibrary,
                onClick = { onQuickAction("找出超过 500MB 的视频") }
            )
        }

        item {
            OptimizationCard(
                title = "精确排查完全重复文件",
                description = "先大小粗筛再计算 SHA-256 内容哈希，安全保留单份原件",
                icon = Icons.Default.ContentCopy,
                onClick = { onQuickAction("排查重复照片") }
            )
        }

        item {
            OptimizationCard(
                title = "整理相册无用截屏",
                description = "集中检索近期或历史屏幕截图，释放图片占用",
                icon = Icons.Default.PhotoCamera,
                onClick = { onQuickAction("整理相册截图") }
            )
        }

        item {
            OptimizationCard(
                title = "清理下载残留安装包",
                description = "检查 Downloads 目录中的旧版 .apk 文件",
                icon = Icons.Default.DeleteOutline,
                onClick = { onQuickAction("找出下载目录里的安装包") }
            )
        }

        item {
            OptimizationCard(
                title = "打开系统存储管理设置",
                description = "辅助跳转至 MagicOS / Android 系统原生存储界面清理应用缓存",
                icon = Icons.Default.SettingsSuggest,
                onClick = { onQuickAction("打开系统存储管理页面") }
            )
        }
    }
}

@Composable
fun CategoryItemCard(
    title: String,
    bytes: Long,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Text(text = formatBytes(bytes), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OptimizationCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(text = description, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
