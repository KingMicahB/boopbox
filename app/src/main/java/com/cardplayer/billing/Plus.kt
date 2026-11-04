package com.cardplayer.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.cardplayer.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Why the "BoopBox Plus" sheet was opened: picks the line at its top. */
enum class PlusReason(@param:StringRes val textRes: Int) {
    CARDS(R.string.plus_reason_cards),
    SPLIT(R.string.plus_reason_split),
    TRIM(R.string.plus_reason_trim),
    BEDTIME(R.string.plus_reason_bedtime),
    NIGHT_LIGHT(R.string.plus_reason_night_light),
    CARD_SLEEP(R.string.plus_reason_card_sleep),
    SPEED(R.string.plus_reason_speed),
    GENERAL(R.string.plus_reason_general)
}

/**
 * BoopBox Plus: a one-time Google Play purchase ([PRODUCT_ID]) that lifts the card limit and unlocks the
 * grown-up extras. Ownership is cached in shared prefs (not filesDir, so backups and .boop files never carry it)
 * so the app knows it offline; every connection to Play re-checks it, so a refund takes Plus away again.
 * Debug builds can pretend to own it (Settings → BoopBox Plus).
 */
object Plus {
    const val PRODUCT_ID = "boopbox_plus"
    private const val TAG = "BoopBoxPlus"
    private val main = Handler(Looper.getMainLooper())

    /** Cards a free install can set up itself. Cards that arrive ready-made (Yoto, written tags, .boop) don't stop at this. */
    const val FREE_CARD_LIMIT = 5

    private const val PREFS = "plus"
    private const val KEY_OWNED = "owned"
    private const val KEY_DEBUG = "debug_plus"

    private lateinit var appContext: Context
    private lateinit var client: BillingClient
    private var details: ProductDetails? = null

    private val owned = MutableStateFlow(false)
    private val debugPlus = MutableStateFlow(false)
    private val _active = MutableStateFlow(false)
    /** True when Plus features are on. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    /** The price in the phone's currency, once Play has told us. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Purchase outcomes worth a notice ("Thanks!", "Play isn't available"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val isActive: Boolean get() = _active.value

    /** Debug builds only: the "pretend Plus is bought" switch is shown. */
    val canPretend: Boolean
        get() = ::appContext.isInitialized && (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    val pretending: StateFlow<Boolean> = debugPlus.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        owned.value = prefs.getBoolean(KEY_OWNED, false)
        debugPlus.value = canPretend && prefs.getBoolean(KEY_DEBUG, false)
        recompute()
        client = BillingClient.newBuilder(appContext)
            .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        connect()
    }

    fun setPretend(on: Boolean) {
        if (!canPretend) return
        debugPlus.value = on
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DEBUG, on).apply()
        recompute()
    }

    /** True when one more card of the grown-up's own may be set up next to [cardCount] existing ones. */
    fun canAddCard(cardCount: Int): Boolean = isActive || cardCount < FREE_CARD_LIMIT

    /**
     * Opens Google Play's purchase sheet. If the product hasn't loaded yet (Play was slow, offline at launch, or an
     * earlier query failed), it connects and asks Play again first, then opens the sheet or says exactly why it can't.
     */
    fun buy(activity: Activity) {
        val d = details
        if (d != null && client.isReady) {
            launch(activity, d)
            return
        }
        val ref = WeakReference(activity)
        withProduct { problem ->
            main.post {
                val a = ref.get()
                val found = details
                if (problem == null && found != null && a != null && !a.isFinishing) launch(a, found)
                else _messages.tryEmit(problem ?: appContext.getString(R.string.plus_store_unavailable))
            }
        }
    }

    private fun launch(activity: Activity, d: ProductDetails) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow: ${result.responseCode} ${result.debugMessage}")
            _messages.tryEmit(appContext.getString(R.string.plus_store_error, describe(result)))
        }
    }

    /** Connects if needed and loads [PRODUCT_ID]; [done] gets null once [details] is set, else a reason to show. */
    private fun withProduct(done: (String?) -> Unit) {
        if (client.isReady) {
            queryDetails(done)
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "billing setup: ${result.responseCode} ${result.debugMessage}")
                    done(appContext.getString(R.string.plus_store_error, describe(result)))
                    return
                }
                queryDetails(done)
                queryPurchases(announce = false)
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun describe(result: BillingResult): String {
        val what = when (result.responseCode) {
            BillingResponseCode.BILLING_UNAVAILABLE -> "billing unavailable for this Play account or app install"
            BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR -> "no connection to Play"
            BillingResponseCode.ITEM_UNAVAILABLE -> "Plus isn't available in this country or account"
            else -> result.debugMessage.ifBlank { "error" }
        }
        return "$what (${result.responseCode})"
    }

    /** "Restore purchase": asks Play again what this account owns. */
    fun refresh() {
        if (client.isReady) queryPurchases(announce = true) else connect()
    }

    private fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "billing setup: ${result.responseCode} ${result.debugMessage}")
                    return
                }
                queryDetails()
                queryPurchases(announce = false)
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun queryDetails(done: ((String?) -> Unit)? = null) {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, found ->
            if (result.responseCode != BillingResponseCode.OK) {
                Log.w(TAG, "product details: ${result.responseCode} ${result.debugMessage}")
                done?.invoke(appContext.getString(R.string.plus_store_error, describe(result)))
                return@queryProductDetailsAsync
            }
            details = found.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
            if (details == null) {
                // Play answered but has no such product for this app: the product isn't active in Play Console,
                // or this install isn't the Play-signed app (sideloaded / releaseCheck build / not from a test track).
                Log.w(TAG, "product details: '$PRODUCT_ID' not returned (unfetched=${found.unfetchedProductList.map { it.statusCode }})")
                done?.invoke(appContext.getString(R.string.plus_not_for_sale))
            } else {
                done?.invoke(null)
            }
        }
    }

    private fun queryPurchases(announce: Boolean) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode != BillingResponseCode.OK) return@queryPurchasesAsync
            val mine = purchases.filter { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            mine.forEach(::acknowledge)
            setOwned(mine.isNotEmpty())
            if (announce) {
                _messages.tryEmit(appContext.getString(if (mine.isNotEmpty()) R.string.plus_restored else R.string.plus_nothing_to_restore))
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases.orEmpty().filter { PRODUCT_ID in it.products }.forEach { p ->
                when (p.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        acknowledge(p)
                        setOwned(true)
                        _messages.tryEmit(appContext.getString(R.string.plus_thanks))
                    }
                    Purchase.PurchaseState.PENDING -> _messages.tryEmit(appContext.getString(R.string.plus_pending))
                    else -> Unit
                }
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases(announce = true)
            BillingResponseCode.USER_CANCELED -> Unit
            else -> _messages.tryEmit(appContext.getString(R.string.plus_buy_failed, result.debugMessage.ifBlank { result.responseCode.toString() }))
        }
    }

    /** Play refunds purchases that are not acknowledged within three days. */
    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
    }

    private fun setOwned(value: Boolean) {
        owned.value = value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWNED, value).apply()
        recompute()
    }

    private fun recompute() {
        _active.value = owned.value || debugPlus.value
    }
}
