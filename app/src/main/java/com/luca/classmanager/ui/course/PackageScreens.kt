@file:OptIn(ExperimentalMaterial3Api::class)

package com.luca.classmanager.ui.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.luca.classmanager.domain.logic.AmountParser
import com.luca.classmanager.domain.logic.CreditUnits
import com.luca.classmanager.domain.logic.LessonParser
import com.luca.classmanager.domain.logic.ParseOutcome
import com.luca.classmanager.domain.logic.formatCents
import com.luca.classmanager.domain.logic.formatLessons
import com.luca.classmanager.domain.logic.formatLessonsOrUnknown
import com.luca.classmanager.domain.model.CoursePackageInfo
import com.luca.classmanager.domain.model.PackageDraft
import com.luca.classmanager.domain.model.PaymentInfo
import com.luca.classmanager.domain.repository.CourseRepository
import com.luca.classmanager.ui.checkin.LucaDateDialog
import com.luca.classmanager.ui.components.LoadingPane
import com.luca.classmanager.ui.components.LucaOutlinedButton
import com.luca.classmanager.ui.components.LucaPrimaryButton
import com.luca.classmanager.ui.components.LucaTextButton
import com.luca.classmanager.ui.components.MessagePane
import com.luca.classmanager.ui.components.PagePadding
import com.luca.classmanager.ui.components.SectionTitle
import com.luca.classmanager.ui.home.simpleFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PackageListUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val courseName: String = "",
    val packages: List<CoursePackageInfo> = emptyList(),
    val payments: List<PaymentInfo> = emptyList(),
)

