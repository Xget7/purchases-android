package com.revenuecat.apitester.kotlin

import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.OwnershipType
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.Store
import com.revenuecat.purchases.VerificationResult
import org.json.JSONObject
import java.util.Date

@Suppress("unused", "UNUSED_VARIABLE", "DEPRECATION", "LongParameterList")
private class EntitlementInfoAPI {
    fun check(entitlementInfo: EntitlementInfo) {
        with(entitlementInfo) {
            val identifier: String = identifier
            val active: Boolean = isActive
            val willRenew: Boolean = willRenew
            val periodType: PeriodType = periodType
            val latestPurchaseDate: Date = latestPurchaseDate
            val originalPurchaseDate: Date = originalPurchaseDate
            val expirationDate: Date? = expirationDate
            val store: Store = store
            val productIdentifier: String = productIdentifier
            val productPlanIdentifier: String? = productPlanIdentifier
            val sandbox: Boolean = isSandbox
            val unsubscribeDetectedAt: Date? = unsubscribeDetectedAt
            val billingIssueDetectedAt: Date? = billingIssueDetectedAt
            val ownershipType: OwnershipType = ownershipType
            val verification: VerificationResult = verification
        }
    }

    fun checkConstructor(
        identifier: String,
        isActive: Boolean,
        willRenew: Boolean,
        periodType: PeriodType,
        latestPurchaseDate: Date,
        originalPurchaseDate: Date,
        expirationDate: Date?,
        store: Store,
        productIdentifier: String,
        productPlanIdentifier: String?,
        isSandbox: Boolean,
        unsubscribeDetectedAt: Date?,
        billingIssueDetectedAt: Date?,
        ownershipType: OwnershipType,
        jsonObject: JSONObject,
        verification: VerificationResult,
    ) {
        val entitlementInfo = EntitlementInfo(
            identifier = identifier,
            isActive = isActive,
            willRenew = willRenew,
            periodType = periodType,
            latestPurchaseDate = latestPurchaseDate,
            originalPurchaseDate = originalPurchaseDate,
            expirationDate = expirationDate,
            store = store,
            productIdentifier = productIdentifier,
            productPlanIdentifier = productPlanIdentifier,
            isSandbox = isSandbox,
            unsubscribeDetectedAt = unsubscribeDetectedAt,
            billingIssueDetectedAt = billingIssueDetectedAt,
            ownershipType = ownershipType,
            jsonObject = jsonObject,
            verification = verification,
        )
        val entitlementInfo2 = EntitlementInfo(
            identifier = identifier,
            isActive = isActive,
            willRenew = willRenew,
            periodType = periodType,
            latestPurchaseDate = latestPurchaseDate,
            originalPurchaseDate = originalPurchaseDate,
            expirationDate = expirationDate,
            store = store,
            productIdentifier = productIdentifier,
            productPlanIdentifier = productPlanIdentifier,
            isSandbox = isSandbox,
            unsubscribeDetectedAt = unsubscribeDetectedAt,
            billingIssueDetectedAt = billingIssueDetectedAt,
            ownershipType = ownershipType,
            jsonObject = jsonObject,
        )
    }

    fun store(store: Store) {
        when (store) {
            Store.APP_STORE,
            Store.MAC_APP_STORE,
            Store.PLAY_STORE,
            Store.STRIPE,
            Store.PROMOTIONAL,
            Store.UNKNOWN_STORE,
            Store.AMAZON,
            Store.RC_BILLING,
            Store.EXTERNAL,
            Store.PADDLE,
            -> {}
        }.exhaustive
    }

    fun periodType(type: PeriodType) {
        when (type) {
            PeriodType.NORMAL,
            PeriodType.INTRO,
            PeriodType.TRIAL,
            PeriodType.PREPAID,
            -> {}
        }.exhaustive
    }

    fun ownershipType(type: OwnershipType) {
        when (type) {
            OwnershipType.PURCHASED,
            OwnershipType.FAMILY_SHARED,
            OwnershipType.UNKNOWN,
            -> {}
        }.exhaustive
    }
}
