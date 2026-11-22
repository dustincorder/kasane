package cord.kasane.music.offline

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class OfflineModeException : IOException("Offline Only mode is on. Turn it off in Settings to use the network.")

/** In Offline Only mode no request leaves the app through the shared client. */
class OfflineGateInterceptor(private val offlineOnly: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (offlineOnly()) throw OfflineModeException()
        return chain.proceed(chain.request())
    }
}
