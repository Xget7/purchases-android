package com.revenuecat.purchases

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.revenuecat.purchases.common.LogIntent
import com.revenuecat.purchases.common.PlatformInfo
import com.revenuecat.purchases.common.errorLog
import com.revenuecat.purchases.common.events.FeatureEvent
import com.revenuecat.purchases.common.infoLog
import com.revenuecat.purchases.common.log
import com.revenuecat.purchases.customercenter.CustomerCenterListener
import com.revenuecat.purchases.deeplinks.DeepLinkParser
import com.revenuecat.purchases.interfaces.Callback
import com.revenuecat.purchases.interfaces.GetAmazonLWAConsentStatusCallback
import com.revenuecat.purchases.interfaces.GetCustomerCenterConfigCallback
import com.revenuecat.purchases.interfaces.GetStoreProductsCallback
import com.revenuecat.purchases.interfaces.GetStorefrontCallback
import com.revenuecat.purchases.interfaces.GetVirtualCurrenciesCallback
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.interfaces.RedeemWebPurchaseListener
import com.revenuecat.purchases.interfaces.SyncAttributesAndOfferingsCallback
import com.revenuecat.purchases.interfaces.SyncPurchasesCallback
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.BillingFeature
import com.revenuecat.purchases.models.InAppMessageType
import com.revenuecat.purchases.models.StoreProduct
import com.revenuecat.purchases.paywalls.DownloadedFontFamily
import com.revenuecat.purchases.strings.BillingStrings
import com.revenuecat.purchases.strings.ConfigureStrings
import com.revenuecat.purchases.utils.DefaultIsDebugBuildProvider
import com.revenuecat.purchases.virtualcurrencies.VirtualCurrencies
import java.net.URL

/**
 * Entry point for Purchases. It should be instantiated as soon as your app has a unique user id
 * for your user. This can be when a user logs in if you have accounts or on launch if you can
 * generate a random user identifier.
 * Make sure you follow the [quickstart](https://docs.revenuecat.com/docs/getting-started-1)
 * guide to setup your RevenueCat account.
 * @warning Only one instance of Purchases should be instantiated at a time!
 */
