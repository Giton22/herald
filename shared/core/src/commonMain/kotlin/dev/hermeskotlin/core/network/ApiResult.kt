package dev.hermeskotlin.core.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** Outcome of an authenticated dashboard call. */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** 401 on login: wrong username/password. */
    data object InvalidCredentials : ApiResult<Nothing>

    /** 401 on an authenticated call: cookies missing/expired and refresh failed — sign in again. */
    data object SessionExpired : ApiResult<Nothing>

    /** 429: login rate limit. */
    data object RateLimited : ApiResult<Nothing>

    /** Network failure or 5xx: worth retrying. */
    data class Unavailable(val message: String) : ApiResult<Nothing>

    /** Anything else (4xx with a message). */
    data class Failed(val status: Int, val message: String) : ApiResult<Nothing>
}

@Serializable
private data class ErrorBody(val detail: String? = null, val error: String? = null, val message: String? = null)

/** Runs [block] and classifies the response. [isLogin] maps 401 to [ApiResult.InvalidCredentials]. */
internal suspend fun apiCall(isLogin: Boolean = false, block: suspend () -> HttpResponse): ApiResult<HttpResponse> =
    try {
        val response = block()
        val status = response.status
        when {
            status.isSuccess() -> ApiResult.Success(response)
            status == HttpStatusCode.Unauthorized ->
                if (isLogin) ApiResult.InvalidCredentials else ApiResult.SessionExpired
            status == HttpStatusCode.TooManyRequests -> ApiResult.RateLimited
            status.value >= 500 -> ApiResult.Unavailable(response.errorMessage())
            else -> ApiResult.Failed(status.value, response.errorMessage())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Unavailable(e.message ?: e::class.simpleName ?: "Connection failed")
    }

/** `detail` is FastAPI's error key; it is a plain string except for a few structured 503s. */
private suspend fun HttpResponse.errorMessage(): String {
    val body = runCatching { body<ErrorBody>() }.getOrNull()
    return body?.detail ?: body?.message ?: body?.error ?: "HTTP ${status.value}"
}

/** Transforms a success value; a failing transform (bad JSON) becomes [ApiResult.Failed]. */
suspend fun <T, R> ApiResult<T>.map(transform: suspend (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> try {
        ApiResult.Success(transform(value))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failed(0, "Unexpected response: ${e.message}")
    }
    is ApiResult.InvalidCredentials -> this
    is ApiResult.SessionExpired -> this
    is ApiResult.RateLimited -> this
    is ApiResult.Unavailable -> this
    is ApiResult.Failed -> this
}

/** A short, user-facing description of a non-success result. */
val ApiResult<*>.errorMessage: String?
    get() = when (this) {
        is ApiResult.Success -> null
        ApiResult.InvalidCredentials -> "Wrong username or password."
        ApiResult.SessionExpired -> "Your session expired. Sign in again."
        ApiResult.RateLimited -> "Too many attempts. Wait a minute and try again."
        is ApiResult.Unavailable -> "Can't reach the gateway: $message"
        is ApiResult.Failed -> message
    }
