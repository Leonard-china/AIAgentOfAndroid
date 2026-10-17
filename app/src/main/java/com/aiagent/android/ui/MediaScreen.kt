package com.aiagent.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.model.MediaItem
import com.aiagent.android.model.MediaType
import com.aiagent.android.model.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaScreen(
    mediaItems: List<MediaItem>,
    isLoading: Boolean,
    isPartialPhotoAccess: Boolean,
    onRefresh: () -> Unit,
    onTrashSelected: (List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("全部") }
    val filters = listOf("全部", "图片", "视频", "截屏", "安装包", "文档")
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var detailItem by remember { mutableStateOf<MediaItem?>(null) }

    val filteredItems = remember(mediaItems, selectedFilter) {
        when (selectedFilter) {
            "图片" -> mediaItems.filter { it.mediaType == MediaType.IMAGE && !it.isScreenshot }
            "视频" -> mediaItems.filter { it.mediaType == MediaType.VIDEO }
            "截屏" -> mediaItems.filter { it.isScreenshot }
            "安装包" -> mediaItems.filter { it.mediaType == MediaType.APK }
            "文档" -> mediaItems.filter { it.mediaType == MediaType.DOCUMENT }
            else -> mediaItems
        }
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Partial photo access notification banner
            if (isPartialPhotoAccess) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "当前为受限照片访问范围（仅包含您手动授权的照片）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Top Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filters) { f ->
                    FilterChip(
                        selected = selectedFilter == f,
                        onClick = { selectedFilter = f },
                        label = { Text(f, fontSize = 13.sp) }
                    )
                }
            }

            // Count header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "共找到 ${filteredItems.size} 个项目",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                if (selectedIds.isNotEmpty()) {
                    TextButton(onClick = { selectedIds = emptySet() }) {
                        Text("取消选择 (${selectedIds.size})")
                    }
                }
            }

            // Media List
            if (filteredItems.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("当前分类下未找到文件", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        val isSelected = selectedIds.contains(item.id)
                        MediaListItem(
                            item = item,
                            isSelected = isSelected,
                            onToggleSelect = {
                                selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                            },
                            onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                                } else {
                                    detailItem = item
                                }
                            }
                        )
                    }
                }
            }
        }

        // Floating Batch Action Bar
        if (selectedIds.isNotEmpty()) {
            val selectedItemsList = filteredItems.filter { selectedIds.contains(it.id) }
            val totalBytes = selectedItemsList.sumOf { it.sizeBytes }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("已选 ${selectedIds.size} 项", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("合计：${formatBytes(totalBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Button(
                        onClick = {
                            onTrashSelected(selectedItemsList)
                            selectedIds = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("移入回收站")
                    }
                }
            }
        }

        // Detail Metadata Dialog
        detailItem?.let { item ->
            FileDetailDialog(item = item, onDismiss = { detailItem = null })
        }
    }
}

@Composable
fun MediaListItem(
    item: MediaItem,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else
            MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when (item.mediaType) {
                            MediaType.IMAGE -> Color(0xFFE3F2FD)
                            MediaType.VIDEO -> Color(0xFFFFEBEE)
                            MediaType.APK -> Color(0xFFE8F5E9)
                            MediaType.DOCUMENT -> Color(0xFFFFF3E0)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (item.mediaType) {
                        MediaType.IMAGE -> if (item.isScreenshot) Icons.Default.PhotoCamera else Icons.Default.Image
                        MediaType.VIDEO -> Icons.Default.Movie
                        MediaType.APK -> Icons.Default.Android
                        MediaType.DOCUMENT -> Icons.Default.Description
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = when (item.mediaType) {
                        MediaType.IMAGE -> Color(0xFF1976D2)
                        MediaType.VIDEO -> Color(0xFFD32F2F)
                        MediaType.APK -> Color(0xFF388E3C)
                        MediaType.DOCUMENT -> Color(0xFFF57C00)
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row {
                    Text(text = item.formatSize(), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.width(8.dp))
                    val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(item.dateModified))
                    Text(text = dateStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    if (item.bucketName.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = item.bucketName, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() }
            )
        }
    }
}

@Composable
fun FileDetailDialog(item: MediaItem, onDismiss: () -> Unit) {
    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.dateModified))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件详情", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(label = "文件名", value = item.displayName)
                DetailRow(label = "体积大小", value = "${formatBytes(item.sizeBytes)} (${item.sizeBytes} 字节)")
                DetailRow(label = "文件类型", value = item.mimeType)
                DetailRow(label = "修改日期", value = dateStr)
                DetailRow(label = "物理路径", value = item.path)
                if (item.bucketName.isNotBlank()) {
                    DetailRow(label = "归属相册", value = item.bucketName)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
