package com.tangent.sdk.paywall

import com.superwall.sdk.paywall.presentation.PaywallInfo

/**
 * The Superwall paywall the user last saw or bought on (iOS's `PaywallAttribution`), for attributing purchase
 * analytics to the paywall, placement and experiment that drove them. Captured when a paywall opens and upgraded when
 * a purchase completes on it.
 */
data class PaywallAttribution(
    val paywallId: String,
    val paywallName: String,
    /** The placement that triggered the paywall (`null` when presented another way). */
    val placement: String?,
    /** How the paywall was presented: "programmatically", "identifier" or "placement". */
    val presentedBy: String,
    val experimentId: String?,
    val variantId: String?,
    /** The product bought on this paywall; set only once [didConvert]. */
    val productId: String?,
    val didConvert: Boolean,
    val capturedAtMillis: Long
) {
    internal companion object {
        fun from(info: PaywallInfo, productId: String?, didConvert: Boolean) = PaywallAttribution(
            paywallId = info.identifier,
            paywallName = info.name,
            placement = info.presentedByEventWithName,
            presentedBy = info.presentedBy,
            experimentId = info.experiment?.id,
            variantId = info.experiment?.variant?.id,
            productId = productId,
            didConvert = didConvert,
            capturedAtMillis = System.currentTimeMillis()
        )
    }
}
