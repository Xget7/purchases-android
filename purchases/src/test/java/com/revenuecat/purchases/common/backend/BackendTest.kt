package com.revenuecat.purchases.common.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.PostReceiptInitiationSource
import com.revenuecat.purchases.PresentedOfferingContext
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.common.AppConfig
import com.revenuecat.purchases.common.Backend
import com.revenuecat.purchases.common.BackendHelper
import com.revenuecat.purchases.common.CustomerInfoFactory
import com.revenuecat.purchases.common.Delay
import com.revenuecat.purchases.common.Dispatcher
import com.revenuecat.purchases.common.HTTPClient
import com.revenuecat.purchases.common.PostReceiptDataErrorCallback
import com.revenuecat.purchases.common.PostReceiptErrorHandlingBehavior
import com.revenuecat.purchases.common.ReceiptInfo
import com.revenuecat.purchases.common.SyncDispatcher
import com.revenuecat.purchases.common.createCustomerInfo
import com.revenuecat.purchases.common.createResult
import com.revenuecat.purchases.common.networking.Endpoint
import com.revenuecat.purchases.common.networking.HTTPResult
import com.revenuecat.purchases.common.networking.PostReceiptResponse
import com.revenuecat.purchases.common.networking.WebBillingPhase
import com.revenuecat.purchases.common.networking.WebBillingPrice
import com.revenuecat.purchases.common.networking.WebBillingProductResponse
import com.revenuecat.purchases.common.networking.WebBillingProductsResponse
import com.revenuecat.purchases.common.networking.WebBillingPurchaseOption
import com.revenuecat.purchases.common.networking.RCHTTPStatusCodes
import com.revenuecat.purchases.common.offlineentitlements.ProductEntitlementMapping
import com.revenuecat.purchases.common.offlineentitlements.createProductEntitlementMapping
import com.revenuecat.purchases.common.toMap
import com.revenuecat.purchases.models.GoogleReplacementMode
import com.revenuecat.purchases.models.GoogleStoreProduct
import com.revenuecat.purchases.models.GoogleSubscriptionOption
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.Price
import com.revenuecat.purchases.models.PricingPhase
import com.revenuecat.purchases.models.RecurrenceMode
import com.revenuecat.purchases.models.SubscriptionOptions
import com.revenuecat.purchases.paywalls.events.PaywallPostReceiptData
import com.revenuecat.purchases.utils.Responses
import com.revenuecat.purchases.utils.filterNotNullValues
import com.revenuecat.purchases.utils.getNullableString
import com.revenuecat.purchases.utils.mockProductDetails
import com.revenuecat.purchases.utils.stubStoreProduct
import com.revenuecat.purchases.utils.stubSubscriptionOption
import com.revenuecat.purchases.virtualcurrencies.VirtualCurrencies
import com.revenuecat.purchases.virtualcurrencies.VirtualCurrenciesFactory
import com.revenuecat.purchases.virtualcurrencies.VirtualCurrency
import io.mockk.every
import kotlinx.serialization.SerializationException
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Fail.fail
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val API_KEY = "TEST_API_KEY"

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class BackendTest {

    @Before
    fun setup() {
        mockkObject(CustomerInfoFactory)
        mockkObject(VirtualCurrenciesFactory)
        receivedError = null
        receivedOfferingsJSON = null
        receivedCustomerInfo = null
        receivedPostReceiptErrorHandlingBehavior = null
        receivedCustomerInfoCreated = null
        receivedIsServerError = null
        receivedVirtualCurrencies = null
        receivedWebBillingProductsResponse = null
    }

    @After
    fun tearDown() {
        unmockkObject(CustomerInfoFactory)
        unmockkObject(VirtualCurrenciesFactory)
    }

    private var mockClient: HTTPClient = mockk(relaxed = true)
    private val mockBaseURL = URL("http://mock-api-test.revenuecat.com/")
    private val diagnosticsEndpoint = Endpoint.PostDiagnostics
    private val productEntitlementMappingEndpoint = Endpoint.GetProductEntitlementMapping
    private val defaultAuthHeaders = mapOf("Authorization" to "Bearer $API_KEY")
    private val mockAppConfig: AppConfig = mockk<AppConfig>().apply {
        every { baseURL } returns mockBaseURL
        every { customEntitlementComputation } returns false
        every { fallbackBaseURLs } returns emptyList()
    }
    private val dispatcher = spyk(SyncDispatcher())
    private val backendHelper = BackendHelper(API_KEY, dispatcher, mockAppConfig, mockClient)
    private var backend: Backend = Backend(
        mockAppConfig,
        dispatcher,
        dispatcher,
        mockClient,
        backendHelper
    )
    private val asyncDispatcher = spyk(
        Dispatcher(
            ThreadPoolExecutor(
                1,
                2,
                0,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue()
            )
        )
    )
    private var asyncBackendHelper: BackendHelper = BackendHelper(API_KEY, asyncDispatcher, mockAppConfig, mockClient)
    private var asyncBackend: Backend = Backend(
        mockAppConfig,
        asyncDispatcher,
        Dispatcher(
            ThreadPoolExecutor(
                1,
                2,
                0,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue()
            )
        ),
        mockClient,
        asyncBackendHelper
    )
    private val appUserID = "jerry"
    private val storeProduct = stubStoreProduct("productID")
    private val productIDs = listOf("product_id_0", "product_id_1")
    private val basicReceiptInfo = ReceiptInfo(
        productIDs,
        presentedOfferingContext = PresentedOfferingContext("offering_a")
    )
    private val fetchToken = "fetch_token"
    private val initiationSource = PostReceiptInitiationSource.PURCHASE
    private val defaultTimeout = 2000L

    private var receivedCustomerInfo: CustomerInfo? = null
    private var receivedCustomerInfoCreated: Boolean? = null
    private var receivedVirtualCurrencies: VirtualCurrencies? = null
    private var receivedWebBillingProductsResponse: WebBillingProductsResponse? = null
    private var receivedOfferingsJSON: JSONObject? = null
    private var receivedError: PurchasesError? = null
    private var receivedPostReceiptErrorHandlingBehavior: PostReceiptErrorHandlingBehavior? = null
    private var receivedIsServerError: Boolean? = null
    private val noOfferingsResponse = "{'offerings': [], 'current_offering_id': null}"

    private val headersSlot = slot<Map<String, String>>()
    private val requestBodySlot = slot<Map<String, Any?>>()

    private val onReceiveCustomerInfoSuccessHandler: (CustomerInfo) -> Unit = { info ->
        this@BackendTest.receivedCustomerInfo = info
    }

    private val onReceivePostReceiptSuccessHandler: (PostReceiptResponse) -> Unit =
        { postReceiptResponse ->
            this@BackendTest.receivedCustomerInfo = postReceiptResponse.customerInfo
        }

    private val postReceiptErrorCallback: PostReceiptDataErrorCallback =
        { error, errorHandlingBehavior, _ ->
            this@BackendTest.receivedError = error
            this@BackendTest.receivedPostReceiptErrorHandlingBehavior = errorHandlingBehavior
        }

    private val onReceiveCustomerInfoErrorHandler: (PurchasesError, Boolean) -> Unit = { error, isServerError ->
        this@BackendTest.receivedError = error
        this@BackendTest.receivedIsServerError = isServerError
    }

    private val onReceiveOfferingsResponseSuccessHandler: (JSONObject) -> Unit = { offeringsJSON ->
        this@BackendTest.receivedOfferingsJSON = offeringsJSON
    }

    private val onReceiveOfferingsErrorHandler: (PurchasesError, Boolean) -> Unit = { error, isServerError ->
        this@BackendTest.receivedError = error
        this@BackendTest.receivedIsServerError = isServerError
    }

    private val onLoginSuccessHandler: (CustomerInfo, Boolean) -> Unit = { customerInfo, created ->
        this@BackendTest.receivedCustomerInfo = customerInfo
        this@BackendTest.receivedCustomerInfoCreated = created
    }

    private val onReceiveLoginErrorHandler: (PurchasesError) -> Unit = {
        this@BackendTest.receivedError = it
    }

    private val onReceiveVirtualCurrenciesSuccessHandler: (VirtualCurrencies) -> Unit = { info ->
        this@BackendTest.receivedVirtualCurrencies = info
    }

    private val onReceiveVirtualCurrenciesErrorHandler: (PurchasesError) -> Unit = { error ->
        this@BackendTest.receivedError = error
    }

    private val onReceiveWebBillingProductsSuccessHandler: (WebBillingProductsResponse) -> Unit = { response ->
        this@BackendTest.receivedWebBillingProductsResponse = response
    }

    private val onReceiveWebBillingProductsErrorHandler: (PurchasesError) -> Unit = { error ->
        this@BackendTest.receivedError = error
    }

    // region general backend functionality
    @Test
    fun canBeCreated() {
        assertThat(backend).isNotNull
    }

    @Test
    fun getSubscriberInfoCallsProperURL() {

        val info = getCustomerInfo(200, null, null)

        assertThat(receivedCustomerInfo).isNotNull
        assertThat(receivedCustomerInfo).isEqualTo(info)
    }

    @Test
    fun getSubscriberInfoFailsIfNot20X() {
        val failureCode = ThreadLocalRandom.current().nextInt(300, 500 + 1)

        getCustomerInfo(failureCode, null, null)

        assertThat(receivedCustomerInfo).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun clientErrorCallsErrorHandler() {
        getCustomerInfo(200, IOException(), null)

        assertThat(receivedCustomerInfo).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun attemptsToParseErrorMessageFromServer() {
        getCustomerInfo(404, null, "{'code': 7225, 'message': 'Dude not found'}")

        assertThat(receivedError).`as`("Received error is not null").isNotNull
        assertThat(receivedError!!.underlyingErrorMessage).`as`("Received underlying message is not null").isNotNull
        assertThat(receivedError!!.underlyingErrorMessage!!).contains("Dude not found")
    }

    @Test
    fun handlesMissingMessageInErrorBody() {
        getCustomerInfo(404, null, "{'no_message': 'Dude not found'}")
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun `getCustomerInfo error callback returns isServerError true if response code is 500`() {
        getCustomerInfo(RCHTTPStatusCodes.ERROR, null, "{}")
        assertThat(receivedIsServerError).isEqualTo(true)
    }

    @Test
    fun `getCustomerInfo error callback returns isServerError false if response code is 400`() {
        getCustomerInfo(RCHTTPStatusCodes.BAD_REQUEST, null, "{}")
        assertThat(receivedIsServerError).isEqualTo(false)
    }

    @Test
    fun `getCustomerInfo does not add post fields to sign`() {
        getCustomerInfo(200, null, null)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetCustomerInfo(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun doesntDispatchIfClosed() {
        backend.getOfferings(
            appUserID = "id",
            appInBackground = false,
            onSuccess = {},
            onError = { _, _ -> }
        )

        backend.close()

        backend.getOfferings(
            appUserID = "id",
            appInBackground = false,
            onSuccess = {},
            onError = { _, _ -> }
        )
    }

    @Test
    fun `clearing caches clears http caches`() {
        backend.clearCaches()

        verify {
            mockClient.clearCaches()
        }
    }

    // endregion

    // region getCustomerInfo

    @Test
    fun getCustomerInfoCallsProperURL() {

        val info = getCustomerInfo(200, null, null)

        assertThat(receivedCustomerInfo).isNotNull
        assertThat(receivedCustomerInfo).isEqualTo(info)
    }

    @Test
    fun getCustomerInfoFailsIfNot20X() {
        val failureCode = ThreadLocalRandom.current().nextInt(300, 500 + 1)

        getCustomerInfo(failureCode, null, null)

        assertThat(receivedCustomerInfo).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun `given multiple getCustomerInfo calls for same subscriber same body, only one is triggered`() {
        mockResponse(
            Endpoint.GetCustomerInfo(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetCustomerInfo(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `given getCustomerInfo call on foreground, then one in background, only one request without delay is triggered`() {
        mockResponse(
            Endpoint.GetCustomerInfo(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            asyncDispatcher.enqueue(any(), Delay.NONE)
        }
    }

    @Test
    fun `given getCustomerInfo call on background, then one in foreground, both are executed`() {
        mockResponse(
            Endpoint.GetCustomerInfo(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetCustomerInfo(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `customer info call is enqueued with delay if on background`() {
        dispatcher.calledDelay = null

        getCustomerInfo(200, clientException = null, resultBody = null, appInBackground = true)

        val calledWithRandomDelay: Delay? = dispatcher.calledDelay
        assertThat(calledWithRandomDelay).isNotNull
        assertThat(calledWithRandomDelay).isEqualTo(Delay.DEFAULT)
    }

    // endregion

    // region postReceipt

    @Test
    fun postReceiptCallsProperURL() {
        val info = mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received info is not null").isNotNull
        assertThat(info).isEqualTo(receivedCustomerInfo)
    }

    @Test
    fun `postReceipt calls backend once`() {
        mockPostReceiptResponseAndPost(
            backend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `postReceipt passes replacement mode and pricing phases as maps in body`() {
        val subscriptionOption = storeProduct.subscriptionOptions!!.first()
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct,
            subscriptionOptionId = subscriptionOption.id,
            replacementMode = GoogleReplacementMode.WITHOUT_PRORATION
        )

        mockPostReceiptResponseAndPost(
            backend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        val expectedPricingPhases = receiptInfo.pricingPhases
        val mappedExpectedPricingPhases = expectedPricingPhases?.map { it.toMap() }
        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured.keys).contains("pricing_phases")
        assertThat(requestBodySlot.captured["pricing_phases"]).isEqualTo(mappedExpectedPricingPhases)

        assertThat(mappedExpectedPricingPhases).isEqualTo(
            listOf(
                mapOf(
                    "billingPeriod" to "P1M",
                    "recurrenceMode" to RecurrenceMode.INFINITE_RECURRING.identifier,
                    "billingCycleCount" to 0,
                    "formattedPrice" to "\$4.99",
                    "priceAmountMicros" to 4990000L,
                    "priceCurrencyCode" to "USD"
                )
            )
        )

        assertThat(requestBodySlot.captured.keys).contains("proration_mode")
        // Backend expects the legacy proration mode values.
        assertThat(requestBodySlot.captured["proration_mode"]).isEqualTo("IMMEDIATE_WITHOUT_PRORATION")
    }

    @Test
    fun `postReceipt has product_plan_id in body if receipt is GoogleStoreProduct subscription`() {
        val productId = "product_id"
        val basePlanId = "base_plan_id"
        val productDetails = mockProductDetails()

        val recurringPhase = PricingPhase(
            billingPeriod = Period.create("P1M"),
            recurrenceMode = RecurrenceMode.INFINITE_RECURRING,
            billingCycleCount = 0,
            price = Price(
                formatted = "$9.00",
                amountMicros = 9000000,
                currencyCode = "USD",
            )
        )
        val subscriptionOption = GoogleSubscriptionOption(
            productId = productId,
            basePlanId = basePlanId,
            offerId = null,
            pricingPhases = listOf(recurringPhase),
            tags = emptyList(),
            productDetails = productDetails,
            offerToken = "mock-token"
        )

        val googleStoreProduct = GoogleStoreProduct(
            productId = productId,
            basePlanId = basePlanId,
            type = ProductType.SUBS,
            price = Price("$9.00", 9000000, "USD"),
            name = "TITLE",
            title = "TITLE (App name)",
            description = "DESCRIPTION",
            period = Period.create("P1M"),
            subscriptionOptions = SubscriptionOptions(listOf(subscriptionOption)),
            defaultOption = subscriptionOption,
            productDetails = productDetails
        )

        val receiptInfo = ReceiptInfo(
            productIDs = listOf(productId),
            storeProduct = googleStoreProduct,
            subscriptionOptionId = basePlanId
        )

        mockPostReceiptResponseAndPost(
            backend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured["platform_product_ids"]).isEqualTo(
            listOf(
                mapOf(
                    "product_id" to productId,
                    "base_plan_id" to basePlanId,
                    "offer_id" to null,
                )
            )
        )
    }

    @Test
    fun `postReceipt doesn't have product_plan_id in body if receipt is GoogleStoreProduct in-app`() {
        val productId = "product_id"
        val productDetails = mockProductDetails()

        val googleStoreProduct = GoogleStoreProduct(
            productId = productId,
            basePlanId = null,
            type = ProductType.SUBS,
            price = Price("$9.00", 9000000, "USD"),
            name = "TITLE",
            title = "TITLE (App name)",
            description = "DESCRIPTION",
            period = Period.create("P1M"),
            subscriptionOptions = null,
            defaultOption = null,
            productDetails = productDetails
        )

        val receiptInfo = ReceiptInfo(
            productIDs = listOf(productId),
            storeProduct = googleStoreProduct
        )

        mockPostReceiptResponseAndPost(
            backend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured["product_plan_id"]).isNull()
    }

    @Test
    fun `postReceipt passes normal duration in body`() {
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct
        )

        val expectedDuration = receiptInfo.duration

        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured.keys).contains("normal_duration")
        assertThat(requestBodySlot.captured["normal_duration"]).isEqualTo(expectedDuration)
    }

    @Test
    fun `postReceipt passes store user ID in body`() {
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct
        )

        val expectedStoreUserId = "id"

        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = receiptInfo,
            storeAppUserID = expectedStoreUserId,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured.keys).contains("store_user_id")
        assertThat(requestBodySlot.captured["store_user_id"]).isEqualTo(expectedStoreUserId)
    }

    @Test
    fun `postReceipt posts initiationSource`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(productIDs = productIDs, storeProduct = storeProduct),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured.keys).contains("initiation_source")
        assertThat(requestBodySlot.captured["initiation_source"]).isEqualTo(initiationSource.postReceiptFieldValue)
    }

    @Test
    fun postReceiptCallsFailsFor4XX() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 401,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received info is null").isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun `given multiple post calls for same subscriber, only one is triggered`() {
        val lock = CountDownLatch(2)

        mockPostReceiptResponseAndPost(
            asyncBackend,
            isRestore = false,
            finishTransactions = true,
            delayed = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        mockPostReceiptResponseAndPost(
            asyncBackend,
            isRestore = false,
            finishTransactions = true,
            delayed = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )

        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `gets updated subscriber after post`() {
        val verificationResult = VerificationResult.NOT_REQUESTED
        val initialInfo = createCustomerInfo(Responses.validFullPurchaserResponse)
        val updatedInfo = createCustomerInfo(Responses.validEmptyPurchaserResponse)

        assertThat(initialInfo).isEqualTo(
            CustomerInfoFactory.buildCustomerInfo(initialInfo.rawData, null, verificationResult)
        )

        mockResponse(
            Endpoint.GetCustomerInfo(appUserID),
            null,
            200,
            null,
            initialInfo.rawData.toString(),
            true,
            shouldMockCustomerInfo = false
        )

        val lock = CountDownLatch(3)

        // Given calls

        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            assertThat(it).isEqualTo(initialInfo)
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                mockResponse(
                    Endpoint.GetCustomerInfo(appUserID),
                    null,
                    200,
                    null,
                    updatedInfo.rawData.toString(),
                    true,
                    shouldMockCustomerInfo = false
                )

                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        asyncBackend.getCustomerInfo(appUserID, appInBackground = false, onSuccess = {
            assertThat(it).isEqualTo(updatedInfo)
            lock.countDown()
        }, onError = onReceiveCustomerInfoErrorHandler)

        // Expect requests:

        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                any(),
                any()
            )
        }
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetCustomerInfo(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun postReceiptNotFinishingTransactions() {
        val info = mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(productIDs),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received info is not null").isNotNull
        assertThat(info).isEqualTo(receivedCustomerInfo)
    }

    @Test
    fun `postReceipt passes price and currency`() {
        val info = mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received info is not null").isNotNull
        assertThat(info).isEqualTo(receivedCustomerInfo)
    }

    @Test
    fun `given multiple post calls for same subscriber different price, both are triggered`() {
        val receiptInfo1 = ReceiptInfo(
            productIDs,
            presentedOfferingContext = PresentedOfferingContext("offering_a"),
            storeProduct = storeProduct,
            subscriptionOptionId = "abc"
        )

        val receiptInfo2 = ReceiptInfo(
            productIDs,
            presentedOfferingContext = PresentedOfferingContext("offering_a"),
            storeProduct = storeProduct,
            subscriptionOptionId = "ef"
        )

        val lock = CountDownLatch(2)
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo1,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo2,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any() as Map<String, Any?>,
                any(),
                any()
            )
        }
    }

    @Test
    fun `given multiple post calls for same subscriber different durations, both are triggered`() {
        val receiptInfo1 = ReceiptInfo(
            productIDs,
            presentedOfferingContext = PresentedOfferingContext("offering_a"),
            storeProduct = storeProduct
        )

        val originalSubscriptionOption = storeProduct.subscriptionOptions!!.first()
        val originalDuration = originalSubscriptionOption.pricingPhases[0].billingPeriod.iso8601
        val subscriptionOption = stubSubscriptionOption(originalSubscriptionOption.id, originalDuration + "a")
        val storeProduct2 = stubStoreProduct(
            storeProduct.id,
            subscriptionOption
        )

        val receiptInfo2 = ReceiptInfo(
            productIDs,
            presentedOfferingContext = PresentedOfferingContext("offering_a"),
            storeProduct = storeProduct2
        )

        val lock = CountDownLatch(2)
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo1,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo2,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any() as Map<String, Any?>,
                any(),
                any()
            )
        }
    }

    @Test
    fun `given multiple post calls for same subscriber different offering, both are triggered`() {
        val lock = CountDownLatch(2)
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = basicReceiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )

        val receiptInfo2 = ReceiptInfo(
            basicReceiptInfo.productIDs,
            basicReceiptInfo.presentedOfferingContext?.copy(
                offeringIdentifier = basicReceiptInfo.presentedOfferingContext.offeringIdentifier + "a"
            )
        )

        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo2,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        lock.await()
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `given multiple post calls for same subscriber same durations, only one is triggered`() {
        val receiptInfo = ReceiptInfo(
            productIDs,
            presentedOfferingContext = PresentedOfferingContext("offering_a"),
            storeProduct = storeProduct
        )

        val lock = CountDownLatch(2)
        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )

        mockPostReceiptResponseAndPost(
            asyncBackend,
            delayed = true,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        lock.await()
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any() as Map<String, Any?>,
                any(),
                any()
            )
        }
    }

    @Test
    fun `postReceipt passes durations`() {
        val info = mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received purchaser info is not null").isNotNull
        assertThat(info).isEqualTo(receivedCustomerInfo)
    }

    @Test
    fun `given multiple post calls for same subscriber different store user ID, both are triggered`() {
        val lock = CountDownLatch(2)
        val receiptInfo = ReceiptInfo(productIDs)

        mockPostReceiptResponseAndPost(
            asyncBackend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            storeAppUserID = null,
            initiationSource = initiationSource,
            delayed = true,
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )

        mockPostReceiptResponseAndPost(
            asyncBackend,
            isRestore = false,
            finishTransactions = true,
            receiptInfo = receiptInfo,
            initiationSource = initiationSource,
            delayed = true,
            storeAppUserID = "store_app_user_id",
            onSuccess = { _ ->
                lock.countDown()
            },
            onError = postReceiptErrorCallback
        )
        lock.await()
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                any(),
                any()
            )
        }
    }

    @Test
    fun `postReceipt calls fail for multiple product ids errors`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 400,
            isRestore = false,
            clientException = null,
            resultBody = """
                {"code":7662,
                "message":"The product IDs list provided is not an array or does not contain only a single element."
                }""".trimIndent(),
            finishTransactions = true,
            receiptInfo = ReceiptInfo(productIDs),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedCustomerInfo).`as`("Received info is null").isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
        assertThat(receivedError!!.code)
            .`as`("Received error code is the right one")
            .isEqualTo(PurchasesErrorCode.UnsupportedError)
        assertThat(receivedPostReceiptErrorHandlingBehavior)
            .isEqualTo(PostReceiptErrorHandlingBehavior.SHOULD_NOT_CONSUME)
    }

    @Test
    fun `postReceipt error callback says purchase can be consumed if 4xx error and not unsupported error`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = RCHTTPStatusCodes.BAD_REQUEST,
            isRestore = false,
            clientException = null,
            resultBody = """
                {"code":7226,
                "message":"Backend bad request."
                }""".trimIndent(),
            finishTransactions = true,
            receiptInfo = ReceiptInfo(productIDs),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedPostReceiptErrorHandlingBehavior)
            .isEqualTo(PostReceiptErrorHandlingBehavior.SHOULD_BE_MARKED_SYNCED)
    }

    @Test
    fun `postReceipt error callback returns isServerError true if response code is 500`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = RCHTTPStatusCodes.ERROR,
            isRestore = false,
            clientException = null,
            resultBody = """
                {"code":7662,
                "message":"The product IDs list provided is not an array or does not contain only a single element."
                }""".trimIndent(),
            finishTransactions = true,
            receiptInfo = ReceiptInfo(productIDs),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedPostReceiptErrorHandlingBehavior)
            .isEqualTo(PostReceiptErrorHandlingBehavior.SHOULD_USE_OFFLINE_ENTITLEMENTS_AND_NOT_CONSUME)
    }

    @Test
    fun `postReceipt error callback returns isServerError false if response code is 400`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = RCHTTPStatusCodes.BAD_REQUEST,
            isRestore = false,
            clientException = null,
            resultBody = """
                {"code":7662,
                "message":"The product IDs list provided is not an array or does not contain only a single element."
                }""".trimIndent(),
            finishTransactions = true,
            receiptInfo = ReceiptInfo(productIDs),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(receivedPostReceiptErrorHandlingBehavior)
            .isEqualTo(PostReceiptErrorHandlingBehavior.SHOULD_NOT_CONSUME)
    }

    @Test
    fun `postReceipt passes price_string as header`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        assertThat(headersSlot.isCaptured).isTrue
        assertThat(headersSlot.captured.keys).contains("price_string")
        assertThat(headersSlot.captured["price_string"]).isEqualTo("$4.99")
    }

    @Test
    fun `postReceipt passes marketplace as header`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
            marketplace = "DE"
        )

        assertThat(headersSlot.isCaptured).isTrue
        assertThat(headersSlot.captured.keys).contains("marketplace")
        assertThat(headersSlot.captured["marketplace"]).isEqualTo("DE")
    }

    @Test
    fun `postReceipt passes price_string and marketplace as header`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
            marketplace = "US"
        )

        assertThat(headersSlot.isCaptured).isTrue
        assertThat(headersSlot.captured.keys).contains("price_string")
        assertThat(headersSlot.captured["price_string"]).isEqualTo("$4.99")
        assertThat(headersSlot.captured.keys).contains("marketplace")
        assertThat(headersSlot.captured["marketplace"]).isEqualTo("US")
    }

    @Test
    fun `postReceipt uses correct post fields to sign`() {
        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = ReceiptInfo(
                productIDs,
                storeProduct = storeProduct
            ),
            storeAppUserID = null,
            initiationSource = initiationSource,
        )

        val expectedPostFieldsToSign = listOf(
            "app_user_id" to appUserID,
            "fetch_token" to fetchToken,
        )
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.PostReceipt,
                any(),
                expectedPostFieldsToSign,
                any()
            )
        }
    }

    @Test
    fun `postReceipt passes paywall in body`() {
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct
        )

        val expectedStoreUserId = "id"

        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = receiptInfo,
            storeAppUserID = expectedStoreUserId,
            initiationSource = initiationSource,
            paywallPostReceiptData = PaywallPostReceiptData(
                sessionID = "1234-1234-1234-1234",
                revision = 17,
                displayMode = "full_screen",
                darkMode = true,
                localeIdentifier = "en_US",
                offeringId = "onboarding"
            )
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured.keys).contains("paywall")
        assertThat(requestBodySlot.captured["paywall"]).isEqualTo(mapOf(
            "session_id" to "1234-1234-1234-1234",
            "revision" to 17,
            "display_mode" to "full_screen",
            "dark_mode" to true,
            "locale" to "en_US",
            "offering_id" to "onboarding",
        ))
    }

    @Test
    fun `postReceipt passes presented_placement_identifier in body`() {
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct,
            presentedOfferingContext = PresentedOfferingContext(
                "offering_a",
                placementIdentifier = "placement_a",
                targetingContext = null,
            ),
        )

        val expectedStoreUserId = "id"

        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = receiptInfo,
            storeAppUserID = expectedStoreUserId,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured["presented_offering_identifier"]).isEqualTo("offering_a")
        assertThat(requestBodySlot.captured["presented_placement_identifier"]).isEqualTo("placement_a")
    }

    @Test
    fun `postReceipt passes applied targeting rule in body`() {
        val receiptInfo = ReceiptInfo(
            productIDs = productIDs,
            storeProduct = storeProduct,
            presentedOfferingContext = PresentedOfferingContext(
                "offering_a",
                placementIdentifier = null,
                targetingContext = PresentedOfferingContext.TargetingContext(
                    revision = 1,
                    ruleId = "abc123",
                ),
            ),
        )

        val expectedStoreUserId = "id"

        mockPostReceiptResponseAndPost(
            backend,
            responseCode = 200,
            isRestore = false,
            clientException = null,
            resultBody = null,
            finishTransactions = false,
            receiptInfo = receiptInfo,
            storeAppUserID = expectedStoreUserId,
            initiationSource = initiationSource,
        )

        assertThat(requestBodySlot.isCaptured).isTrue
        assertThat(requestBodySlot.captured["applied_targeting_rule"]).isEqualTo(mapOf(
            "revision" to 1,
            "rule_id" to "abc123",
        ))
    }

    // endregion

    // region getOfferings

    @Test
    fun `given a no offerings response`() {
        mockResponse(Endpoint.GetOfferings(appUserID), null, 200, null, noOfferingsResponse)

        backend.getOfferings(
            appUserID,
            appInBackground = false,
            onSuccess = onReceiveOfferingsResponseSuccessHandler,
            onError = { _, _ -> fail("Should be success") }
        )

        assertThat(receivedOfferingsJSON).`as`("Received offerings response is not null").isNotNull
        assertThat(receivedOfferingsJSON!!.getJSONArray("offerings").length()).isZero
        assertThat(receivedOfferingsJSON!!.getNullableString("current_offering_id")).isNull()
    }

    @Test
    fun `given a server error, correct callback values are given`() {
        mockResponse(Endpoint.GetOfferings(appUserID), null, RCHTTPStatusCodes.ERROR, null, null)

        backend.getOfferings(
            appUserID,
            appInBackground = false,
            onSuccess = { fail("Should be error") },
            onError = onReceiveOfferingsErrorHandler
        )

        assertThat(receivedError).isNotNull
        assertThat(receivedIsServerError).isTrue
    }

    @Test
    fun `given a non server error, correct callback values are given`() {
        mockResponse(Endpoint.GetOfferings(appUserID), null, RCHTTPStatusCodes.BAD_REQUEST, null, null)

        backend.getOfferings(
            appUserID,
            appInBackground = false,
            onSuccess = { fail("Should be error") },
            onError = onReceiveOfferingsErrorHandler
        )

        assertThat(receivedError).isNotNull
        assertThat(receivedIsServerError).isFalse
    }

    @Test
    fun `given multiple get offerings calls for same user, only one is triggered`() {
        mockResponse(
            Endpoint.GetOfferings(appUserID),
            null,
            200,
            null,
            noOfferingsResponse,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getOfferings(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        asyncBackend.getOfferings(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetOfferings(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `given multiple offerings get calls for different user, both are triggered`() {
        mockResponse(
            Endpoint.GetOfferings(appUserID),
            null,
            200,
            null,
            noOfferingsResponse,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getOfferings(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        asyncBackend.getOfferings("anotherUser", appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetOfferings(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetOfferings("anotherUser"),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `given getOfferings call on foreground, then one in background, only one request without delay is triggered`() {
        mockResponse(
            Endpoint.GetOfferings(appUserID),
            null,
            200,
            null,
            noOfferingsResponse,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getOfferings(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        asyncBackend.getOfferings(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            asyncDispatcher.enqueue(any(), Delay.NONE)
        }
    }

    @Test
    fun `given getOfferings call on background, then one in foreground, both are executed`() {
        mockResponse(
            Endpoint.GetOfferings(appUserID),
            null,
            200,
            null,
            noOfferingsResponse,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getOfferings(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        asyncBackend.getOfferings(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveOfferingsErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetOfferings(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `offerings call is enqueued with delay if on background`() {
        mockResponse(Endpoint.GetOfferings(appUserID), null, 200, null, noOfferingsResponse)
        dispatcher.calledDelay = null
        backend.getOfferings(
            appUserID,
            appInBackground = true,
            onSuccess = onReceiveOfferingsResponseSuccessHandler,
            onError = onReceiveOfferingsErrorHandler
        )

        val calledDelay = dispatcher.calledDelay
        assertThat(calledDelay).isNotNull
        assertThat(calledDelay).isEqualTo(Delay.DEFAULT)
    }

    @Test
    fun `getOfferings does not send post fields to sign`() {
        mockResponse(Endpoint.GetOfferings(appUserID), null, 200, null, noOfferingsResponse)

        backend.getOfferings(
            appUserID,
            appInBackground = false,
            onSuccess = onReceiveOfferingsResponseSuccessHandler,
            onError = { _, _ -> fail("Should be success") }
        )

        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetOfferings(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    // endregion

    // region identity
    @Test
    fun `logIn makes the right http call`() {
        val newAppUserID = "newId"
        val body = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        mockResponse(
            Endpoint.LogIn,
            body,
            201,
            null,
            Responses.validFullPurchaserResponse
        )

        backend.logIn(
            appUserID,
            newAppUserID,
            onLoginSuccessHandler
        ) {
            fail("Should have called success")
        }
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.LogIn,
                body,
                any(),
                any()
            )
        }
    }

    @Test
    fun `logIn correctly parses customerInfo`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = Responses.validFullPurchaserResponse
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 201,
            clientException = null,
            resultBody = resultBody,
            delayed = false,
            shouldMockCustomerInfo = false
        )
        val expectedCustomerInfo = createCustomerInfo(resultBody)

        backend.logIn(
            appUserID,
            newAppUserID,
            onLoginSuccessHandler
        ) {
            fail("Should have called success")
        }
        assertThat(receivedCustomerInfo).isEqualTo(expectedCustomerInfo)
        assertThat(receivedCustomerInfoCreated).isEqualTo(true)
    }

    @Test
    fun `logIn calls OnError if customerInfo can't be parsed`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID
        )
        val resultBody = "{}"
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 201,
            clientException = null,
            resultBody = resultBody,
            delayed = false,
            shouldMockCustomerInfo = false
        )

        backend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                fail("Should have called error")
            },
            onReceiveLoginErrorHandler
        )
        assertThat(receivedError).isNotNull
        assertThat(receivedError?.code).isEqualTo(PurchasesErrorCode.UnknownError)
    }

    @Test
    fun `logIn returns created true if status is 201`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = Responses.validFullPurchaserResponse
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 201,
            clientException = null,
            resultBody = resultBody
        )

        backend.logIn(
            appUserID,
            newAppUserID,
            onLoginSuccessHandler
        ) {
            fail("Should have called success")
        }
        assertThat(receivedCustomerInfoCreated).isTrue
    }

    @Test
    fun `logIn returns created false if status isn't 201`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = Responses.validFullPurchaserResponse
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody
        )

        backend.logIn(
            appUserID,
            newAppUserID,
            onLoginSuccessHandler
        ) {
            fail("Should have called success")
        }
        assertThat(receivedCustomerInfoCreated).isFalse
    }

    @Test
    fun `logIn uses correct post fields to sign`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = Responses.validFullPurchaserResponse
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody
        )

        backend.logIn(
            appUserID,
            newAppUserID,
            onLoginSuccessHandler
        ) {
            fail("Should have called success")
        }

        val expectedPostFieldsToSign = listOf(
            "app_user_id" to appUserID,
            "new_app_user_id" to newAppUserID,
        )
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.LogIn,
                requestBody,
                expectedPostFieldsToSign,
                any()
            )
        }
    }

    @Test
    fun `given multiple login calls for same ids, only one is triggered`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = Responses.validFullPurchaserResponse
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody,
            delayed = true
        )

        val lock = CountDownLatch(2)
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                lock.countDown()
            },
            {
                fail("Should have called success")
            }
        )
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                lock.countDown()
            },
            {
                fail("Should have called success")
            }
        )
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.LogIn,
                requestBody,
                any(),
                any()
            )
        }
    }

    @Test
    fun `given multiple login calls for same ids, only one http call is triggered, and all onError callbacks are called if customerInfo can't be parsed`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = "{}"
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody,
            delayed = true
        )

        val lock = CountDownLatch(2)
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                fail("Should have called error")
            },
            {
                lock.countDown()
            }
        )
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                fail("Should have called error")
            },
            {
                lock.countDown()
            }
        )
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.LogIn,
                requestBody,
                any(),
                any()
            )
        }
    }

    @Test
    fun `given multiple login calls for same ids, only one http call is triggered, and all onError callbacks are called if call is not successful`() {
        val newAppUserID = "newId"
        val requestBody = mapOf(
            "new_app_user_id" to newAppUserID,
            "app_user_id" to appUserID
        )
        val resultBody = "{}"
        mockResponse(
            Endpoint.LogIn,
            requestBody,
            responseCode = 500,
            clientException = null,
            resultBody = resultBody,
            delayed = true
        )

        val lock = CountDownLatch(2)
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                fail("Should have called error")
            },
            {
                lock.countDown()
            }
        )
        asyncBackend.logIn(
            appUserID,
            newAppUserID,
            { _, _ ->
                fail("Should have called error")
            },
            {
                lock.countDown()
            }
        )
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.LogIn,
                requestBody,
                any(),
                any()
            )
        }
    }

    // region postDiagnostics

    @Test
    fun `postDiagnostics makes call with correct parameters`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        backend.postDiagnostics(diagnosticsList, {}, { _, _ -> })
        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = AppConfig.diagnosticsURL,
                endpoint = diagnosticsEndpoint,
                body = mapOf("entries" to JSONArray(diagnosticsList)),
                postFieldsToSign = null,
                requestHeaders = mapOf("Authorization" to "Bearer TEST_API_KEY")
            )
        }
    }

    @Test
    fun `postDiagnostics only executes once same request if one in progress`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = null,
            delayed = true,
            baseURL = AppConfig.diagnosticsURL,
        )
        val lock = CountDownLatch(3)
        asyncBackend.postDiagnostics(diagnosticsList, { lock.countDown() }, { _, _ -> fail("expected success") })
        asyncBackend.postDiagnostics(diagnosticsList, { lock.countDown() }, { _, _ -> fail("expected success") })
        asyncBackend.postDiagnostics(diagnosticsList, { lock.countDown() }, { _, _ -> fail("expected success") })
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = AppConfig.diagnosticsURL,
                endpoint = diagnosticsEndpoint,
                body = mapOf("entries" to JSONArray(diagnosticsList)),
                postFieldsToSign = null,
                requestHeaders = mapOf("Authorization" to "Bearer TEST_API_KEY")
            )
        }
    }

    @Test
    fun `postDiagnostics executes same request if done after first one finishes`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = null,
            delayed = true,
            baseURL = AppConfig.diagnosticsURL,
        )
        val lock = CountDownLatch(1)
        asyncBackend.postDiagnostics(diagnosticsList, { lock.countDown() }, { _, _ -> fail("expected success") })
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        val lock2 = CountDownLatch(1)
        asyncBackend.postDiagnostics(diagnosticsList, { lock2.countDown() }, { _, _ -> fail("expected success") })
        lock2.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock2.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                baseURL = AppConfig.diagnosticsURL,
                endpoint = diagnosticsEndpoint,
                body = mapOf("entries" to JSONArray(diagnosticsList)),
                postFieldsToSign = null,
                requestHeaders = mapOf("Authorization" to "Bearer TEST_API_KEY")
            )
        }
    }

    @Test
    fun `postDiagnostics calls error handler without retry when InsufficientPermissionsError`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = SecurityException(),
            resultBody = null,
            baseURL = AppConfig.diagnosticsURL,
        )
        var errorCalled = false
        backend.postDiagnostics(
            diagnosticsList,
            { fail("expected error") },
            { error, shouldRetry ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.InsufficientPermissionsError)
                assertFalse(shouldRetry)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `postDiagnostics calls error handler with retry when Network error`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = IOException(),
            resultBody = null,
            baseURL = AppConfig.diagnosticsURL,
        )
        var errorCalled = false
        backend.postDiagnostics(
            diagnosticsList,
            { fail("expected error") },
            { error, shouldRetry ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.NetworkError)
                assertTrue(shouldRetry)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `postDiagnostics calls error handler with retry if status code is 500`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 500,
            clientException = null,
            resultBody = null,
            baseURL = AppConfig.diagnosticsURL,
        )
        var errorCalled = false
        backend.postDiagnostics(
            diagnosticsList,
            { fail("expected error") },
            { error, shouldRetry ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.UnknownBackendError)
                assertTrue(shouldRetry)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `postDiagnostics calls error handler without retry if status code is 400`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 400,
            clientException = null,
            resultBody = "{\"code\":7101}", // BackendStoreProblem
            baseURL = AppConfig.diagnosticsURL,
        )
        var errorCalled = false
        backend.postDiagnostics(
            diagnosticsList,
            { fail("expected error") },
            { error, shouldRetry ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
                assertFalse(shouldRetry)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `postDiagnostics calls success handler`() {
        val diagnosticsList = listOf(JSONObject("{\"test-key\":\"test-value\"}"))
        val resultBody = "{\"test-response-key\":1234}"
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody,
            baseURL = AppConfig.diagnosticsURL,
        )
        var successCalled = false
        backend.postDiagnostics(
            diagnosticsList,
            {
                successCalled = true
                assertThat(it.toString()).isEqualTo(resultBody)
            },
            { _, _ -> fail("expected success") }
        )
        assertTrue(successCalled)
    }

    @Test
    fun `postDiagnostics call is enqueued with delay`() {
        mockResponse(
            endpoint = diagnosticsEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = "{}",
            baseURL = AppConfig.diagnosticsURL,
        )
        dispatcher.calledDelay = null
        backend.postDiagnostics(
            listOf(JSONObject("{\"test-key\":\"test-value\"}")),
            {},
            { _, _ -> fail("expected success") }
        )

        val calledDelay = dispatcher.calledDelay
        assertThat(calledDelay).isNotNull
        assertThat(calledDelay).isEqualTo(Delay.LONG)
    }

    // endregion

    // region getProductEntitlementMapping

    @Test
    fun `getProductEntitlementMapping makes call with correct parameters`() {
        backend.getProductEntitlementMapping({}, {})
        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = mockBaseURL,
                endpoint = productEntitlementMappingEndpoint,
                body = null,
                postFieldsToSign = null,
                requestHeaders = defaultAuthHeaders
            )
        }
    }

    @Test
    fun `getProductEntitlementMapping only executes once same request if one in progress`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = "{\"product_entitlement_mapping\":{}}",
            delayed = true
        )
        val lock = CountDownLatch(3)
        asyncBackend.getProductEntitlementMapping({ lock.countDown() }, { fail("expected succcess") })
        asyncBackend.getProductEntitlementMapping({ lock.countDown() }, { fail("expected succcess") })
        asyncBackend.getProductEntitlementMapping({ lock.countDown() }, { fail("expected succcess") })
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = mockBaseURL,
                endpoint = productEntitlementMappingEndpoint,
                body = null,
                postFieldsToSign = null,
                requestHeaders = defaultAuthHeaders
            )
        }
    }

    @Test
    fun `getProductEntitlementMapping executes same request if done after first one finishes`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = "{\"product_entitlement_mapping\":{}}",
            delayed = true
        )

        val lock = CountDownLatch(1)
        asyncBackend.getProductEntitlementMapping({ lock.countDown() }, { fail("expected succcess") })
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)

        val lock2 = CountDownLatch(1)
        asyncBackend.getProductEntitlementMapping({ lock2.countDown() }, { fail("expected succcess") })
        lock2.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock2.count).isEqualTo(0)

        verify(exactly = 2) {
            mockClient.performRequest(
                baseURL = mockBaseURL,
                endpoint = productEntitlementMappingEndpoint,
                body = null,
                postFieldsToSign = null,
                requestHeaders = defaultAuthHeaders
            )
        }
    }

    @Test
    fun `getProductEntitlementMapping calls error handler when Network error`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 200,
            clientException = IOException(),
            resultBody = null
        )
        var errorCalled = false
        backend.getProductEntitlementMapping(
            { fail("expected error") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.NetworkError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `getProductEntitlementMapping calls error handler if status code is 500`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 500,
            clientException = null,
            resultBody = null,
            baseURL = mockBaseURL
        )
        var errorCalled = false
        backend.getProductEntitlementMapping(
            { fail("expected error") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.UnknownBackendError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `getProductEntitlementMapping calls error handler if status code is 400`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 400,
            clientException = null,
            resultBody = "{\"code\":7101}", // BackendStoreProblem
            baseURL = mockBaseURL
        )
        var errorCalled = false
        backend.getProductEntitlementMapping(
            { fail("expected error") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.StoreProblemError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `getProductEntitlementMapping calls success handler`() {
        val resultBody = "{\"product_entitlement_mapping\":{" +
            "\"test-product-id\":{\"product_identifier\":\"test-product-id\"," +
            "\"entitlements\":[\"entitlement-1\",\"entitlement-2\"]" +
            "}}}"
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = resultBody,
            baseURL = mockBaseURL
        )
        var successCalled = false
        backend.getProductEntitlementMapping(
            {
                successCalled = true
                val expectedMapping = createProductEntitlementMapping(
                    mapOf(
                        "test-product-id" to ProductEntitlementMapping.Mapping(
                            "test-product-id",
                            null,
                            listOf("entitlement-1", "entitlement-2")
                        )
                    )
                )
                assertThat(it).isEqualTo(expectedMapping)
            },
            { error -> fail("expected success $error", error) }
        )
        assertTrue(successCalled)
    }

    @Test
    fun `getProductEntitlementMapping call is enqueued with long delay`() {
        mockResponse(
            endpoint = productEntitlementMappingEndpoint,
            body = null,
            responseCode = 200,
            clientException = null,
            resultBody = "{\"product_entitlement_mapping\":{}}",
            baseURL = mockBaseURL
        )
        dispatcher.calledDelay = null
        backend.getProductEntitlementMapping(
            {},
            { fail("expected success") }
        )

        val calledDelay = dispatcher.calledDelay
        assertThat(calledDelay).isNotNull
        assertThat(calledDelay).isEqualTo(Delay.LONG)
    }

    // endregion

    // region getVirtualCurrencies

    @Test
    fun getVirtualCurrenciesCallsProperURL() {
        val virtualCurrencies = getVirtualCurrencies(200, null, null)

        assertThat(receivedVirtualCurrencies).isNotNull
        assertThat(receivedVirtualCurrencies).isEqualTo(virtualCurrencies)

        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = mockBaseURL,
                endpoint = Endpoint.GetVirtualCurrencies(appUserID),
                body = null,
                postFieldsToSign = null,
                requestHeaders = defaultAuthHeaders
            )
        }
    }

    @Test
    fun `getVirtualCurrencies calls success handler for successful request`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            Responses.validFullVirtualCurrenciesResponse,
            true,
            shouldMockVirtualCurrencies = false
        )
        var successCalled = false
        backend.getVirtualCurrencies(appUserID, false,
            {
                successCalled = true
                val expectedVirtualCurrencies = VirtualCurrencies(
                    all = mapOf(
                        "COIN" to VirtualCurrency(
                            balance = 1,
                            name = "Coin",
                            code = "COIN",
                            serverDescription = "It's a coin",
                        ),
                        "RC_COIN" to VirtualCurrency(
                            balance = 0,
                            name = "RC Coin",
                            code = "RC_COIN",
                            serverDescription = null,
                        ),
                    ),
                )
                assertThat(it).isEqualTo(expectedVirtualCurrencies)
            },
            { error -> fail("expected success $error", error) }
        )
        assertTrue(successCalled)
    }

    @Test
    fun getVirtualCurrenciesFailsIf40X() {
        val failureCode = 400

        getVirtualCurrencies(failureCode, null, null)

        assertThat(receivedVirtualCurrencies).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun getVirtualCurrenciesFailsIf50X() {
        val failureCode = 500

        getVirtualCurrencies(failureCode, null, null)

        assertThat(receivedVirtualCurrencies).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun `getVirtualCurrencies calls error handler when a Network error occurs`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            IOException(),
            null
        )
        var errorCalled = false
        backend.getVirtualCurrencies(
            appUserID,
            appInBackground = false,
            { fail("expected error handler to be called") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.NetworkError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `given multiple getVirtualCurrencies calls for same subscriber same body, only one is triggered`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetVirtualCurrencies(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `given getVirtualCurrencies call on foreground, then one in background, only one request without delay is triggered`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            asyncDispatcher.enqueue(any(), Delay.NONE)
        }
    }

    @Test
    fun `given getVirtualCurrencies call on background, then one in foreground, both are executed`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = true, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        asyncBackend.getVirtualCurrencies(appUserID, appInBackground = false, onSuccess = {
            lock.countDown()
        }, onError = onReceiveVirtualCurrenciesErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 2) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.GetVirtualCurrencies(appUserID),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }

    @Test
    fun `getVirtualCurrencies call is enqueued with delay if on background`() {
        dispatcher.calledDelay = null

        getVirtualCurrencies(200, clientException = null, resultBody = null, appInBackground = true)

        val calledWithRandomDelay: Delay? = dispatcher.calledDelay
        assertThat(calledWithRandomDelay).isNotNull
        assertThat(calledWithRandomDelay).isEqualTo(Delay.DEFAULT)
    }

    @Test
    fun `getVirtualCurrencies calls error handler when VirtualCurrenciesFactory throws JSONException`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            virtualCurrenciesFactoryException = JSONException("Invalid JSON")
        )
        var errorCalled = false
        backend.getVirtualCurrencies(
            appUserID,
            appInBackground = false,
            { fail("expected error handler to be called") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.NetworkError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `getVirtualCurrencies calls error handler when VirtualCurrenciesFactory throws SerializationException`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            virtualCurrenciesFactoryException = SerializationException("Serialization error")
        )
        var errorCalled = false
        backend.getVirtualCurrencies(
            appUserID,
            appInBackground = false,
            { fail("expected error handler to be called") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.UnknownError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `getVirtualCurrencies calls error handler when VirtualCurrenciesFactory throws IllegalArgumentException`() {
        mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            200,
            null,
            null,
            virtualCurrenciesFactoryException = IllegalArgumentException("Invalid input")
        )
        var errorCalled = false
        backend.getVirtualCurrencies(
            appUserID,
            appInBackground = false,
            { fail("expected error handler to be called") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.UnknownError)
            }
        )
        assertTrue(errorCalled)
    }
    // endregion Virtual currencies

    // region WebBilling products

    @Test
    fun getWebBillingProductsCallsProperURL() {
        val productIDs = setOf("product1", "product2")
        val response = getWebBillingProductsResponse(200, productIDs, null, null)

        assertThat(receivedWebBillingProductsResponse).isNotNull
        assertThat(receivedWebBillingProductsResponse).isEqualTo(response)

        verify(exactly = 1) {
            mockClient.performRequest(
                baseURL = mockBaseURL,
                endpoint = Endpoint.WebBillingGetProducts(appUserID, productIDs),
                body = null,
                postFieldsToSign = null,
                requestHeaders = defaultAuthHeaders
            )
        }
    }

    @Test
    fun `getWebBillingProducts calls success handler for successful request`() {
        val productIDs = setOf("product1", "product2")
        mockGetWebBillingProductsResponse(
            Endpoint.WebBillingGetProducts(appUserID, productIDs),
            200,
            null,
            Responses.validWebBillingProductsResponse,
            true,
        )
        var successCalled = false
        backend.getWebBillingProducts(appUserID, productIDs,
            {
                successCalled = true
                val expectedWebBillingProductsResponse = WebBillingProductsResponse(
                    productDetails = listOf(
                        WebBillingProductResponse(
                            identifier = "product1",
                            productType = "subscription",
                            title = "Test Monthly Subscription",
                            description = "A test monthly subscription product",
                            defaultPurchaseOptionId = "base_option",
                            purchaseOptions = mapOf(
                                "base_option" to WebBillingPurchaseOption(
                                    base = WebBillingPhase(
                                        price = WebBillingPrice(
                                            amountMicros = 9990000,
                                            currency = "EUR",
                                        ),
                                        periodDuration = "P1M",
                                        cycleCount = 1,
                                    )
                                )
                            ),
                        ),
                        WebBillingProductResponse(
                            identifier = "product2",
                            productType = "subscription",
                            title = "Test Monthly Subscription",
                            description = "A test monthly subscription product",
                            defaultPurchaseOptionId = "base_option",
                            purchaseOptions = mapOf(
                                "base_option" to WebBillingPurchaseOption(
                                    base = WebBillingPhase(
                                        price = WebBillingPrice(
                                            amountMicros = 9990000,
                                            currency = "EUR",
                                        ),
                                        periodDuration = "P1M",
                                        cycleCount = 1,
                                    )
                                )
                            ),
                        ),
                    ),
                )
                assertThat(it).isEqualTo(expectedWebBillingProductsResponse)
            },
            { error -> fail("expected success $error", error) }
        )
        assertTrue(successCalled)
    }

    @Test
    fun getWebBillingProductsFailsIf40X() {
        val failureCode = 400

        getWebBillingProductsResponse(failureCode, emptySet(), null, null)

        assertThat(receivedVirtualCurrencies).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun getWebBillingProductsFailsIf50X() {
        val failureCode = 500

        getWebBillingProductsResponse(failureCode, emptySet(), null, null)

        assertThat(receivedVirtualCurrencies).isNull()
        assertThat(receivedError).`as`("Received error is not null").isNotNull
    }

    @Test
    fun `getWebBillingProducts calls error handler when a Network error occurs`() {
        val productIDs = setOf("product1", "product2")
        mockGetWebBillingProductsResponse(
            Endpoint.WebBillingGetProducts(appUserID, productIDs),
            200,
            IOException(),
            null
        )
        var errorCalled = false
        backend.getWebBillingProducts(
            appUserID,
            productIDs,
            { fail("expected error handler to be called") },
            { error ->
                errorCalled = true
                assertThat(error.code).isEqualTo(PurchasesErrorCode.NetworkError)
            }
        )
        assertTrue(errorCalled)
    }

    @Test
    fun `given multiple getWebBillingProduct calls for same subscriber same body, only one is triggered`() {
        val productIDs = setOf("product1", "product2")
        mockGetWebBillingProductsResponse(
            Endpoint.WebBillingGetProducts(appUserID, productIDs),
            200,
            null,
            null,
            true
        )
        val lock = CountDownLatch(2)
        asyncBackend.getWebBillingProducts(appUserID, productIDs, onSuccess = {
            lock.countDown()
        }, onError = onReceiveWebBillingProductsErrorHandler)
        asyncBackend.getWebBillingProducts(appUserID, productIDs, onSuccess = {
            lock.countDown()
        }, onError = onReceiveWebBillingProductsErrorHandler)
        lock.await(defaultTimeout, TimeUnit.MILLISECONDS)
        assertThat(lock.count).isEqualTo(0)
        verify(exactly = 1) {
            mockClient.performRequest(
                mockBaseURL,
                Endpoint.WebBillingGetProducts(appUserID, productIDs),
                body = null,
                postFieldsToSign = null,
                any()
            )
        }
    }
    // endregion WebBilling Products

    // region helpers

    private fun mockResponse(
        endpoint: Endpoint,
        body: Map<String, Any?>?,
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        delayed: Boolean = false,
        shouldMockCustomerInfo: Boolean = true,
        baseURL: URL = mockBaseURL
    ): CustomerInfo {
        val info: CustomerInfo = mockk()

        val result = HTTPResult.createResult(responseCode, resultBody ?: "{}")

        if (shouldMockCustomerInfo) {
            every {
                CustomerInfoFactory.buildCustomerInfo(result)
            } returns info
        }
        val everyMockedCall = every {
            mockClient.performRequest(
                eq(baseURL),
                eq(endpoint),
                (if (body == null) any() else capture(requestBodySlot)),
                any(),
                capture(headersSlot)
            )
        }

        if (clientException == null) {
            everyMockedCall answers {
                if (delayed) Thread.sleep(200)
                result
            }
        } else {
            everyMockedCall throws clientException
        }

        return info
    }

    private fun mockPostReceiptResponseAndPost(
        backend: Backend,
        token: String = fetchToken,
        responseCode: Int = 200,
        isRestore: Boolean,
        clientException: Exception? = null,
        resultBody: String? = null,
        finishTransactions: Boolean,
        receiptInfo: ReceiptInfo,
        storeAppUserID: String?,
        initiationSource: PostReceiptInitiationSource,
        delayed: Boolean = false,
        marketplace: String? = null,
        paywallPostReceiptData: PaywallPostReceiptData? = null,
        onSuccess: (PostReceiptResponse) -> Unit = onReceivePostReceiptSuccessHandler,
        onError: PostReceiptDataErrorCallback = postReceiptErrorCallback
    ): CustomerInfo {
        val info = mockPostReceiptResponse(
            isRestore = isRestore,
            responseCode = responseCode,
            clientException = clientException,
            resultBody = resultBody,
            finishTransactions = finishTransactions,
            receiptInfo = receiptInfo,
            storeAppUserID = storeAppUserID,
            paywallPostReceiptData = paywallPostReceiptData,
            delayed = delayed
        )

        backend.postReceiptData(
            purchaseToken = token,
            appUserID = appUserID,
            isRestore = isRestore,
            finishTransactions = finishTransactions,
            subscriberAttributes = emptyMap(),
            receiptInfo = receiptInfo,
            storeAppUserID = storeAppUserID,
            marketplace = marketplace,
            initiationSource = initiationSource,
            paywallPostReceiptData = paywallPostReceiptData,
            onSuccess = onSuccess,
            onError = onError
        )

        return info
    }

    private fun mockPostReceiptResponse(
        token: String = fetchToken,
        isRestore: Boolean,
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        delayed: Boolean = false,
        finishTransactions: Boolean,
        receiptInfo: ReceiptInfo,
        storeAppUserID: String?,
        paywallPostReceiptData: PaywallPostReceiptData? = null,
    ): CustomerInfo {
        val body = mapOf(
            "fetch_token" to token,
            "app_user_id" to appUserID,
            "product_ids" to receiptInfo.productIDs,
            "is_restore" to isRestore,
            "presented_offering_identifier" to receiptInfo.presentedOfferingContext?.offeringIdentifier,
            "presented_placement_identifier" to receiptInfo.presentedOfferingContext?.placementIdentifier,
            "observer_mode" to !finishTransactions,
            "price" to receiptInfo.price,
            "currency" to receiptInfo.currency,
            "normal_duration" to receiptInfo.duration,
            "store_user_id" to storeAppUserID,
            "paywall" to paywallPostReceiptData?.toMap(),
        ).filterNotNullValues()

        return mockResponse(
            Endpoint.PostReceipt,
            body,
            responseCode,
            clientException,
            resultBody,
            delayed
        )
    }

    private fun getCustomerInfo(
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        appInBackground: Boolean = false
    ): CustomerInfo {
        val info =
            mockResponse(Endpoint.GetCustomerInfo(appUserID), null, responseCode, clientException, resultBody)

        backend.getCustomerInfo(
            appUserID,
            appInBackground,
            onReceiveCustomerInfoSuccessHandler,
            onReceiveCustomerInfoErrorHandler
        )

        return info
    }

    private fun getVirtualCurrencies(
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        appInBackground: Boolean = false
    ): VirtualCurrencies {
        val virtualCurrencies = mockGetVirtualCurrenciesResponse(
            Endpoint.GetVirtualCurrencies(appUserID),
            null,
            responseCode,
            clientException,
            resultBody
        )

        backend.getVirtualCurrencies(
            appUserID,
            appInBackground,
            onReceiveVirtualCurrenciesSuccessHandler,
            onReceiveVirtualCurrenciesErrorHandler
        )

        return virtualCurrencies
    }

    private fun getWebBillingProductsResponse(
        responseCode: Int,
        productIDs: Set<String> = emptySet(),
        clientException: Exception?,
        resultBody: String?,
    ): WebBillingProductsResponse {
        val productsResponse = mockGetWebBillingProductsResponse(
            Endpoint.WebBillingGetProducts(appUserID, productIDs),
            responseCode,
            clientException,
            resultBody
        )

        backend.getWebBillingProducts(
            appUserID,
            productIDs,
            onReceiveWebBillingProductsSuccessHandler,
            onReceiveWebBillingProductsErrorHandler,
        )

        return productsResponse
    }

    private fun mockGetVirtualCurrenciesResponse(
        endpoint: Endpoint,
        body: Map<String, Any?>?,
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        delayed: Boolean = false,
        shouldMockVirtualCurrencies: Boolean = true,
        virtualCurrenciesFactoryException: Exception? = null,
        baseURL: URL = mockBaseURL
    ): VirtualCurrencies {
        val virtualCurrencies: VirtualCurrencies = mockk()

        val result = HTTPResult.createResult(responseCode, resultBody ?: "{\"virtual_currencies\":{}}")

        if (virtualCurrenciesFactoryException != null) {
            every {
                VirtualCurrenciesFactory.buildVirtualCurrencies(result)
            } throws virtualCurrenciesFactoryException
        } else if (shouldMockVirtualCurrencies) {
            every {
                VirtualCurrenciesFactory.buildVirtualCurrencies(result)
            } returns virtualCurrencies
        }
        val everyMockedCall = every {
            mockClient.performRequest(
                eq(baseURL),
                eq(endpoint),
                (if (body == null) any() else capture(requestBodySlot)),
                any(),
                capture(headersSlot)
            )
        }

        if (clientException == null) {
            everyMockedCall answers {
                if (delayed) Thread.sleep(200)
                result
            }
        } else {
            everyMockedCall throws clientException
        }

        return virtualCurrencies
    }

    private fun mockGetWebBillingProductsResponse(
        endpoint: Endpoint,
        responseCode: Int,
        clientException: Exception?,
        resultBody: String?,
        delayed: Boolean = false,
        baseURL: URL = mockBaseURL
    ): WebBillingProductsResponse {
        val response = WebBillingProductsResponse(productDetails = emptyList())

        val result = HTTPResult.createResult(responseCode, resultBody ?: "{\"product_details\":[]}")

        val everyMockedCall = every {
            mockClient.performRequest(
                eq(baseURL),
                eq(endpoint),
                null,
                any(),
                capture(headersSlot)
            )
        }

        if (clientException == null) {
            everyMockedCall answers {
                if (delayed) Thread.sleep(200)
                result
            }
        } else {
            everyMockedCall throws clientException
        }

        return response
    }

    // endregion
}
