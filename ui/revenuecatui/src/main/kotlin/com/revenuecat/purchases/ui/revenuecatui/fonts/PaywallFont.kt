package com.revenuecat.purchases.ui.revenuecatui.fonts

import android.os.Parcelable
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.revenuecat.purchases.ui.revenuecatui.fonts.PaywallFont.GoogleFont
import com.revenuecat.purchases.ui.revenuecatui.fonts.PaywallFont.ResourceFont
import dev.drewhamilton.poko.Poko
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler

/**
 * Represents a font. You can create either a [GoogleFont] or a [ResourceFont].
 */
sealed class PaywallFont : Parcelable {
    /**
     * Represents a downloadable Google Font.
     */
    @Parcelize
    @Poko
    class GoogleFont(
        /**
         * Name of the Google font you want to use.
         */
        val fontName: String,
        /**
         * Provider of the Google font.
         */
        val fontProvider: GoogleFontProvider,
        /**
         * The weight of the font. The system uses this to match a font to a font request.
         */
        @TypeParceler<FontWeight, FontWeightParceler>()
        val fontWeight: FontWeight = FontWeight.Normal,
        /**
         * The style of the font, normal or italic. The system uses this to match a font to a font request.
         * We use int instead of [FontStyle] because [FontStyle] is not compatible with Java.
         */
        val fontStyle: Int = FontStyle.Normal.value,
    ) : PaywallFont()

    @Parcelize
    @Poko
    class ResourceFont(
        /**
         * The resource ID of the font file in font resources.
         */
        @FontRes
        val resourceId: Int,
        /**
         * The weight of the font. The system uses this to match a font to a font request.
         */
        @TypeParceler<FontWeight, FontWeightParceler>()
        val fontWeight: FontWeight = FontWeight.Normal,
        /**
         * The style of the font, normal or italic. The system uses this to match a font to a font request.
         * We use int instead of [FontStyle] because [FontStyle] is not compatible with Java.
         */
        val fontStyle: Int = FontStyle.Normal.value,
    ) : PaywallFont()

    @Parcelize
    @Poko
    class AssetFont(
        /**
         * Full path starting from the assets directory (i.e. dir/myfont.ttf for assets/dir/myfont.ttf).
         */
        val path: String,
        /**
         * The weight of the font. The system uses this to match a font to a font request.
         */
        @TypeParceler<FontWeight, FontWeightParceler>()
        val fontWeight: FontWeight = FontWeight.Normal,
        /**
         * The style of the font, normal or italic. The system uses this to match a font to a font request.
         * We use int instead of [FontStyle] because [FontStyle] is not compatible with Java.
         */
        val fontStyle: Int = FontStyle.Normal.value,
    ) : PaywallFont()
}
