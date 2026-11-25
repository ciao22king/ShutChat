package dev.shutchat.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shutchat.app.core.Format
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Circular avatar with the initials of a name. */
@Composable
fun Avatar(seed: String, name: String, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(seed)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Format.initials(name),
            color = Color(0xFF10131C),
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
        )
    }
}

/** Small green dot for an online member. */
@Composable
fun OnlineDot(online: Boolean, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (online) Color(0xFF3BC97A) else Color(0xFF5A5A66)),
    )
}

/** Section label used across Settings and group details. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

/** One line of the transport status footer. */
@Composable
fun StatusLine(active: Boolean, color: Color, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) color else Color(0xFF4A4A56)))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val imageCache = ConcurrentHashMap<String, ImageBitmap>()

/**
 * Decodes a local image once and keeps it for the session.
 *
 * Attachments are a few megabytes at most and a conversation shows the same
 * picture many times after scrolling, so caching by path is enough.
 */
fun imageAt(path: String): ImageBitmap? {
    imageCache[path]?.let { return it }
    val decoded = runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
    }.getOrNull() ?: return null
    imageCache[path] = decoded
    return decoded
}

/** Rounded picture preview for an image message. */
@Composable
fun ImagePreview(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { imageAt(path) }
    if (bitmap == null) {
        Box(
            modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("Photo", style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    Image(
        bitmap,
        contentDescription = "Photo",
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
    )
}

/** Small monospace chip used for invite codes. */
@Composable
fun CodeText(code: String, modifier: Modifier = Modifier) {
    Text(
        code,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = 1.sp,
        ),
        modifier = modifier,
    )
}

/** Draws a QR matrix as squares, sized to the available width. */
@Composable
fun QrCode(text: String, size: Dp = 220.dp) {
    val matrix = remember(text) { dev.shutchat.desktop.DesktopQr.matrix(text) }
    if (matrix == null) {
        Text(
            "Could not generate the QR. Use the text code.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Box(
        Modifier
            .size(size)
            .background(Color.White)
            .padding(8.dp),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(size - 16.dp)) {
            val module = this.size.minDimension / matrix.size
            matrix.forEachIndexed { y, row ->
                row.forEachIndexed { x, dark ->
                    if (dark) {
                        drawRect(
                            color = Color(0xFF0F1B2A),
                            topLeft = androidx.compose.ui.geometry.Offset(x * module, y * module),
                            size = androidx.compose.ui.geometry.Size(module, module),
                        )
                    }
                }
            }
        }
    }
}

/** Column of key/value rows used in the diagnostics block. */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(180.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Empty state shown when no chat is selected. */
@Composable
fun EmptyPane(title: String, body: String, actions: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        actions()
    }
}