class Purchases internal constructor(
    @get:JvmSynthetic internal val purchasesOrchestrator: PurchasesOrchestrator,
) : LifecycleDelegate {
    /**
     * The current configuration parameters of the Purchases SDK.
     */
    val currentConfiguration: PurchasesConfiguration
        get() = purchasesOrchestrator.currentConfiguration

    /**
     * Default to TRUE, set this to FALSE if you are consuming and acknowledging transactions
     * outside of the Purchases SDK.
     */
    @Deprecated(
        "\"Finishing transactions\" is not a platform-agnostic term.",
        ReplaceWith("purchasesAreCompletedBy"),
    )
    var finishTransactions: Boolean
        @Synchronized get() = purchasesOrchestrator.finishTransactions

        @Synchronized set(value) {
            purchasesOrchestrator.finishTransactions = value
        }

    /**
     * Default to TRUE, set this to FALSE if you are consuming and acknowledging transactions
     * outside of the Purchases SDK.
     */
    var purchasesAreCompletedBy: PurchasesAreCompletedBy
        @Synchronized get() =
            if (purchasesOrchestrator.finishTransactions) {
                PurchasesAreCompletedBy.REVENUECAT
            } else {
                PurchasesAreCompletedBy.MY_APP
            }

        @Synchronized set(value) {
            purchasesOrchestrator.finishTransactions = when (value) {
                PurchasesAreCompletedBy.REVENUECAT -> true
                PurchasesAreCompletedBy.MY_APP -> false
            }
        }

    /**
     * The passed in or generated app user ID
     */
    val appUserID: String
        @Synchronized get() = purchasesOrchestrator.appUserID

    /**
     * The storefront country code in ISO-3166-1 alpha2.
     * This may be null if the store hasn't connected yet or fetching the country code hasn't finished or failed.
     * To get the country code asynchronously use [getStorefrontCountryCode] or [awaitStorefrontCountryCode].
     */
    val storefrontCountryCode: String?
        @Synchronized get() = purchasesOrchestrator.storefrontCountryCode

    /**
     * The listener is responsible for handling changes to customer information.
     * Make sure [removeUpdatedCustomerInfoListener] is called when the listener needs to be destroyed.
     */
    var updatedCustomerInfoListener: UpdatedCustomerInfoListener?
        @Synchronized get() = purchasesOrchestrator.updatedCustomerInfoListener

        @Synchronized set(value) {
            purchasesOrchestrator.updatedCustomerInfoListener = value
        }

    /**
     * Listener that receives callbacks for Customer Center events such as restore initiated,
     * subscription cancellations, and customer feedback submission.
     *
     * The Customer Center is a self-service UI component that helps users manage their subscriptions
     * and provides support features. This listener allows your app to respond to various user
     * actions taken within the Customer Center.
     *
     * Take a look at [our docs] (https://rev.cat/customer-center) for more information.
     *
     * @important To prevent memory leaks, always set this property to null when the listener's
     * lifecycle ends (e.g., in Activity.onDestroy() or Fragment.onDestroyView()).
     *
     * @note To use the Customer Center functionality, you need to include the RevenueCat UI SDK
     * by adding the 'purchases-ui' dependency to your app's build.gradle file.
     */
    var customerCenterListener: CustomerCenterListener? by purchasesOrchestrator::customerCenterListener

    /**
     * If the `appUserID` has been generated by RevenueCat
     */
    val isAnonymous: Boolean
        get() = purchasesOrchestrator.isAnonymous

    /**
     * The currently configured store
     */
    val store: Store
        get() = purchasesOrchestrator.store

    @Suppress("EmptyFunctionBlock", "DeprecatedCallableAddReplaceWith")
    @Deprecated("Will be removed in next major. Logic has been moved to PurchasesOrchestrator")
    override fun onAppBackgrounded() {
        purchasesOrchestrator.onAppBackgrounded()
    }

    @Suppress("EmptyFunctionBlock", "DeprecatedCallableAddReplaceWith")
    @Deprecated("Will be removed in next major. Logic has been moved to PurchasesOrchestrator")
    override fun onAppForegrounded() {
        purchasesOrchestrator.onAppForegrounded()
    }

    // region Public Methods

    /**
     * This method will try to obtain the Store (Google/Amazon) country code in ISO-3166-1 alpha2.
     * If there is any error, it will return null and log said error.
     */
    fun getStorefrontCountryCode(callback: GetStorefrontCallback) {
        purchasesOrchestrator.getStorefrontCountryCode(callback)
    }

    /**
     * This method will send active subscriptions and unconsumed one time purchases to the RevenueCat backend.
     * Call this when using your own implementation for subscriptions anytime a sync is needed, such as when migrating
     * existing users to RevenueCat. The [SyncPurchasesCallback.onSuccess] callback will be called if all purchases
     * have been synced successfully or there are no purchases.
     * Otherwise, the [SyncPurchasesCallback.onError] callback will be called with a
     * [PurchasesError] indicating the first error found.
     *
     * Note: For Amazon, this method will also send expired subscriptions and consumed one time purchases to RevenueCat.
     *
     * @param [listener] Called when all purchases have been synced with the backend, either successfully or with
     * an error. If no purchases are present, the success function will be called.
     * @warning This function should only be called if you're migrating to RevenueCat or if you have set
     * [purchasesAreCompletedBy] to [MY_APP][PurchasesAreCompletedBy.MY_APP].
     * @warning This function could take a relatively long time to execute, depending on the amount of purchases
     * the user has. Consider that when waiting for this operation to complete.
     */
    @JvmOverloads
    fun syncPurchases(
        listener: SyncPurchasesCallback? = null,
    ) {
        purchasesOrchestrator.syncPurchases(listener)
    }

    /**
     * This method will send an Amazon purchase to the RevenueCat backend. This function should only be called if you
     * have set [purchasesAreCompletedBy] to [MY_APP][PurchasesAreCompletedBy.MY_APP] or when performing a client side
     * migration of your current users to RevenueCat.
     *
     * The receipt IDs are cached if successfully posted so they are not posted more than once.
     *
     * @param [productID] Product ID associated to the purchase.
     * @param [receiptID] ReceiptId that represents the Amazon purchase.
     * @param [amazonUserID] Amazon's userID. This parameter will be ignored when syncing a Google purchase.
     * @param [isoCurrencyCode] Product's currency code in ISO 4217 format.
     * @param [price] Product's price.
     */
    @Deprecated(
        "syncObserverModeAmazonPurchase is being deprecated in favor of syncAmazonPurchase.",
        ReplaceWith("syncAmazonPurchase(productID, receiptID, amazonUserID, isoCurrencyCode, price)"),
    )
    fun syncObserverModeAmazonPurchase(
        productID: String,
        receiptID: String,
        amazonUserID: String,
        isoCurrencyCode: String?,
        price: Double?,
    ) {
        syncAmazonPurchase(
            productID = productID,
            receiptID = receiptID,
            amazonUserID = amazonUserID,
            isoCurrencyCode = isoCurrencyCode,
            price = price,
        )
    }

    /**
     * This method will send an Amazon purchase to the RevenueCat backend. This function should only be called if you
     * have set [purchasesAreCompletedBy] to [MY_APP][PurchasesAreCompletedBy.MY_APP] or when performing a client side
     * migration of your current users to RevenueCat.
     *
     * The receipt IDs are cached if successfully posted so they are not posted more than once.
     *
     * @param [productID] Product ID associated to the purchase.
     * @param [receiptID] ReceiptId that represents the Amazon purchase.
     * @param [amazonUserID] Amazon's userID. This parameter will be ignored when syncing a Google purchase.
     * @param [isoCurrencyCode] Product's currency code in ISO 4217 format.
     * @param [price] Product's price.
     */
    fun syncAmazonPurchase(
        productID: String,
        receiptID: String,
        amazonUserID: String,
        isoCurrencyCode: String?,
        price: Double?,
    ) {
        purchasesOrchestrator.syncAmazonPurchase(
            productID,
            receiptID,
            amazonUserID,
            isoCurrencyCode,
            price,
        )
    }

    /**
     * Syncs subscriber attributes and then fetches the configured offerings for this user. This method is intended to
     * be called when using Targeting Rules with Custom Attributes. Any subscriber attributes should be set before
     * calling this method to ensure the returned offerings are applied with the latest subscriber attributes.
     *
     * This method is rate limited to 5 calls per minute. It will log a warning and return offerings cache when reached.
     *
     * Refer to [the guide](https://www.revenuecat.com/docs/tools/targeting) for more targeting information
     * For more offerings information, see [getOfferings]
     *
     * @param [listener] Called when subscriber attribute syncing is finished and offerings are available. Called
     * immediately if rate limit is reached.
     */
    fun syncAttributesAndOfferingsIfNeeded(
        callback: SyncAttributesAndOfferingsCallback,
    ) {
        purchasesOrchestrator.syncAttributesAndOfferingsIfNeeded(callback)
    }

    /**
     * Fetch the configured offerings for this user. Offerings allows you to configure your in-app
     * products vis RevenueCat and greatly simplifies management. See
     * [the guide](https://docs.revenuecat.com/offerings) for more info.
     *
     * Offerings will be fetched and cached on instantiation so that, by the time they are needed,
     * your prices are loaded for your purchase flow. Time is money.
     *
     * @param [listener] Called when offerings are available. Called immediately if offerings are cached.
     */
    fun getOfferings(
        listener: ReceiveOfferingsCallback,
    ) {
        purchasesOrchestrator.getOfferings(listener)
    }

    /**
     * Gets the StoreProduct(s) for the given list of product ids for all product types.
     * @param [productIds] List of productIds
     * @param [callback] Response callback
     */
    fun getProducts(
        productIds: List<String>,
        callback: GetStoreProductsCallback,
    ) {
        getProducts(productIds, null, callback)
    }

    /**
     * Gets the StoreProduct(s) for the given list of product ids of type [type]
     * @param [productIds] List of productIds
     * @param [type] A product type to filter (no filtering applied if null)
     * @param [callback] Response callback
     */
    fun getProducts(
        productIds: List<String>,
        type: ProductType? = null,
        callback: GetStoreProductsCallback,
    ) {
        purchasesOrchestrator.getProducts(productIds, type, callback)
    }

    /**
     * Initiate a purchase with the given [PurchaseParams].
     * Initialized with an [Activity] either a [Package], [StoreProduct], or [SubscriptionOption].
     *
     * If a [Package] or [StoreProduct] is used to build the [PurchaseParams], the [StoreProduct.defaultOption] will
     * be purchased.
     * [StoreProduct.defaultOption] is selected via the following logic:
     *   - Filters out offers with "rc-ignore-offer" and "rc-customer-center" tag
     *   - Uses [SubscriptionOption] with the longest free trial or cheapest first phase
     *   - Falls back to use base plan
     *
     *   @params [purchaseParams] The parameters configuring the purchase. See [PurchaseParams.Builder] for options.
     *   @params [callback] The PurchaseCallback that will be called when purchase completes.
     */
    fun purchase(
        purchaseParams: PurchaseParams,
        callback: PurchaseCallback,
    ) {
        purchasesOrchestrator.purchase(purchaseParams, callback)
    }

    /**
     * Purchases a [StoreProduct]. If purchasing a subscription, it will choose the default [SubscriptionOption].
     *
     * The default [SubscriptionOption] logic:
     *   - Filters out offers with "rc-ignore-offer" and "rc-customer-center" tag
     *   - Uses [SubscriptionOption] WITH longest free trial or cheapest first phase
     *   - Falls back to use base plan
     *
     * @param [activity] Current activity
     * @param [storeProduct] The StoreProduct of the product you wish to purchase
     * @param [callback] The PurchaseCallback that will be called when purchase completes.
     */
    @Deprecated(
        "Use purchase() and PurchaseParams.Builder instead",
        ReplaceWith("purchase()"),
    )
    fun purchaseProduct(
        activity: Activity,
        storeProduct: StoreProduct,
        callback: PurchaseCallback,
    ) {
        purchase(PurchaseParams.Builder(activity, storeProduct).build(), callback)
    }

    /**
     * Purchase a [Package]. If purchasing a subscription, it will choose the default [SubscriptionOption].
     *
     * The default [SubscriptionOption] logic:
     *   - Filters out offers with "rc-ignore-offer" and "rc-customer-center" tag
     *   - Uses [SubscriptionOption] WITH longest free trial or cheapest first phase
     *   - Falls back to use base plan
     *
     * @param [activity] Current activity
     * @param [packageToPurchase] The Package you wish to purchase
     * @param [listener] The listener that will be called when purchase completes.
     */
    @Deprecated(
        "Use purchase() and PurchaseParams.Builder instead",
        ReplaceWith("purchase()"),
    )
    fun purchasePackage(
        activity: Activity,
        packageToPurchase: Package,
        listener: PurchaseCallback,
    ) {
        purchase(PurchaseParams.Builder(activity, packageToPurchase).build(), listener)
    }

    /**
     * Restores purchases made with the current Play Store account for the current user.
     * This method will post all active subscriptions and non consumed one time purchases associated with the current
     * Play Store account to RevenueCat and become associated with the current `appUserID`. If the receipt token is
     * being used by an existing user, the current `appUserID` will be aliased together with the
     * `appUserID` of the existing user. Going forward, either `appUserID` will be able to reference
     * the same user.
     *
     * Note: For Amazon, this method will also send expired subscriptions and consumed one time purchases to RevenueCat.
     *
     * You shouldn't use this method if you have your own account system. In that case
     * "restoration" is provided by your app passing the same `appUserId` used to purchase originally.
     * @param [callback] The listener that will be called when purchase restore completes.
     */
    fun restorePurchases(
        callback: ReceiveCustomerInfoCallback,
    ) {
        purchasesOrchestrator.restorePurchases(callback)
    }

    /**
     * This function will change the current appUserID.
     * Typically this would be used after a log out to identify a new user without calling configure
     * @param newAppUserID The new appUserID that should be linked to the currently user
     * @param [callback] An optional listener to listen for successes or errors.
     */
    @JvmOverloads
    fun logIn(
        newAppUserID: String,
        callback: LogInCallback? = null,
    ) {
        purchasesOrchestrator.logIn(newAppUserID, callback)
    }

    /**
     * Resets the Purchases client clearing the save appUserID. This will generate a random user
     * id and save it in the cache.
     * @param [callback] An optional listener to listen for successes or errors.
     */
    @JvmOverloads
    fun logOut(callback: ReceiveCustomerInfoCallback? = null) {
        purchasesOrchestrator.logOut(callback)
    }

    /**
     * Call close when you are done with this instance of Purchases
     */
    fun close() {
        purchasesOrchestrator.close()
    }

    /**
     * Get latest available customer info.
     * @param callback A listener called when purchaser info is available and not stale.
     * Called immediately if purchaser info is cached. Purchaser info can be null if an error occurred.
     */
    fun getCustomerInfo(
        callback: ReceiveCustomerInfoCallback,
    ) {
        purchasesOrchestrator.getCustomerInfo(CacheFetchPolicy.default(), true, callback)
    }

    /**
     * Get latest available customer info.
     * @param fetchPolicy Specifies cache behavior for customer info retrieval
     * @param callback A listener called when purchaser info is available and not stale.
     * Purchaser info can be null if an error occurred.
     */
    fun getCustomerInfo(
        fetchPolicy: CacheFetchPolicy,
        callback: ReceiveCustomerInfoCallback,
    ) {
        purchasesOrchestrator.getCustomerInfo(fetchPolicy, true, callback)
    }

    /**
     * Fetches the virtual currencies for the current subscriber.
     *
     * @param callback A listener called when the virtual currencies are available.
     */
    fun getVirtualCurrencies(
        callback: GetVirtualCurrenciesCallback,
    ) {
        purchasesOrchestrator.getVirtualCurrencies(callback = callback)
    }

    /**
     * Invalidates the cache for virtual currencies.
     *
     * This is useful for cases where a virtual currency's balance might have been updated
     * outside of the app, like if you decreased a user's balance from the user spending a virtual currency,
     * or if you increased the balance from your backend using the server APIs.
     *
     * For more info, see our [virtual currency docs](https://www.revenuecat.com/docs/offerings/virtual-currency)
     */
    fun invalidateVirtualCurrenciesCache() {
        purchasesOrchestrator.invalidateVirtualCurrenciesCache()
    }

    /**
     * The currently cached [VirtualCurrencies] if one is available.
     * This is synchronous, and therefore useful for contexts where an app needs a [VirtualCurrencies]
     * right away without waiting for a callback. This value will remain null until virtual currencies
     * have been fetched at least once with [Purchases.getVirtualCurrencies] or an equivalent function.
     *
     * This allows initializing state to ensure that UI can be loaded from the very first frame.
     */
    val cachedVirtualCurrencies: VirtualCurrencies?
        get() = purchasesOrchestrator.cachedVirtualCurrencies

    /**
     * Call this when you are finished using the [UpdatedCustomerInfoListener]. You should call this
     * to avoid memory leaks.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun removeUpdatedCustomerInfoListener() {
        purchasesOrchestrator.removeUpdatedCustomerInfoListener()
    }

    /**
     * Google Play only, no-op for Amazon.
     * Displays the specified in-app message types to the user as a snackbar if there are any available to be shown.
     * If [PurchasesConfiguration.showInAppMessagesAutomatically] is enabled, this will be done
     * automatically on each Activity's onStart.
     *
     * For more info: https://rev.cat/googleplayinappmessaging
     */
    @JvmOverloads
    fun showInAppMessagesIfNeeded(
        activity: Activity,
        inAppMessageTypes: List<InAppMessageType> = listOf(InAppMessageType.BILLING_ISSUES),
    ) {
        purchasesOrchestrator.showInAppMessagesIfNeeded(activity, inAppMessageTypes)
    }

    /**
     * Invalidates the cache for customer information.
     *
     * Most apps will not need to use this method; invalidating the cache can leave your app in an invalid state.
     * Refer to https://rev.cat/customer-info-cache for more information on
     * using the cache properly.
     *
     * This is useful for cases where purchaser information might have been updated outside of the
     * app, like if a promotional subscription is granted through the RevenueCat dashboard.
     */
    fun invalidateCustomerInfoCache() {
        purchasesOrchestrator.invalidateCustomerInfoCache()
    }

    /**
     * Used by `RevenueCatUI` to keep track of [FeatureEvent]s.
     */
    @InternalRevenueCatAPI
    @JvmSynthetic
    fun track(event: FeatureEvent) {
        purchasesOrchestrator.track(event)
    }

    // Kept internal since it's not meant for public usage.
    internal fun getCustomerCenterConfigData(
        callback: GetCustomerCenterConfigCallback,
    ) {
        purchasesOrchestrator.getCustomerCenterConfig(callback)
    }

    // region Subscriber Attributes
    // region Special Attributes

    /**
     * Subscriber attributes are useful for storing additional, structured information on a user.
     * Since attributes are writable using a public key they should not be used for
     * managing secure or sensitive information such as subscription status, coins, etc.
     *
     * Key names starting with "$" are reserved names used by RevenueCat. For a full list of key
     * restrictions refer to our guide: https://docs.revenuecat.com/docs/subscriber-attributes
     *
     * @param attributes Map of attributes by key. Set the value as null to delete an attribute.
     */
    fun setAttributes(attributes: Map<String, String?>) {
        purchasesOrchestrator.setAttributes(attributes)
    }

    /**
     * Subscriber attribute associated with the Email address for the user
     *
     * @param email Null or empty will delete the subscriber attribute.
     */
    fun setEmail(email: String?) {
        purchasesOrchestrator.setEmail(email)
    }

    /**
     * Subscriber attribute associated with the phone number for the user
     *
     * @param phoneNumber Null or empty will delete the subscriber attribute.
     */
    fun setPhoneNumber(phoneNumber: String?) {
        purchasesOrchestrator.setPhoneNumber(phoneNumber)
    }

    /**
     * Subscriber attribute associated with the display name for the user
     *
     * @param displayName Null or empty will delete the subscriber attribute.
     */
    fun setDisplayName(displayName: String?) {
        purchasesOrchestrator.setDisplayName(displayName)
    }

    /**
     * Subscriber attribute associated with the push token for the user
     *
     * @param fcmToken Null or empty will delete the subscriber attribute.
     */
    fun setPushToken(fcmToken: String?) {
        purchasesOrchestrator.setPushToken(fcmToken)
    }

    // endregion
    // region Integration IDs

    /**
     * Subscriber attribute associated with the Mixpanel Distinct ID for the user
     *
     * @param mixpanelDistinctID null or an empty string will delete the subscriber attribute.
     */
    fun setMixpanelDistinctID(mixpanelDistinctID: String?) {
        purchasesOrchestrator.setMixpanelDistinctID(mixpanelDistinctID)
    }

    /**
     * Subscriber attribute associated with the OneSignal Player Id for the user
     * Required for the RevenueCat OneSignal integration. Deprecated for OneSignal versions above v9.0.
     *
     * @param onesignalID null or an empty string will delete the subscriber attribute
     */
    fun setOnesignalID(onesignalID: String?) {
        purchasesOrchestrator.setOnesignalID(onesignalID)
    }

    /**
     * Subscriber attribute associated with the OneSignal User ID for the user
     * Required for the RevenueCat OneSignal integration with versions v11.0 and above.
     *
     * @param onesignalUserID null or an empty string will delete the subscriber attribute
     */
    fun setOnesignalUserID(onesignalUserID: String?) {
        purchasesOrchestrator.setOnesignalUserID(onesignalUserID)
    }

    /**
     * Subscriber attribute associated with the Airship Channel ID
     * Required for the RevenueCat Airship integration
     *
     * @param airshipChannelID null or an empty string will delete the subscriber attribute
     */
    fun setAirshipChannelID(airshipChannelID: String?) {
        purchasesOrchestrator.setAirshipChannelID(airshipChannelID)
    }

    /**
     * Subscriber attribute associated with the Firebase App Instance ID for the user
     * Required for the RevenueCat Firebase integration
     *
     * @param firebaseAppInstanceID null or an empty string will delete the subscriber attribute.
     */
    fun setFirebaseAppInstanceID(firebaseAppInstanceID: String?) {
        purchasesOrchestrator.setFirebaseAppInstanceID(firebaseAppInstanceID)
    }

    /**
     * Subscriber attribute associated with the Tenjin Analytics installation ID for the user
     * Required for the RevenueCat Tenjin integration
     *
     * @param tenjinAnalyticsInstallationID null or an empty string will delete the subscriber attribute.
     */
    fun setTenjinAnalyticsInstallationID(tenjinAnalyticsInstallationID: String?) {
        purchasesOrchestrator.setTenjinAnalyticsInstallationID(tenjinAnalyticsInstallationID)
    }

    /**
     * Subscriber attribute associated with the PostHog User ID for the user
     * Required for the RevenueCat PostHog integration
     *
     * @param postHogUserId null or an empty string will delete the subscriber attribute
     */
    fun setPostHogUserId(postHogUserId: String?) {
        purchasesOrchestrator.setPostHogUserId(postHogUserId)
    }

    // endregion
    // region Attribution IDs

    /**
     * Automatically collect subscriber attributes associated with the device identifiers
     * $gpsAdId, $androidId, $ip
     *
     * @warning In accordance with [Google Play's data safety guidelines] (https://rev.cat/google-plays-data-safety),
     * you should not be calling this function if your app targets children.
     *
     * @warning You must declare the [AD_ID Permission](https://rev.cat/google-advertising-id) when your app targets
     * Android 13 or above. Apps that don't declare the permission will get a string of zeros.
     */
    fun collectDeviceIdentifiers() {
        purchasesOrchestrator.collectDeviceIdentifiers()
    }

    /**
     * Subscriber attribute associated with the Adjust Id for the user
     * Required for the RevenueCat Adjust integration
     *
     * @param adjustID null or an empty string will delete the subscriber attribute
     */
    fun setAdjustID(adjustID: String?) {
        purchasesOrchestrator.setAdjustID(adjustID)
    }

    /**
     * Subscriber attribute associated with the AppsFlyer Id for the user
     * Required for the RevenueCat AppsFlyer integration
     *
     * @param appsflyerID null or an empty string will delete the subscriber attribute
     */
    fun setAppsflyerID(appsflyerID: String?) {
        purchasesOrchestrator.setAppsflyerID(appsflyerID)
    }

    /**
     * Subscriber attribute associated with the Facebook SDK Anonymous Id for the user
     * Recommended for the RevenueCat Facebook integration
     *
     * @param fbAnonymousID null or an empty string will delete the subscriber attribute
     */
    fun setFBAnonymousID(fbAnonymousID: String?) {
        purchasesOrchestrator.setFBAnonymousID(fbAnonymousID)
    }

    /**
     * Subscriber attribute associated with the mParticle Id for the user
     * Recommended for the RevenueCat mParticle integration
     *
     * @param mparticleID null or an empty string will delete the subscriber attribute
     */
    fun setMparticleID(mparticleID: String?) {
        purchasesOrchestrator.setMparticleID(mparticleID)
    }

    /**
     * Subscriber attribute associated with the CleverTap ID for the user
     * Required for the RevenueCat CleverTap integration
     *
     * @param cleverTapID null or an empty string will delete the subscriber attribute.
     */
    fun setCleverTapID(cleverTapID: String?) {
        purchasesOrchestrator.setCleverTapID(cleverTapID)
    }

    /**
     * Subscriber attribute associated with the Kochava Device ID for the user
     * Recommended for the RevenueCat Kochava integration
     *
     * @param kochavaDeviceID null or an empty string will delete the subscriber attribute.
     */
    fun setKochavaDeviceID(kochavaDeviceID: String?) {
        purchasesOrchestrator.setKochavaDeviceID(kochavaDeviceID)
    }

    // endregion
    // region Campaign parameters

    /**
     * Subscriber attribute associated with the install media source for the user
     *
     * @param mediaSource null or an empty string will delete the subscriber attribute.
     */
    fun setMediaSource(mediaSource: String?) {
        purchasesOrchestrator.setMediaSource(mediaSource)
    }

    /**
     * Subscriber attribute associated with the install campaign for the user
     *
     * @param campaign null or an empty string will delete the subscriber attribute.
     */
    fun setCampaign(campaign: String?) {
        purchasesOrchestrator.setCampaign(campaign)
    }

    /**
     * Subscriber attribute associated with the install ad group for the user
     *
     * @param adGroup null or an empty string will delete the subscriber attribute.
     */
    fun setAdGroup(adGroup: String?) {
        purchasesOrchestrator.setAdGroup(adGroup)
    }

    /**
     * Subscriber attribute associated with the install ad for the user
     *
     * @param ad null or an empty string will delete the subscriber attribute.
     */
    fun setAd(ad: String?) {
        purchasesOrchestrator.setAd(ad)
    }

    /**
     * Subscriber attribute associated with the install keyword for the user
     *
     * @param keyword null or an empty string will delete the subscriber attribute.
     */
    fun setKeyword(keyword: String?) {
        purchasesOrchestrator.setKeyword(keyword)
    }

    /**
     * Subscriber attribute associated with the install ad creative for the user
     *
     * @param creative null or an empty string will delete the subscriber attribute.
     */
    fun setCreative(creative: String?) {
        purchasesOrchestrator.setCreative(creative)
    }

    //endregion
    //endregion
    //endregion

    // region Paywall fonts

    @InternalRevenueCatAPI
    fun getCachedFontFamilyOrStartDownload(
        fontInfo: UiConfig.AppConfig.FontsConfig.FontInfo.Name,
    ): DownloadedFontFamily? {
        return purchasesOrchestrator.getCachedFontFamilyOrStartDownload(fontInfo)
    }

    // endregion Paywall Fonts

    // region Deprecated

    /**
     * If it should allow sharing Play Store accounts. False by
     * default. If true treats all purchases as restores, aliasing together appUserIDs that share a
     * Play Store account.
     */
    @Deprecated(
        "Replaced with configuration in the RevenueCat dashboard",
        ReplaceWith("configure through the RevenueCat dashboard"),
    )
    var allowSharingPlayStoreAccount: Boolean
        @Synchronized get() = purchasesOrchestrator.allowSharingPlayStoreAccount

        @Synchronized set(value) {
            purchasesOrchestrator.allowSharingPlayStoreAccount = value
        }

    /**
     * Gets the StoreProduct for the given list of subscription products.
     * @param [productIds] List of productIds
     * @param [callback] Response callback
     */
    @Deprecated(
        "Replaced with getProducts() which returns both subscriptions and non-subscriptions",
        ReplaceWith("getProducts()"),
    )
    fun getSubscriptionSkus(
        productIds: List<String>,
        callback: GetStoreProductsCallback,
    ) {
        purchasesOrchestrator.getProductsOfTypes(productIds.toSet(), setOf(ProductType.SUBS), callback)
    }

    /**
     * Gets the StoreProduct for the given list of non-subscription products.
     * @param [productIds] List of productIds
     * @param [callback] Response callback
     */
    @Deprecated(
        "Replaced with getProducts() which returns both subscriptions and non-subscriptions",
        ReplaceWith("getProducts()"),
    )
    fun getNonSubscriptionSkus(
        productIds: List<String>,
        callback: GetStoreProductsCallback,
    ) {
        purchasesOrchestrator.getProductsOfTypes(productIds.toSet(), setOf(ProductType.INAPP), callback)
    }

    /**
     * Note: This method only works for the Amazon Appstore. There is no Google equivalent at this time.
     * Calling from a Google-configured app will always return AmazonLWAConsentStatus.UNAVAILABLE.
     *
     * Get the Login with Amazon consent status for the current user. Used to implement one-click
     * account creation using Quick Subscribe.
     *
     * For more information, check the documentation:
     * https://developer.amazon.com/docs/in-app-purchasing/iap-quicksubscribe.html
     *
     * @param [callback] Response callback
     */
    fun getAmazonLWAConsentStatus(callback: GetAmazonLWAConsentStatusCallback) {
        purchasesOrchestrator.getAmazonLWAConsentStatus(callback)
    }
    // endregion

    /**
     * Redeem a web purchase using a [WebPurchaseRedemption] object obtained
     * through [Intent.asWebPurchaseRedemption] or [Purchases.parseAsWebPurchaseRedemption].
     */
    fun redeemWebPurchase(webPurchaseRedemption: WebPurchaseRedemption, listener: RedeemWebPurchaseListener) {
        purchasesOrchestrator.redeemWebPurchase(webPurchaseRedemption, listener)
    }

    // region Static
    companion object {

        @InternalRevenueCatAPI
        fun getImageLoader(context: Context): Any {
            return PurchasesOrchestrator.getImageLoader(context)
        }

        /**
         * Given an intent, parses the associated link if any and returns a [WebPurchaseRedemption], which can
         * be used to redeem a web purchase using [Purchases.redeemWebPurchase]
         * @return A parsed version of the link or null if it's not a valid RevenueCat web purchase redemption link.
         */
        @JvmStatic
        fun parseAsWebPurchaseRedemption(intent: Intent): WebPurchaseRedemption? {
            val intentData = intent.data ?: return null
            return DeepLinkParser.parseWebPurchaseRedemption(intentData)
        }

        /**
         * Given a url string, parses the link and returns a [WebPurchaseRedemption], which can
         * be used to redeem a web purchase using [Purchases.redeemWebPurchase]
         * @return A parsed version of the link or null if it's not a valid RevenueCat web purchase redemption link.
         */
        @JvmStatic
        fun parseAsWebPurchaseRedemption(string: String): WebPurchaseRedemption? {
            try {
                val uri = Uri.parse(string)
                return DeepLinkParser.parseWebPurchaseRedemption(uri)
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                errorLog(e) { "Error parsing URL: $string" }
                return null
            }
        }

        /**
         * DO NOT MODIFY. This is used internally by the Hybrid SDKs to indicate which platform is
         * being used
         */
        @JvmStatic
        var platformInfo: PlatformInfo
            get() = PurchasesOrchestrator.platformInfo
            set(value) { PurchasesOrchestrator.platformInfo = value }

        /**
         * Enable debug logging. Useful for debugging issues with the lovely team @RevenueCat
         */
        @JvmStatic
        @Deprecated(message = "Use logLevel instead")
        var debugLogsEnabled
            get() = PurchasesOrchestrator.debugLogsEnabled
            set(value) {
                PurchasesOrchestrator.debugLogsEnabled = value
            }

        /**
         * Configure log level. Useful for debugging issues with the lovely team @RevenueCat
         * By default, LogLevel.DEBUG in debug builds, and LogLevel.INFO in release builds.
         */
        @JvmStatic
        var logLevel: LogLevel
            get() = PurchasesOrchestrator.logLevel
            set(value) {
                PurchasesOrchestrator.logLevel = value
            }

        /**
         * Set a custom log handler for redirecting logs to your own logging system.
         * Defaults to [android.util.Log].
         *
         * By default, this sends info, warning, and error messages.
         * If you wish to receive Debug level messages, see [debugLogsEnabled].
         */
        @JvmStatic
        var logHandler: LogHandler
            @Synchronized get() = PurchasesOrchestrator.logHandler

            @Synchronized set(value) {
                PurchasesOrchestrator.logHandler = value
            }

        @JvmSynthetic
        internal var backingFieldSharedInstance: Purchases? = null

        /**
         * Singleton instance of Purchases. [configure] will set this
         * @return A previously set singleton Purchases instance
         * @throws UninitializedPropertyAccessException if the shared instance has not been configured.
         */
        @JvmStatic
        var sharedInstance: Purchases
            get() =
                backingFieldSharedInstance
                    ?: throw UninitializedPropertyAccessException(ConfigureStrings.NO_SINGLETON_INSTANCE)

            @VisibleForTesting(otherwise = VisibleForTesting.NONE)
            internal set(value) {
                backingFieldSharedInstance?.close()
                backingFieldSharedInstance = value
            }

        /**
         * Current version of the Purchases SDK
         */
        @JvmStatic
        val frameworkVersion = PurchasesOrchestrator.frameworkVersion

        /**
         * Set this property to your proxy URL before configuring Purchases *only*
         * if you've received a proxy key value from your RevenueCat contact.
         */
        @JvmStatic
        var proxyURL: URL?
            get() = PurchasesOrchestrator.proxyURL
            set(value) { PurchasesOrchestrator.proxyURL = value }

        /**
         * True if [configure] has been called and [Purchases.sharedInstance] is set
         */
        @JvmStatic
        val isConfigured: Boolean
            get() = this.backingFieldSharedInstance != null

        /**
         * Configures an instance of the Purchases SDK with a specified API key. The instance will
         * be set as a singleton. You should access the singleton instance using [Purchases.sharedInstance]
         * @param configuration: the [PurchasesConfiguration] object you wish to use to configure [Purchases].
         * @return An instantiated `[Purchases] object that has been set as a singleton.
         */
        @JvmStatic
        fun configure(
            configuration: PurchasesConfiguration,
        ): Purchases {
            if (isConfigured) {
                if (backingFieldSharedInstance?.purchasesOrchestrator?.currentConfiguration == configuration) {
                    infoLog { ConfigureStrings.INSTANCE_ALREADY_EXISTS_WITH_SAME_CONFIG }
                    return sharedInstance
                } else {
                    infoLog { ConfigureStrings.INSTANCE_ALREADY_EXISTS }
                }
            }
            return PurchasesFactory(
                isDebugBuild = DefaultIsDebugBuildProvider(configuration.context),
            ).createPurchases(
                configuration,
                platformInfo,
                proxyURL,
            ).also {
                @SuppressLint("RestrictedApi")
                sharedInstance = it
            }
        }

        /**
         * Note: This method only works for the Google Play Store. There is no Amazon equivalent at this time.
         * Calling from an Amazon-configured app will return true.
         *
         * Check if billing is supported for the current Play user (meaning IN-APP purchases are supported)
         * and optionally, whether all features in the list of specified feature types are supported. This method is
         * asynchronous since it requires a connected BillingClient.
         * @param context A context object that will be used to connect to the billing client
         * @param features A list of feature types to check for support. Feature types must be one of [BillingFeature]
         *                 By default, is an empty list and no specific feature support will be checked.
         * @param callback Callback that will be notified when the check is complete.
         */
        @JvmStatic
        @JvmOverloads
        fun canMakePayments(
            context: Context,
            features: List<BillingFeature> = listOf(),
            callback: Callback<Boolean>,
        ) {
            val currentStore = sharedInstance.purchasesOrchestrator.appConfig.store
            if (currentStore != Store.PLAY_STORE) {
                log(LogIntent.RC_ERROR) { BillingStrings.CANNOT_CALL_CAN_MAKE_PAYMENTS }
                callback.onReceived(true)
                return
            }
            PurchasesOrchestrator.canMakePayments(context, features, callback)
        }
    }

    // endregion
}
