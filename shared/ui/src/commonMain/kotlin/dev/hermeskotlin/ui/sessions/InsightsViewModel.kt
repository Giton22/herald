package dev.hermeskotlin.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.insights.InsightsApi
import dev.hermeskotlin.core.insights.UsageReport
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Desktop's Insights periods. */
enum class InsightsPeriod(val days: Int, val label: String) { Week(7, "7 days"), Month(30, "30 days"), Quarter(90, "90 days") }

data class InsightsUiState(
    val period: InsightsPeriod = InsightsPeriod.Month,
    val report: UsageReport? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val sessionExpired: Boolean = false,
)

/** What the profile's agent has used over a period: cost, sessions, tokens by day, models, tools and skills. */
class InsightsViewModel(private val api: InsightsApi) : ViewModel() {

    private val _state = MutableStateFlow(InsightsUiState())
    val state: StateFlow<InsightsUiState> = _state.asStateFlow()

    private var gateway: SavedGateway? = null
    private var profile: String? = null
    private var loadJob: Job? = null

    fun bind(gateway: SavedGateway, profile: String?) {
        if (this.gateway == gateway && this.profile == profile) return
        this.gateway = gateway
        this.profile = profile
        _state.value = InsightsUiState(period = _state.value.period)
        refresh()
    }

    fun selectPeriod(period: InsightsPeriod) {
        if (period == _state.value.period) return
        // Drop the old period's report so the page shows a spinner, then this period's numbers or error.
        _state.update { it.copy(period = period, report = null) }
        refresh()
    }

    fun refresh() {
        val url = gateway?.gatewayUrl ?: return
        val period = _state.value.period
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val result = api.usage(url, period.days, profile)
            _state.update { state ->
                when (result) {
                    is ApiResult.Success -> state.copy(report = result.value, loading = false)
                    else -> state.copy(
                        loading = false,
                        error = result.errorMessage,
                        sessionExpired = result == ApiResult.SessionExpired,
                    )
                }
            }
        }
    }

    /** Clears the expiry flag once a screen has acted on it; the view model outlives the screen, so
     *  leaving it set would bounce the next mount after a fresh sign-in. */
    fun consumeSessionExpired() = _state.update { it.copy(sessionExpired = false) }
}
