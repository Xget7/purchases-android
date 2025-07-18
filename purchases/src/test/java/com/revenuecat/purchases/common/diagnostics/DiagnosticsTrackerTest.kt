package com.revenuecat.purchases.common.diagnostics

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfos
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchasesAreCompletedBy.MY_APP
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.common.AppConfig
import com.revenuecat.purchases.common.Dispatcher
import com.revenuecat.purchases.common.PlatformInfo
import com.revenuecat.purchases.common.SyncDispatcher
import com.revenuecat.purchases.common.networking.Endpoint
import com.revenuecat.purchases.common.networking.HTTPResult
import com.revenuecat.purchases.common.playServicesVersionName
import com.revenuecat.purchases.common.playStoreVersionName
import com.revenuecat.purchases.strings.OfflineEntitlementsStrings
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifySequence
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class DiagnosticsTrackerTest {

    private val testDiagnosticsEntry = DiagnosticsEntry(
        name = DiagnosticsEntryName.HTTP_REQUEST_PERFORMED,
        properties = mapOf("test-key-1" to "test-value-1"),
        appSessionID = UUID.randomUUID(),
    )

    private lateinit var diagnosticsFileHelper: DiagnosticsFileHelper
    private lateinit var dispatcher: Dispatcher
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor
    private lateinit var diagnosticsTrackerListener: DiagnosticsEventTrackerListener
    private var diagnosticsTrackListenerCallCount = 0

    private lateinit var diagnosticsTracker: DiagnosticsTracker

    private lateinit var context: Context

    @Before
    fun setup() {
        mockkStatic("com.revenuecat.purchases.common.UtilsKt")
        context = mockk<Context>(relaxed = true).apply {
            every { playStoreVersionName } returns "123"
            every { playServicesVersionName } returns "456"
        }
        diagnosticsFileHelper = mockk<DiagnosticsFileHelper>().apply {
            every { isDiagnosticsFileTooBig() } returns false
        }
        dispatcher = SyncDispatcher()
        diagnosticsTrackerListener = DiagnosticsEventTrackerListener {
            diagnosticsTrackListenerCallCount++
        }
        diagnosticsTrackListenerCallCount = 0

        mockSharedPreferences()

        diagnosticsTracker = createDiagnosticsTracker()
        diagnosticsTracker.listener = diagnosticsTrackerListener
    }

    @After
    fun tearDown() {
        unmockkStatic("com.revenuecat.purchases.common.UtilsKt")
    }

    @Test
    fun `trackEvent performs correct calls`() {
        every { diagnosticsFileHelper.appendEvent(testDiagnosticsEntry) } just Runs
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
        verify(exactly = 1) { diagnosticsFileHelper.appendEvent(testDiagnosticsEntry) }
        verify(exactly = 0) { diagnosticsFileHelper.deleteFile() }
        verify(exactly = 0) { diagnosticsFileHelper.appendEvent(match {
            it.name == DiagnosticsEntryName.MAX_EVENTS_STORED_LIMIT_REACHED
        })}
    }

    @Test
    fun `trackEvent calls listener`() {
        every { diagnosticsFileHelper.appendEvent(testDiagnosticsEntry) } just Runs
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
        Assertions.assertThat(diagnosticsTrackListenerCallCount).isEqualTo(1)
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
        Assertions.assertThat(diagnosticsTrackListenerCallCount).isEqualTo(3)
    }

    @Test
    fun `trackEvent clears diagnostics file if too big, then adds event`() {
        every { diagnosticsFileHelper.deleteFile() } just Runs
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        every { diagnosticsFileHelper.isDiagnosticsFileTooBig() }.returnsMany(true, false)
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
        verifySequence {
            diagnosticsFileHelper.isDiagnosticsFileTooBig()
            diagnosticsFileHelper.deleteFile()
            diagnosticsFileHelper.appendEvent(match {
                it.name == DiagnosticsEntryName.MAX_EVENTS_STORED_LIMIT_REACHED
            })
            diagnosticsFileHelper.appendEvent(match {
                it == testDiagnosticsEntry
            })
        }
    }

    @Test
    fun `trackEvent handles IOException`() {
        every { diagnosticsFileHelper.appendEvent(any()) } throws IOException()
        diagnosticsTracker.trackEvent(testDiagnosticsEntry)
    }

    @Test
    fun `trackEventInCurrentThread does not enqueue request`() {
        dispatcher.close()
        every { diagnosticsFileHelper.appendEvent(any()) } throws IOException()
        diagnosticsTracker.trackEventInCurrentThread(testDiagnosticsEntry)
    }

    @Test
    fun `trackHttpRequestPerformed tracks correct event when coming from cache`() {
        val expectedProperties = mapOf(
            "host" to "test.host.com",
            "play_store_version" to "123",
            "play_services_version" to "456",
            "endpoint_name" to "post_receipt",
            "response_time_millis" to 1234L,
            "successful" to true,
            "response_code" to 200,
            "etag_hit" to true,
            "verification_result" to "NOT_REQUESTED",
            "is_retry" to false
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackHttpRequestPerformed(
            "test.host.com",
            Endpoint.PostReceipt,
            1234L.milliseconds,
            true,
            200,
            null,
            HTTPResult.Origin.CACHE,
            VerificationResult.NOT_REQUESTED,
            false
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.HTTP_REQUEST_PERFORMED && event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackHttpRequestPerformed tracks correct event when coming from backend`() {
        val expectedProperties = mapOf(
            "host" to "test.host.com",
            "play_store_version" to "123",
            "play_services_version" to "456",
            "endpoint_name" to "get_offerings",
            "response_time_millis" to 1234L,
            "successful" to true,
            "response_code" to 200,
            "backend_error_code" to 1234,
            "etag_hit" to false,
            "verification_result" to "NOT_REQUESTED",
            "is_retry" to false
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackHttpRequestPerformed(
            "test.host.com",
            Endpoint.GetOfferings("test id"),
            1234L.milliseconds,
            true,
            200,
            1234,
            HTTPResult.Origin.BACKEND,
            VerificationResult.NOT_REQUESTED,
            false
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.HTTP_REQUEST_PERFORMED && event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackHttpRequestPerformed tracks correct event when is retry`() {
        val expectedProperties = mapOf(
            "host" to "test.host.com",
            "play_store_version" to "123",
            "play_services_version" to "456",
            "endpoint_name" to "get_offerings",
            "response_time_millis" to 1234L,
            "successful" to true,
            "response_code" to 200,
            "backend_error_code" to 1234,
            "etag_hit" to false,
            "verification_result" to "NOT_REQUESTED",
            "is_retry" to true
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackHttpRequestPerformed(
            "test.host.com",
            Endpoint.GetOfferings("test id"),
            1234L.milliseconds,
            true,
            200,
            1234,
            HTTPResult.Origin.BACKEND,
            VerificationResult.NOT_REQUESTED,
            true
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.HTTP_REQUEST_PERFORMED && event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackMaxEventsStoredLimitReached tracks correct event`() {
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackMaxEventsStoredLimitReached()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.MAX_EVENTS_STORED_LIMIT_REACHED &&
                    event.properties == mapOf(
                    "play_store_version" to "123",
                    "play_services_version" to "456",
                )
            })
        }
    }

    // region Google Billing

    @Test
    fun `trackGoogleQueryProductDetailsRequest tracks correct event`() {
        val expectedProperties = mapOf(
            "requested_product_ids" to setOf("test-product-id", "test-product-id-2"),
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_type_queried" to "subs",
            "billing_response_code" to 12,
            "billing_debug_message" to "test-debug-message",
            "response_time_millis" to 1234L
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGoogleQueryProductDetailsRequest(
            requestedProductIds = setOf("test-product-id", "test-product-id-2"),
            productType = "subs",
            billingResponseCode = 12,
            billingDebugMessage = "test-debug-message",
            responseTime = 1234L.milliseconds
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GOOGLE_QUERY_PRODUCT_DETAILS_REQUEST &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGoogleQueryPurchasesRequest tracks correct event`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_type_queried" to "subs",
            "billing_response_code" to 12,
            "billing_debug_message" to "test-debug-message",
            "response_time_millis" to 1234L,
            "found_product_ids" to listOf("test-product-id", "test-product-id-2"),
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGoogleQueryPurchasesRequest(
            productType = "subs",
            billingResponseCode = 12,
            billingDebugMessage = "test-debug-message",
            responseTime = 1234L.milliseconds,
            foundProductIds = listOf("test-product-id", "test-product-id-2"),
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GOOGLE_QUERY_PURCHASES_REQUEST &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGooglePurchaseStarted tracks correct event`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_id" to "test-product-id",
            "old_product_id" to "test-old-product-id",
            "has_intro_trial" to true,
            "has_intro_price" to false,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGooglePurchaseStarted(
            productId = "test-product-id",
            oldProductId = "test-old-product-id",
            hasIntroTrial = true,
            hasIntroPrice = false,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GOOGLE_PURCHASE_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGooglePurchaseUpdateReceived tracks correct event`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_ids" to listOf("test-product-id", "test-product-id-2"),
            "purchase_statuses" to listOf("PURCHASED", "PENDING"),
            "billing_response_code" to 12,
            "billing_debug_message" to "test-debug-message",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGooglePurchaseUpdateReceived(
            productIds = listOf("test-product-id", "test-product-id-2"),
            purchaseStatuses = listOf("PURCHASED", "PENDING"),
            billingResponseCode = 12,
            billingDebugMessage = "test-debug-message",
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GOOGLE_PURCHASES_UPDATE_RECEIVED &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion

    // region Amazon Billing

    @Test
    fun `trackAmazonQueryProductDetailsRequest tracks correct event`() {
        val diagnosticsTracker = createDiagnosticsTracker(Store.AMAZON)
        val expectedTags = mapOf(
            "successful" to true,
            "response_time_millis" to 1234L,
            "requested_product_ids" to setOf("test-product-id")
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackAmazonQueryProductDetailsRequest(
            wasSuccessful = true,
            responseTime = 1234L.milliseconds,
            requestedProductIds = setOf("test-product-id")
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.AMAZON_QUERY_PRODUCT_DETAILS_REQUEST &&
                    event.properties == expectedTags
            })
        }
    }

    @Test
    fun `trackAmazonQueryPurchasesRequest tracks correct event`() {
        val diagnosticsTracker = createDiagnosticsTracker(Store.AMAZON)
        val expectedTags = mapOf(
            "successful" to true,
            "response_time_millis" to 1234L,
            "found_product_ids" to listOf("test-product-id", "test-product-id-2")
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackAmazonQueryPurchasesRequest(
            wasSuccessful = true,
            responseTime = 1234L.milliseconds,
            foundProductIds = listOf("test-product-id", "test-product-id-2")
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.AMAZON_QUERY_PURCHASES_REQUEST &&
                    event.properties == expectedTags
            })
        }
    }

    @Test
    fun `trackAmazonPurchaseAttempt tracks correct event`() {
        val diagnosticsTracker = createDiagnosticsTracker(Store.AMAZON)
        val expectedTags = mapOf(
            "product_id" to "test-product-id",
            "request_status" to "ERROR",
            "error_code" to 100,
            "error_message" to "test error message",
            "response_time_millis" to 1234L,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackAmazonPurchaseAttempt(
            productId = "test-product-id",
            requestStatus = "ERROR",
            errorCode = 100,
            errorMessage = "test error message",
            responseTime = 1234L.milliseconds
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.AMAZON_PURCHASE_ATTEMPT &&
                    event.properties == expectedTags
            })
        }
    }

    // endregion

    @Test
    fun `trackFeatureNotSupported tracks correct event`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "billing_response_code" to -2,
            "billing_debug_message" to "debug message",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackProductDetailsNotSupported(
            billingResponseCode = -2,
            billingDebugMessage = "debug message"
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.PRODUCT_DETAILS_NOT_SUPPORTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackCustomerInfoVerificationResultIfNeeded does not track when verification not requested`() {
        val customerInfo = mockk<CustomerInfo>().apply {
            every { entitlements } returns mockk<EntitlementInfos>().apply {
                every { verification } returns VerificationResult.NOT_REQUESTED
            }
        }
        diagnosticsTracker.trackCustomerInfoVerificationResultIfNeeded(
            customerInfo = customerInfo,
        )
        verify(exactly = 0) {
            diagnosticsFileHelper.appendEvent(any())
        }
    }

    @Test
    fun `trackCustomerInfoVerificationResultIfNeeded tracks when verification is failed`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "verification_result" to "FAILED",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        val customerInfo = mockk<CustomerInfo>().apply {
            every { entitlements } returns mockk<EntitlementInfos>().apply {
                every { verification } returns VerificationResult.FAILED
            }
        }
        diagnosticsTracker.trackCustomerInfoVerificationResultIfNeeded(
            customerInfo = customerInfo,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.CUSTOMER_INFO_VERIFICATION_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // region Offline Entitlements

    @Test
    fun `trackEnteredOfflineEntitlementsMode tracks correct data`() {
        val expectedProperties = mapOf<String, Any>(
            "play_store_version" to "123",
            "play_services_version" to "456",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackEnteredOfflineEntitlementsMode()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.ENTERED_OFFLINE_ENTITLEMENTS_MODE &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackErrorEnteringOfflineEntitlementsMode tracks correct data for unknown error`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "offline_entitlement_error_reason" to "unknown",
            "error_message" to "Unknown error. Underlying error: test error message",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackErrorEnteringOfflineEntitlementsMode(
            PurchasesError(
                PurchasesErrorCode.UnknownError,
                "test error message"
            )
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.ERROR_ENTERING_OFFLINE_ENTITLEMENTS_MODE &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackErrorEnteringOfflineEntitlementsMode tracks correct data for one time purchase error`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "offline_entitlement_error_reason" to "one_time_purchase_found",
            "error_message" to "There was a problem with the operation. Looks like we don't support that yet. Check the underlying error for more details. Underlying error: Offline entitlements are not supported for one time purchases. Found one time purchases. See for more info: https://rev.cat/offline-entitlements",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackErrorEnteringOfflineEntitlementsMode(
            PurchasesError(
                PurchasesErrorCode.UnsupportedError,
                underlyingErrorMessage = OfflineEntitlementsStrings.OFFLINE_ENTITLEMENTS_UNSUPPORTED_INAPP_PURCHASES,
            )
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.ERROR_ENTERING_OFFLINE_ENTITLEMENTS_MODE &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackErrorEnteringOfflineEntitlementsMode tracks correct data for entitlement mapping error`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "offline_entitlement_error_reason" to "no_entitlement_mapping_available",
            "error_message" to "There was a problem related to the customer info. Underlying error: Product entitlement mapping is required for offline entitlements. Skipping offline customer info calculation.",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackErrorEnteringOfflineEntitlementsMode(
            PurchasesError(
                PurchasesErrorCode.CustomerInfoError,
                underlyingErrorMessage = OfflineEntitlementsStrings.PRODUCT_ENTITLEMENT_MAPPING_REQUIRED,
            )
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.ERROR_ENTERING_OFFLINE_ENTITLEMENTS_MODE &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion

    // region Get Offerings

    @Test
    fun `trackGetOfferingsStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetOfferingsStarted()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_OFFERINGS_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGetOfferingsResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "requested_product_ids" to setOf("product1", "product2"),
            "not_found_product_ids" to setOf("product3", "product4"),
            "error_message" to "test error message",
            "error_code" to 100,
            "cache_status" to "NOT_CHECKED",
            "response_time_millis" to 1234L,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetOfferingsResult(
            requestedProductIds = setOf("product1", "product2"),
            notFoundProductIds = setOf("product3", "product4"),
            errorMessage = "test error message",
            errorCode = 100,
            cacheStatus = DiagnosticsTracker.CacheStatus.NOT_CHECKED,
            verificationResult = null,
            responseTime = 1234L.milliseconds,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_OFFERINGS_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion

    // region Get Products

    @Test
    fun `trackGetProductsStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "requested_product_ids" to setOf("product1", "product2"),
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetProductsStarted(requestedProductIds = setOf("product1", "product2"))
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_PRODUCTS_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGetProductsResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "requested_product_ids" to setOf("product1", "product2"),
            "not_found_product_ids" to setOf("product3", "product4"),
            "error_message" to "test error message",
            "error_code" to 100,
            "response_time_millis" to 1234L,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetProductsResult(
            requestedProductIds = setOf("product1", "product2"),
            notFoundProductIds = setOf("product3", "product4"),
            errorMessage = "test error message",
            errorCode = 100,
            responseTime = 1234L.milliseconds,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_PRODUCTS_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion

    // region Sync Purchases

    @Test
    fun `trackSyncPurchasesStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackSyncPurchasesStarted()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.SYNC_PURCHASES_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackSyncPurchasesResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "response_time_millis" to 1234L,
            "error_message" to "test error message",
            "error_code" to 100,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackSyncPurchasesResult(
            errorCode = 100,
            errorMessage = "test error message",
            responseTime = 1234L.milliseconds,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.SYNC_PURCHASES_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion Sync Purchases

    // region Restore Purchases

    @Test
    fun `trackRestorePurchasesStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackRestorePurchasesStarted()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.RESTORE_PURCHASES_STARTED &&


                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackRestorePurchasesResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "response_time_millis" to 1234L,
            "error_message" to "test error message",
            "error_code" to 100,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackRestorePurchasesResult(
            errorCode = 100,
            errorMessage = "test error message",
            responseTime = 1234L.milliseconds,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.RESTORE_PURCHASES_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion Restore Purchases

    // region Get Customer Info

    @Test
    fun `trackGetCustomerInfoStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetCustomerInfoStarted()
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_CUSTOMER_INFO_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackGetCustomerInfoResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "verification_result" to "VERIFIED_ON_DEVICE",
            "had_unsynced_purchases_before" to true,
            "fetch_policy" to "NOT_STALE_CACHED_OR_CURRENT",
            "error_message" to "test error message",
            "error_code" to 100,
            "response_time_millis" to 1234L,
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackGetCustomerInfoResult(
            CacheFetchPolicy.NOT_STALE_CACHED_OR_CURRENT,
            VerificationResult.VERIFIED_ON_DEVICE,
            true,
            errorMessage = "test error message",
            errorCode = 100,
            responseTime = 1234L.milliseconds,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.GET_CUSTOMER_INFO_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion Get Customer Info

    // region Purchase

    @Test
    fun `trackPurchaseStarted tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_id" to "productId",
            "product_type" to "NON_SUBSCRIPTION",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackPurchaseStarted("productId", ProductType.INAPP)
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.PURCHASE_STARTED &&
                    event.properties == expectedProperties
            })
        }
    }

    @Test
    fun `trackPurchaseResult tracks correct data`() {
        val expectedProperties = mapOf(
            "play_store_version" to "123",
            "play_services_version" to "456",
            "product_id" to "productId",
            "product_type" to "AUTO_RENEWABLE_SUBSCRIPTION",
            "error_code" to 100,
            "error_message" to "test error message",
            "response_time_millis" to 1234L,
            "verification_result" to "VERIFIED",
        )
        every { diagnosticsFileHelper.appendEvent(any()) } just Runs
        diagnosticsTracker.trackPurchaseResult(
            "productId",
            ProductType.SUBS,
            100,
            "test error message",
            1234L.milliseconds,
            VerificationResult.VERIFIED,
        )
        verify(exactly = 1) {
            diagnosticsFileHelper.appendEvent(match { event ->
                event.name == DiagnosticsEntryName.PURCHASE_RESULT &&
                    event.properties == expectedProperties
            })
        }
    }

    // endregion Purchase

    private fun mockSharedPreferences() {
        sharedPreferences = mockk()
        sharedPreferencesEditor = mockk()
        every { sharedPreferences.edit() } returns sharedPreferencesEditor
        every { sharedPreferencesEditor.apply() } just Runs
        every {
            sharedPreferencesEditor.remove(DiagnosticsHelper.CONSECUTIVE_FAILURES_COUNT_KEY)
        } returns sharedPreferencesEditor
        every {
            sharedPreferencesEditor.putInt(DiagnosticsHelper.CONSECUTIVE_FAILURES_COUNT_KEY, any())
        } returns sharedPreferencesEditor
        every {
            sharedPreferences.getInt(DiagnosticsHelper.CONSECUTIVE_FAILURES_COUNT_KEY, 0)
        } returns 0
    }

    private fun createAppConfig(store: Store = Store.PLAY_STORE): AppConfig {
        return AppConfig(
            context = context,
            purchasesAreCompletedBy = MY_APP,
            showInAppMessagesAutomatically = false,
            platformInfo = PlatformInfo(flavor = "native", version = "3.2.0"),
            proxyURL = null,
            store = store,
            isDebugBuild = false,
        )
    }

    private fun createDiagnosticsTracker(store: Store = Store.PLAY_STORE): DiagnosticsTracker {
        return DiagnosticsTracker(
            createAppConfig(store),
            diagnosticsFileHelper,
            DiagnosticsHelper(mockk(), diagnosticsFileHelper, lazy { sharedPreferences }),
            dispatcher
        )
    }
}
