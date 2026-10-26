package com.luca.classmanager.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.luca.classmanager.di.AppContainer
import com.luca.classmanager.domain.model.CourseFilter
import com.luca.classmanager.ui.calendar.CalendarScreen
import com.luca.classmanager.ui.checkin.CheckInScreen
import com.luca.classmanager.ui.course.AdjustmentScreen
import com.luca.classmanager.ui.course.AttendanceScreen
import com.luca.classmanager.ui.course.CourseDetailScreen
import com.luca.classmanager.ui.course.CourseFormScreen
import com.luca.classmanager.ui.course.CourseListScreen
import com.luca.classmanager.ui.course.PackageFormScreen
import com.luca.classmanager.ui.course.PackageListScreen
import com.luca.classmanager.ui.home.HomeScreen
import com.luca.classmanager.ui.settings.SettingsScreen
import com.luca.classmanager.ui.stats.FeeCenterScreen
import com.luca.classmanager.ui.stats.StatsScreen
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val CHECKIN = "checkin"
    const val STATS = "stats"
    const val FEES = "fees"
    const val SETTINGS = "settings"
    const val COURSES = "course-list?filter={filter}"
    const val COURSE_ADD = "course-editor/new"
    const val COURSE_DETAIL = "course/{courseId}"
    const val COURSE_EDIT = "course-editor/{courseId}"
    const val PACKAGES = "course/{courseId}/packages"
    const val PACKAGE_NEW = "course/{courseId}/package-new"
    const val PACKAGE_EDIT = "course/{courseId}/package/{packageId}"
    const val ADJUSTMENTS = "course/{courseId}/adjustments"
    const val ATTENDANCE = "course/{courseId}/attendance"

    fun courses(filter: CourseFilter) = "course-list?filter=${filter.name}"
    fun detail(id: String) = "course/$id"
    fun edit(id: String) = "course-editor/$id"
    fun packages(id: String) = "course/$id/packages"
    fun packageNew(id: String) = "course/$id/package-new"
    fun packageEdit(courseId: String, packageId: String) = "course/$courseId/package/$packageId"
    fun adjustments(id: String) = "course/$id/adjustments"
    fun attendance(id: String) = "course/$id/attendance"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "首页", Icons.Filled.Home),
    Tab(Routes.CALENDAR, "日历", Icons.Filled.DateRange),
    Tab(Routes.CHECKIN, "打卡", Icons.Filled.CheckCircle),
    Tab(Routes.STATS, "统计", Icons.Filled.BarChart),
)

@Composable
fun LucaAppRoot(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route in setOf(Routes.HOME, Routes.CALENDAR, Routes.CHECKIN, Routes.STATS)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showNotice: (String, String?, (() -> Unit)?) -> Unit = { text, action, onAction ->
        scope.launch {
            val result = snackbarHostState.showSnackbar(text, actionLabel = action)
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                LucaBottomBar(route, navController)
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    attachments = container.attachmentStore,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenCourses = { navController.navigate(Routes.courses(CourseFilter.ALL)) },
                    onAddCourse = { navController.navigate(Routes.COURSE_ADD) },
                    onOpenCourse = { id -> navController.navigate(Routes.detail(id)) },
                    onOpenFees = { navController.navigate(Routes.FEES) },
                    onNotice = showNotice,
                )
            }
            composable(Routes.CALENDAR) {
                CalendarScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    attachments = container.attachmentStore,
                    onOpenCourse = { id -> navController.navigate(Routes.detail(id)) },
                    onNotice = showNotice,
                )
            }
            composable(Routes.CHECKIN) {
                CheckInScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    attachments = container.attachmentStore,
                    onOpenCourse = { id -> navController.navigate(Routes.detail(id)) },
                    onOpenHistory = { id -> navController.navigate(Routes.attendance(id)) },
                    onNotice = showNotice,
                )
            }
            composable(Routes.STATS) {
                StatsScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    onOpenCourse = { id -> navController.navigate(Routes.detail(id)) },
                    onOpenFees = { navController.navigate(Routes.FEES) },
                )
            }
            composable(Routes.FEES) {
                FeeCenterScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    onBack = { navController.popBackStack() },
                    onOpenCourse = { id -> navController.navigate(Routes.detail(id)) },
                    onNotice = { text -> showNotice(text, null, null) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = container.settingsRepository,
                    repository = container.courseRepository,
                    onBack = { navController.popBackStack() },
                    onNotice = { text -> showNotice(text, null, null) },
                )
            }
            composable(
                route = Routes.COURSES,
                arguments = listOf(
                    navArgument("filter") {
                        type = NavType.StringType
                        defaultValue = CourseFilter.ALL.name
                    },
                ),
            ) { entry ->
                val filter = entry.arguments?.getString("filter")
                    ?.let { runCatching { CourseFilter.valueOf(it) }.getOrNull() }
                    ?: CourseFilter.ALL
                CourseListScreen(
                    repository = container.courseRepository,
                    initialFilter = filter,
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate(Routes.COURSE_ADD) },
                    onOpen = { id -> navController.navigate(Routes.detail(id)) },
                )
            }
            composable(Routes.COURSE_ADD) {
                CourseFormScreen(
                    repository = container.courseRepository,
                    courseId = null,
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        navController.navigate(Routes.detail(id)) {
                            popUpTo(Routes.COURSE_ADD) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = Routes.COURSE_DETAIL,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                CourseDetailScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    attachments = container.attachmentStore,
                    courseId = courseId,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(Routes.edit(courseId)) },
                    onDeleted = { navController.popBackStack() },
                    onOpenPackages = { navController.navigate(Routes.packages(courseId)) },
                    onOpenAdjustments = { navController.navigate(Routes.adjustments(courseId)) },
                    onOpenAttendance = { navController.navigate(Routes.attendance(courseId)) },
                    onNotice = showNotice,
                )
            }
            composable(
                route = Routes.COURSE_EDIT,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                CourseFormScreen(
                    repository = container.courseRepository,
                    courseId = courseId,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.PACKAGES,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                PackageListScreen(
                    repository = container.courseRepository,
                    courseId = courseId,
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate(Routes.packageNew(courseId)) },
                    onEdit = { packageId -> navController.navigate(Routes.packageEdit(courseId, packageId)) },
                )
            }
            composable(
                route = Routes.PACKAGE_NEW,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                PackageFormScreen(
                    repository = container.courseRepository,
                    courseId = courseId,
                    packageId = null,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.PACKAGE_EDIT,
                arguments = listOf(
                    navArgument("courseId") { type = NavType.StringType },
                    navArgument("packageId") { type = NavType.StringType },
                ),
            ) { entry ->
                PackageFormScreen(
                    repository = container.courseRepository,
                    courseId = entry.arguments?.getString("courseId").orEmpty(),
                    packageId = entry.arguments?.getString("packageId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.ADJUSTMENTS,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                AdjustmentScreen(
                    repository = container.courseRepository,
                    courseId = entry.arguments?.getString("courseId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.ATTENDANCE,
                arguments = listOf(navArgument("courseId") { type = NavType.StringType }),
            ) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                AttendanceScreen(
                    repository = container.courseRepository,
                    settings = container.settingsRepository,
                    attachments = container.attachmentStore,
                    courseId = courseId,
                    onBack = { navController.popBackStack() },
                    onNotice = showNotice,
                )
            }
        }
    }
}

@Composable
private fun LucaBottomBar(currentRoute: String?, navController: NavHostController) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                alwaysShowLabel = true,
            )
        }
    }
}
