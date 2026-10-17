package ir.amir.triedgame.billing

import android.util.Log
import androidx.activity.ComponentActivity
import ir.amir.triedgame.BuildConfig
import ir.cafebazaar.poolakey.Connection
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

/**
 * Wraps Cafe Bazaar's official Poolakey billing SDK for the four consumable
 * wallet top-up packages. All four are consumable: the user can buy any of
 * them repeatedly -- each purchase is consumed immediately after being
 * credited so it becomes available for purchase again right away.
 *
 * Built directly from Cafe Bazaar's official Kotlin implementation guide
 * (developers.cafebazaar.ir/en/guidelines/in-app-billing/implementation/kotlin).
 * One assumption flagged for verification once Android Studio's autocomplete
 * is available: the exact field names on the purchase result object
 * (assumed `productId` and `purchaseToken`, matching PurchaseRequest's own
 * field names) -- the doc's example was trimmed at that exact spot, so
 * double-check these two property names compile; everything else here
 * (class names, callback structure, method signatures) is taken verbatim
 * from the official doc.
 */
class BillingManager(
    private val activity: ComponentActivity,
    private val onWalletTopUp: (usdAmount: Double) -> Unit,
    private val onSetupError: (String) -> Unit = {}
) {
    private val payment: Payment by lazy {
        val securityCheck = SecurityCheck.Enable(rsaPublicKey = BuildConfig.BAZAAR_PUBLIC_KEY)
        val paymentConfig = PaymentConfiguration(localSecurityCheck = securityCheck)
        Payment(context = activity, config = paymentConfig)
    }

    private var connection: Connection? = null

    companion object {
        private const val TAG = "BillingManager"

        // Product IDs as configured in the Cafe Bazaar developer panel.
        val SKUS = listOf("25dolar", "65dolar", "200dolar", "1000dolar")

        /** USD credited to the wallet for each SKU, per the Bazaar product setup. */
        val USD_REWARD: Map<String, Double> = mapOf(
            "25dolar" to 25.0,
            "65dolar" to 65.0,
            "200dolar" to 200.0,
            "1000dolar" to 1000.0
        )

        /** Toman price shown in the shop UI for each SKU (informational; the real
         * charge is handled entirely by Bazaar's own payment UI). */
        val TOMAN_PRICE: Map<String, Long> = mapOf(
            "25dolar" to 5_000L,
            "65dolar" to 10_000L,
            "200dolar" to 20_000L,
            "1000dolar" to 80_000L
        )

        /** SKUs to badge as a special/featured deal in the shop UI. */
        val SPECIAL_OFFER_SKUS = setOf("1000dolar")
    }

    fun start() {
        connection = payment.connect {
            connectionSucceed {
                Log.d(TAG, "Connected to Bazaar billing service")
                queryPurchasedAndConsumePending()
            }
            connectionFailed { throwable ->
                onSetupError(throwable.message ?: "billing setup failed")
            }
            disconnected {
                Log.d(TAG, "Disconnected from Bazaar billing service")
            }
        }
    }

    /**
     * On every app start we must query purchased products: this catches any
     * purchase that completed but wasn't consumed/credited last time (e.g.
     * app was killed mid-flow) so the user is never charged without
     * receiving their wallet top-up.
     */
    private fun queryPurchasedAndConsumePending() {
        payment.getPurchasedProducts {
            querySucceed { purchasedProducts ->
                purchasedProducts.forEach { entity ->
                    consumeAndCredit(entity.productId, entity.purchaseToken)
                }
            }
            queryFailed { throwable ->
                Log.e(TAG, "Querying purchased products failed", throwable)
            }
        }
    }

    fun purchase(sku: String) {
        val request = PurchaseRequest(productId = sku, payload = sku)
        payment.purchaseProduct(activity.activityResultRegistry, request) {
            purchaseFlowBegan {
                Log.d(TAG, "Purchase flow began for $sku")
            }
            failedToBeginFlow { throwable ->
                Log.e(TAG, "Failed to begin purchase flow for $sku", throwable)
            }
            purchaseSucceed { purchaseEntity ->
                consumeAndCredit(purchaseEntity.productId, purchaseEntity.purchaseToken)
            }
            purchaseCanceled {
                Log.d(TAG, "Purchase canceled for $sku")
            }
            purchaseFailed { throwable ->
                Log.e(TAG, "Purchase failed for $sku", throwable)
            }
        }
    }

    private fun consumeAndCredit(sku: String, purchaseToken: String) {
        payment.consumeProduct(purchaseToken = purchaseToken) {
            consumeSucceed {
                val usd = USD_REWARD[sku] ?: 0.0
                if (usd > 0.0) onWalletTopUp(usd)
            }
            consumeFailed { throwable ->
                Log.e(TAG, "Consume failed for $sku", throwable)
            }
        }
    }

    fun dispose() {
        connection?.disconnect()
        connection = null
    }
}
