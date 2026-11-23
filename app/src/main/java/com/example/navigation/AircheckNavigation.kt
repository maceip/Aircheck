package com.example.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import com.example.data.StudioTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Type-safe Jetpack Navigation 3 destinations implementing NavKey.
 */
@Serializable
sealed interface ScreenKey : NavKey {
    @Serializable
    data object ScriptKey : ScreenKey

    @Serializable
    data object PhotoStageKey : ScreenKey

    @Serializable
    data object ClipsKey : ScreenKey

    @Serializable
    data class ClipReviewKey(val clipId: String) : ScreenKey

    @Serializable
    data class ClipperKey(val projectId: String) : ScreenKey

    @Serializable
    data object SystemDeckKey : ScreenKey
}

val TOP_LEVEL_TABS: Map<StudioTab, ScreenKey> = linkedMapOf(
    StudioTab.SCRIPT to ScreenKey.ScriptKey,
    StudioTab.PHOTO to ScreenKey.PhotoStageKey,
    StudioTab.CLIPS to ScreenKey.ClipsKey
)

fun StudioTab.toScreenKey(): ScreenKey = TOP_LEVEL_TABS[this] ?: ScreenKey.ScriptKey

fun NavKey.toStudioTabOrNull(): StudioTab? = when (this) {
    ScreenKey.ScriptKey -> StudioTab.SCRIPT
    ScreenKey.PhotoStageKey -> StudioTab.PHOTO
    ScreenKey.ClipsKey -> StudioTab.CLIPS
    else -> null
}

/**
 * Official Navigation 3 Multiple Back Stacks state holder.
 * Each of the 3 primary tabs (`Script` · `Photo` · `Clips`) owns an isolated `NavBackStack<NavKey>`
 * decorated with `SaveableStateHolderNavEntryDecorator` and `ViewModelStoreNavEntryDecorator`
 * so switching tabs swaps list references in <1 ms without losing scroll position or cursors.
 */
class AircheckNavigationState(
    val startRoute: NavKey,
    topLevelRouteState: MutableState<NavKey>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>
) {
    var topLevelRoute: NavKey by topLevelRouteState

    val currentRoute: NavKey
        get() = backStacks[topLevelRoute]?.lastOrNull() ?: topLevelRoute

    val activeTab: StudioTab
        get() = topLevelRoute.toStudioTabOrNull() ?: StudioTab.SCRIPT

    @Composable
    fun toDecoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>
    ): List<NavEntry<NavKey>> {
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>()
            )
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = entryProvider
            )
        }
        return getTopLevelRoutesInUse().flatMap { decoratedEntries[it] ?: emptyList() }
    }

    private fun getTopLevelRoutesInUse(): List<NavKey> =
        if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute)
        }
}

@Composable
fun rememberAircheckNavigationState(
    startRoute: NavKey = ScreenKey.ScriptKey,
    topLevelRoutes: Set<NavKey> = setOf(
        ScreenKey.ScriptKey,
        ScreenKey.PhotoStageKey,
        ScreenKey.ClipsKey
    )
): AircheckNavigationState {
    val topLevelRoute = rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver(
        save = { key ->
            when (key) {
                ScreenKey.PhotoStageKey -> "photo"
                ScreenKey.ClipsKey -> "clips"
                else -> "script"
            }
        },
        restore = { raw ->
            when (raw) {
                "photo" -> ScreenKey.PhotoStageKey
                "clips" -> ScreenKey.ClipsKey
                else -> ScreenKey.ScriptKey
            }
        }
    )) {
        mutableStateOf(startRoute)
    }

    val backStacks = topLevelRoutes.associateWith { key -> rememberNavBackStack(key) }

    return remember(startRoute, topLevelRoutes) {
        AircheckNavigationState(
            startRoute = startRoute,
            topLevelRouteState = topLevelRoute,
            backStacks = backStacks
        )
    }
}

class AircheckNavigator(val state: AircheckNavigationState) {
    private val _reselectEvents = MutableSharedFlow<NavKey>(extraBufferCapacity = 1)
    val reselectEvents = _reselectEvents.asSharedFlow()

    fun selectTab(tab: StudioTab) {
        val target = tab.toScreenKey()
        if (state.topLevelRoute == target) {
            _reselectEvents.tryEmit(target)
        } else {
            state.topLevelRoute = target
        }
    }

    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            val stack = state.backStacks[state.topLevelRoute] ?: return
            if (stack.lastOrNull() != route) {
                stack.add(route)
            }
        }
    }

    fun openClipReview(clipId: String) {
        state.topLevelRoute = ScreenKey.ClipsKey
        val clipsStack = state.backStacks[ScreenKey.ClipsKey] ?: return
        val target = ScreenKey.ClipReviewKey(clipId)
        if (clipsStack.lastOrNull() is ScreenKey.ClipReviewKey) {
            clipsStack[clipsStack.lastIndex] = target
        } else {
            clipsStack.add(target)
        }
    }

    fun openClipper(projectId: String) {
        val stack = state.backStacks[state.topLevelRoute] ?: return
        val target = ScreenKey.ClipperKey(projectId)
        if (stack.lastOrNull() is ScreenKey.ClipperKey) {
            stack[stack.lastIndex] = target
        } else {
            stack.add(target)
        }
    }

    fun goBack(): Boolean {
        val currentStack = state.backStacks[state.topLevelRoute] ?: return false
        val currentRoute = currentStack.lastOrNull() ?: return false
        return if (currentRoute == state.topLevelRoute) {
            if (state.topLevelRoute != state.startRoute) {
                state.topLevelRoute = state.startRoute
                true
            } else {
                false
            }
        } else {
            currentStack.removeLastOrNull()
            true
        }
    }
}
