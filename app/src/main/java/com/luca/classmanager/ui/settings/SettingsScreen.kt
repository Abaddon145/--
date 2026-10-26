@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.GiftValuation
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.giftValuationLabel
import com.luca.classmanager.domain.model.ThemeMode
import com.luca.classmanager.domain.repository.SettingsRepository
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> = settings.themeMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeMode.SYSTEM,
    )
    val renewalUnits: StateFlow<Int> = settings.renewalThresholdCreditUnits.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CreditUnits.DEFAULT_RENEWAL_UNITS,
    )
    val giftValuation: StateFlow<GiftValuation> = settings.giftValuation.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GiftValuation.ZERO,
    )

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setRenewal(units: Int) {
        viewModelScope.launch { settings.setRenewalThresholdCreditUnits(units) }
    }

    fun setGift(mode: GiftValuation) {
        viewModelScope.launch { settings.setGiftValuation(mode) }
    }

    companion object {
        fun factory(settings: SettingsRepository): ViewModelProvider.Factory = simpleFactory {
            SettingsViewModel(settings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    repository: com.luca.classmanager.domain.repository.CourseRepository,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings))
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    val renewalUnits by viewModel.renewalUnits.collectAsStateWithLifecycle()
    val gift by viewModel.giftValuation.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PagePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("主题")
            ThemeMode.entries.forEach { option ->
                val selected = mode == option
                Card(
                    onClick = { viewModel.setTheme(option) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(themeLabel(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            SectionTitle("续费提醒")
            Text("剩余课时低于 ${formatLessons(renewalUnits)} 时，首页和课程里会提示续费。某门课可以单独改。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LucaOutlinedButton(
                    text = "降低",
                    onClick = { viewModel.setRenewal(renewalUnits - 1) },
                    enabled = renewalUnits > 1,
                    modifier = Modifier.weight(1f),
                )
                LucaOutlinedButton(
                    text = "提高",
                    onClick = { viewModel.setRenewal(renewalUnits + 1) },
                    enabled = renewalUnits < 60,
                    modifier = Modifier.weight(1f),
                )
            }
            SectionTitle("赠送课时价值")
            Text("默认按 0 元。只有套餐本身已经有购买单价时，才可以改成和购买课时同价。价格未知时仍显示待补充，不会编一个数。")
            GiftValuation.entries.forEach { option ->
                val selected = gift == option
                Card(
                    onClick = { viewModel.setGift(option) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(giftValuationLabel(option), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            SectionTitle("数据")
            Text("课程、课时和设置都保存在这台设备上。不需要登录，也不需要网络。")
            MaintenanceSection(settings, repository, onNotice)
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}
