package com.revenuecat.purchases.common

import android.content.Context
import com.revenuecat.purchases.DangerousSettings
import com.revenuecat.purchases.PurchasesAreCompletedBy
import com.revenuecat.purchases.PurchasesAreCompletedBy.REVENUECAT
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.common.diagnostics.DiagnosticsTracker
import com.revenuecat.purchases.common.networking.ETagManager
import com.revenuecat.purchases.common.networking.Endpoint
import com.revenuecat.purchases.common.networking.HTTPRequest
import com.revenuecat.purchases.common.networking.HTTPResult
import com.revenuecat.purchases.common.verification.SigningManager
import com.revenuecat.purchases.interfaces.StorefrontProvider
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import java.net.URL
import java.util.Date
import java.util.Locale

internal abstract class BaseHTTPClientTest {

    protected val countryCode = "JP"

    protected lateinit var server: MockWebServer
    protected lateinit var baseURL: URL

    protected lateinit var mockSigningManager: SigningManager
    protected lateinit var mockStorefrontProvider: StorefrontProvider

    @Before
    fun setup() {
        mockStorefrontProvider = mockk<StorefrontProvider>().apply {
            every { getStorefront() } returns countryCode
        }

        server = MockWebServer()
        baseURL = server.url("/v1").toUrl()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    protected val mockETagManager = mockk<ETagManager>().also {
        every {
            it.getETagHeaders(any(), any(), any())
        } answers {
            mapOf(HTTPRequest.ETAG_HEADER_NAME to "")
        }
    }
    protected val expectedPlatformInfo = PlatformInfo("flutter", "2.1.0")
    protected lateinit var client: HTTPClient

    protected fun createClient(
        appConfig: AppConfig = createAppConfig(),
        diagnosticsTracker: DiagnosticsTracker? = null,
        dateProvider: DateProvider = DefaultDateProvider(),
        eTagManager: ETagManager = mockETagManager,
        signingManager: SigningManager? = null,
        storefrontProvider: StorefrontProvider = mockStorefrontProvider,
        localeProvider: LocaleProvider = DefaultLocaleProvider()
    ) = HTTPClient(
        appConfig,
        eTagManager,
        diagnosticsTracker,
        signingManager ?: mockSigningManager,
        storefrontProvider,
        dateProvider,
        localeProvider = localeProvider
    )

    protected fun createAppConfig(
        context: Context = createContext(),
        purchasesAreCompletedBy: PurchasesAreCompletedBy = REVENUECAT,
        showInAppMessagesAutomatically: Boolean = false,
        platformInfo: PlatformInfo = expectedPlatformInfo,
        proxyURL: URL? = baseURL,
        store: Store = Store.PLAY_STORE,
        isDebugBuild: Boolean = false,
        customEntitlementComputation: Boolean = false,
        forceServerErrors: Boolean = false,
        forceSigningErrors: Boolean = false,
    ): AppConfig {
        return AppConfig(
            context = context,
            purchasesAreCompletedBy = purchasesAreCompletedBy,
            showInAppMessagesAutomatically = showInAppMessagesAutomatically,
            platformInfo = platformInfo,
            proxyURL = proxyURL,
            store = store,
            isDebugBuild = isDebugBuild,
            dangerousSettings = DangerousSettings(customEntitlementComputation = customEntitlementComputation),
            runningTests = true,
            forceServerErrors = forceServerErrors,
            forceSigningErrors = forceSigningErrors,
        )
    }

    protected fun enqueue(
        endpoint: Endpoint,
        expectedResult: HTTPResult,
        verificationResult: VerificationResult = VerificationResult.NOT_REQUESTED,
        requestDateHeader: Date? = null,
        server: MockWebServer = this.server,
    ) {
        every {
            mockETagManager.getHTTPResultFromCacheOrBackend(
                expectedResult.responseCode,
                expectedResult.payload,
                eTagHeader = any(),
                urlPath = endpoint.getPath(),
                refreshETag = false,
                requestDate = requestDateHeader,
                verificationResult = verificationResult
            )
        } returns expectedResult
        val response = MockResponse()
            .setBody(expectedResult.payload)
            .setResponseCode(expectedResult.responseCode)
            .apply {
                if (requestDateHeader != null) {
                    setHeader(HTTPResult.REQUEST_TIME_HEADER_NAME, requestDateHeader.time)
                }
            }
        server.enqueue(response)
    }

    private fun createContext(): Context {
        return mockk<Context>(relaxed = true).apply {
            every { packageName } answers { "mock-package-name" }
            every { getLocale() } returns Locale.US
        }
    }
}
