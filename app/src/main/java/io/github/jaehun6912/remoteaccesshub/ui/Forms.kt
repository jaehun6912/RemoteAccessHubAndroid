package io.github.jaehun6912.remoteaccesshub.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 설정·시작 설정 화면에서 같이 쓰는 입력 줄. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, color = p.muted) } },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = if (enabled) p.subText else p.muted, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, bold: Boolean = false) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (enabled) p.text else p.muted, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun <T> RadioGroup(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, hint: String? = null, enabled: Boolean = true) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = p.subText, style = MaterialTheme.typography.labelLarge)
        for ((value, text) in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = value == selected, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Text(text, color = if (enabled) p.text else p.muted)
            }
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = p.subText, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
fun SectionTitle(text: String) {
    val p = LocalPalette.current
    Spacer(Modifier.height(18.dp))
    Text(text, color = p.accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
}

@Composable
fun Para(text: String, sub: Boolean = false) {
    val p = LocalPalette.current
    Text(text, color = if (sub) p.subText else p.text, style = if (sub) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

/** 누를 수 있는 글자(링크 모양). */
@Composable
fun LinkText(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(text, color = p.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))
}
