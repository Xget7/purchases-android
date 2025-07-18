@file:Suppress("LongParameterList")

package com.revenuecat.purchases.ui.revenuecatui.components.style

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Immutable
import com.revenuecat.purchases.paywalls.components.properties.Shape
import com.revenuecat.purchases.paywalls.components.properties.Size
import com.revenuecat.purchases.paywalls.components.properties.SizeConstraint.Fit
import com.revenuecat.purchases.ui.revenuecatui.components.PresentedOverride
import com.revenuecat.purchases.ui.revenuecatui.components.PresentedTabsPartial
import com.revenuecat.purchases.ui.revenuecatui.components.properties.BackgroundStyles
import com.revenuecat.purchases.ui.revenuecatui.components.properties.BorderStyles
import com.revenuecat.purchases.ui.revenuecatui.components.properties.ColorStyles
import com.revenuecat.purchases.ui.revenuecatui.components.properties.ShadowStyles
import com.revenuecat.purchases.ui.revenuecatui.helpers.NonEmptyList

@Immutable
internal data class TabControlButtonComponentStyle(
    @get:JvmSynthetic
    val tabIndex: Int,
    @get:JvmSynthetic
    val stack: StackComponentStyle,
) : ComponentStyle {
    override val visible: Boolean = stack.visible
    override val size: Size = stack.size
}

@Immutable
internal class TabControlToggleComponentStyle(
    @get:JvmSynthetic
    val thumbColorOn: ColorStyles,
    @get:JvmSynthetic
    val thumbColorOff: ColorStyles,
    @get:JvmSynthetic
    val trackColorOn: ColorStyles,
    @get:JvmSynthetic
    val trackColorOff: ColorStyles,
) : ComponentStyle {
    override val visible: Boolean = true
    override val size: Size = Size(width = Fit, height = Fit)
}

@Immutable
internal data class TabsComponentStyle(
    @get:JvmSynthetic
    override val visible: Boolean,
    @get:JvmSynthetic
    override val size: Size,
    @get:JvmSynthetic
    val padding: PaddingValues,
    @get:JvmSynthetic
    val margin: PaddingValues,
    @get:JvmSynthetic
    val background: BackgroundStyles?,
    @get:JvmSynthetic
    val shape: Shape,
    @get:JvmSynthetic
    val border: BorderStyles?,
    @get:JvmSynthetic
    val shadow: ShadowStyles?,
    @get:JvmSynthetic
    val control: TabControlStyle,
    @get:JvmSynthetic
    val tabs: NonEmptyList<Tab>,
    @get:JvmSynthetic
    val overrides: List<PresentedOverride<PresentedTabsPartial>>,
) : ComponentStyle {

    @Immutable
    data class Tab(@get:JvmSynthetic val stack: StackComponentStyle)
}

@Immutable
internal sealed interface TabControlStyle : ComponentStyle {
    @Immutable
    data class Buttons(@get:JvmSynthetic val stack: StackComponentStyle) : TabControlStyle {
        override val visible: Boolean = stack.visible
        override val size: Size = stack.size
    }

    @Immutable
    data class Toggle(@get:JvmSynthetic val stack: StackComponentStyle) : TabControlStyle {
        override val visible: Boolean = stack.visible
        override val size: Size = stack.size
    }
}