class PackageListViewModel(
    private val repository: CourseRepository,
    private val courseId: String,
) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    val uiState = refresh.flatMapLatest {
        repository.observeCourses().map { courses ->
            val course = courses.firstOrNull { it.id == courseId }
            if (course == null) {
                PackageListUi(loading = false, missing = true)
            } else {
                PackageListUi(
                    loading = false,
                    courseName = course.name,
                    packages = course.packages,
                    payments = course.payments,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackageListUi())

    private val _pendingDelete = MutableStateFlow<String?>(null)
    val pendingDelete = _pendingDelete

    fun askDelete(id: String) {
        _pendingDelete.value = id
    }

    fun dismissDelete() {
        _pendingDelete.value = null
    }

    fun confirmDelete() {
        val id = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { repository.deletePackage(id) }
    }

    companion object {
        fun factory(repository: CourseRepository, courseId: String): ViewModelProvider.Factory = simpleFactory {
            PackageListViewModel(repository, courseId)
        }
    }
}

@Composable
fun PackageListScreen(
    repository: CourseRepository,
    courseId: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val viewModel: PackageListViewModel = viewModel(
        key = courseId,
        factory = PackageListViewModel.factory(repository, courseId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LedgerTopBar(title = "课时套餐", onBack = onBack)
        when {
            state.loading -> LoadingPane()
            state.missing -> MessagePane("找不到这门课程", "返回", onBack)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.courseName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "每次续费单独记一个套餐。金额留空会保持待补充，不会当成 0 元。套餐会带出一条缴费记录，费用中心里不会把这笔钱再加一次。额外缴费也在费用中心记。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LucaPrimaryButton(text = "新增套餐", onClick = onAdd)
                if (state.packages.isEmpty()) {
                    Text("还没有课时套餐。")
                } else {
                    state.packages.forEach { pkg ->
                        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("购买 ${formatLessonsOrUnknown(pkg.purchasedCreditUnits)}")
                                Text("赠送 ${formatLessons(pkg.giftCreditUnits)}")
                                Text("金额 ${pkg.amountCents?.let(::formatCents) ?: "待补充"}")
                                Text("单价 ${pkg.unitPriceCents?.let { "${formatCents(it)}/课时" } ?: "待补充"}")
                                Text(pkg.paidOn?.let { "缴费日期 $it" } ?: "缴费日期未记录")
                                pkg.note?.let { Text(it) }
                                LucaOutlinedButton(text = "编辑", onClick = { onEdit(pkg.id) })
                                LucaTextButton(
                                    text = "删除套餐",
                                    onClick = { viewModel.askDelete(pkg.id) },
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                SectionTitle("续费与缴费记录")
                if (state.payments.isEmpty()) {
                    Text("保存套餐后，这里会出现对应的缴费记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.payments.forEach { payment ->
                        Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(payment.amountCents?.let(::formatCents) ?: "金额待补充")
                                Text(payment.paidOn?.let { "缴费日期 $it" } ?: "缴费日期未记录")
                                Text(payment.note ?: "课时套餐缴费")
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除这个套餐？") },
            text = { Text("套餐和对应的缴费记录会一起删除。已经打过的卡不会被删掉。") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("取消") } },
        )
    }
}

data class PackageFormUi(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val courseName: String = "",
    val purchased: String = "",
    val gift: String = "0",
    val amount: String = "",
    val unitPrice: String = "",
    val paidOn: String? = null,
    val note: String = "",
    val error: String? = null,
    val saving: Boolean = false,
)

class PackageFormViewModel(
    private val repository: CourseRepository,
    private val courseId: String,
    private val packageId: String?,
) : ViewModel() {
    private val form = MutableStateFlow(PackageFormUi())
    val uiState = form
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val course = repository.getCourse(courseId)
            if (course == null) {
                form.value = PackageFormUi(loading = false, missing = true)
                return@launch
            }
            val pkg = packageId?.let { id -> course.packages.firstOrNull { it.id == id } }
            if (packageId != null && pkg == null) {
                form.value = PackageFormUi(loading = false, missing = true)
                return@launch
            }
            form.value = PackageFormUi(
                loading = false,
                courseName = course.name,
                purchased = pkg?.purchasedCreditUnits?.let { CreditUnits.format(it) }.orEmpty(),
                gift = CreditUnits.format(pkg?.giftCreditUnits ?: 0),
                amount = pkg?.amountCents?.let(AmountParser::formatYuanInput).orEmpty(),
                unitPrice = pkg?.unitPriceCents?.let(AmountParser::formatYuanInput).orEmpty(),
                paidOn = pkg?.paidOn,
                note = pkg?.note.orEmpty(),
            )
        }
    }

    fun setPurchased(value: String) = update { copy(purchased = value, error = null) }
    fun setGift(value: String) = update { copy(gift = value, error = null) }
    fun setAmount(value: String) = update { copy(amount = value, error = null) }
    fun setUnitPrice(value: String) = update { copy(unitPrice = value, error = null) }
    fun setPaidOn(value: String?) = update { copy(paidOn = value, error = null) }
    fun setNote(value: String) = update { copy(note = value.take(200), error = null) }

    fun save() {
        val current = form.value
        if (current.saving) return
        val purchased = LessonParser.parse(current.purchased)
        val gift = LessonParser.parse(current.gift.ifBlank { "0" })
        val amount = AmountParser.parseYuanToCents(current.amount)
        val unitPrice = AmountParser.parseYuanToCents(current.unitPrice)
        val error = when {
            purchased is ParseOutcome.Invalid -> "购买课时只接受整数或半数，留空表示待补充"
            gift is ParseOutcome.Invalid || gift is ParseOutcome.Empty -> "赠送课时只接受整数或半数"
            gift is ParseOutcome.Value && gift.value < 0 -> "赠送课时不能为负数"
            amount is ParseOutcome.Invalid -> "金额请填写元，最多两位小数。留空表示待补充"
            unitPrice is ParseOutcome.Invalid -> "单价请填写元，最多两位小数。留空表示待补充"
            purchased is ParseOutcome.Empty &&
                (gift !is ParseOutcome.Value || gift.value == 0) &&
                amount is ParseOutcome.Empty &&
                current.note.isBlank() -> "请填写购买课时、赠送课时、金额或备注"
            else -> null
        }
        if (error != null) {
            form.value = current.copy(error = error)
            return
        }
        val draft = PackageDraft(
            purchasedCreditUnits = (purchased as? ParseOutcome.Value)?.value,
            giftCreditUnits = (gift as ParseOutcome.Value).value,
            amountCents = (amount as? ParseOutcome.Value)?.value,
            unitPriceCents = (unitPrice as? ParseOutcome.Value)?.value,
            paidOn = current.paidOn,
            note = current.note.trim().ifBlank { null },
        )
        form.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val id = repository.savePackage(courseId, packageId, draft)
            if (id == null) {
                form.value = current.copy(saving = false, error = "没有保存成功")
            } else {
                _saved.emit(Unit)
            }
        }
    }

    private fun update(block: PackageFormUi.() -> PackageFormUi) {
        form.value = form.value.block()
    }

    companion object {
        fun factory(
            repository: CourseRepository,
            courseId: String,
            packageId: String?,
        ): ViewModelProvider.Factory = simpleFactory {
            PackageFormViewModel(repository, courseId, packageId)
        }
    }
}

@Composable
fun PackageFormScreen(
    repository: CourseRepository,
    courseId: String,
    packageId: String?,
    onBack: () -> Unit,
) {
    val viewModel: PackageFormViewModel = viewModel(
        key = "$courseId-${packageId ?: "new"}",
        factory = PackageFormViewModel.factory(repository, courseId, packageId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickingDate by remember { mutableStateOf(false) }
    LaunchedSaved(viewModel.saved, onBack)
    Column(Modifier.fillMaxSize()) {
        LedgerTopBar(title = if (packageId == null) "新增套餐" else "编辑套餐", onBack = onBack)
        when {
            state.loading -> LoadingPane()
            state.missing -> MessagePane("找不到套餐", "返回", onBack)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(PagePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.courseName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField("购买课时", state.purchased, "留空表示待补充", viewModel::setPurchased, state.saving)
                NumberField("赠送课时", state.gift, "没有赠送就填 0", viewModel::setGift, state.saving)
                NumberField("购买金额（元）", state.amount, "留空表示待补充，填 0 表示没有收费", viewModel::setAmount, state.saving)
                NumberField("单课时价格（元）", state.unitPrice, "留空时，若金额和购买课时都已知，按金额除以课时计算", viewModel::setUnitPrice, state.saving)
                LucaOutlinedButton(
                    text = state.paidOn?.let { "缴费日期 $it" } ?: "选择缴费日期",
                    onClick = { pickingDate = true },
                    enabled = !state.saving,
                )
                if (state.paidOn != null) {
                    LucaTextButton(text = "清除缴费日期", onClick = { viewModel.setPaidOn(null) })
                }
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注") },
                    enabled = !state.saving,
                    minLines = 2,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LucaPrimaryButton(text = if (state.saving) "正在保存" else "保存套餐", onClick = viewModel::save, enabled = !state.saving)
            }
        }
    }
    if (pickingDate) {
        val initial = state.paidOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        LucaDateDialog(
            initial = initial,
            onDismiss = { pickingDate = false },
            onConfirm = {
                viewModel.setPaidOn(it.toString())
                pickingDate = false
            },
        )
    }
}

@Composable
internal fun LedgerTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    hint: String,
    onValue: (String) -> Unit,
    saving: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        enabled = !saving,
        singleLine = true,
    )
}

@Composable
private fun LaunchedSaved(saved: kotlinx.coroutines.flow.Flow<Unit>, onSaved: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        saved.collect { onSaved() }
    }
}
